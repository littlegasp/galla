package app.galla.captain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Sends queued recordings and logs fingerprints whenever there is internet; retries on its own. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        KavachStore.clean(c)
        val cfg = Prefs.config(c)
        val cid = cfg?.optString("cid").orEmpty()
        val auth = Prefs.auth(c)
        val demo = cfg?.optBoolean("demo", true) ?: true
        if (demo || auth == null || !auth.has("refreshToken") || cid.isEmpty()) {
            // Demo (no Firebase yet): behave like an upload so the screens show what will happen, then drop the file.
            KavachStore.list(c).filter { it.state == "queued" }.forEach { Prefs.addBytes(c, it.size); KavachStore.delete(c, it.id) }
            return@withContext Result.success()
        }
        val api = FirebaseRest(c, auth)
        try {
            KavachStore.list(c).filter { !it.registered }.forEach { m ->
                api.register(cid, m); KavachStore.update(c, m.copy(registered = true))
            }
            KavachStore.list(c).filter { it.state == "queued" }.forEach { m ->
                val f = KavachStore.file(c, m.id)
                if (f.exists()) { api.upload(cid, m.id, f.readBytes()); Prefs.addBytes(c, f.length()) }
                KavachStore.delete(c, m.id)
            }
            Result.success()
        } catch (e: FirebaseRest.HttpError) {
            Log.w("Galla", "upload paused: ${e.message}")
            if (e.code in listOf(400, 401, 403) && runAttemptCount > 5) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.w("Galla", "upload paused", e); Result.retry()
        }
    }

    companion object {
        fun kick(c: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork("kavach-upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** Daily nudge at about 10 pm to add the day's screenshots. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Galla reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kavach)
            .setContentTitle(inputData.getString("title") ?: "Galla")
            .setContentText(inputData.getString("body") ?: "")
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(c).notify(7, n)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "galla-reminder"
        private const val NAME = "galla-night-reminder"
        fun schedule(c: Context, on: Boolean, title: String, body: String) {
            val wm = WorkManager.getInstance(c)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(next.timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("title" to title, "body" to body))
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
