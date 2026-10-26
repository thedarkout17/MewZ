// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.search

import com.headunit.music.core.model.Track

/** An album from YouTube Music. [url] opens it; [coverUrl] is its square artwork. */
data class Album(
    val url: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
)

/** Which albums belong in the results for a search (see [relevant]). */
object AlbumMatching {

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
    fun relevant(
        albums: List<Album>,
        query: String,
        topSongs: List<Track>,
        max: Int = 6,
        /** Why each album was shown or hidden (for the log). */
        log: (String) -> Unit = {},
    ): List<Album> {
        // Words like "album" or "by" in the search don't need to be in the title.
        val typed = words(query)
        // The word still being typed may be half done: "starboy albu" means "starboy album".
        val last = wordList(query).lastOrNull()
        fun isWord(w: String, set: Set<String>) =
            w in set || (w == last && w.length >= 3 && set.any { it.startsWith(w) })
        val q = typed.filterNot { isWord(it, FILLER) }.toSet()
        // Asked for the album itself ("starboy album"): show every edition (Deluxe too).
        val wantsEditions = typed.any { isWord(it, EDITION_WORDS) }
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
            log("Album \"${album.title}\" by \"${album.artist}\" (#${rank + 1}): " +
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
    private fun words(text: String): Set<String> = wordList(text).toSet()

    // Split on anything that isn't a letter or digit, in any script. (Not a \p{L} regex:
    // iOS's Kotlin regex engine rejects Unicode classes, which the iPhone tests caught.)
    private fun wordList(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (c in text.lowercase().replace("&", " and ")) {
            if (c.isLetterOrDigit()) word.append(c)
            else if (word.isNotEmpty()) { words += word.toString(); word.clear() }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words.filter { it != "the" }
    }

    private const val TOP_SONGS = 6
    private val FILLER = setOf("album", "albums", "full", "by", "lp", "ep", "deluxe", "edition")
    private val EDITION_WORDS = setOf("album", "albums", "deluxe", "edition", "lp", "ep")
}
