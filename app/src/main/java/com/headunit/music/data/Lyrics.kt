package com.headunit.music.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One line of lyrics; [time] (seconds) is when it's sung, null for unsynced lyrics. */
data class LyricLine(val time: Float?, val text: String)

/** A song's lyrics. [synced] lyrics have a time on every line, so they can follow along. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** The line being sung at [seconds] (synced lyrics only), or -1 before the first. */
    fun lineAt(seconds: Float): Int {
        if (!synced) return -1
        var found = -1
        for (i in lines.indices) {
            val t = lines[i].time ?: continue
            if (t <= seconds + 0.15f) found = i else break
        }
        return found
    }
}

/**
 * Finds lyrics on LRCLIB (lrclib.net): a free, open lyrics database, no key
 * needed. Prefers synced (timed) lyrics; falls back to plain ones. Results are
 * remembered for the session, including "none found".
 */
object LyricsRepository {

    private sealed interface Cached {
        data class Found(val lyrics: Lyrics) : Cached
        data object None : Cached
    }

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Synced lyrics that fit this exact video, or null ("No lyrics found").
     * Only timed lyrics count, and when the video's length is known they must
     * be for a recording of the same length (within [LENGTH_SLACK] seconds),
     * so a different version's timings never show. Slowed / reverb / sped-up /
     * nightcore uploads never match. Throws [IOException] only when the device
     * is actually offline; a slow or failing LRCLIB just means "none" (not
     * remembered, so it's tried again next time).
     */
    suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        when (val c = cache[track.videoId]) {
            is Cached.Found -> return@withContext c.lyrics
            Cached.None -> return@withContext null
            null -> {}
        }
        // Slowed / sped up etc.: no lyrics are timed for that version.
        if (ALTERED.containsMatchIn(track.title)) {
            Log.d("MuZ", "Lyrics for \"${track.title}\": none (altered version)")
            cache[track.videoId] = Cached.None
            return@withContext null
        }
        val title = track.title.trim()
        // "The Weeknd ft. Daft Punk" → "The Weeknd": LRCLIB lists the main artist.
        val artist = track.artist.split(Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+""", RegexOption.IGNORE_CASE))
            .first().trim()
        val duration = track.durationSeconds

        var offline: IOException? = null
        var failed = false
        /** One request; remembers why it failed instead of giving up on the rest. */
        fun tryGet(endpoint: String, vararg params: Pair<String, String>): String? = try {
            get(endpoint, *params)
        } catch (e: IOException) {
            Log.w("MuZ", "LRCLIB $endpoint failed: ${e.javaClass.simpleName} ${e.message}")
            if (isOffline(e)) offline = e else failed = true
            null
        }
        /** Timed lyrics for a recording this video's length (when we know it). */
        fun fits(r: JSONObject): Boolean =
            r.hasSynced() && (duration == null || kotlin.math.abs(r.optDouble("duration", -999.0) - duration) <= LENGTH_SLACK)
        fun results(body: String?): List<JSONObject> {
            val arr = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }.filter(::fits)
        }
        fun pick(list: List<JSONObject>): JSONObject? = list.minByOrNull { r ->
            duration?.let { d -> kotlin.math.abs(r.optDouble("duration", 0.0) - d) } ?: 0.0
        }

        // 1. Exact match from LRCLIB's own database ("get-cached": quick; the plain "get"
        //    goes off to other sources for unknown songs and can take ages).
        val exact = tryGet(
            "get-cached",
            "track_name" to title,
            "artist_name" to artist,
            *(duration?.let { arrayOf("duration" to it.toString()) } ?: emptyArray()),
        )?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf(::fits)

        // 2. Search by title + artist, then 3. by both as free text.
        val best = exact
            ?: pick(results(tryGet("search", "track_name" to title, "artist_name" to artist)))
            ?: pick(results(tryGet("search", "q" to "$title $artist")))

        val lyrics = best?.toLyrics()?.takeIf { it.synced }
        Log.d("MuZ", "Lyrics for \"$title\" by \"$artist\" (${duration ?: "?"} s): " +
            when { lyrics != null -> "synced"; offline != null -> "offline"; failed -> "LRCLIB error"; else -> "none (no synced lyrics this length)" })

        when {
            lyrics != null -> cache[track.videoId] = Cached.Found(lyrics)
            offline != null -> throw offline!!
            // Only remember "none" when LRCLIB actually answered; errors get another try later.
            !failed -> cache[track.videoId] = Cached.None
        }
        lyrics
    }

    /** Can't reach the internet at all (as opposed to LRCLIB being slow or erroring). */
    private fun isOffline(e: IOException) =
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException

    private val ALTERED = Regex(
        """slowed|reverb|sped[\s-]*up|speed[\s-]*up|nightcore|daycore|8d\s*audio|bass\s*boosted|chopped|screwed""",
        RegexOption.IGNORE_CASE,
    )

    private fun JSONObject.hasSynced() =
        !optBoolean("instrumental") && optString("syncedLyrics").let { it.isNotBlank() && it != "null" }

    /** How far (seconds) the lyrics' recording may be from the video's length. */
    private const val LENGTH_SLACK = 3.0

    /** Only timed lyrics: plain ones aren't shown. */
    private fun JSONObject.toLyrics(): Lyrics? {
        val synced = optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val lines = parseLrc(synced)
        return if (lines.isEmpty()) null else Lyrics(lines, synced = true)
    }

    /** "[01:23.45] Some line" (a line can carry several times) → timed lines, in order. */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d{1,2}(?:[.:]\d{1,3})?)]""")
        return lrc.lines().flatMap { raw ->
            val times = stamp.findAll(raw).map { m ->
                m.groupValues[1].toInt() * 60f + m.groupValues[2].replace(':', '.').toFloat()
            }.toList()
            val text = raw.replace(stamp, "").trim()
            times.map { LyricLine(it, text) }
        }.sortedBy { it.time }
    }

    /** GET lrclib.net/api/[endpoint]; null on 404 (nothing found). */
    private fun get(endpoint: String, vararg params: Pair<String, String>): String? {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("https://lrclib.net/api/$endpoint?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        // LRCLIB asks apps to say who they are.
        conn.setRequestProperty("User-Agent", "MewZ headunit music player (github.com/thedarkout17/MewZ)")
        try {
            return when (conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("LRCLIB HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
