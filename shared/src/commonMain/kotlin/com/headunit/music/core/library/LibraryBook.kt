// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.library

import com.headunit.music.core.model.HISTORY_ID
import com.headunit.music.core.model.LIKED_ID
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track

/**
 * Your libraries, and the rules for changing them:
 * - **Liked**: songs you've hearted, newest first.
 * - **History**: the last [HISTORY_SIZE] songs that played for at least 3
 *   seconds, newest first. Playing a song again moves it back to the top.
 * - **Your libraries**: most recently created / played first.
 *
 * Adding a song to one of your libraries also likes it. Immutable: every
 * change returns a new book; saving it is up to the platform (see LibraryStore).
 */
data class LibraryBook(
    val liked: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    /** Libraries you've made (not Liked or History). */
    val custom: List<Library> = emptyList(),
) {
    fun byId(id: String): Library? = when (id) {
        LIKED_ID -> Library(LIKED_ID, "Liked", liked)
        HISTORY_ID -> Library(HISTORY_ID, "History", history)
        else -> custom.firstOrNull { it.id == id }
    }

    fun isLiked(track: Track): Boolean = liked.any { it.videoId == track.videoId }

    fun contains(libraryId: String, track: Track): Boolean =
        byId(libraryId)?.tracks?.any { it.videoId == track.videoId } == true

    // ---- Liked ----

    fun like(track: Track): LibraryBook = if (isLiked(track)) this else copy(liked = listOf(track) + liked)

    fun unlike(track: Track): LibraryBook = copy(liked = liked.filterNot { it.videoId == track.videoId })

    fun toggleLike(track: Track): LibraryBook = if (isLiked(track)) unlike(track) else like(track)

    // ---- History ----

    /** A song has played for 3 seconds. */
    fun played(track: Track): LibraryBook =
        copy(history = (listOf(track) + history.filterNot { it.videoId == track.videoId }).take(HISTORY_SIZE))

    /** The × on a History row: forget that song was played. */
    fun forgetPlayed(track: Track): LibraryBook = copy(history = history.filterNot { it.videoId == track.videoId })

    // ---- Your libraries ----

    /** A new library called [name] (with id [id]), at the front. */
    fun create(id: String, name: String): LibraryBook =
        copy(custom = listOf(Library(id, name.trim(), emptyList())) + custom)

    /** A song was played from this library: it moves to the front (Liked and History stay put). */
    fun touch(libraryId: String): LibraryBook {
        val lib = custom.firstOrNull { it.id == libraryId } ?: return this
        if (custom.first() === lib) return this
        return copy(custom = listOf(lib) + custom.filterNot { it.id == libraryId })
    }

    /** "Change Cover Songs": [track]'s artwork goes in cover spot [slot]; if it's already in another spot, they swap. */
    fun setCover(libraryId: String, slot: Int, track: Track): LibraryBook = update(libraryId) { lib ->
        val ids = lib.coverTracks.map { it.videoId }.toMutableList()
        if (slot !in ids.indices) return@update lib
        val was = ids.indexOf(track.videoId)
        if (was >= 0) ids[was] = ids[slot]
        ids[slot] = track.videoId
        lib.copy(covers = ids)
    }

    /** A new name for a library you made (Liked and History keep theirs). Blank names are ignored. */
    fun rename(libraryId: String, name: String): LibraryBook {
        val clean = name.trim()
        return if (clean.isEmpty()) this else update(libraryId) { it.copy(name = clean) }
    }

    /** Removes a library you made; its songs stay liked. Liked and History can't be deleted. */
    fun delete(libraryId: String): LibraryBook =
        if (libraryId == LIKED_ID || libraryId == HISTORY_ID) this
        else copy(custom = custom.filterNot { it.id == libraryId })

    /** Adds [track] to the end of a library you made (if it isn't there yet), and likes it. */
    fun addTo(libraryId: String, track: Track): LibraryBook {
        val liked = like(track)
        return if (liked.contains(libraryId, track)) liked else liked.update(libraryId) { it.copy(tracks = it.tracks + track) }
    }

    fun removeFrom(libraryId: String, track: Track): LibraryBook =
        update(libraryId) { lib -> lib.copy(tracks = lib.tracks.filterNot { it.videoId == track.videoId }) }

    private inline fun update(id: String, change: (Library) -> Library): LibraryBook =
        copy(custom = custom.map { if (it.id == id) change(it) else it })

    companion object {
        const val HISTORY_SIZE = 50
    }
}
