package app.galla.captain

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TEST MODE for automatic earnings. With the captain's permission ("Notification access"), notes the
 * notifications that ride apps (Rapido, Uber, Ola, Namma Yatri, inDrive…) show on this phone, so we
 * can learn which ones tell us "online / offline" and "trip done, ₹X". Nothing from other apps is kept.
 * The log stays on this phone; the captain can look at it, clear it, or choose to share it.
 */
class RideNotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { NotifLog.record(this, it, "present") } }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) = NotifLog.record(this, sbn, "posted")
    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotifLog.record(this, sbn, "removed")
}

object NotifLog {
    private const val KEEP_MS = 3L * 24 * 3600 * 1000
    private const val MAX_LINES = 1500
    /** Words that mark a ride app by package name or app name. */
    private val RIDE_WORDS = listOf("rapido", "uber", "olacabs", "ola driver", "oladriver", "yatri", "indrive", "indriver",
        "driver", "captain", "partner", "blusmart", "meru", "porter")
    private val labels = HashMap<String, String>()
    private val lastByKey = HashMap<String, JSONObject>()

    fun hasAccess(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private fun file(c: Context) = File(c.filesDir, "notif-log.jsonl")

    private fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString() }
            .getOrDefault(pkg)
    }

    private fun isRideApp(pkg: String, label: String): Boolean {
        if (pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("app.galla")) return false
        val s = (pkg + " " + label).lowercase()
        return RIDE_WORDS.any { s.contains(it) }
    }

    @Synchronized
    fun record(c: Context, sbn: StatusBarNotification, event: String) {
        try {
            val pkg = sbn.packageName ?: return
            val lbl = label(c, pkg)
            if (!isRideApp(pkg, lbl)) return
            val n = sbn.notification ?: return
            val ex = n.extras
            fun s(k: String) = ex?.getCharSequence(k)?.toString()?.take(300) ?: ""
            val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            val now = System.currentTimeMillis()
            val o = JSONObject()
                .put("t", now).put("ev", event).put("pkg", pkg).put("app", lbl).put("key", sbn.key)
                .put("ongoing", ongoing).put("cat", n.category ?: "").put("ch", n.channelId ?: "")
                .put("title", s(Notification.EXTRA_TITLE)).put("text", s(Notification.EXTRA_TEXT))
                .put("sub", s(Notification.EXTRA_SUB_TEXT)).put("big", s(Notification.EXTRA_BIG_TEXT))
            // Ongoing notifications often refresh every few seconds (timers): note the change, not every tick.
            val prev = lastByKey[sbn.key]
            if (event == "posted" && prev != null && now - prev.optLong("t") < 60_000 &&
                prev.optString("title") == o.optString("title") &&
                (ongoing || prev.optString("text") == o.optString("text"))) return
            lastByKey[sbn.key] = o
            file(c).appendText(o.toString() + "\n")
            if (Math.random() < 0.05) trim(c)
        } catch (_: Exception) { }
    }

    @Synchronized
    private fun trim(c: Context) {
        val f = file(c); if (!f.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val keep = f.readLines().filter { runCatching { JSONObject(it).optLong("t") >= cutoff }.getOrDefault(false) }.takeLast(MAX_LINES)
        f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n")
    }

    @Synchronized
    fun read(c: Context): JSONArray {
        trim(c)
        val arr = JSONArray()
        val f = file(c)
        if (f.exists()) f.readLines().forEach { line -> runCatching { arr.put(JSONObject(line)) } }
        return arr
    }

    @Synchronized
    fun clear(c: Context) { file(c).delete(); lastByKey.clear() }
}
