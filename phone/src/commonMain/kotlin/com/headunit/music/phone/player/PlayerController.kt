package com.headunit.music.phone.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.headunit.music.core.model.Track
import com.headunit.music.core.playback.AudioUnavailableException
import com.headunit.music.core.playback.PlayerError
import com.headunit.music.core.playback.QueueState
import com.headunit.music.core.sources.AudioStreamSource
import com.headunit.music.core.sources.MixSource
import com.headunit.music.phone.Settings
import com.headunit.music.phone.data.Http
import com.headunit.music.phone.data.TrackJson
import com.headunit.music.phone.data.YouTubeAudio
import com.headunit.music.phone.data.YouTubeMix
import com.headunit.music.phone.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

/**
 * Plays songs as audio only and keeps the queue: the iPhone's version of the
 * headunit's player/PlayerController.kt, with the same controls and state so
 * the screens work the same way.
 *
 * Each song's audio link is found straight from the phone ([YouTubeAudio]) and
 * played by the [AudioEngine] (AVPlayer). A song played from search is
 * followed by its YouTube Mix ([YouTubeMix]), like YouTube's autoplay. The
 * next song's link is looked up while this one plays and handed to the engine,
 * which buffers it so it follows on without a gap, screen locked or not.
 *
 * All state is Compose state, so the screens update on their own. Main thread only.
 */
class PlayerController(
    private val engine: AudioEngine = createAudioEngine(),
    private val audio: AudioStreamSource = YouTubeAudio,
    private val mix: MixSource = YouTubeMix,
) {
    // ---- Queue (the rules are the shared core's QueueState) ----
    private var q by mutableStateOf(QueueState())

    val queue: List<Track> get() = q.tracks
    val index: Int get() = q.index
    /** Name of the library or album being played ("Road trip — 3 of 20"); null for search plays. */
    val queueName: String? get() = q.name
    val current: Track? get() = q.current
    val hasPrevious: Boolean get() = q.hasPrevious
    val hasNext: Boolean get() = q.hasNext
    val shuffle: Boolean get() = q.shuffle
    val autoMix: Boolean get() = q.autoMix

    /** Loop button: replay the current song when it ends. */
    var loopSong by mutableStateOf(false)
        private set

    private var mixJob: Job? = null
    private var advanceWhenMixArrives = false

    // ---- Playback ----
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionSeconds by mutableFloatStateOf(0f)
        private set
    var durationSeconds by mutableFloatStateOf(0f)
        private set
    /** Set when a song can't be played; cleared on the next song. */
    var error by mutableStateOf<PlayerError?>(null)
        private set

    /** Now Playing open (true) or minimized to the dock (false). */
    var expanded by mutableStateOf(false)

    /** Called once per play when a song has played for 3 seconds (History listens). */
    var onPlayedLongEnough: ((Track) -> Unit)? = null
    private var playedMs = 0L
    private var reportedPlay = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Restored from last time but not loaded yet: the first Play starts from here (ms). */
    private var restoredAtMs: Long? = null
    private var sinceSaveMs = 0L
    private var loadJob: Job? = null
    private var skipJob: Job? = null
    private var preloadJob: Job? = null
    /** One fresh-link retry per song (links expire after a few hours). */
    private var retriedVideoId: String? = null
    /** The song the engine has loaded (null when nothing is, e.g. restored from last time). */
    private var loadedId: String? = null
    /** Seconds to ignore the engine's position after a seek, so the bar doesn't jump back. */
    private var seekSettleMs = 0L

    private var tapAt: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        engine.listener = EngineListener()
        restoreState()
        scope.launch {
            while (isActive) {
                tick()
                delay(POLL_MS)
            }
        }
    }

    /** A few times a second: copy the engine's state into ours, count play time, save now and then. */
    private fun tick() {
        val s = engine.state()
        if (s.currentId != null && s.currentId == current?.videoId) {
            val wasPlaying = isPlaying
            isPlaying = s.isPlaying
            isBuffering = s.isBuffering || (loadJob?.isActive == true)
            if (seekSettleMs > 0) seekSettleMs -= POLL_MS else positionSeconds = s.positionSeconds
            s.durationSeconds?.takeIf { it > 0f }?.let { durationSeconds = it }
            if (wasPlaying && !isPlaying && !isBuffering) saveState() // paused: remember exactly where
            if (isPlaying) tapAt?.let {
                log("Load \"${current?.title}\": ${it.elapsedNow().inWholeMilliseconds} ms from tap to sound")
                tapAt = null
            }
        } else if (loadJob?.isActive != true && loadedId == null) {
            isPlaying = false
        }
        if (isPlaying) {
            playedMs += POLL_MS
            sinceSaveMs += POLL_MS
            if (sinceSaveMs >= SAVE_EVERY_MS) saveState()
            if (!reportedPlay && playedMs >= COUNTS_AS_PLAYED_MS) {
                reportedPlay = true
                current?.let { onPlayedLongEnough?.invoke(it) }
            }
        }
        engine.updateNowPlaying(current?.let { itemFor(it, "") }, positionSeconds, durationSeconds, isPlaying, hasPrevious, hasNext)
    }

    // ---- Controls (same as the headunit's) ----

    /** Plays [tracks] from [startIndex]; the rest become the queue. [wrap]: loop (libraries / albums). */
    fun playQueue(tracks: List<Track>, startIndex: Int, name: String? = null, wrap: Boolean = false) {
        val started = q.start(tracks, startIndex, name, wrap) ?: return
        stopMix()
        q = started
        playAt(q.index)
        expanded = true
    }

    /** Plays [track] (tapped in search results), followed by its YouTube Mix. */
    fun playWithMix(track: Track) {
        stopMix()
        q = q.startMix(track)
        playAt(0)
        expanded = true
    }

    /** Hold-to-queue: [track] plays after the current song. If nothing is playing it starts. True if queued. */
    fun addToQueue(track: Track): Boolean {
        if (current == null) {
            playWithMix(track)
            return false
        }
        update(q.enqueue(track))
        return true
    }

    fun isHeldQueued(track: Track): Boolean = q.isHeld(track)

    fun skipTo(i: Int) {
        if (i !in queue.indices || i == index) return
        playAt(i)
    }

    fun moveInQueue(from: Int, to: Int) = update(q.move(from, to))

    fun playNext(i: Int) = update(q.playNext(i))

    fun removeFromQueue(i: Int) {
        val removed = q.remove(i)
        if (removed == q) return
        update(removed)
        if (q.needsMoreMix) current?.let { extendMix(it) }
    }

    fun renameQueue(from: String, to: String) {
        val renamed = q.renamed(from, to)
        if (renamed == q) return
        q = renamed
        saveState()
    }

    fun toggleShuffle() = update(q.toggledShuffle())

    fun toggleLoop() {
        loopSong = !loopSong
        engine.loop = loopSong
        preloadNext() // looping: nothing queued behind this song
    }

    fun togglePlay() {
        if (current == null) return
        if (needsLoad()) {
            loadAndPlay()
            return
        }
        val playing = engine.state().isPlaying || engine.state().isBuffering
        if (playing) engine.pause() else engine.resume()
        isPlaying = !playing
        if (playing) saveState()
    }

    fun next() {
        when (val step = q.next()) {
            is QueueState.Next.PlayAt -> playAt(step.index)
            QueueState.Next.AwaitMix -> current?.let {
                advanceWhenMixArrives = true
                extendMix(it)
            }
            QueueState.Next.Nothing -> {}
        }
    }

    /** Restarts the song, or goes back one (see [QueueState.previous]). */
    fun previous() {
        val i = q.previous(positionSeconds)
        if (i == null) seekTo(0f) else playAt(i)
    }

    fun seekTo(seconds: Float) {
        positionSeconds = seconds.coerceIn(0f, durationSeconds.coerceAtLeast(0f).takeIf { it > 0f } ?: seconds)
        if (restoredAtMs != null || loadedId == null) {
            restoredAtMs = (positionSeconds * 1000).toLong()
        } else {
            seekSettleMs = 750
            engine.seekTo(positionSeconds)
        }
    }

    // ---- Inside ----

    private fun needsLoad(): Boolean = current != null && (restoredAtMs != null || loadedId == null) && loadJob?.isActive != true

    private fun loadAndPlay() {
        val at = restoredAtMs
        if (at != null) playAt(index, resumeAtMs = at) else playAt(index)
    }

    private fun update(new: QueueState) {
        val next = q.ahead(1)
        q = new
        if (q.ahead(1) != next) preloadNext()
    }

    private fun stopMix() {
        mixJob?.cancel()
        mixJob = null
        advanceWhenMixArrives = false
    }

    private fun extendMix(seed: Track) {
        if (mixJob?.isActive == true) return
        mixJob = scope.launch {
            val more = mix.upNext(seed)
            val topped = q.withMix(more)
            if (topped.tracks.size == q.tracks.size) {
                q = topped
                return@launch
            }
            update(topped)
            if (advanceWhenMixArrives) {
                advanceWhenMixArrives = false
                next()
            }
        }
    }

    private fun playAt(i: Int, resumeAtMs: Long = 0L) {
        val track = queue.getOrNull(i) ?: return
        if (track.videoId != retriedVideoId) retriedVideoId = null
        skipJob?.cancel()
        loadJob?.cancel()
        restoredAtMs = null
        q = q.at(i)
        error = null
        advanceWhenMixArrives = false
        if (q.needsMoreMix) extendMix(track)
        if (resumeAtMs == 0L) {
            playedMs = 0L
            reportedPlay = false
        }
        positionSeconds = resumeAtMs / 1000f
        durationSeconds = (track.durationSeconds ?: 0).toFloat()
        isBuffering = true
        seekSettleMs = 0
        tapAt = TimeSource.Monotonic.markNow()
        // (The engine keeps playing the old song until the new one is ready, unless it's the same.)
        if (engine.state().currentId != track.videoId) engine.pause()

        loadJob = scope.launch {
            try {
                val url = audio.urlFor(track.videoId)
                if (current?.videoId != track.videoId) return@launch
                engine.play(itemFor(track, url), resumeAtMs / 1000f)
                loadedId = track.videoId
                preloadNext()
            } catch (e: AudioUnavailableException) {
                fail(PlayerError.from(e.reason))
            }
        }
    }

    /** Looks up the next song's link and hands it to the engine to buffer (nothing while looping). */
    private fun preloadNext() {
        preloadJob?.cancel()
        val next = q.ahead(1)
        if (next == null || loopSong) {
            engine.prepareNext(null)
            return
        }
        preloadJob = scope.launch {
            delay(1_500) // let the song that's playing get going
            val url = runCatching { audio.urlFor(next.videoId) }.getOrNull() ?: return@launch
            if (q.ahead(1)?.videoId == next.videoId && !loopSong) engine.prepareNext(itemFor(next, url))
        }
    }

    private fun itemFor(track: Track, url: String) = EngineItem(
        videoId = track.videoId,
        url = url,
        userAgent = YouTubeAudio.USER_AGENT,
        title = track.title,
        artist = track.artist,
        album = queueName,
        artworkUrl = track.artworkUrl,
        artworkLetterboxed = track.artworkLetterboxed,
        durationHintSeconds = (track.durationSeconds ?: 0).toFloat(),
    )

    private fun fail(e: PlayerError) {
        error = e
        isPlaying = false
        isBuffering = false
        loadedId = null
        if (e.skippable && hasNext) {
            skipJob = scope.launch {
                delay(2500)
                next()
            }
        }
    }

    private inner class EngineListener : AudioEngine.Listener {
        override fun onEnded(videoId: String) {
            if (videoId != current?.videoId) return
            positionSeconds = durationSeconds
            next()
        }

        override fun onFailed(videoId: String, message: String?) {
            val track = current ?: return
            if (videoId != track.videoId) return
            log("Playback failed for ${track.title}: $message")
            if (retriedVideoId != track.videoId) {
                // Most often an expired link: fetch a fresh one and carry on from here.
                retriedVideoId = track.videoId
                audio.forget(track.videoId)
                playAt(index, resumeAtMs = (positionSeconds * 1000).toLong())
            } else {
                fail(PlayerError.Other)
            }
        }

        override fun onRemoteCommand(command: RemoteCommand) {
            when (command) {
                RemoteCommand.Play -> if (!isPlaying) togglePlay()
                RemoteCommand.Pause -> if (isPlaying) togglePlay()
                RemoteCommand.TogglePlayPause -> togglePlay()
                RemoteCommand.Next -> next()
                RemoteCommand.Previous -> previous()
            }
        }

        override fun onRemoteSeek(seconds: Float) = seekTo(seconds)

        override fun onInterrupted(began: Boolean, shouldResume: Boolean) {
            if (began) {
                isPlaying = false
                saveState()
            } else if (shouldResume && loadedId != null) {
                engine.resume()
            }
        }

        override fun onOutputLost() {
            if (isPlaying) togglePlay()
        }
    }

    // ---- Remembering the last song across app restarts ----

    @Serializable
    private data class Saved(
        val queue: List<TrackJson>,
        val original: List<TrackJson> = emptyList(),
        val index: Int,
        val positionMs: Long = 0,
        val durationSeconds: Double = 0.0,
        val wrap: Boolean = false,
        val autoMix: Boolean = false,
        val shuffle: Boolean = false,
        val loop: Boolean = false,
        val held: List<String> = emptyList(),
        val name: String? = null,
    )

    /** Saves the queue, the song and where it's at. Called as it plays, on pause, and when the app goes away. */
    fun saveState() {
        sinceSaveMs = 0L
        val kept = q.forSaving(KEEP_BEHIND, KEEP_AHEAD)
        if (kept.current == null) {
            Settings.remove(KEY_STATE)
            return
        }
        val saved = Saved(
            queue = kept.tracks.map(TrackJson::of),
            original = kept.original.map(TrackJson::of),
            index = kept.index,
            positionMs = restoredAtMs ?: (positionSeconds * 1000).toLong(),
            durationSeconds = durationSeconds.toDouble(),
            wrap = kept.wrap,
            autoMix = kept.autoMix,
            shuffle = kept.shuffle,
            loop = loopSong,
            held = kept.held.toList(),
            name = kept.name,
        )
        Settings.putString(KEY_STATE, Http.json.encodeToString(Saved.serializer(), saved))
    }

    /** Puts last time's song back in the dock, paused where it was left. Nothing loads until Play. */
    private fun restoreState() {
        val raw = Settings.getString(KEY_STATE) ?: return
        runCatching {
            val o = Http.json.decodeFromString(Saved.serializer(), raw)
            val restored = QueueState(
                tracks = o.queue.map(TrackJson::toTrack),
                index = o.index,
                name = o.name,
                wrap = o.wrap,
                shuffle = o.shuffle,
                autoMix = o.autoMix,
                original = o.original.map(TrackJson::toTrack),
                held = o.held.toSet(),
            ).restored() ?: return
            q = restored
            loopSong = o.loop
            engine.loop = loopSong
            restoredAtMs = o.positionMs
            positionSeconds = o.positionMs / 1000f
            durationSeconds = o.durationSeconds.toFloat().takeIf { it > 0f }
                ?: (restored.current?.durationSeconds ?: 0).toFloat()
            reportedPlay = true
        }.onFailure { log("Couldn't restore last song: ${it.message}") }
    }

    private companion object {
        const val POLL_MS = 250L
        const val SAVE_EVERY_MS = 5_000L
        const val KEY_STATE = "player.state"
        const val KEEP_BEHIND = 50
        const val KEEP_AHEAD = 250
        const val COUNTS_AS_PLAYED_MS = 3_000L
    }
}
