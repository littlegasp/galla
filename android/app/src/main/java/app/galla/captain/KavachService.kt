package app.galla.captain

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Galla Kavach recorder. Runs as a foreground service (with a permanent notification) so it keeps
 * recording while Rapido, Uber or Maps is on screen and while the screen is off.
 * Every minute becomes one file. With video on, each minute's MP4 is kept (sealed) on the phone for
 * 2 hours and its sound is also cut out into a small audio file that is uploaded straight away.
 */
class KavachService : LifecycleService() {

    companion object {
        private const val TAG = "GallaKavach"
        private const val CHANNEL = "galla-kavach"
        private const val NOTIF_ID = 42
        private const val SEG_MS = 60_000L
        const val ACTION_START = "app.galla.captain.START"
        const val ACTION_STOP = "app.galla.captain.STOP"

        @Volatile var running = false
        @Volatile var startedAt = 0L
        @Volatile var gpsOk = true
        @Volatile var videoActive = false

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, KavachService::class.java).setAction(ACTION_START))

        fun stop(c: Context) {
            if (running) c.startService(Intent(c, KavachService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var cfg = JSONObject()
    private var wake: PowerManager.WakeLock? = null

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var audioRec: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioStart = 0L

    private var stopping = false
    private var lm: LocationManager? = null
    private var lastLoc: Location? = null
    private val locListener = LocationListener { lastLoc = it }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            if (videoActive) recording?.stop()               // Finalize event starts the next minute
            else { finishAudioSegment(); startAudioSegment() }
            main.postDelayed(this, SEG_MS)
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            else -> if (!running) begin()
        }
        return START_NOT_STICKY
    }

    private fun text(key: String, fallback: String) = cfg.optJSONObject("text")?.optString(key)?.takeIf { it.isNotEmpty() } ?: fallback

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Galla Kavach", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Galla Kavach is recording"
        })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, KavachService::class.java).setAction(ACTION_STOP), flags)
        val sos = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SOS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kavach)
            .setContentTitle(text("title", "Galla Kavach"))
            .setContentText(text("recording", "Recording"))
            .setColor(0xFFE53935.toInt())
            .setOngoing(true)
            .setUsesChronometer(true).setWhen(startedAt).setShowWhen(true)
            .setContentIntent(open)
            .addAction(0, text("sos", "SOS"), sos)
            .addAction(0, text("stop", "Stop"), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun begin() {
        cfg = Prefs.config(this) ?: JSONObject()
        if (!has(Manifest.permission.RECORD_AUDIO)) { Log.w(TAG, "no microphone permission"); stopSelf(); return }
        val wantVideo = cfg.optBoolean("video", true) && has(Manifest.permission.CAMERA)
        val hasLoc = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        startedAt = System.currentTimeMillis()
        var types = 0
        if (Build.VERSION.SDK_INT >= 30) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (wantVideo) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLoc) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), types)
        } catch (e: Exception) {
            Log.e(TAG, "could not start foreground", e); stopSelf(); return
        }
        running = true; stopping = false
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galla:kavach").apply { acquire(14 * 3600 * 1000L) }
        if (hasLoc) startLocation()
        if (wantVideo) startCamera() else { videoActive = false; startAudioSegment() }
        main.postDelayed(rotate, SEG_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        lm = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching { if (lm?.isProviderEnabled(p) == true) lm?.requestLocationUpdates(p, 30_000L, 20f, locListener, Looper.getMainLooper()) }
        }
        lastLoc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    private fun freshLoc(): Location? {
        val l = lastLoc
        gpsOk = l != null && System.currentTimeMillis() - l.time < 5 * 60_000
        return if (gpsOk) l else null
    }

    /* ---------- video (CameraX), one MP4 per minute ---------- */

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .setTargetVideoEncodingBitRate(600_000)
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                val selector = if (cfg.optString("facing") == "back") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                // A preview use case too, so the Kavach screen can show what the camera sees while recording.
                val pv = Preview.Builder().build()
                PreviewHub.surface?.let { pv.setSurfaceProvider(it) }
                p.unbindAll()                                // also releases the screen's "camera check"
                p.bindToLifecycle(this, selector, pv, vc)
                PreviewHub.servicePreview = pv
                videoCapture = vc; videoActive = true
                PreviewHub.onCameraChanged?.invoke()
                startVideoSegment()
            } catch (e: Exception) {
                Log.e(TAG, "camera unavailable, recording audio only", e)
                videoActive = false
                if (running) startAudioSegment()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startVideoSegment() {
        val vc = videoCapture ?: return
        val start = System.currentTimeMillis()
        val file = File(cacheDir, "seg-$start.mp4")
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                val end = System.currentTimeMillis()
                io.execute { processVideo(file, start, end) }
                if (running && !stopping) startVideoSegment() else finishStop()
            }
        }
    }

    private fun processVideo(file: File, start: Long, end: Long) {
        try {
            if (!file.exists() || file.length() < 1024) return
            val audio = File(cacheDir, "seg-$start.m4a")
            if (extractAudio(file, audio)) { store("audio", start, end, "audio/mp4", audio.readBytes()); audio.delete() }
            store("video", start, end, "video/mp4", file.readBytes())
        } catch (e: Exception) {
            Log.e(TAG, "could not save a minute of video", e)
        } finally {
            file.delete(); UploadWorker.kick(this)
        }
    }

    /** Copies the sound track of an MP4 into its own small file, without re-encoding. */
    private fun extractAudio(src: File, dst: File): Boolean {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        return try {
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return false
            ex.selectTrack(track)
            val m = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); mux = m
            val out = m.addTrack(ex.getTrackFormat(track))
            m.start()
            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(out, buf, info)
                samples++
                ex.advance()
            }
            m.stop()
            samples > 0
        } catch (e: Exception) {
            Log.w(TAG, "no audio track", e); false
        } finally {
            runCatching { mux?.release() }
            ex.release()
        }
    }

    /* ---------- audio only (video off or no camera) ---------- */

    private fun startAudioSegment() {
        try {
            val start = System.currentTimeMillis()
            val f = File(cacheDir, "aud-$start.m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(22_050)
            r.setAudioChannels(1)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            audioRec = r; audioFile = f; audioStart = start
        } catch (e: Exception) {
            Log.e(TAG, "microphone busy", e)
        }
    }

    private fun finishAudioSegment() {
        val r = audioRec ?: return
        audioRec = null
        runCatching { r.stop() }
        r.release()
        val f = audioFile ?: return
        val st = audioStart; val en = System.currentTimeMillis()
        io.execute {
            try { if (f.length() > 512) store("audio", st, en, "audio/mp4", f.readBytes()) }
            catch (e: Exception) { Log.e(TAG, "could not save a minute of audio", e) }
            finally { f.delete(); UploadWorker.kick(this) }
        }
    }

    /* ---------- seal and keep ---------- */

    private fun store(kind: String, start: Long, end: Long, mime: String, plain: ByteArray) {
        val loc = freshLoc()
        val rand = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val id = "$start-$kind-$rand"
        val lat = loc?.let { Math.round(it.latitude * 1e5) / 1e5 }
        val lng = loc?.let { Math.round(it.longitude * 1e5) / 1e5 }
        val head = JSONObject().put("id", id).put("cid", cfg.optString("cid")).put("kind", kind)
            .put("start", start).put("end", end).put("mime", mime)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
            .put("acc", loc?.accuracy?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("device", "android")
        val sealed = Sealer.seal(plain, head, Sealer.keyFor(cfg))
        val state = if (kind == "audio" || KavachStore.covered(this, start, end)) "queued" else "local"
        KavachStore.add(this, KavachStore.Meta(id, kind, start, end, sealed.hash, sealed.bytes.size.toLong(),
            sealed.plainSize.toLong(), state, false, lat, lng), sealed.bytes)
    }

    /* ---------- stop ---------- */

    private fun stopAll() {
        if (!running) { stopSelf(); return }
        running = false; stopping = true
        main.removeCallbacks(rotate)
        finishAudioSegment()
        val rec = recording
        if (rec != null && videoActive) {
            rec.stop()                                       // finishStop() runs on the Finalize event
            main.postDelayed({ finishStop() }, 4000)         // safety net
        } else finishStop()
    }

    private fun finishStop() {
        if (!stopping) return
        stopping = false
        recording = null
        runCatching { provider?.unbindAll() }
        PreviewHub.servicePreview = null
        runCatching { lm?.removeUpdates(locListener) }
        runCatching { if (wake?.isHeld == true) wake?.release() }
        videoActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        PreviewHub.onCameraChanged?.invoke()                 // the screen can take the camera back for "camera check"
    }

    override fun onDestroy() {
        running = false; videoActive = false
        PreviewHub.servicePreview = null
        main.removeCallbacksAndMessages(null)
        runCatching { recording?.stop() }
        runCatching { audioRec?.stop(); audioRec?.release() }
        runCatching { lm?.removeUpdates(locListener) }
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }
}
