package com.headunit.music.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.headunit.music.core.library.LibraryBook
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Your libraries, saved on the device. The rules (Liked, History, your own
 * libraries) live in [LibraryBook], in the shared core; this keeps the current
 * book as Compose state, so screens update by themselves, and saves each part
 * as it changes.
 */
class LibraryStore private constructor(context: Context) {

    companion object {
        @Volatile private var instance: LibraryStore? = null

        /** The app's one copy, shared by the screen and the player (which records History). */
        fun get(context: Context): LibraryStore =
            instance ?: synchronized(this) {
                instance ?: LibraryStore(context.applicationContext).also { instance = it }
            }

        private const val KEY_LIKED = "liked"
        private const val KEY_HISTORY = "history"
        private const val KEY_CUSTOM = "custom"
    }

    private val prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE)

    private var book by mutableStateOf(
        LibraryBook(liked = readTracks(KEY_LIKED), history = readTracks(KEY_HISTORY), custom = readCustom()),
    )

    val liked: List<Track> get() = book.liked
    val history: List<Track> get() = book.history
    /** Libraries you've made (not Liked or History). */
    val custom: List<Library> get() = book.custom

    fun byId(id: String): Library? = book.byId(id)
    fun isLiked(track: Track): Boolean = book.isLiked(track)
    fun contains(libraryId: String, track: Track): Boolean = book.contains(libraryId, track)

    // ---- Changes (each one saved straight away) ----

    fun toggleLike(track: Track) = change { it.toggleLike(track) }
    fun like(track: Track) = change { it.like(track) }
    fun unlike(track: Track) = change { it.unlike(track) }

    /** Called by the player once a song has played for 3 seconds. */
    fun recordPlayed(track: Track) = change { it.played(track) }

    /** The × on a History row. */
    fun removeFromHistory(track: Track) = change { it.forgetPlayed(track) }

    fun create(name: String): Library {
        val id = UUID.randomUUID().toString()
        change { it.create(id, name) }
        return book.byId(id)!!
    }

    fun touch(libraryId: String) = change { it.touch(libraryId) }
    fun setCover(libraryId: String, slot: Int, track: Track) = change { it.setCover(libraryId, slot, track) }
    fun rename(libraryId: String, name: String) = change { it.rename(libraryId, name) }
    fun delete(libraryId: String) = change { it.delete(libraryId) }
    fun addTo(libraryId: String, track: Track) = change { it.addTo(libraryId, track) }
    fun removeFrom(libraryId: String, track: Track) = change { it.removeFrom(libraryId, track) }

    /** Applies [edit] and saves whichever parts it changed. */
    private inline fun change(edit: (LibraryBook) -> LibraryBook) {
        val before = book
        val after = edit(before)
        if (after == before) return
        book = after
        if (after.liked != before.liked) writeTracks(KEY_LIKED, after.liked)
        if (after.history != before.history) writeTracks(KEY_HISTORY, after.history)
        if (after.custom != before.custom) writeCustom(after.custom)
    }

    // ---- Saving (JSON in SharedPreferences) ----

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
            val covers = o.optJSONArray("covers")?.let { c -> List(c.length()) { k -> c.getString(k) } }.orEmpty()
            Library(o.getString("id"), o.getString("name"), o.getJSONArray("tracks").toTracks(), covers)
        }
    }.getOrDefault(emptyList())

    private fun writeCustom(custom: List<Library>) {
        val arr = JSONArray()
        custom.forEach { lib ->
            arr.put(
                JSONObject().put("id", lib.id).put("name", lib.name).put("tracks", lib.tracks.toJson())
                    .put("covers", JSONArray(lib.covers))
            )
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
}
