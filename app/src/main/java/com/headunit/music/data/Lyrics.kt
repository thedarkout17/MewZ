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
     * Lyrics for [track], or null if there are none. Throws [IOException] only
     * when the device is actually offline; a slow or failing LRCLIB just means
     * "none" (not remembered, so it's tried again next time).
     *
     * Slowed / reverb / sped-up / nightcore uploads get the original song's
     * lyrics, unsynced (the original's timings wouldn't match).
     */
    suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        when (val c = cache[track.videoId]) {
            is Cached.Found -> return@withContext c.lyrics
            Cached.None -> return@withContext null
            null -> {}
        }
        val altered = ALTERED.containsMatchIn(track.title)
        val title = originalTitle(track.title)
        // "The Weeknd ft. Daft Punk" → "The Weeknd": LRCLIB lists the main artist.
        val artist = track.artist.split(Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+""", RegexOption.IGNORE_CASE))
            .first().trim()
        // An altered version's length doesn't match the original's.
        val duration = track.durationSeconds?.takeIf { !altered }

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
        fun results(body: String?): List<JSONObject> {
            val arr = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.hasLyrics() }
        }
        fun pick(list: List<JSONObject>): JSONObject? = list.minByOrNull { r ->
            val synced = if (r.optString("syncedLyrics").isNotBlank()) 0.0 else 1000.0
            val off = duration?.let { d -> kotlin.math.abs(r.optDouble("duration", 0.0) - d) } ?: 0.0
            synced + off
        }

        // 1. Exact match from LRCLIB's own database ("get-cached": quick; the plain "get"
        //    goes off to other sources for unknown songs and can take ages).
        val exact = tryGet(
            "get-cached",
            "track_name" to title,
            "artist_name" to artist,
            *(duration?.let { arrayOf("duration" to it.toString()) } ?: emptyArray()),
        )?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.hasLyrics() }

        // 2. Search by title + artist, then 3. by both as free text.
        val best = exact
            ?: pick(results(tryGet("search", "track_name" to title, "artist_name" to artist)))
            ?: pick(results(tryGet("search", "q" to "$title $artist")))

        var lyrics = best?.toLyrics()
        // Slowed / sped up: the original's words, without its (wrong) timings.
        if (lyrics != null && altered && lyrics.synced) {
            lyrics = Lyrics(lyrics.lines.map { LyricLine(null, it.text) }, synced = false)
        }
        Log.d("MuZ", "Lyrics for \"$title\" by \"$artist\"" + (if (altered) " (altered version)" else "") + ": " +
            when { lyrics != null -> if (lyrics.synced) "synced" else "plain"; offline != null -> "offline"; failed -> "LRCLIB error"; else -> "none" })

        when {
            lyrics != null -> cache[track.videoId] = Cached.Found(lyrics)
            offline != null -> throw offline!!
            // Only remember "none" when LRCLIB actually answered; errors get another try later.
            !failed -> cache[track.videoId] = Cached.None
        }
        lyrics
    }

    /** "Starboy (Slowed + Reverb)" / "Starboy - sped up" → "Starboy". */
    private fun originalTitle(title: String): String {
        var t = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]""")) { m ->
            if (ALTERED.containsMatchIn(m.value)) "" else m.value
        }
        t = t.replace(Regex("""\s*[-|–—]\s*[^-|–—]*(?:${ALTERED.pattern})[^-|–—]*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(ALTERED, "").replace(Regex("""\s+[+&x]\s*$"""), "")
        return t.replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { title.trim() }
    }

    /** Can't reach the internet at all (as opposed to LRCLIB being slow or erroring). */
    private fun isOffline(e: IOException) =
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException

    private val ALTERED = Regex(
        """slowed|reverb|sped[\s-]*up|speed[\s-]*up|nightcore|daycore|8d\s*audio|bass\s*boosted|chopped|screwed""",
        RegexOption.IGNORE_CASE,
    )

    private fun JSONObject.hasLyrics() =
        !optBoolean("instrumental") &&
            (optString("syncedLyrics").isNotBlank() || optString("plainLyrics").isNotBlank())

    private fun JSONObject.toLyrics(): Lyrics? {
        val synced = optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Lyrics(plain.lines().map { LyricLine(null, it.trim()) }, synced = false)
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
