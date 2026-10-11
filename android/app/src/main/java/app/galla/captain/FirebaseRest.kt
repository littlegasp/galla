package app.galla.captain

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Talks to Firebase over plain HTTPS as the same signed-in captain the web app uses
 * (the web app hands over its sign-in refresh token), so the same security rules apply.
 */
class FirebaseRest(private val ctx: Context, auth: JSONObject) {
    private val apiKey = auth.getString("apiKey")
    private val projectId = auth.getString("projectId")
    private val bucket = auth.getString("bucket")
    private var refreshToken = auth.getString("refreshToken")

    class HttpError(val code: Int, msg: String) : IOException("HTTP $code: $msg")

    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setFixedLengthStreamingMode(body.size) }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally { conn.disconnect() }
    }

    /** A short-lived sign-in token, refreshed from the long-lived one when needed. */
    private fun idToken(): String {
        Prefs.idToken(ctx)?.let { (t, exp) -> if (exp - 120_000 > System.currentTimeMillis()) return t }
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val (code, text) = request(
            "https://securetoken.googleapis.com/v1/token?key=$apiKey", "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray()
        )
        if (code != 200) throw HttpError(code, text.take(300))
        val j = JSONObject(text)
        val token = j.getString("id_token")
        val exp = System.currentTimeMillis() + j.optString("expires_in", "3600").toLong() * 1000
        Prefs.setIdToken(ctx, token, exp)
        j.optString("refresh_token").takeIf { it.isNotEmpty() && it != refreshToken }?.let {
            refreshToken = it
            Prefs.auth(ctx)?.let { a -> Prefs.setAuth(ctx, a.put("refreshToken", it)) }
        }
        return token
    }

    /** Adds one encrypted file under kavach/<code>/ (the rules only allow adding, never reading or deleting). */
    fun upload(cid: String, id: String, data: ByteArray) {
        val name = "kavach/$cid/$id.gkv"
        val boundary = "galla" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("contentType", "application/octet-stream").toString()
        val out = ByteArrayOutputStream(data.size + 512)
        out.write("--$boundary\r\nContent-Type: application/json; charset=utf-8\r\n\r\n$meta\r\n".toByteArray())
        out.write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        out.write(data)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val url = "https://firebasestorage.googleapis.com/v0/b/$bucket/o?name=" + URLEncoder.encode(name, "UTF-8")
        val (code, text) = request(url, "POST", mapOf(
            "Authorization" to "Firebase ${idToken()}",
            "Content-Type" to "multipart/related; boundary=$boundary",
            "X-Goog-Upload-Protocol" to "multipart"
        ), out.toByteArray())
        if (code !in 200..299) throw HttpError(code, text.take(300))
    }

    /** Logs the recording's fingerprint (SHA-256) with its time and place, as the web app does. */
    fun register(cid: String, m: KavachStore.Meta) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun str(v: String) = JSONObject().put("stringValue", v)
        fun int(v: Long) = JSONObject().put("integerValue", v.toString())
        fun dbl(v: Double?) = if (v == null) JSONObject().put("nullValue", "NULL_VALUE") else JSONObject().put("doubleValue", v)
        fun ts(v: Long) = JSONObject().put("timestampValue", iso.format(Date(v)))
        val fields = JSONObject()
            .put("id", str(m.id)).put("kind", str(m.kind)).put("start", int(m.start)).put("end", int(m.end))
            .put("hash", str(m.hash)).put("size", int(m.plainSize)).put("lat", dbl(m.lat)).put("lng", dbl(m.lng))
            .put("expireAt", ts(m.start + 8L * 24 * 3600 * 1000)).put("createdAt", ts(System.currentTimeMillis()))
        val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/captains/$cid/kavach?documentId=${m.id}"
        val (code, text) = request(url, "POST", mapOf(
            "Authorization" to "Bearer ${idToken()}", "Content-Type" to "application/json"
        ), JSONObject().put("fields", fields).toString().toByteArray())
        if (code !in 200..299 && code != 409) throw HttpError(code, text.take(300))   // 409 = already logged
    }
}
