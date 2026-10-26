package com.headunit.music.phone.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.headunit.music.core.library.LibraryBook
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track
import com.headunit.music.phone.Settings
import com.headunit.music.phone.builtInApiKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * Saving on the iPhone. Same JSON shapes as the headunit (data/TrackJson.kt,
 * LibraryStore.kt), so libraries could be moved between the two later.
 */

@Serializable
internal data class TrackJson(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val artworkUrl: String? = null,
    val artworkLetterboxed: Boolean = false,
    val durationSeconds: Int? = null,
) {
    fun toTrack() = Track(videoId, title, artist, thumbnailUrl, artworkUrl ?: thumbnailUrl, artworkLetterboxed, durationSeconds)

    companion object {
        fun of(t: Track) = TrackJson(t.videoId, t.title, t.artist, t.thumbnailUrl, t.artworkUrl, t.artworkLetterboxed, t.durationSeconds)
    }
}

@Serializable
internal data class LibraryJson(
    val id: String,
    val name: String,
    val tracks: List<TrackJson>,
    val covers: List<String> = emptyList(),
)

private val trackList = ListSerializer(TrackJson.serializer())

internal fun List<Track>.toJsonString(): String = Http.json.encodeToString(trackList, map(TrackJson::of))
internal fun String.toTracks(): List<Track> =
    runCatching { Http.json.decodeFromString(trackList, this).map { it.toTrack() } }.getOrDefault(emptyList())

/**
 * Your libraries, saved on the phone. The rules (Liked, History, your own
 * libraries) are the shared core's [LibraryBook]; this keeps the current book
 * as Compose state, so screens update by themselves, and saves each part as it
 * changes. Same as the headunit's LibraryStore.
 */
class LibraryStore {

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

    fun toggleLike(track: Track) = change { it.toggleLike(track) }
    fun like(track: Track) = change { it.like(track) }
    fun unlike(track: Track) = change { it.unlike(track) }

    /** Called by the player once a song has played for 3 seconds. */
    fun recordPlayed(track: Track) = change { it.played(track) }

    /** The × on a History row. */
    fun removeFromHistory(track: Track) = change { it.forgetPlayed(track) }

    @OptIn(ExperimentalUuidApi::class)
    fun create(name: String): Library {
        val id = Uuid.random().toString()
        change { it.create(id, name) }
        return book.byId(id)!!
    }

    fun touch(libraryId: String) = change { it.touch(libraryId) }
    fun setCover(libraryId: String, slot: Int, track: Track) = change { it.setCover(libraryId, slot, track) }
    fun rename(libraryId: String, name: String) = change { it.rename(libraryId, name) }
    fun delete(libraryId: String) = change { it.delete(libraryId) }
    fun addTo(libraryId: String, track: Track) = change { it.addTo(libraryId, track) }
    fun removeFrom(libraryId: String, track: Track) = change { it.removeFrom(libraryId, track) }

    private inline fun change(edit: (LibraryBook) -> LibraryBook) {
        val before = book
        val after = edit(before)
        if (after == before) return
        book = after
        if (after.liked != before.liked) Settings.putString(KEY_LIKED, after.liked.toJsonString())
        if (after.history != before.history) Settings.putString(KEY_HISTORY, after.history.toJsonString())
        if (after.custom != before.custom) writeCustom(after.custom)
    }

    private fun readTracks(key: String): List<Track> = Settings.getString(key)?.toTracks().orEmpty()

    private fun readCustom(): List<Library> = runCatching {
        val raw = Settings.getString(KEY_CUSTOM) ?: return emptyList()
        Http.json.decodeFromString(ListSerializer(LibraryJson.serializer()), raw)
            .map { Library(it.id, it.name, it.tracks.map(TrackJson::toTrack), it.covers) }
    }.getOrDefault(emptyList())

    private fun writeCustom(custom: List<Library>) {
        val json = custom.map { lib -> LibraryJson(lib.id, lib.name, lib.tracks.map(TrackJson::of), lib.covers) }
        Settings.putString(KEY_CUSTOM, Http.json.encodeToString(ListSerializer(LibraryJson.serializer()), json))
    }

    private companion object {
        const val KEY_LIKED = "libraries.liked"
        const val KEY_HISTORY = "libraries.history"
        const val KEY_CUSTOM = "libraries.custom"
    }
}

/**
 * Past searches, newest first. Searching something again moves it back to the
 * top instead of duplicating it. Same as the headunit's SearchHistory.
 */
class SearchHistory(private val maxEntries: Int = 30) {

    fun load(): List<String> = runCatching {
        Http.json.decodeFromString(ListSerializer(String.serializer()), Settings.getString(KEY) ?: return emptyList())
    }.getOrDefault(emptyList())

    /** Records a search and returns the updated list. */
    fun add(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return load()
        val updated = (listOf(q) + load().filterNot { it.equals(q, ignoreCase = true) }).take(maxEntries)
        save(updated)
        return updated
    }

    /** Removes one entry and returns the updated list. */
    fun remove(query: String): List<String> {
        val updated = load().filterNot { it.equals(query, ignoreCase = true) }
        save(updated)
        return updated
    }

    /** Past searches that fit what's being typed: ones that start with it first, then ones with a word starting with it. */
    fun matching(history: List<String>, typed: String): List<String> {
        val t = typed.trim().lowercase()
        if (t.isEmpty()) return history
        val starts = history.filter { it.lowercase().startsWith(t) && !it.equals(typed.trim(), ignoreCase = true) }
        val words = history.filter { h ->
            h !in starts && !h.equals(typed.trim(), ignoreCase = true) &&
                h.lowercase().split(' ').any { it.startsWith(t) }
        }
        return starts + words
    }

    private fun save(list: List<String>) =
        Settings.putString(KEY, Http.json.encodeToString(ListSerializer(String.serializer()), list))

    private companion object {
        const val KEY = "search.history"
    }
}

/**
 * The YouTube Data API key: one pasted into the app wins, otherwise the one
 * built in from the YOUTUBE_API_KEY secret.
 */
object ApiKey {
    private const val KEY = "youtube.apiKey"
    var pasted by mutableStateOf(Settings.getString(KEY).orEmpty())
        private set

    val current: String get() = pasted.ifBlank { builtInApiKey() }

    fun set(key: String) {
        pasted = key.trim()
        if (pasted.isEmpty()) Settings.remove(KEY) else Settings.putString(KEY, pasted)
    }
}
