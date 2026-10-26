// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.search

import com.headunit.music.core.model.Track

/** What a search can come back with. */
sealed interface SearchResult {
    /** [nextPageToken]: pass back to [SongSearchSource.search] for more results; null when there are no more. */
    data class Success(val tracks: List<Track>, val nextPageToken: String? = null) : SearchResult
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

/** A row in the results: a song, or an album among them. */
sealed interface ResultRow {
    val key: String
    data class Song(val track: Track) : ResultRow { override val key get() = track.videoId }
    data class AlbumItem(val album: Album) : ResultRow { override val key get() = "album:" + album.url }
}

/**
 * Puts each album just after the first song by its artist (so "starboy" gives
 * the song, then the album), or after the second song if none of its songs
 * came up.
 */
fun mixInAlbums(tracks: List<Track>, albums: List<Album>): List<ResultRow> {
    if (albums.isEmpty()) return tracks.map { ResultRow.Song(it) }
    fun norm(s: String) = s.lowercase().removePrefix("the ").filter { it.isLetterOrDigit() }
    val after = HashMap<Int, MutableList<Album>>() // song position → albums to put after it
    albums.forEach { album ->
        val artist = norm(album.artist)
        val at = tracks.indexOfFirst { artist.isNotEmpty() && norm(it.artist).startsWith(artist) }
            .takeIf { it >= 0 } ?: minOf(1, tracks.size - 1)
        after.getOrPut(at) { mutableListOf() } += album
    }
    return buildList {
        if (tracks.isEmpty()) albums.forEach { add(ResultRow.AlbumItem(it)) }
        tracks.forEachIndexed { i, t ->
            add(ResultRow.Song(t))
            after[i]?.forEach { add(ResultRow.AlbumItem(it)) }
        }
    }
}
