package com.headunit.music.data.youtube

import android.text.Html
import com.headunit.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a search can come back with. */
sealed interface SearchResult {
    data class Success(val tracks: List<Track>) : SearchResult
    data class Failure(val reason: Reason, val detail: String? = null) : SearchResult

    enum class Reason {
        /** No API key in local.properties. */
        MissingKey,
        /** Daily quota used up (YouTube gives 10,000 units/day; a search costs 100). */
        QuotaExceeded,
        /** Key rejected: wrong key, API not enabled, or app restrictions don't match. */
        BadKey,
        /** No connection / timed out. */
        Offline,
        /** Anything else YouTube returned. */
        Other,
    }
}

/**
 * Searches YouTube for songs through the official YouTube Data API v3.
 *
 * Each search makes two calls:
 *  1. search.list: music-category videos that allow embedding (100 quota units)
 *  2. videos.list: their durations (1 unit)
 *
 * Only embeddable videos are returned, so everything found can be played
 * with YouTube's official embedded player later.
 */
class YouTubeSearch(
    private val apiKey: String,
    private val maxResults: Int = 20,
) {
    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchResult.Failure(SearchResult.Reason.MissingKey)

        try {
            val searchJson = get(
                "search",
                "part" to "snippet",
                "type" to "video",
                "videoCategoryId" to MUSIC_CATEGORY,
                "videoEmbeddable" to "true",
                "maxResults" to maxResults.toString(),
                "q" to query,
            )
            val items = searchJson.optJSONArray("items") ?: return@withContext SearchResult.Success(emptyList())

            val tracks = buildList {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    val snippet = item.optJSONObject("snippet") ?: continue
                    if (id.isEmpty()) continue
                    val (splitTitle, splitArtistName) = splitArtist(
                        cleanTitle(unescape(snippet.optString("title"))),
                        cleanArtist(unescape(snippet.optString("channelTitle"))),
                    )
                    val (title, artist) = moveFeaturedArtists(splitTitle, splitArtistName)
                    val thumbs = snippet.optJSONObject("thumbnails")
                    val artUrl = bestArtwork(thumbs, id)
                    add(
                        Track(
                            videoId = id,
                            title = title,
                            artist = artist,
                            thumbnailUrl = bestThumbnail(thumbs, id),
                            artworkUrl = artUrl,
                        )
                    )
                }
            }

            SearchResult.Success(withDurations(tracks))
        } catch (e: ApiException) {
            SearchResult.Failure(e.reason, e.message)
        } catch (e: IOException) {
            SearchResult.Failure(SearchResult.Reason.Offline, e.message)
        } catch (e: Exception) {
            SearchResult.Failure(SearchResult.Reason.Other, e.message)
        }
    }

    /** Fills in durations. If this call fails, results still show, just without times. */
    private fun withDurations(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        return try {
            val json = get(
                "videos",
                "part" to "contentDetails",
                "id" to tracks.joinToString(",") { it.videoId },
            )
            val items = json.optJSONArray("items") ?: return tracks
            val seconds = HashMap<String, Int>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val iso = item.optJSONObject("contentDetails")?.optString("duration").orEmpty()
                parseIsoDuration(iso)?.let { seconds[item.optString("id")] = it }
            }
            tracks.map { it.copy(durationSeconds = seconds[it.videoId]) }
                // Live streams and premieres report 0:00; they're not songs.
                .filter { it.durationSeconds != 0 }
        } catch (e: Exception) {
            tracks
        }
    }

    private fun get(endpoint: String, vararg params: Pair<String, String>): JSONObject {
        val query = (params.toList() + ("key" to apiKey))
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("$BASE/$endpoint?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException.from(code, body)
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private class ApiException(val reason: SearchResult.Reason, message: String?) : Exception(message) {
        companion object {
            fun from(code: Int, body: String): ApiException {
                val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
                val why = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                val message = error?.optString("message")
                val reason = when {
                    why == "quotaExceeded" || why == "dailyLimitExceeded" -> SearchResult.Reason.QuotaExceeded
                    why == "keyInvalid" || code == 400 && why == "badRequest" && message?.contains("API key") == true ->
                        SearchResult.Reason.BadKey
                    code == 403 -> SearchResult.Reason.BadKey // API not enabled, or key restricted to another app
                    else -> SearchResult.Reason.Other
                }
                return ApiException(reason, message ?: "HTTP $code")
            }
        }
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/youtube/v3"
        private const val MUSIC_CATEGORY = "10"

        private fun unescape(s: String): String =
            Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

        /** Prefer the 480x360 "high" image; fall back through smaller ones. */
        /**
         * Artwork for the big square. YouTube's sizes:
         *   maxres 1280x720 and medium 320x180 are true 16:9 (no bars);
         *   standard 640x480, high 480x360 and default 120x90 are 4:3 with black
         *   bars above and below the picture.
         * Prefer the biggest; the artwork trims any bars itself (TrimBarsTransformation),
         * which also catches cinema-format videos that have bars even at 16:9.
         */
        private fun bestArtwork(thumbs: JSONObject?, id: String): String {
            for (size in listOf("maxres", "standard", "high", "medium")) {
                val url = thumbs?.optJSONObject(size)?.optString("url")
                if (!url.isNullOrEmpty()) return url
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }

        private fun bestThumbnail(thumbs: JSONObject?, id: String): String {
            for (size in listOf("high", "medium", "default")) {
                val url = thumbs?.optJSONObject(size)?.optString("url")
                if (!url.isNullOrEmpty()) return url
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }

        // "(Official Music Video)", "[Official Audio]", "(Lyrics)", "| Official Video" ...
        private val TITLE_NOISE = Regex(
            """\s*[(\[|]\s*(official\s*(music\s*)?(video|audio|visuali[sz]er|lyric\s*video)|lyrics?(\s*video)?|audio|hd|hq|4k|music\s*video|video\s*oficial)\s*[)\]]?""",
            RegexOption.IGNORE_CASE,
        )

        /** Strips YouTube clutter so titles read like song names. */
        fun cleanTitle(raw: String): String =
            raw.replace(TITLE_NOISE, "").trim().trimEnd('-', '|', '–').trim().ifEmpty { raw }

        /** "Taylor Swift - Topic" → "Taylor Swift", "TaylorSwiftVEVO" → "TaylorSwift". */
        fun cleanArtist(raw: String): String =
            raw.removeSuffix(" - Topic").removeSuffix("VEVO").removeSuffix("Vevo").trim().ifEmpty { raw }

        /**
         * Music uploads are usually titled "Artist - Song". When they are, show the
         * song as the title and the artist from the title (more accurate than the
         * channel name, which is often a label). Otherwise keep title + channel.
         */
        fun splitArtist(title: String, channel: String): Pair<String, String> {
            val parts = title.split(" - ", " – ", " — ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[0].length <= 60) {
                return parts[1].trim() to parts[0].trim()
            }
            return title to channel
        }

        private val FEAT_BRACKETED = Regex("""\s*[(\[]\s*(?:ft|feat|featuring)\.?\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
        private val FEAT_TAIL = Regex("""\s+(?:ft|feat|featuring)\.?\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val NAME_SEPARATORS = Regex("""\s+(?:ft|feat|featuring)\.?\s+|\s*,\s*|\s+&\s+""", RegexOption.IGNORE_CASE)

        /**
         * "Starboy ft. Daft Punk" by The Weeknd → "Starboy" by "The Weeknd ft. Daft Punk".
         * Handles "(feat. X)", "ft. X & Y", and uploads that repeat the credit twice.
         */
        fun moveFeaturedArtists(title: String, artist: String): Pair<String, String> {
            val credits = mutableListOf<String>()
            var t = FEAT_BRACKETED.replace(title) { credits += it.groupValues[1]; "" }
            FEAT_TAIL.find(t)?.let { m ->
                credits += m.groupValues[1]
                t = t.substring(0, m.range.first)
            }
            val names = credits
                .flatMap { it.split(NAME_SEPARATORS) }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }
                .distinctBy { it.lowercase() }
            val cleanTitle = t.trim().ifEmpty { title }
            return if (names.isEmpty()) cleanTitle to artist
            else cleanTitle to "$artist ft. ${names.joinToString(", ")}"
        }

        /** "PT1H2M3S" → 3723. Null if it can't be read. */
        fun parseIsoDuration(iso: String): Int? {
            val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return null
            val (d, h, min, s) = m.destructured
            return (d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 +
                (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        }
    }
}
