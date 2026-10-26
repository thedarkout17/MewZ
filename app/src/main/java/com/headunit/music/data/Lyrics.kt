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

    /** Lyrics for [track], or null if there are none. Throws [IOException] when offline. */
    suspend fun find(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        when (val c = cache[track.videoId]) {
            is Cached.Found -> return@withContext c.lyrics
            Cached.None -> return@withContext null
            null -> {}
        }
        val title = track.title.trim()
        // "The Weeknd ft. Daft Punk" → "The Weeknd": LRCLIB lists the main artist.
        val artist = track.artist.split(Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+""", RegexOption.IGNORE_CASE))
            .first().trim()

        // 1. Exact match (title + artist, and length when we know it).
        val exact = get(
            "get",
            "track_name" to title,
            "artist_name" to artist,
            *(track.durationSeconds?.let { arrayOf("duration" to it.toString()) } ?: emptyArray()),
        )?.let { JSONObject(it) }

        // 2. Search, preferring results with synced lyrics and a length close to this song's.
        val best = exact?.takeIf { it.hasLyrics() } ?: run {
            val body = get("search", "track_name" to title, "artist_name" to artist)
                ?: get("search", "q" to "$title $artist")
            val results = body?.let { JSONArray(it) } ?: JSONArray()
            (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.hasLyrics() }
                .minByOrNull { r ->
                    val synced = if (r.optString("syncedLyrics").isNotBlank()) 0 else 1000
                    val off = track.durationSeconds?.let { d -> kotlin.math.abs(r.optDouble("duration", 0.0) - d) } ?: 0.0
                    synced + off
                }
        }

        val lyrics = best?.toLyrics()
        cache[track.videoId] = if (lyrics != null) Cached.Found(lyrics) else Cached.None
        Log.d("MuZ", "Lyrics for \"$title\" by \"$artist\": " +
            when { lyrics == null -> "none"; lyrics.synced -> "synced"; else -> "plain" })
        lyrics
    }

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
