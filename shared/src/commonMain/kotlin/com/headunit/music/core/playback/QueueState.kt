// Shared core (Android headunit + iPhone): plain Kotlin, no platform imports.
package com.headunit.music.core.playback

import com.headunit.music.core.model.Track
import kotlin.random.Random

/**
 * Everything about what's playing and what comes next: the songs, where we
 * are, and the rules for changing them (the queue page, hold-to-queue, the
 * YouTube Mix, shuffle, libraries looping).
 *
 * Immutable: every change returns a new [QueueState], and nothing here plays
 * audio or touches the network. The player (ExoPlayer on Android; AVPlayer
 * on iOS) holds the current state, plays [current], and reacts to what
 * changed (e.g. [ahead] = what to pre-buffer).
 */
data class QueueState(
    /** The songs, in play order. */
    val tracks: List<Track> = emptyList(),
    /** Position of the song playing in [tracks]; -1 when there's none. */
    val index: Int = -1,
    /**
     * Name of the library or album being played, shown as "Road trip — 3 of 20".
     * Null when playing from search results: those aren't a collection.
     */
    val name: String? = null,
    /** Go back to the first song after the last one (libraries / albums, not search). */
    val wrap: Boolean = false,
    val shuffle: Boolean = false,
    /**
     * Playing a song from search: the queue is that song's YouTube Mix (like
     * YouTube's autoplay), topped up as it runs low ([withMix]) so it never ends.
     */
    val autoMix: Boolean = false,
    /** The queue in its unshuffled order, so turning shuffle off puts it back. */
    val original: List<Track> = emptyList(),
    /** Songs added with a hold (or a double-tap) that haven't played yet: they line up after the current song. */
    val held: Set<String> = emptySet(),
    /** Songs already in this mix, so top-ups don't repeat them. */
    val mixSeen: Set<String> = emptySet(),
) {
    val current: Track? get() = tracks.getOrNull(index)

    /** Libraries loop both ways: back from the first song goes to the last. */
    val hasPrevious: Boolean get() = index > 0 || (wrap && tracks.size > 1)

    val hasNext: Boolean get() = index in 0 until tracks.size - 1 || (wrap && tracks.isNotEmpty()) || autoMix

    /** True if [track] was added with a hold and hasn't played yet. */
    fun isHeld(track: Track): Boolean = track.videoId in held

    /** Running low on mix songs: time to fetch more ([withMix]), seeded by the song playing. */
    val needsMoreMix: Boolean get() = autoMix && index >= tracks.size - 3

    /** The song [steps] places after the current one (libraries wrap round), if any. */
    fun ahead(steps: Int): Track? {
        if (tracks.isEmpty() || index < 0) return null
        val i = index + steps
        return when {
            i < tracks.size -> tracks[i]
            wrap -> tracks[i % tracks.size].takeIf { it.videoId != current?.videoId }
            else -> null
        }
    }

    // ---- Moving through it ----

    /** What "next" (the button, or a song ending) should do. */
    sealed interface Next {
        data class PlayAt(val index: Int) : Next
        /** At the end of a mix: more songs are on the way ([withMix]); play on when they arrive. */
        data object AwaitMix : Next
        data object Nothing : Next
    }

    fun next(): Next = when {
        index < tracks.size - 1 -> Next.PlayAt(index + 1)
        wrap && tracks.isNotEmpty() -> Next.PlayAt(0) // back to the start
        autoMix && current != null -> Next.AwaitMix
        else -> Next.Nothing
    }

    /**
     * Where "previous" goes, like most players: null = restart the song (you're
     * past [RESTART_AFTER_SECONDS], or there's nowhere to go back to), else the
     * song before. In a library (which loops), back from the first song goes to the last.
     */
    fun previous(positionSeconds: Float): Int? = when {
        positionSeconds > RESTART_AFTER_SECONDS || !hasPrevious -> null
        index > 0 -> index - 1
        else -> tracks.size - 1
    }

    /** Now playing the song at [i] (it's no longer "queued"). */
    fun at(i: Int): QueueState {
        val track = tracks.getOrNull(i) ?: return this
        return copy(index = i, held = held - track.videoId)
    }

    // ---- Starting something new ----

    /**
     * Plays [list] from [startIndex]; the rest become the queue. [wrap]: start
     * over after the last song (libraries / albums). With shuffle on, the tapped
     * song goes first and the rest are shuffled after it. Null if [startIndex] is out of range.
     */
    fun start(
        list: List<Track>,
        startIndex: Int,
        name: String? = null,
        wrap: Boolean = false,
        random: Random = Random,
    ): QueueState? {
        if (startIndex !in list.indices) return null
        val base = copy(
            name = name, wrap = wrap, original = list,
            autoMix = false, mixSeen = emptySet(), held = emptySet(),
        )
        return if (shuffle) {
            val first = list[startIndex]
            base.copy(tracks = listOf(first) + (list - first).shuffled(random)).at(0)
        } else {
            base.copy(tracks = list).at(startIndex)
        }
    }

    /** Plays [track] (tapped in search results), followed by its YouTube Mix rather than the other results. */
    fun startMix(track: Track): QueueState = copy(
        tracks = listOf(track), original = listOf(track), name = null, wrap = false,
        autoMix = true, mixSeen = setOf(track.videoId), held = emptySet(),
    ).at(0)

    // ---- Changing what's coming up ----

    /**
     * Hold-to-queue: [track] plays after the current song (and after anything
     * queued before it), ahead of the rest of the list or mix. If it was already
     * coming up later, it moves forward rather than playing twice. Needs a song
     * playing ([current]); otherwise returns this unchanged.
     */
    fun enqueue(track: Track): QueueState {
        val now = current ?: return this
        val behind = tracks.take(index + 1)
        val ahead = tracks.drop(index + 1).filter { it.videoId != track.videoId }
        val queuedFirst = ahead.takeWhile { it.videoId in held }.size
        val newTracks = behind + ahead.take(queuedFirst) + track + ahead.drop(queuedFirst)

        // Same spot in the unshuffled order, so turning shuffle off keeps it.
        val base = original.filter { it.videoId != track.videoId || it == now }
        val at = (base.indexOf(now) + 1).coerceAtLeast(0)
        val afterNow = base.drop(at)
        val q = afterNow.takeWhile { it.videoId in held }.size
        val newOriginal = base.take(at) + afterNow.take(q) + track + afterNow.drop(q)

        return copy(tracks = newTracks, original = newOriginal, held = held + track.videoId, mixSeen = mixSeen + track.videoId)
    }

    /**
     * Queue page double-tap: the song at [i] joins the queued songs, at the end
     * of them (like hold-to-queue). Double-tapping one that's already queued
     * moves it to the very front.
     */
    fun playNext(i: Int): QueueState {
        if (i !in tracks.indices || i == index) return this
        val track = tracks[i]
        val list = tracks.toMutableList()
        list.removeAt(i)
        val now = if (i < index) index - 1 else index
        val alreadyQueued = i > index && track.videoId in held
        val queuedAhead = if (alreadyQueued) 0 else list.drop(now + 1).takeWhile { it.videoId in held }.size
        list.add(now + 1 + queuedAhead, track)
        return copy(tracks = list, index = now, held = held + track.videoId).inSync()
    }

    /** Queue page drag: move the upcoming song at [from] to [to] (both after the current song). */
    fun move(from: Int, to: Int): QueueState {
        if (from == to || from <= index || to <= index || from !in tracks.indices || to !in tracks.indices) return this
        return copy(tracks = tracks.toMutableList().apply { add(to, removeAt(from)) }).inSync()
    }

    /** Queue page ×: take the upcoming song at [i] out. */
    fun remove(i: Int): QueueState {
        if (i <= index || i !in tracks.indices) return this
        val track = tracks[i]
        val o = original.indexOfLast { it.videoId == track.videoId }
        return copy(
            tracks = tracks.toMutableList().apply { removeAt(i) },
            original = if (o >= 0) original.toMutableList().apply { removeAt(o) } else original,
            held = held - track.videoId,
        )
    }

    /**
     * Adds a mix top-up: only songs not already in this mix, shuffled if shuffle
     * is on. Returns this unchanged if it's not a mix any more or nothing was new.
     */
    fun withMix(more: List<Track>, random: Random = Random): QueueState {
        if (!autoMix) return this
        val seen = mixSeen.toMutableSet()
        val fresh = more.filter { seen.add(it.videoId) }
        if (fresh.isEmpty()) return copy(mixSeen = seen)
        return copy(
            tracks = tracks + if (shuffle) fresh.shuffled(random) else fresh,
            original = original + fresh,
            mixSeen = seen,
        )
    }

    /** Shuffle the songs after the current one; turning it off restores the original order. */
    fun toggledShuffle(random: Random = Random): QueueState {
        val on = !shuffle
        val now = current ?: return copy(shuffle = on)
        if (autoMix) {
            // A mix keeps what's been played behind you; only the songs ahead move.
            val played = tracks.take(index + 1)
            val ahead = original.filter { it !in played }
            return copy(shuffle = on, tracks = played + if (on) ahead.shuffled(random) else ahead)
        }
        return if (on) {
            copy(shuffle = true, tracks = listOf(now) + (original - now).shuffled(random), index = 0)
        } else {
            copy(shuffle = false, tracks = original, index = original.indexOf(now).coerceAtLeast(0))
        }
    }

    /** The library being played was renamed: show the new name. */
    fun renamed(from: String, to: String): QueueState = if (name == from) copy(name = to) else this

    /** Hand-arranged queue: unshuffled, the "original" order is simply what's shown. */
    private fun inSync(): QueueState = if (shuffle) this else copy(original = tracks)

    // ---- Saving ----

    /**
     * A smaller copy for saving: at most [behind] songs before the current one and
     * [ahead] after it. (The mix's "seen" list isn't kept; [restored] rebuilds it.)
     */
    fun forSaving(behind: Int = 50, ahead: Int = 250): QueueState {
        if (current == null) return QueueState()
        val from = (index - behind).coerceAtLeast(0)
        val to = (index + ahead).coerceAtMost(tracks.size)
        val kept = tracks.subList(from, to)
        val keptIds = kept.mapTo(HashSet()) { it.videoId }
        return copy(
            tracks = kept,
            index = index - from,
            original = original.filter { it.videoId in keptIds },
            held = held.filterTo(HashSet()) { it in keptIds },
            mixSeen = emptySet(),
        )
    }

    /** After loading a saved copy: fills in what saving left out. Null if it doesn't make sense. */
    fun restored(): QueueState? {
        if (index !in tracks.indices) return null
        return copy(
            original = original.ifEmpty { tracks },
            mixSeen = if (autoMix) tracks.mapTo(HashSet()) { it.videoId } else emptySet(),
        )
    }

    companion object {
        /** Previous restarts the song once you're this far in. */
        const val RESTART_AFTER_SECONDS = 2.5f
    }
}
