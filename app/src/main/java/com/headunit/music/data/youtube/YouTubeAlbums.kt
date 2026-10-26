package com.headunit.music.data.youtube

import com.headunit.music.core.sources.AlbumSource
import com.headunit.music.core.search.Album
import com.headunit.music.core.text.SongText
import android.util.Log
import com.headunit.music.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Album search, through YouTube Music's own album catalogue (NewPipeExtractor's
 * "music_albums" search), so only real released albums come back, not the
 * "Best of ..." playlists people make. Free: no API quota is used.
 */
object YouTubeAlbums : AlbumSource {

    /** At most this many albums from one search (the relevance filter shows fewer). */
    private const val MAX_RESULTS = 12

    /** Albums' songs once fetched (for the song count in results, and opening them instantly). */
    private val trackCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()

    /** Albums matching [query] (YouTube Music's first page, best match first). Throws on failure. */
    override suspend fun search(query: String): List<Album> = withContext(Dispatchers.IO) {
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
                    artist = SongText.cleanArtist(item.uploaderName.orEmpty()),
                    coverUrl = cover,
                )
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
    }


    /**
     * The album's songs, in order. Each one uses the album's square cover as its
     * artwork (cleaner than the video stills). Throws on failure.
     */
    override suspend fun tracks(album: Album): List<Track> = withContext(Dispatchers.IO) {
        trackCache[album.url]?.let { return@withContext it }
        NewPipeDownloader.init()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, album.url)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        // Albums are short, but follow the pages just in case (capped).
        var pages = 0
        while (page != null && pages < 5) {
            val more = runCatching { PlaylistInfo.getMoreItems(ServiceList.YouTube, album.url, page) }
                .onFailure { Log.w("MewZ", "Album page failed: ${it.message}") }
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
            .also { if (it.isNotEmpty()) trackCache[album.url] = it }
    }
}
