// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.sources

import com.headunit.music.core.model.Lyrics
import com.headunit.music.core.model.Track
import com.headunit.music.core.search.Album
import com.headunit.music.core.search.SearchResult

/*
 * What the app needs from the outside world. Each platform plugs in its own:
 * on Android these are YouTubeSearch (Data API), AudioStreams / YouTubeMix /
 * YouTubeAlbums (NewPipeExtractor), LyricsRepository (LRCLIB) and
 * YouTubeSuggestions. The iPhone app (phone/) implements the same interfaces in
 * plain Kotlin (phone/src/commonMain/.../phone/data).
 */

/** Song search. */
interface SongSearchSource {
    /** One page of results; pass the previous page's [SearchResult.Success.nextPageToken] for more. */
    suspend fun search(query: String, pageToken: String? = null): SearchResult
}

/** Finds a song's audio stream, to play or pre-buffer. */
interface AudioStreamSource {
    /** The stream's address. Throws [com.headunit.music.core.playback.AudioUnavailableException]. */
    suspend fun urlFor(videoId: String): String

    /** Drop a remembered address (e.g. it expired and playback failed), so the next lookup is fresh. */
    fun forget(videoId: String)
}

/** "Up next" songs for a song, like YouTube's autoplay. */
interface MixSource {
    /** Songs to follow [seed], without [seed] itself. Empty if nothing could be found. */
    suspend fun upNext(seed: Track): List<Track>
}

/** Albums, for search. */
interface AlbumSource {
    /** Albums matching [query], best match first. Throws on failure. */
    suspend fun search(query: String): List<Album>

    /** The album's songs, in order. Throws on failure. */
    suspend fun tracks(album: Album): List<Track>
}

/** Timed lyrics. */
interface LyricsSource {
    /** Lyrics for [track], or null if there are none. */
    suspend fun find(track: Track): Lyrics?
}

/** Search suggestions shown while typing. */
fun interface SuggestionSource {
    suspend fun suggest(query: String, limit: Int): List<String>
}
