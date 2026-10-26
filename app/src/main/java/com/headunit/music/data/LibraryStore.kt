package com.headunit.music.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A collection of songs. [id] is [LIKED_ID], [HISTORY_ID] or a random id for ones you make. */
data class Library(val id: String, val name: String, val tracks: List<Track>)

const val LIKED_ID = "liked"
const val HISTORY_ID = "history"

/**
 * Your libraries, saved on the device:
 * - **Liked**: songs you've hearted, newest first.
 * - **History**: the last [HISTORY_SIZE] songs that played for at least 3 seconds, newest first.
 *   Playing a song again moves it back to the top instead of listing it twice.
 * - Libraries you make, in the order you made them.
 *
 * Adding a song to one of your libraries also likes it.
 * Everything is Compose state, so screens update by themselves.
 */
class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE)

    var liked by mutableStateOf(readTracks(KEY_LIKED))
        private set
    var history by mutableStateOf(readTracks(KEY_HISTORY))
        private set
    /** Libraries you've made (not Liked or History). */
    var custom by mutableStateOf(readCustom())
        private set

    fun byId(id: String): Library? = when (id) {
        LIKED_ID -> Library(LIKED_ID, "Liked", liked)
        HISTORY_ID -> Library(HISTORY_ID, "History", history)
        else -> custom.firstOrNull { it.id == id }
    }

    // ---- Liked ----

    fun isLiked(track: Track): Boolean = liked.any { it.videoId == track.videoId }

    fun toggleLike(track: Track) {
        if (isLiked(track)) unlike(track) else like(track)
    }

    fun like(track: Track) {
        if (isLiked(track)) return
        liked = listOf(track) + liked
        writeTracks(KEY_LIKED, liked)
    }

    fun unlike(track: Track) {
        liked = liked.filterNot { it.videoId == track.videoId }
        writeTracks(KEY_LIKED, liked)
    }

    // ---- History ----

    /** Called by the player once a song has played for 3 seconds. */
    fun recordPlayed(track: Track) {
        history = (listOf(track) + history.filterNot { it.videoId == track.videoId }).take(HISTORY_SIZE)
        writeTracks(KEY_HISTORY, history)
    }

    // ---- Your libraries ----

    fun create(name: String): Library {
        val lib = Library(UUID.randomUUID().toString(), name.trim(), emptyList())
        custom = custom + lib
        writeCustom()
        return lib
    }

    /** Removes a library you made. Liked and History can't be deleted; its songs stay liked. */
    fun delete(libraryId: String) {
        if (libraryId == LIKED_ID || libraryId == HISTORY_ID) return
        custom = custom.filterNot { it.id == libraryId }
        writeCustom()
    }

    fun contains(libraryId: String, track: Track): Boolean =
        byId(libraryId)?.tracks?.any { it.videoId == track.videoId } == true

    /** Adds [track] to the end of a library you made, and likes it. */
    fun addTo(libraryId: String, track: Track) {
        like(track)
        if (contains(libraryId, track)) return
        updateCustom(libraryId) { it.copy(tracks = it.tracks + track) }
    }

    fun removeFrom(libraryId: String, track: Track) {
        updateCustom(libraryId) { lib -> lib.copy(tracks = lib.tracks.filterNot { it.videoId == track.videoId }) }
    }

    private fun updateCustom(id: String, change: (Library) -> Library) {
        custom = custom.map { if (it.id == id) change(it) else it }
        writeCustom()
    }

    // ---- Saving ----

    private fun readTracks(key: String): List<Track> =
        runCatching { JSONArray(prefs.getString(key, null) ?: return emptyList()).toTracks() }
            .getOrDefault(emptyList())

    private fun writeTracks(key: String, tracks: List<Track>) {
        prefs.edit().putString(key, tracks.toJson().toString()).apply()
    }

    private fun readCustom(): List<Library> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_CUSTOM, null) ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Library(o.getString("id"), o.getString("name"), o.getJSONArray("tracks").toTracks())
        }
    }.getOrDefault(emptyList())

    private fun writeCustom() {
        val arr = JSONArray()
        custom.forEach { lib ->
            arr.put(JSONObject().put("id", lib.id).put("name", lib.name).put("tracks", lib.tracks.toJson()))
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private companion object {
        const val HISTORY_SIZE = 30
        const val KEY_LIKED = "liked"
        const val KEY_HISTORY = "history"
        const val KEY_CUSTOM = "custom"
    }
}
