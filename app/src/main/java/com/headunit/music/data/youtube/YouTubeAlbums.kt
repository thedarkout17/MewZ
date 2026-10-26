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
     * Keeps only albums that belong in the results for [query], so a search for a
     * song or album name doesn't fill the row with every obscure album that
     * happens to share the name. An album stays when:
     *  - its title is what was searched for (ignoring "(Deluxe)" and the like),
     *    and its artist is either named in the search or is one of the artists
     *    in the top song results (YouTube ranks those by popularity, so the
     *    well-known album makes it and look-alikes don't); or
     *  - the search is just an artist's name: then that artist's albums.
     */
    fun relevant(albums: List<Album>, query: String, topSongs: List<Track>, max: Int = 6): List<Album> {
        // Words like "album" or "by" in the search don't need to be in the title.
        val typed = words(query)
        val q = typed - FILLER
        // Asked for the album itself ("starboy album"): show every edition (Deluxe too).
        val wantsEditions = typed.any { it in EDITION_WORDS }
        if (q.isEmpty()) return emptyList()
        // Artists that matter for this search: the first few song results'.
        val popular = topSongs.take(TOP_SONGS).map { words(it.artist) }
        // Artists typed into the search ("starboy kid cosmo"): their names aren't part of
        // any album title, so take them out before comparing titles.
        val namedWords = albums.map { words(it.artist) }
            .filter { it.isNotEmpty() && q.containsAll(it) }
            .flatten().toSet()
        val kept = albums.filterIndexed { rank, album ->
            val artist = words(album.artist)
            if (artist.isEmpty()) return@filterIndexed false
            val named = q.containsAll(artist)
            val title = words(coreTitle(album.title))
            // (Never strip words that are in this album's own title.)
            val rest = q - (if (named) artist else namedWords - title)
            val playedForThis = popular.any { it.containsAll(artist) }
            val keep = when {
                // "the weeknd": their albums (if they're who the song results are by too,
                // so an unknown act that happens to be called "Starboy" doesn't count).
                named && rest.isEmpty() -> playedForThis
                // Title has to be what was asked for.
                title.isEmpty() || rest != title -> false
                // ...by an artist named in the search, or one people actually play for it.
                named -> true
                // Not named: an artist people play for this search, or YouTube Music's own
                // top album for it (it ranks by popularity, so that's the well-known one).
                else -> playedForThis || rank == 0
            }
            Log.d("MuZ", "Album \"${album.title}\" by \"${album.artist}\" (#${rank + 1}): " +
                if (keep) "shown" else "hidden (title fits: ${rest == title}, artist in songs: $playedForThis)")
            keep
        }
        // One edition per album ("Starboy" / "Starboy (Deluxe)" → the higher-ranked one),
        // unless the album itself was asked for.
        val editions = if (wantsEditions) kept else kept.distinctBy { words(coreTitle(it.title)) to words(it.artist) }
        return editions.take(max)
    }

    /** "Starboy (Deluxe Edition) [Remastered]" → "Starboy". */
    private fun coreTitle(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+(deluxe|expanded|remaster(ed)?|anniversary|edition|version).*$""", RegexOption.IGNORE_CASE), "")

    /** Lower-case words, without punctuation or a leading "the" (so "The Weeknd" matches "weeknd"). */
    private fun words(text: String): Set<String> =
        text.lowercase()
            .replace("&", " and ")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() && it != "the" }
            .toSet()

    private const val TOP_SONGS = 6
    private val FILLER = setOf("album", "albums", "full", "by", "lp", "ep", "deluxe", "edition")
    private val EDITION_WORDS = setOf("album", "albums", "deluxe", "edition", "lp", "ep")

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
