package com.headunit.music.phone.data

import com.headunit.music.core.model.Lrc
import com.headunit.music.core.model.LyricLine
import com.headunit.music.core.model.Lyrics
import com.headunit.music.core.model.Track
import com.headunit.music.core.sources.LyricsSource
import com.headunit.music.phone.log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (lrclib.net): free, open, no key. Prefers synced (timed)
 * lyrics, falls back to plain ones; remembers results (including "none") for
 * the session. Same as the headunit's LyricsRepository.
 */
object LrcLibLyrics : LyricsSource {

    private sealed interface Cached {
        data class Found(val lyrics: Lyrics) : Cached
        data object None : Cached
    }

    private val cache = HashMap<String, Cached>()

    class Offline(message: String) : Exception(message)

    override suspend fun find(track: Track): Lyrics? {
        when (val c = cache[track.videoId]) {
            is Cached.Found -> return c.lyrics
            Cached.None -> return null
            null -> {}
        }
        val title = track.title.trim()
        // "The Weeknd ft. Daft Punk" → "The Weeknd": LRCLIB lists the main artist.
        val artist = track.artist.split(Regex("""\s+(?:ft\.?|feat\.?|featuring)\s+""", RegexOption.IGNORE_CASE))
            .first().trim()

        val exact = get(
            "get",
            "track_name" to title,
            "artist_name" to artist,
            *(track.durationSeconds?.let { arrayOf("duration" to it.toString()) } ?: emptyArray()),
        )?.let { Http.json.parseToJsonElement(it) as? JsonObject }

        val best = exact?.takeIf { it.hasLyrics() } ?: run {
            val body = get("search", "track_name" to title, "artist_name" to artist)
                ?: get("search", "q" to "$title $artist")
            val results = body?.let { Http.json.parseToJsonElement(it) as? JsonArray }.orEmpty()
            results.mapNotNull { it as? JsonObject }
                .filter { it.hasLyrics() }
                .minByOrNull { r ->
                    val synced = if (r.str("syncedLyrics").isNullOrBlank()) 1000.0 else 0.0
                    val off = track.durationSeconds?.let { d -> abs((r.str("duration")?.toDoubleOrNull() ?: 0.0) - d) } ?: 0.0
                    synced + off
                }
        }

        val lyrics = best?.toLyrics()
        cache[track.videoId] = if (lyrics != null) Cached.Found(lyrics) else Cached.None
        log("Lyrics for \"$title\" by \"$artist\": " + when { lyrics == null -> "none"; lyrics.synced -> "synced"; else -> "plain" })
        return lyrics
    }

    private fun JsonObject.hasLyrics() =
        str("instrumental") != "true" && (!str("syncedLyrics").isNullOrBlank() || !str("plainLyrics").isNullOrBlank())

    private fun JsonObject.toLyrics(): Lyrics? {
        str("syncedLyrics")?.takeIf { it.isNotBlank() }?.let { synced ->
            val lines = Lrc.parse(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = str("plainLyrics")?.takeIf { it.isNotBlank() } ?: return null
        return Lyrics(plain.lines().map { LyricLine(null, it.trim()) }, synced = false)
    }

    /** GET lrclib.net/api/[endpoint]; null on 404 (nothing found). Throws [Offline] when it can't be reached. */
    private suspend fun get(endpoint: String, vararg params: Pair<String, String>): String? {
        val query = params.joinToString("&") { (k, v) -> "$k=${encodeQuery(v)}" }
        val response = try {
            Http.client.get("https://lrclib.net/api/$endpoint?$query") {
                // LRCLIB asks apps to say who they are.
                header("User-Agent", "MewZ iPhone music player (github.com/thedarkout17/MewZ)")
            }
        } catch (e: Exception) {
            throw Offline(e.message ?: "LRCLIB unreachable")
        }
        return when (response.status.value) {
            in 200..299 -> response.bodyAsText()
            404 -> null
            else -> throw Offline("LRCLIB HTTP ${response.status.value}")
        }
    }
}
