package app.galla.captain

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sosWaiting = intent?.getBooleanExtra(EXTRA_SOS, false) == true
        val code = Prefs.code(this)
        if (code.isNullOrBlank()) showCodeScreen() else showWeb(code)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w == null) { finish(); return }
                w.evaluateJavascript("(window.gallaBack && window.gallaBack()) ? 'y' : 'n'") { r -> if (r?.contains("y") != true) finish() }
            }
        })
        UploadWorker.kick(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SOS, false)) { sosWaiting = true; runSosIfReady() }
    }

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
        val hint = TextView(this).apply {
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
        box.addView(title); box.addView(hint); box.addView(input, lp); box.addView(go, LinearLayout.LayoutParams(lp).apply { topMargin = pad })
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
            override fun onPageFinished(view: WebView, url: String) { pageReady = true; runSosIfReady() }
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
        setContentView(w)
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

    override fun onDestroy() {
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

        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
    }
}
