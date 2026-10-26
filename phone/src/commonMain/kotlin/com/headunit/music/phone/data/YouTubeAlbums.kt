package com.headunit.music.phone.data

import com.headunit.music.core.model.Track
import com.headunit.music.core.search.Album
import com.headunit.music.core.sources.AlbumSource
import com.headunit.music.core.text.SongText
import com.headunit.music.phone.data.YouTubeMix.songEntries
import com.headunit.music.phone.log
import kotlinx.serialization.json.JsonObject

/**
 * Album search through YouTube Music's own album catalogue, so only real
 * released albums come back, not "Best of ..." playlists. Same as the
 * headunit's YouTubeAlbums (NewPipe's "music_albums" search). No API quota used.
 */
object YouTubeAlbums : AlbumSource {

    private const val MAX_RESULTS = 12

    /** Albums' songs once fetched, so reopening one is instant. */
    private val trackCache = HashMap<String, List<Track>>()

    override suspend fun search(query: String): List<Album> {
        val page = InnerTube.musicSearch(query, InnerTube.MUSIC_ALBUMS_PARAMS)
        return page.findAll("musicResponsiveListItemRenderer")
            .filter { it.str("musicItemRendererDisplayPolicy") != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT" }
            .mapNotNull(::toAlbum)
            .distinctBy { it.url }
            .take(MAX_RESULTS)
    }

    private fun toAlbum(item: JsonObject): Album? {
        val columns = item.arr("flexColumns").orEmpty()
        fun column(i: Int) = columns.getOrNull(i)?.path("musicResponsiveListItemFlexColumnRenderer", "text")
        val title = column(0).text() ?: return null
        // Second line: "Album", " • ", artist, " • ", year.
        val runs = column(1).arr("runs").orEmpty()
        val artistRuns = runs.filter {
            it.path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig").str("pageType") == "MUSIC_PAGE_TYPE_ARTIST"
        }
        val artist = artistRuns.joinToString(", ") { it.str("text").orEmpty() }
            .ifEmpty { runs.getOrNull(2)?.str("text").orEmpty() }
        val playlistId = item.findString("playlistId") { it.startsWith("OLAK5uy_") }
            ?: item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchPlaylistEndpoint").str("playlistId")
            ?: return null
        val cover = biggestThumbnail(item.path("thumbnail", "musicThumbnailRenderer", "thumbnail"))?.first
            ?.let(::bigCover) ?: return null
        return Album(
            url = "https://music.youtube.com/playlist?list=$playlistId",
            title = title,
            artist = SongText.cleanArtist(artist),
            coverUrl = cover,
        )
    }

    /** YouTube Music covers come sized by their URL ("...=w120-h120-..."): ask for a big one. */
    private fun bigCover(url: String): String =
        url.replace(Regex("""=w\d+-h\d+"""), "=w720-h720")

    override suspend fun tracks(album: Album): List<Track> {
        trackCache[album.url]?.let { return it }
        val id = album.url.substringAfter("list=").substringBefore('&')
        // The album's playlist panel on a watch page has all its songs in order...
        var entries = runCatching { YouTubeMix.panel(InnerTube.next(null, id))?.songEntries().orEmpty() }
            .onFailure { log("Album via next failed: ${it.message}") }
            .getOrDefault(emptyList())
        // ...or, failing that, its playlist page.
        if (entries.isEmpty()) {
            entries = InnerTube.browsePlaylist(id).findAll("playlistVideoRenderer")
        }
        return entries
            .mapNotNull { videoEntryToTrack(it, maxSeconds = Int.MAX_VALUE) }
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
