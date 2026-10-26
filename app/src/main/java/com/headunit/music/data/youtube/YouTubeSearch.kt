package com.headunit.music.data.youtube

import com.headunit.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** What a search can come back with. */
sealed interface SearchResult {
    data class Success(val tracks: List<Track>) : SearchResult
    data class Failure(val reason: Reason, val detail: String? = null) : SearchResult

    enum class Reason {
        /** No connection / timed out. */
        Offline,
        /** YouTube is rate-limiting this connection (wants a captcha). Usually clears up in a while. */
        RateLimited,
        /** YouTube changed its pages and NewPipeExtractor needs an update. */
        ExtractorOutdated,
        /** Anything else. */
        Other,
    }
}

/**
 * Searches YouTube for songs with NewPipeExtractor, which reads YouTube's own
 * search like a browser does. No API key and no daily limit; results and
 * durations come back in one request.
 */
class YouTubeSearch(private val maxResults: Int = 20) {

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        try {
            NewPipeDownloader.init()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "",
            )
            val items = SearchInfo.getInfo(service, handler).relatedItems
                .filterIsInstance<StreamInfoItem>()
                // Songs only: skip live streams and anything without a length.
                .filter { it.streamType == StreamType.VIDEO_STREAM && it.duration > 0 }

            val tracks = items.mapNotNull { item ->
                val id = videoIdOf(item.url) ?: return@mapNotNull null
                val (splitTitle, splitArtistName) = splitArtist(
                    cleanTitle(item.name),
                    cleanArtist(item.uploaderName.orEmpty()),
                )
                val (title, artist) = moveFeaturedArtists(splitTitle, splitArtistName)
                Track(
                    videoId = id,
                    title = title,
                    artist = artist,
                    // 480x360 (4:3, bars baked in) for the results list; always exists.
                    thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    // 1280x720 for the square artwork; only HD uploads have it, and
                    // the artwork falls back to thumbnailUrl when it's missing.
                    artworkUrl = "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                    durationSeconds = item.duration.toInt(),
                )
            }.distinctBy { it.videoId }.take(maxResults)

            SearchResult.Success(tracks)
        } catch (e: ReCaptchaException) {
            SearchResult.Failure(SearchResult.Reason.RateLimited, e.message)
        } catch (e: IOException) {
            SearchResult.Failure(SearchResult.Reason.Offline, e.message)
        } catch (e: ExtractionException) {
            SearchResult.Failure(SearchResult.Reason.ExtractorOutdated, e.message)
        } catch (e: Exception) {
            SearchResult.Failure(SearchResult.Reason.Other, e.message)
        }
    }

    companion object {
        private val VIDEO_ID = Regex("""(?:v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")

        fun videoIdOf(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

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

    }
}
