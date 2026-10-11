package app.galla.captain

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small settings kept on the phone: the captain code, the recorder config from the web app, and sign-in for uploads. */
object Prefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("galla", Context.MODE_PRIVATE)

    fun code(c: Context): String? = sp(c).getString("code", null)
    fun setCode(c: Context, v: String) = sp(c).edit().putString("code", v).apply()

    fun config(c: Context): JSONObject? = sp(c).getString("kv_config", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun setConfig(c: Context, j: JSONObject) = sp(c).edit().putString("kv_config", j.toString()).apply()

    fun auth(c: Context): JSONObject? = sp(c).getString("auth", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun setAuth(c: Context, j: JSONObject) = sp(c).edit().putString("auth", j.toString()).apply()

    fun idToken(c: Context): Pair<String, Long>? {
        val t = sp(c).getString("id_token", null) ?: return null
        return t to sp(c).getLong("id_token_exp", 0L)
    }
    fun setIdToken(c: Context, token: String, expiresAt: Long) =
        sp(c).edit().putString("id_token", token).putLong("id_token_exp", expiresAt).apply()

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun addBytes(c: Context, n: Long) {
        val s = sp(c); val d = today()
        val cur = if (s.getString("bytes_day", "") == d) s.getLong("bytes", 0L) else 0L
        s.edit().putString("bytes_day", d).putLong("bytes", cur + n).apply()
    }
    fun bytesToday(c: Context): Long = sp(c).let { if (it.getString("bytes_day", "") == today()) it.getLong("bytes", 0L) else 0L }

    /** Time windows (from a safety report or SOS) whose video must go to the server. */
    fun holds(c: Context): List<Pair<Long, Long>> {
        val arr = runCatching { JSONArray(sp(c).getString("holds", "[]")) }.getOrElse { JSONArray() }
        val week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONArray(i) ?: return@mapNotNull null
            val p = o.optLong(0) to o.optLong(1)
            if (p.second > week) p else null
        }
    }
    fun addHold(c: Context, from: Long, to: Long) {
        val arr = JSONArray()
        (holds(c) + (from to to)).forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        sp(c).edit().putString("holds", arr.toString()).apply()
    }
}
