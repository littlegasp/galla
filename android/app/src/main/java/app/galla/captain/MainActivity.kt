package app.galla.captain

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * The Galla app: shows the Galla web app (earnings, week report, settings, Kavach screens) and
 * gives it native powers through the "GallaNative" bridge: background recording, uploads,
 * phone/SMS links, screenshot picking and the 10 pm reminder.
 */
class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_SOS = "sos" }

    private var web: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingStart = false
    private var pageReady = false
    private var sosWaiting = false

    // Live camera preview drawn over the web page's camera frame on the Kavach screen.
    private var previewView: PreviewView? = null
    private var checkPreview: Preview? = null          // the screen's own preview when not recording
    private var checkFacing = ""
    private var lastPreview: JSONObject? = null
    private var askedCamera = false

    // Screenshots shared to Galla from the gallery / screenshot toast, waiting for the page to be ready.
    private var sharedUris: List<Uri> = emptyList()
    private val bg = Executors.newSingleThreadExecutor()

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray()); fileCallback = null
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (pendingStart) {
            pendingStart = false
            val mic = res[Manifest.permission.RECORD_AUDIO] ?: has(Manifest.permission.RECORD_AUDIO)
            if (mic) KavachService.start(this)
            else Toast.makeText(this, "Microphone permission is needed for Kavach", Toast.LENGTH_LONG).show()
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) lastPreview?.let { updatePreview(it) }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sosWaiting = intent?.getBooleanExtra(EXTRA_SOS, false) == true
        intent?.let { takeShared(it) }
        val code = Prefs.code(this)
        if (code.isNullOrBlank()) showCodeScreen() else showWeb(code)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w == null) { finish(); return }
                w.evaluateJavascript("(window.gallaBack && window.gallaBack()) ? 'y' : 'n'") { r -> if (r?.contains("y") != true) finish() }
            }
        })
        PreviewHub.onCameraChanged = { lastPreview?.let { updatePreview(it) } }
        UploadWorker.kick(this)
    }

    override fun onResume() {
        super.onResume()
        web?.evaluateJavascript("window.gallaPreviewSync && window.gallaPreviewSync()", null)
    }

    override fun onPause() {
        hidePreview()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SOS, false)) { sosWaiting = true; runSosIfReady() }
        takeShared(intent); deliverShared()
    }

    /* ---------- "Share → Galla" ---------- */

    private fun takeShared(i: Intent) {
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris = uris.take(8)
    }

    /** Shrinks each shared image (720 px JPEG, like the web app does) and hands them to the screenshot import. */
    private fun deliverShared() {
        val w = web ?: return
        if (!pageReady || sharedUris.isEmpty()) return
        val uris = sharedUris; sharedUris = emptyList()
        bg.execute {
            val urls = JSONArray()
            uris.forEach { u -> shrink(u)?.let { urls.put(it) } }
            if (urls.length() > 0) runOnUiThread { w.evaluateJavascript("window.gallaImportShared && window.gallaImportShared($urls)", null) }
        }
    }

    private fun shrink(u: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
        val bmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (bmp == null) null else {
            val scaled = if (bmp.width > 720) Bitmap.createScaledBitmap(bmp, 720, (bmp.height * 720f / bmp.width).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }

    private fun runSosIfReady() {
        if (sosWaiting && pageReady) {
            sosWaiting = false
            web?.evaluateJavascript("window.gallaNativeSOS && window.gallaNativeSOS()", null)
        }
    }

    /* ---------- first open: the captain's personal code ---------- */

    private fun showCodeScreen() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212")); setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Galla"; textSize = 40f; setTextColor(Color.parseColor("#FFC629")); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val help = TextView(this).apply {
            text = "अपना Galla कोड या लिंक डालें\nEnter your Galla code or paste your link"
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad)
        }
        val input = EditText(this).apply {
            hint = "U5UZALG4"; textSize = 22f; setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.WHITE); setPadding(pad, pad / 2, pad, pad / 2)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val go = Button(this).apply {
            text = "शुरू करें · Start"; textSize = 20f; setTextColor(Color.parseColor("#121212"))
            setBackgroundColor(Color.parseColor("#FFC629"))
        }
        go.setOnClickListener {
            val raw = input.text.toString().trim()
            val code = Regex("[?&]c=([A-Za-z0-9]{6,20})").find(raw)?.groupValues?.get(1)
                ?: raw.takeIf { Regex("^[A-Za-z0-9]{6,20}$").matches(it) }
            if (code == null) { Toast.makeText(this, "कोड सही नहीं है · Code not valid", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            Prefs.setCode(this, code)
            showWeb(code)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 }
        box.addView(title); box.addView(help); box.addView(input, lp); box.addView(go, LinearLayout.LayoutParams(lp).apply { topMargin = pad })
        setContentView(box)
    }

    /* ---------- the web app ---------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(code: String) {
        val w = WebView(this)
        web = w
        w.setBackgroundColor(Color.parseColor("#F6F2E7"))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            userAgentString = "$userAgentString GallaAndroid/${BuildConfig.VERSION_NAME}"
        }
        w.addJavascriptInterface(Bridge(), "GallaNative")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val ours = u.host == Uri.parse(BuildConfig.APP_URL).host && (u.path ?: "").startsWith("/galla")
                if (ours) return false
                openOutside(u); return true                       // tel:, sms:, WhatsApp and other sites
            }
            override fun onPageFinished(view: WebView, url: String) { pageReady = true; runSosIfReady(); deliverShared() }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(emptyArray())
                fileCallback = cb
                return try { pickImages.launch("image/*"); true } catch (e: ActivityNotFoundException) { fileCallback = null; false }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
            }
        }
        val root = FrameLayout(this)
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // draws correctly inside the rounded box
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
            val r = 12 * resources.displayMetrics.density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, r) }
            }
            clipToOutline = true
        }
        root.addView(pv, FrameLayout.LayoutParams(1, 1))
        previewView = pv
        setContentView(root)
        w.loadUrl(BuildConfig.APP_URL + "?c=" + Uri.encode(code))
    }

    private fun openOutside(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, u.toString(), Toast.LENGTH_LONG).show() }
    }

    /** Asks for whatever Kavach still needs, then starts the recorder. */
    private fun startKavach(video: Boolean) {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (video) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { !has(it) }
        if (missing.isEmpty()) KavachService.start(this)
        else { pendingStart = true; askPerms.launch(missing.toTypedArray()) }
    }

    /* ---------- camera preview on the Kavach screen ---------- */

    /** The web page says where its camera frame is (CSS px = dp) and whether it's on screen. */
    private fun updatePreview(j: JSONObject) {
        lastPreview = j
        val pv = previewView ?: return
        if (!j.optBoolean("show")) { hidePreview(); return }
        val d = resources.displayMetrics.density
        pv.layoutParams = FrameLayout.LayoutParams((j.optInt("w") * d).toInt(), (j.optInt("h") * d).toInt()).apply {
            leftMargin = (j.optInt("x") * d).toInt(); topMargin = (j.optInt("y") * d).toInt()
        }
        pv.visibility = View.VISIBLE
        if (KavachService.running) {
            // Recording: show the recorder's own camera feed.
            releaseCheck()
            PreviewHub.attach(pv.surfaceProvider)
        } else {
            PreviewHub.detach()
            val facing = j.optString("facing", "front")
            if (checkPreview == null || checkFacing != facing) startCheck(facing)
        }
    }

    private fun hidePreview() {
        previewView?.visibility = View.GONE
        PreviewHub.detach()
        releaseCheck()
    }

    /** "Camera check" before recording, so the captain can set the phone in its holder. */
    private fun startCheck(facing: String) {
        if (!has(Manifest.permission.CAMERA)) {
            if (!askedCamera) { askedCamera = true; askCamera.launch(Manifest.permission.CAMERA) }
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val pv = previewView ?: return@addListener
            if (KavachService.running || pv.visibility != View.VISIBLE) return@addListener
            try {
                val p = future.get()
                checkPreview?.let { p.unbind(it) }
                val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val sel = if (facing == "back") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                p.bindToLifecycle(this, sel, prev)
                checkPreview = prev; checkFacing = facing
            } catch (e: Exception) { checkPreview = null }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releaseCheck() {
        val prev = checkPreview ?: return
        checkPreview = null; checkFacing = ""
        runCatching { ProcessCameraProvider.getInstance(this).get().unbind(prev) }
    }

    override fun onDestroy() {
        PreviewHub.onCameraChanged = null
        hidePreview()
        web?.destroy(); web = null
        super.onDestroy()
    }

    /** What the web app can call. Runs on a background thread, so UI work is posted to the main thread. */
    inner class Bridge {
        @JavascriptInterface
        fun start(json: String): String {
            return try {
                val cfg = JSONObject(json)
                Prefs.setConfig(this@MainActivity, cfg)
                cfg.optJSONObject("auth")?.let { if (it.optString("refreshToken").isNotEmpty()) Prefs.setAuth(this@MainActivity, it) }
                runOnUiThread { startKavach(cfg.optBoolean("video", true)) }
                "ok"
            } catch (e: Exception) { "error:" + e.message }
        }

        @JavascriptInterface fun stop() = KavachService.stop(this@MainActivity)

        @JavascriptInterface fun status(): String = KavachStore.status(this@MainActivity).toString()

        @JavascriptInterface
        fun hold(from: Double, to: Double) {
            KavachStore.hold(this@MainActivity, from.toLong(), to.toLong())
            UploadWorker.kick(this@MainActivity)
        }

        @JavascriptInterface
        fun setAuth(json: String) {
            runCatching { Prefs.setAuth(this@MainActivity, JSONObject(json)); UploadWorker.kick(this@MainActivity) }
        }

        @JavascriptInterface
        fun setReminder(on: Boolean, title: String, body: String) {
            if (on && Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                runOnUiThread { askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            ReminderWorker.schedule(this@MainActivity, on, title, body)
        }

        @JavascriptInterface
        fun preview(json: String) { runCatching { val j = JSONObject(json); runOnUiThread { updatePreview(j) } } }

        // Test mode for automatic earnings (ride-app notifications).
        @JavascriptInterface fun notifAccess(): Boolean = NotifLog.hasAccess(this@MainActivity)

        @JavascriptInterface
        fun notifSettings() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (e: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }

        @JavascriptInterface fun notifLog(): String = NotifLog.read(this@MainActivity).toString()

        @JavascriptInterface fun notifClear() = NotifLog.clear(this@MainActivity)

        /** The captain chooses where to send the log (e.g. WhatsApp to the Galla team). */
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, "Galla"))
            }
        }

        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
    }
}
