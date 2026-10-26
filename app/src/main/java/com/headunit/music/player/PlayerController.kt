package com.headunit.music.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.headunit.music.data.Track
import com.headunit.music.player.HiddenYouTubePlayer.Companion.STATE_BUFFERING
import com.headunit.music.player.HiddenYouTubePlayer.Companion.STATE_ENDED
import com.headunit.music.player.HiddenYouTubePlayer.Companion.STATE_PLAYING
import com.headunit.music.player.HiddenYouTubePlayer.Companion.STATE_UNSTARTED

/**
 * Plays songs through YouTube's own player, hidden: you see the artwork and
 * hear the song. Keeps a simple queue.
 *
 * Two hidden players take turns. One plays the current song; once it's
 * playing, the other loads the next song muted and holds it at 0:00. Skipping
 * (or the song ending) swaps them, so the next song starts instantly.
 *
 * All state is Compose state, so the screens update on their own.
 */
class PlayerController(context: Context) {

    // ---- Queue ----
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    /**
     * Name of the library or playlist being played, shown as "Road trip — 3 of 20".
     * Null when playing from search results: those aren't a collection, so no position is shown.
     */
    var queueName by mutableStateOf<String?>(null)
        private set
    val current: Track? by derivedStateOf { queue.getOrNull(index) }
    val hasPrevious: Boolean by derivedStateOf { index > 0 }
    val hasNext: Boolean by derivedStateOf {
        index in 0 until queue.size - 1 || (wrapQueue && queue.isNotEmpty())
    }

    /** The queue in its original order, so turning shuffle off puts it back. */
    private var originalOrder: List<Track> = emptyList()

    var shuffle by mutableStateOf(false)
        private set
    /** Loop button: replay the current song when it ends. */
    var loopSong by mutableStateOf(false)
        private set

    /**
     * Go back to the first song after the last one. Libraries and playlists
     * will play this way; search results don't.
     */
    var wrapQueue by mutableStateOf(false)
        private set

    // ---- Playback (fed by the active player) ----
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionSeconds by mutableFloatStateOf(0f)
        private set
    var durationSeconds by mutableFloatStateOf(0f)
        private set
    /** Set when YouTube refuses a video; cleared on the next song. */
    var error by mutableStateOf<PlayerError?>(null)
        private set

    /** Now Playing screen open (true) or minimized to the dock (false). */
    var expanded by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var skipOnError: Runnable? = null

    private val events: HiddenYouTubePlayer.Events = object : HiddenYouTubePlayer.Events {
        override fun onState(player: HiddenYouTubePlayer, state: Int) {
            if (player === active) onActiveState(state)
        }

        override fun onTime(player: HiddenYouTubePlayer, position: Double, duration: Double) {
            if (player !== active) return
            positionSeconds = position.toFloat()
            if (duration > 0) durationSeconds = duration.toFloat()
        }

        override fun onError(player: HiddenYouTubePlayer, code: Int) {
            if (player === active) onActiveError(code)
        }
    }

    private val players: List<HiddenYouTubePlayer> = listOf(
        HiddenYouTubePlayer(context, events),
        HiddenYouTubePlayer(context, events),
    )
    private var active: HiddenYouTubePlayer = players[0]
    private val standby: HiddenYouTubePlayer
        get() = if (active === players[0]) players[1] else players[0]

    /** Both hidden players' views; the overlay keeps them on screen, tiny, behind the artwork. */
    val playerViews: List<WebView> get() = players.map { it.view }

    // Load timing for the log: tap → first sound. Filter Logcat by "MuZ".
    private var tapAt = 0L
    private var startedFromPreload = false

    // ---- Controls ----

    /**
     * Plays [tracks] starting at [startIndex]; the rest become the queue.
     * [wrap]: start over after the last song (libraries / playlists pass true).
     */
    fun playQueue(tracks: List<Track>, startIndex: Int, name: String? = null, wrap: Boolean = false) {
        if (startIndex !in tracks.indices) return
        originalOrder = tracks
        queueName = name
        wrapQueue = wrap
        if (shuffle) {
            // Keep the tapped song first, shuffle the rest after it.
            val first = tracks[startIndex]
            queue = listOf(first) + (tracks - first).shuffled()
            playAt(0)
        } else {
            queue = tracks
            playAt(startIndex)
        }
        expanded = true
    }

    /** Shuffle the songs after the current one; turning it off restores the original order. */
    fun toggleShuffle() {
        val now = current
        shuffle = !shuffle
        if (now == null) return
        if (shuffle) {
            queue = listOf(now) + (originalOrder - now).shuffled()
            index = 0
        } else {
            queue = originalOrder
            index = originalOrder.indexOf(now).coerceAtLeast(0)
        }
        // The next song changed: get the new one ready instead.
        if (isPlaying) preloadNext()
    }

    fun toggleLoop() {
        loopSong = !loopSong
    }

    fun togglePlay() {
        if (current == null) return
        if (isPlaying) active.pause() else active.play()
    }

    fun next() {
        when {
            index < queue.size - 1 -> playAt(index + 1)
            wrapQueue && queue.isNotEmpty() -> playAt(0) // back to the start
        }
    }

    /** Like most players: restarts the song if you're past 3 seconds, else goes back one. */
    fun previous() {
        if (positionSeconds > 3f || !hasPrevious) seekTo(0f) else playAt(index - 1)
    }

    fun seekTo(seconds: Float) {
        positionSeconds = seconds.coerceIn(0f, durationSeconds.coerceAtLeast(0f))
        active.seek(positionSeconds)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        players.forEach { it.destroy() }
    }

    private fun playAt(i: Int) {
        val track = queue.getOrNull(i) ?: return
        skipOnError?.let { main.removeCallbacks(it) }
        index = i
        error = null
        positionSeconds = 0f
        durationSeconds = (track.durationSeconds ?: 0).toFloat()
        isBuffering = true
        tapAt = SystemClock.elapsedRealtime()

        val ready = standby
        if (ready.videoId == track.videoId && ready.preloaded) {
            // The other player already has this song buffered at 0:00: swap and go.
            active.stop()
            active = ready
            active.activate()
            startedFromPreload = true
        } else {
            standby.stop() // whatever it was holding isn't next anymore
            active.load(track.videoId)
            startedFromPreload = false
        }
    }

    /** Get the next song ready in the standby player (only once this one is playing). */
    private fun preloadNext() {
        val next = queue.getOrNull(index + 1) ?: if (wrapQueue) queue.firstOrNull() else null
        if (next == null || next.videoId == current?.videoId) return
        if (standby.videoId == next.videoId) return // already there (or on its way)
        standby.preload(next.videoId)
    }

    private fun onActiveState(state: Int) {
        isPlaying = state == STATE_PLAYING
        isBuffering = state == STATE_BUFFERING || state == STATE_UNSTARTED
        active.view.keepScreenOn = isPlaying
        if (state == STATE_PLAYING) {
            if (tapAt > 0) {
                val ms = SystemClock.elapsedRealtime() - tapAt
                Log.d(TAG, "Load \"${current?.title}\": ${ms} ms" + if (startedFromPreload) " (preloaded)" else "")
                tapAt = 0
            }
            // Playing smoothly now, so the next song can use some bandwidth.
            preloadNext()
        }
        if (state == STATE_ENDED) {
            when {
                loopSong -> { active.seek(0f); active.play() }
                hasNext -> next()
                else -> isBuffering = false
            }
        }
    }

    private fun onActiveError(code: Int) {
        val e = PlayerError.from(code)
        error = e
        isPlaying = false
        isBuffering = false
        // Songs YouTube won't play here: move on by themselves.
        if (e.skippable && hasNext) {
            skipOnError = Runnable { next() }.also { main.postDelayed(it, 2500) }
        }
    }
}

private const val TAG = "MuZ"

/** Why YouTube wouldn't play a video, in words for the screen. */
enum class PlayerError(val message: String, val skippable: Boolean) {
    NotAllowed("This song can't be played outside YouTube", skippable = true),
    Unavailable("This video was removed or is private", skippable = true),
    BadRequest("Couldn't load this song", skippable = true),
    PlayerConfig("YouTube blocked the player setup (error 153)", skippable = false),
    Other("Couldn't play this song", skippable = true);

    companion object {
        fun from(code: Int) = when (code) {
            101, 150 -> NotAllowed
            100 -> Unavailable
            2 -> BadRequest
            152, 153 -> PlayerConfig
            else -> Other
        }
    }
}

