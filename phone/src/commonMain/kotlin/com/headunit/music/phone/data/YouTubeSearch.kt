package com.headunit.music.phone.data

import com.headunit.music.core.model.Track
import com.headunit.music.core.search.SearchResult
import com.headunit.music.core.sources.SongSearchSource
import com.headunit.music.core.text.SongText
import com.headunit.music.phone.appBundleId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Song search through the official YouTube Data API v3, exactly like the
 * headunit (data/youtube/YouTubeSearch.kt): music-category videos that allow
 * embedding (100 quota units), then their durations (1 unit).
 *
 * [apiKey] is read on every search, so a key pasted into the app works straight away.
 */
class YouTubeSearch(
    private val apiKey: () -> String,
    private val maxResults: Int = 20,
) : SongSearchSource {

    override suspend fun search(query: String, pageToken: String?): SearchResult {
        val key = apiKey().trim()
        if (key.isBlank()) return SearchResult.Failure(SearchResult.Reason.MissingKey)
        return try {
            val searchJson = get(
                key, "search",
                "part" to "snippet",
                "type" to "video",
                "videoCategoryId" to MUSIC_CATEGORY,
                "videoEmbeddable" to "true",
                "maxResults" to (if (pageToken == null) maxResults else MORE_PAGE_SIZE).toString(),
                "q" to query,
                *(if (pageToken != null) arrayOf("pageToken" to pageToken) else emptyArray()),
            )
            val next = searchJson.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            val items = searchJson.arr("items") ?: return SearchResult.Success(emptyList())
            val tracks = items.mapNotNull { item ->
                val id = item.obj("id").str("videoId").orEmpty()
                val snippet = item.obj("snippet") ?: return@mapNotNull null
                if (id.isEmpty()) return@mapNotNull null
                val (splitTitle, splitArtistName) = SongText.splitArtist(
                    SongText.cleanTitle(unescapeHtml(snippet.str("title").orEmpty())),
                    SongText.cleanArtist(unescapeHtml(snippet.str("channelTitle").orEmpty())),
                )
                val (title, artist) = SongText.moveFeaturedArtists(splitTitle, splitArtistName)
                val thumbs = snippet.obj("thumbnails")
                val (artUrl, letterboxed) = bestArtwork(thumbs, id)
                Track(
                    videoId = id,
                    title = title,
                    artist = artist,
                    thumbnailUrl = bestThumbnail(thumbs, id),
                    artworkUrl = artUrl,
                    artworkLetterboxed = letterboxed,
                )
            }
            SearchResult.Success(withDurations(key, tracks), next)
        } catch (e: ApiException) {
            SearchResult.Failure(e.reason, e.message)
        } catch (e: Exception) {
            if (isNetworkError(e)) SearchResult.Failure(SearchResult.Reason.Offline, e.message)
            else SearchResult.Failure(SearchResult.Reason.Other, e.message)
        }
    }

    /** Fills in durations. If this call fails, results still show, just without times. */
    private suspend fun withDurations(key: String, tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        return try {
            val json = get(key, "videos", "part" to "contentDetails", "id" to tracks.joinToString(",") { it.videoId })
            val seconds = HashMap<String, Int>()
            json.arr("items").orEmpty().forEach { item ->
                val iso = item.obj("contentDetails").str("duration").orEmpty()
                SongText.parseIsoDuration(iso)?.let { seconds[item.str("id").orEmpty()] = it }
            }
            tracks.map { it.copy(durationSeconds = seconds[it.videoId]) }
                // Live streams and premieres report 0:00; they're not songs.
                .filter { it.durationSeconds != 0 }
        } catch (e: Exception) {
            tracks
        }
    }

    private suspend fun get(key: String, endpoint: String, vararg params: Pair<String, String>): JsonObject {
        val query = (params.toList() + ("key" to key)).joinToString("&") { (k, v) -> "$k=${encodeQuery(v)}" }
        val response = Http.client.get("$BASE/$endpoint?$query") {
            header("Accept", "application/json")
            // Lets the key work if it's restricted to iOS apps (Google checks this header).
            header("X-Ios-Bundle-Identifier", appBundleId())
        }
        val body = response.bodyAsText()
        val code = response.status.value
        if (code !in 200..299) throw ApiException.from(code, body)
        return Http.json.parseToJsonElement(body).jsonObject
    }

    private class ApiException(val reason: SearchResult.Reason, message: String?) : Exception(message) {
        companion object {
            fun from(code: Int, body: String): ApiException {
                val error = runCatching { Http.json.parseToJsonElement(body).obj("error") }.getOrNull()
                val why = error.arr("errors")?.firstOrNull().str("reason").orEmpty()
                val message = error.str("message")
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

        /**
         * Artwork for the big square: maxres 1280x720 and medium 320x180 are true 16:9;
         * standard / high / default are 4:3 with black bars, flagged so the artwork
         * zooms past them.
         */
        private fun bestArtwork(thumbs: JsonElement?, id: String): Pair<String, Boolean> {
            for ((size, letterboxed) in listOf("maxres" to false, "standard" to true, "high" to true, "medium" to false)) {
                val url = thumbs.obj(size).str("url")
                if (!url.isNullOrEmpty()) return url to letterboxed
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg" to true
        }

        private fun bestThumbnail(thumbs: JsonElement?, id: String): String {
            for (size in listOf("high", "medium", "default")) {
                val url = thumbs.obj(size).str("url")
                if (!url.isNullOrEmpty()) return url
            }
            return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }
    }
}
