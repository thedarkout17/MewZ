package com.headunit.music.data.youtube

import android.util.Log
import com.headunit.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** An album from YouTube Music. [url] opens it; [coverUrl] is its square artwork. */
data class Album(
    val url: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
)

/**
 * Album search, through YouTube Music's own album catalogue (NewPipeExtractor's
 * "music_albums" search), so only real released albums come back, not the
 * "Best of ..." playlists people make. Free: no API quota is used.
 */
object YouTubeAlbums {

    /** Albums matching [query] (YouTube Music's first page, best match first). Throws on failure. */
    suspend fun search(query: String, max: Int = 12): List<Album> = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "",
        )
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<PlaylistInfoItem>()
            .mapNotNull { item ->
                val cover = item.thumbnails.maxByOrNull { it.width.toLong() * it.height }?.url
                if (item.url.isNullOrEmpty() || cover.isNullOrEmpty()) null
                else Album(
                    url = item.url,
                    title = item.name.orEmpty(),
                    artist = YouTubeSearch.cleanArtist(item.uploaderName.orEmpty()),
                    coverUrl = cover,
                )
            }
            .distinctBy { it.url }
            .take(max)
    }

    /**
     * The album's songs, in order. Each one uses the album's square cover as its
     * artwork (cleaner than the video stills). Throws on failure.
     */
    suspend fun tracks(album: Album): List<Track> = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, album.url)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        // Albums are short, but follow the pages just in case (capped).
        var pages = 0
        while (page != null && pages < 5) {
            val more = runCatching { PlaylistInfo.getMoreItems(ServiceList.YouTube, album.url, page) }
                .onFailure { Log.w("MuZ", "Album page failed: ${it.message}") }
                .getOrNull() ?: break
            items += more.items
            page = more.nextPage
            pages++
        }
        items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { YouTubeMix.toTrack(it, maxSeconds = Long.MAX_VALUE) }
            .distinctBy { it.videoId }
            .map { t ->
                t.copy(
                    // Album tracks are by the album's artist unless the title says otherwise.
                    artist = t.artist.ifBlank { album.artist },
                    artworkUrl = album.coverUrl,
                    artworkLetterboxed = false,
                )
            }
    }
}
