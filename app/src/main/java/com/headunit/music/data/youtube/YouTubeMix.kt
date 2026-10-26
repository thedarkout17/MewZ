package com.headunit.music.data.youtube

import com.headunit.music.core.sources.MixSource
import com.headunit.music.core.text.SongText
import android.util.Log
import com.headunit.music.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * "Up next" songs for a song, like YouTube's autoplay (and Musi).
 *
 * Uses the song's YouTube Mix: the endless radio playlist YouTube builds for
 * every video (list id "RD" + video id). If YouTube won't give a mix, falls
 * back to the video's related videos. Free: no API quota is used.
 */
object YouTubeMix : MixSource {

    /** Songs to follow [seed], without [seed] itself. Empty if nothing could be found. */
    override suspend fun upNext(seed: Track): List<Track> = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val items = runCatching { mix(seed.videoId) }
            .onFailure { Log.w(TAG, "Mix failed for ${seed.videoId}: ${it.message}") }
            .getOrNull()
            .orEmpty()
            .ifEmpty {
                runCatching { related(seed.videoId) }
                    .onFailure { Log.w(TAG, "Related failed for ${seed.videoId}: ${it.message}") }
                    .getOrNull()
                    .orEmpty()
            }
        items.mapNotNull { toTrack(it) }
            .filter { it.videoId != seed.videoId }
            .distinctBy { it.videoId }
    }

    private fun mix(videoId: String): List<StreamInfoItem> {
        val url = "https://www.youtube.com/watch?v=$videoId&list=RD$videoId"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = info.relatedItems.toMutableList()
        // A mix page is ~25 songs; take one more page so the queue has some depth.
        if (items.size < 30 && info.hasNextPage()) {
            runCatching { PlaylistInfo.getMoreItems(ServiceList.YouTube, url, info.nextPage).items }
                .getOrNull()?.let { items += it }
        }
        return items
    }

    private fun related(videoId: String): List<StreamInfoItem> =
        StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            .relatedItems.filterIsInstance<StreamInfoItem>()

    /** A NewPipe stream item as one of our songs (also used for album tracks). Null for live streams / too long. */
    internal fun toTrack(item: StreamInfoItem, maxSeconds: Long = MAX_SONG_SECONDS): Track? {
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) return null
        // Skip hour-long compilations and "10 hours of..." uploads; they're not songs.
        if (item.duration > maxSeconds) return null
        val id = VIDEO_ID.find(item.url)?.groupValues?.get(1) ?: return null

        val (splitTitle, splitArtist) = SongText.splitArtist(
            SongText.cleanTitle(item.name.orEmpty()),
            SongText.cleanArtist(item.uploaderName.orEmpty()),
        )
        val (title, artist) = SongText.moveFeaturedArtists(splitTitle, splitArtist)

        val biggest = item.thumbnails.maxByOrNull { it.width.toLong() * it.height }
        val artUrl = biggest?.url?.takeIf { it.isNotEmpty() } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return Track(
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            artworkUrl = artUrl,
            artworkLetterboxed = isLetterboxed(biggest, artUrl),
            durationSeconds = item.duration.takeIf { it > 0 }?.toInt(),
        )
    }

    /** YouTube's 4:3 images (hqdefault, sddefault, ...) have black bars above and below the picture. */
    private fun isLetterboxed(image: Image?, url: String): Boolean {
        if (image != null && image.width > 0 && image.height > 0) return image.width * 3 == image.height * 4
        return !url.contains("maxresdefault") && !url.contains("mqdefault") && !url.contains("hq720")
    }

    private const val TAG = "MuZ"
    private const val MAX_SONG_SECONDS = 15 * 60L
    private val VIDEO_ID = Regex("""[?&]v=([\w-]{11})""")
}
