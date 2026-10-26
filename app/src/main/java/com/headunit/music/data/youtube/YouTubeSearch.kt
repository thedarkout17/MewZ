package com.headunit.music.data.youtube

import com.headunit.music.core.sources.SongSearchSource
import com.headunit.music.core.search.SearchResult
import com.headunit.music.core.text.SongText
import android.text.Html
import com.headunit.music.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
) : SongSearchSource {
    /**
     * One page of results. For more, call again with the previous page's
     * [SearchResult.Success.nextPageToken]. Every page costs the same quota as a
     * new search (~101 units) whatever its size, so later pages ask for
     * [MORE_PAGE_SIZE] songs, the most YouTube allows.
     */
    override suspend fun search(query: String, pageToken: String?): SearchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SearchResult.Failure(SearchResult.Reason.MissingKey)

        try {
            val searchJson = get(
                "search",
                "part" to "snippet",
                "type" to "video",
                "videoCategoryId" to MUSIC_CATEGORY,
                "videoEmbeddable" to "true",
                "maxResults" to (if (pageToken == null) maxResults else MORE_PAGE_SIZE).toString(),
                "q" to query,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken) else emptyArray()),
            )
            val next = searchJson.optString("nextPageToken").takeIf { it.isNotEmpty() }
            val items = searchJson.optJSONArray("items") ?: return@withContext SearchResult.Success(emptyList())

            val tracks = buildList {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    val snippet = item.optJSONObject("snippet") ?: continue
                    if (id.isEmpty()) continue
                    val (splitTitle, splitArtistName) = SongText.splitArtist(
                        SongText.cleanTitle(unescape(snippet.optString("title"))),
                        SongText.cleanArtist(unescape(snippet.optString("channelTitle"))),
                    )
                    val (title, artist) = SongText.moveFeaturedArtists(splitTitle, splitArtistName)
                    val thumbs = snippet.optJSONObject("thumbnails")
                    val (artUrl, artLetterboxed) = bestArtwork(thumbs, id)
                    add(
                        Track(
                            videoId = id,
                            title = title,
                            artist = artist,
                            thumbnailUrl = bestThumbnail(thumbs, id),
                            artworkUrl = artUrl,
                            artworkLetterboxed = artLetterboxed,
                        )
                    )
                }
            }

            SearchResult.Success(withDurations(tracks), next)
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
                SongText.parseIsoDuration(iso)?.let { seconds[item.optString("id")] = it }
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
        /** Songs per page after the first (YouTube's maximum; costs the same as 20). */
        const val MORE_PAGE_SIZE = 50

        private fun unescape(s: String): String =
            Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

        /** Prefer the 480x360 "high" image; fall back through smaller ones. */
        /**
         * Artwork for the big square. YouTube's sizes:
         *   maxres 1280x720 and medium 320x180 are true 16:9 (no bars);
         *   standard 640x480, high 480x360 and default 120x90 are 4:3 with black
         *   bars above and below the picture.
         * Prefer the big bar-free one; otherwise take a 4:3 one and flag it so the
         * artwork zooms past the bars.
         */
        private fun bestArtwork(thumbs: JSONObject?, id: String): Pair<String, Boolean> {
            for ((size, letterboxed) in listOf("maxres" to false, "standard" to true, "high" to true, "medium" to false)) {
                val url = thumbs?.optJSONObject(size)?.optString("url")
                if (!url.isNullOrEmpty()) return url to letterboxed
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg" to true
        }

        private fun bestThumbnail(thumbs: JSONObject?, id: String): String {
            for (size in listOf("high", "medium", "default")) {
                val url = thumbs?.optJSONObject(size)?.optString("url")
                if (!url.isNullOrEmpty()) return url
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }

    }
}
