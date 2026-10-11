package app.galla.captain

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Sealed recordings on the phone: <id>.gkv (encrypted file) + <id>.json (small record).
 * state: "queued" = waiting to upload, "local" = video kept on the phone only (deleted after 2 hours).
 */
object KavachStore {
    const val VIDEO_KEEP_MS = 2L * 3600 * 1000
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    data class Meta(
        val id: String, val kind: String, val start: Long, val end: Long, val hash: String,
        val size: Long, val plainSize: Long, val state: String, val registered: Boolean,
        val lat: Double?, val lng: Double?
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("start", start).put("end", end)
            .put("hash", hash).put("size", size).put("plainSize", plainSize).put("state", state).put("registered", registered)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)

        companion object {
            fun from(j: JSONObject) = Meta(
                j.getString("id"), j.getString("kind"), j.getLong("start"), j.getLong("end"), j.getString("hash"),
                j.optLong("size"), j.optLong("plainSize"), j.optString("state", "local"), j.optBoolean("registered", false),
                if (j.isNull("lat")) null else j.optDouble("lat"), if (j.isNull("lng")) null else j.optDouble("lng")
            )
        }
    }

    private fun dir(c: Context) = File(c.filesDir, "kavach").apply { mkdirs() }
    fun file(c: Context, id: String) = File(dir(c), "$id.gkv")
    private fun metaFile(c: Context, id: String) = File(dir(c), "$id.json")

    @Synchronized fun add(c: Context, m: Meta, sealed: ByteArray) {
        file(c, m.id).writeBytes(sealed)
        metaFile(c, m.id).writeText(m.toJson().toString())
    }

    @Synchronized fun list(c: Context): List<Meta> =
        (dir(c).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { Meta.from(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.start }

    @Synchronized fun update(c: Context, m: Meta) { if (metaFile(c, m.id).exists()) metaFile(c, m.id).writeText(m.toJson().toString()) }

    @Synchronized fun delete(c: Context, id: String) { file(c, id).delete(); metaFile(c, id).delete() }

    fun covered(c: Context, start: Long, end: Long) = Prefs.holds(c).any { end >= it.first && start <= it.second }

    /** A report or SOS covers [from, to]: send the phone-only video from that time. */
    fun hold(c: Context, from: Long, to: Long) {
        Prefs.addHold(c, from, to)
        list(c).filter { it.kind == "video" && it.state == "local" && it.end >= from && it.start <= to }
            .forEach { update(c, it.copy(state = "queued")) }
    }

    fun clean(c: Context) {
        val now = System.currentTimeMillis()
        list(c).forEach {
            val tooOld = it.start < now - MAX_AGE_MS
            val oldVideo = it.kind == "video" && it.state == "local" && it.end < now - VIDEO_KEEP_MS
            if (tooOld || oldVideo) delete(c, it.id)
        }
    }

    fun status(c: Context): JSONObject {
        val all = list(c)
        return JSONObject()
            .put("on", KavachService.running)
            .put("startedAt", KavachService.startedAt)
            .put("queuedAudio", all.count { it.kind == "audio" && it.state == "queued" })
            .put("queuedVideo", all.count { it.kind == "video" && it.state == "queued" })
            .put("localVideoMs", all.filter { it.kind == "video" && it.state == "local" }.sumOf { it.end - it.start })
            .put("bytesToday", Prefs.bytesToday(c))
            .put("gps", KavachService.gpsOk)
            .put("video", KavachService.videoActive)
    }
}
