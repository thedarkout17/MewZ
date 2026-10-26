@file:OptIn(UnstableApi::class)

package com.headunit.music.player

import com.headunit.music.core.playback.PlayerError
import com.headunit.music.core.sources.MixSource
import com.headunit.music.core.sources.AudioStreamSource
import com.headunit.music.core.playback.QueueState
import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.headunit.music.core.model.Track
import com.headunit.music.data.toJson
import com.headunit.music.data.toTracks
import org.json.JSONArray
import org.json.JSONObject
import com.headunit.music.data.youtube.AudioStreams
import com.headunit.music.core.playback.AudioUnavailableException
import com.headunit.music.data.youtube.YouTubeMix
import com.headunit.music.data.youtube.NewPipeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays songs as audio only (no video is downloaded) and keeps a simple queue.
 *
 * Each song's audio stream is found with NewPipeExtractor ([AudioStreams]) and
 * played with Media3 ExoPlayer. A song played from search is followed by its
 * YouTube Mix ([YouTubeMix]), like YouTube's autoplay.
 *
 * For dead zones, the song playing buffers up to 5 minutes ahead, and once
 * it has, the next song's first 5 minutes download into the disk cache.
 *
 * There's one for the whole app ([get]); [PlaybackService] keeps it playing in
 * the background and exposes it ([sessionPlayer]) to the steering wheel,
 * Bluetooth and the headunit's media controls.
 *
 * All state is Compose state, so the screens update on their own.
 */
class PlayerController private constructor(
    context: Context,
    /** Finds each song's audio (NewPipe on Android). */
    private val audio: AudioStreamSource = AudioStreams,
    /** "Up next" songs for search plays (YouTube's Mix). */
    private val mix: MixSource = YouTubeMix,
) {

    companion object {
        @Volatile private var instance: PlayerController? = null

        /**
         * The one player for the whole app. It outlives the screen: the playback
         * service ([PlaybackService]) keeps it (and the music) going in the
         * background, and the screen picks it back up when it reopens.
         */
        fun get(context: Context): PlayerController =
            instance ?: synchronized(this) {
                instance ?: PlayerController(context.applicationContext).also { instance = it }
            }
    }

    // ---- Queue ----
    // The rules (what's next, hold-to-queue, the Mix, shuffle, looping) live in
    // QueueState, in the shared core; this holds the current one as Compose state.
    private var q by mutableStateOf(QueueState())

    val queue: List<Track> get() = q.tracks
    val index: Int get() = q.index
    /** Name of the library or album being played ("Road trip — 3 of 20"); null for search plays. */
    val queueName: String? get() = q.name
    val current: Track? get() = q.current
    val hasPrevious: Boolean get() = q.hasPrevious
    val hasNext: Boolean get() = q.hasNext
    val shuffle: Boolean get() = q.shuffle
    /** Playing a search result's YouTube Mix (topped up as it runs low). */
    val autoMix: Boolean get() = q.autoMix

    /** Loop button: replay the current song when it ends. */
    var loopSong by mutableStateOf(false)
        private set

    private var mixJob: Job? = null
    /** Reached the end while a mix top-up was still loading: play on once it arrives. */
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

    /** Now Playing screen open (true) or minimized to the dock (false). */
    var expanded by mutableStateOf(false)

    /**
     * Called once per play when a song has actually played for [COUNTS_AS_PLAYED_MS]
     * (paused time doesn't count). History listens to this.
     */
    var onPlayedLongEnough: ((Track) -> Unit)? = null
    private var playedMs = 0L
    private var reportedPlay = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Where the last song and queue are kept, so they're still there after the app closes. */
    private val saved = context.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)
    /**
     * Restored from last time but not loaded yet: the first Play loads the song
     * and starts from here (ms) instead of the beginning.
     */
    private var restoredAtMs: Long? = null
    private var sinceSaveMs = 0L
    private var loadJob: Job? = null
    private var skipJob: Job? = null
    /** One fresh-link retry per song (links expire after a few hours). */
    private var retriedVideoId: String? = null

    // Load timing for the log: tap → audio link found → first sound. Filter Logcat by "MewZ".
    private var tapAt = 0L
    private var linkMs = 0L

    /**
     * Reads audio through the on-disk cache: pre-buffered starts come from disk,
     * the rest streams from YouTube (and is cached as it plays).
     */
    private val dataSource: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(AudioCache.get(context))
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setUserAgent(NewPipeDownloader.USER_AGENT),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Downloading the next song's first seconds (cancelled if you move on). */
    private var preloadJob: Job? = null
    @Volatile private var preloadWriter: CacheWriter? = null

    private val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        // Keep what's already played in memory too (up to 5 minutes: a whole song for most),
        // so rewinds are instant. Anything older comes back from the disk cache, never
        // re-downloaded. (ExoPlayer fixes this when the player is made, so it can't vary per song.)
        //
        // Ahead: keep loading up to 5 minutes ahead (the whole song for most), so it plays on
        // through tunnels and dead zones instead of stopping 50 seconds later.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
                // Fills to 5 minutes ahead, then waits until it's down to 4 before topping up,
                // so it loads in bursts and the next songs' downloads get the gaps in between.
                .setBufferDurationsMs(
                    /* minBufferMs = */ AHEAD_BUFFER_MS - TOP_UP_GAP_MS,
                    /* maxBufferMs = */ AHEAD_BUFFER_MS,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        // Keep the CPU and Wi-Fi awake while playing with the screen off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        // Pause for navigation prompts / calls, and when headphones or Bluetooth drop.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply { addListener(Listener()) }

    /**
     * What the media session talks to (steering-wheel buttons, Bluetooth, the
     * headunit's own media controls, voice assistants): ExoPlayer, but with
     * next / previous going through our queue, and Play loading the song first
     * when it isn't loaded yet (e.g. last time's song after a restart).
     */
    val sessionPlayer: Player by lazy { SessionPlayer() }

    private inner class SessionPlayer : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

        override fun play() {
            if (needsLoad()) loadAndPlay() else super.play()
        }

        override fun seekToNext() { this@PlayerController.next() }
        override fun seekToNextMediaItem() { this@PlayerController.next() }
        override fun seekToPrevious() { this@PlayerController.previous() }
        override fun seekToPreviousMediaItem() { this@PlayerController.previous() }
        override fun hasNextMediaItem(): Boolean = this@PlayerController.hasNext
        override fun hasPreviousMediaItem(): Boolean = this@PlayerController.current != null
    }

    /** There's a song, but ExoPlayer doesn't have it loaded (restored from last time, or it failed). */
    private fun needsLoad(): Boolean = current != null && (restoredAtMs != null || exo.mediaItemCount == 0)

    private fun loadAndPlay() {
        val at = restoredAtMs
        if (at != null) playAt(index, resumeAtMs = at) else playAt(index)
    }

    init {
        restoreState()
        // Seek bar / timer: read the position a few times a second while playing.
        scope.launch {
            while (isActive) {
                if (exo.isPlaying) {
                    positionSeconds = exo.currentPosition / 1000f
                    playedMs += POLL_MS
                    sinceSaveMs += POLL_MS
                    if (sinceSaveMs >= SAVE_EVERY_MS) saveState()
                    if (!reportedPlay && playedMs >= COUNTS_AS_PLAYED_MS) {
                        reportedPlay = true
                        current?.let { onPlayedLongEnough?.invoke(it) }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    // ---- Controls ----

    /**
     * Plays [tracks] starting at [startIndex]; the rest become the queue.
     * [wrap]: start over after the last song (libraries / albums pass true).
     */
    fun playQueue(tracks: List<Track>, startIndex: Int, name: String? = null, wrap: Boolean = false) {
        val started = q.start(tracks, startIndex, name, wrap) ?: return
        stopMix()
        q = started
        playAt(q.index)
        expanded = true
    }

    /**
     * Plays [track] (tapped in search results), followed by YouTube's Mix for it
     * rather than the other search results.
     */
    fun playWithMix(track: Track) {
        stopMix()
        q = q.startMix(track)
        playAt(0) // also starts loading the mix
        expanded = true
    }

    /**
     * Hold-to-queue: [track] plays after the current song (and after anything
     * queued before it). If nothing is playing it just starts playing.
     * Returns true if it was queued.
     */
    fun addToQueue(track: Track): Boolean {
        if (current == null) {
            playWithMix(track)
            return false
        }
        update(q.enqueue(track))
        return true
    }

    /** True if [track] was added with a hold and hasn't played yet. */
    fun isHeldQueued(track: Track): Boolean = q.isHeld(track)

    /** Queue list: jump straight to the song at [i]. */
    fun skipTo(i: Int) {
        if (i !in queue.indices || i == index) return
        playAt(i)
    }

    /** Queue page drag: move the upcoming song at [from] to [to]. */
    fun moveInQueue(from: Int, to: Int) = update(q.move(from, to))

    /** Queue page double-tap: the song at [i] plays after the songs you've queued (see [QueueState.playNext]). */
    fun playNext(i: Int) = update(q.playNext(i))

    /** Queue list: take the upcoming song at [i] out. */
    fun removeFromQueue(i: Int) {
        val removed = q.remove(i)
        if (removed == q) return
        update(removed)
        if (q.needsMoreMix) current?.let { extendMix(it) }
    }

    /** The library being played was renamed: show the new name ("Road trip — 3 of 20"). */
    fun renameQueue(from: String, to: String) {
        val renamed = q.renamed(from, to)
        if (renamed == q) return
        q = renamed
        saveState()
    }

    /** Shuffle the songs after the current one; turning it off restores the original order. */
    fun toggleShuffle() = update(q.toggledShuffle())

    /**
     * Changes the queue; if the next song (or the one after) is different now,
     * the pre-buffering is re-planned for them.
     */
    private fun update(new: QueueState) {
        val next = q.ahead(1)
        val after = q.ahead(2)
        q = new
        if (q.ahead(1) != next || q.ahead(2) != after) preloadNext()
    }

    private fun stopMix() {
        mixJob?.cancel()
        mixJob = null
        advanceWhenMixArrives = false
    }

    /** Fetch [seed]'s Mix and add the songs that aren't in the queue yet. */
    private fun extendMix(seed: Track) {
        if (mixJob?.isActive == true) return
        mixJob = scope.launch {
            val more = mix.upNext(seed)
            val topped = q.withMix(more)
            if (topped.tracks.size == q.tracks.size) {
                q = topped // (remembers what was offered, so it isn't offered again)
                return@launch
            }
            update(topped)
            if (advanceWhenMixArrives) {
                advanceWhenMixArrives = false
                next()
            }
        }
    }

    fun toggleLoop() {
        loopSong = !loopSong
        exo.repeatMode = if (loopSong) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun togglePlay() {
        if (current == null) return
        // First Play after reopening the app: load last time's song where it was left.
        if (needsLoad()) {
            loadAndPlay()
            return
        }
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun next() {
        when (val step = q.next()) {
            is QueueState.Next.PlayAt -> playAt(step.index)
            // More mix songs on the way (or try again if the last top-up failed).
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
        positionSeconds = seconds.coerceIn(0f, durationSeconds.coerceAtLeast(0f))
        // Not loaded yet (restored from last time): Play will start from here.
        if (restoredAtMs != null) restoredAtMs = (positionSeconds * 1000).toLong()
        else exo.seekTo((positionSeconds * 1000).toLong())
    }

    // ---- Remembering the last song across app restarts ----

    /** Saves the queue, the song and where it's at. Called as it plays, on pause, and when the app goes away. */
    fun saveState() {
        sinceSaveMs = 0L
        // Keep the file small: at most a few hundred songs around the current one.
        val kept = q.forSaving(KEEP_BEHIND, KEEP_AHEAD)
        if (kept.current == null) {
            saved.edit().clear().apply()
            return
        }
        val positionMs = restoredAtMs
            ?: if (exo.mediaItemCount > 0) exo.currentPosition else (positionSeconds * 1000).toLong()
        val state = JSONObject()
            .put("queue", kept.tracks.toJson())
            .put("original", kept.original.toJson())
            .put("index", kept.index)
            .put("positionMs", positionMs)
            .put("durationSeconds", durationSeconds.toDouble())
            .put("wrap", kept.wrap)
            .put("autoMix", kept.autoMix)
            .put("shuffle", kept.shuffle)
            .put("loop", loopSong)
            .put("held", JSONArray(kept.held.toList()))
            .apply { kept.name?.let { put("name", it) } }
        saved.edit().putString(KEY_STATE, state.toString()).apply()
    }

    /** Puts last time's song back in the dock, paused where it was left. Nothing loads until Play. */
    private fun restoreState() {
        val json = saved.getString(KEY_STATE, null) ?: return
        runCatching {
            val o = JSONObject(json)
            val held = o.optJSONArray("held")?.let { arr -> List(arr.length()) { arr.getString(it) }.toSet() }.orEmpty()
            val restored = QueueState(
                tracks = o.getJSONArray("queue").toTracks(),
                index = o.getInt("index"),
                name = if (o.has("name")) o.getString("name") else null,
                wrap = o.optBoolean("wrap"),
                shuffle = o.optBoolean("shuffle"),
                autoMix = o.optBoolean("autoMix"),
                original = o.optJSONArray("original")?.toTracks().orEmpty(),
                held = held,
            ).restored() ?: return
            q = restored
            loopSong = o.optBoolean("loop")
            exo.repeatMode = if (loopSong) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            val at = o.optLong("positionMs")
            restoredAtMs = at
            positionSeconds = at / 1000f
            durationSeconds = o.optDouble("durationSeconds", 0.0).toFloat()
                .takeIf { it > 0f } ?: (restored.current?.durationSeconds ?: 0).toFloat()
            // A whole new play only counts for History once it's played a while.
            reportedPlay = true
        }.onFailure { Log.w(TAG, "Couldn't restore last song: ${it.message}") }
    }

    fun release() {
        saveState()
        preloadWriter?.cancel()
        scope.cancel()
        exo.release()
    }

    private fun playAt(i: Int, resumeAtMs: Long = 0L) {
        val track = queue.getOrNull(i) ?: return
        if (track.videoId != retriedVideoId) retriedVideoId = null
        skipJob?.cancel()
        loadJob?.cancel()
        restoredAtMs = null // loading for real now
        q = q.at(i)
        error = null
        advanceWhenMixArrives = false
        // Mix running low: fetch more, seeded by this song so it keeps drifting naturally.
        if (q.needsMoreMix) extendMix(track)
        // A fresh play (not a resume after a link refresh) starts the 3-second count again.
        if (resumeAtMs == 0L) {
            playedMs = 0L
            reportedPlay = false
        }
        positionSeconds = resumeAtMs / 1000f
        durationSeconds = (track.durationSeconds ?: 0).toFloat()
        isBuffering = true
        exo.stop()
        tapAt = SystemClock.elapsedRealtime()

        loadJob = scope.launch {
            try {
                val url = audio.urlFor(track.videoId)
                linkMs = SystemClock.elapsedRealtime() - tapAt
                if (current?.videoId != track.videoId) return@launch // moved on meanwhile
                exo.setMediaItem(mediaItem(track, url), resumeAtMs)
                exo.prepare()
                exo.play()
                preloadNext()
            } catch (e: AudioUnavailableException) {
                fail(PlayerError.from(e.reason))
            }
        }
    }

    /**
     * While this song plays, get the next one ready: once this one has finished
     * loading, download the next song's first 5 minutes (the whole song for
     * most) into the disk cache, so it starts instantly and plays through dead
     * zones too; then the first ~5 seconds of the song after that, so even a
     * double skip starts straight away. (Skipping it wastes that
     * download; a cached song costs nothing to play again.)
     */
    private fun preloadNext() {
        preloadJob?.cancel()
        preloadWriter?.cancel()
        val next = q.ahead(1) ?: return
        val after = q.ahead(2)?.takeIf { it.videoId != next.videoId }
        // Strictly in order, one at a time, so nothing competes for a weak signal:
        //   1. the song playing (ExoPlayer buffers it 5 minutes ahead) always comes first;
        //   2. then the next song's first 5 minutes;
        //   3. then the first ~5 seconds of the one after.
        // Whenever the song playing needs more, the download in progress pauses for it.
        preloadJob = scope.launch {
            delay(1_500) // let the song that's playing get going
            cacheStart(next) { url -> firstMinutesBytes(url) }
            if (after != null) cacheStart(after) { SONG_START_BYTES }
        }
    }

    /** Waits while the song that's playing is loading (it always goes first). */
    private suspend fun waitForPlayingSong() {
        while (exo.isLoading) delay(500)
    }

    /**
     * Downloads the start of [track] (the first [bytes] of its audio) into the disk
     * cache, under the same key playback uses. Already cached (e.g. played recently)
     * costs nothing; failures just mean no head start.
     */
    private suspend fun cacheStart(track: Track, bytes: (url: String) -> Long) {
        waitForPlayingSong()
        val url = runCatching { audio.urlFor(track.videoId) }.getOrNull() ?: return
        while (true) {
            waitForPlayingSong()
            val writer = CacheWriter(
                dataSource.createDataSource(),
                DataSpec.Builder()
                    .setUri(url)
                    .setKey(cacheKey(track.videoId, url))
                    .setPosition(0)
                    .setLength(bytes(url))
                    .build(),
                /* temporaryBuffer = */ null,
                /* progressListener = */ null,
            )
            preloadWriter = writer
            val finished = coroutineScope {
                // The song playing needs data: pause this download (it resumes where it stopped).
                val yieldToPlaying = launch {
                    while (true) {
                        delay(300)
                        if (exo.isLoading) { writer.cancel(); break }
                    }
                }
                val ok = withContext(Dispatchers.IO) { runCatching { writer.cache() }.isSuccess }
                yieldToPlaying.cancel()
                ok
            }
            if (preloadWriter === writer) preloadWriter = null
            if (finished) return
            // Paused for the song playing: carry on once it's done. Any other failure: give up
            // (it just means no head start).
            if (!exo.isLoading) return
        }
    }

    /** The song for ExoPlayer, with its title / artist / artwork for the car's media display. */
    private fun mediaItem(track: Track, url: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(url)
            .setCustomCacheKey(cacheKey(track.videoId, url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(Uri.parse(track.artworkUrl))
                    .setAlbumTitle(queueName)
                    .build(),
            )
            .build()

    /**
     * Cache entries are keyed by video + audio format (YouTube's "itag"), not by
     * the stream link, which expires and changes on every lookup. The format is
     * part of the key so bytes from one format are never mixed with another.
     */
    /**
     * How many bytes the first [NEXT_SONG_MS] of a stream take. YouTube's stream
     * links say the file's size ("clen") and length in seconds ("dur"); without
     * them, assume 160 kbps. Includes a little extra for the file's header.
     */
    private fun firstMinutesBytes(url: String): Long {
        val size = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()
        val seconds = DUR.find(url)?.groupValues?.get(1)?.toDoubleOrNull()
        val wanted = NEXT_SONG_MS / 1000.0
        return if (size != null && seconds != null && seconds > 0) {
            if (seconds <= wanted) C.LENGTH_UNSET.toLong() // shorter than that: the whole song
            else (size * (wanted / seconds)).toLong() + HEADER_BYTES
        } else {
            (wanted * 160_000 / 8).toLong() + HEADER_BYTES
        }
    }

    private fun cacheKey(videoId: String, url: String): String =
        "$videoId:" + (ITAG.find(url)?.groupValues?.get(1) ?: "audio")

    private fun fail(e: PlayerError) {
        error = e
        isPlaying = false
        isBuffering = false
        // Songs that can't be played move on by themselves.
        if (e.skippable && hasNext) {
            skipJob = scope.launch {
                delay(2500)
                next()
            }
        }
    }

    private inner class Listener : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            // Paused: remember exactly where. (Not while switching songs, when the old one stops.)
            if (!playing && !isBuffering) saveState()
            if (playing && tapAt > 0) {
                val total = SystemClock.elapsedRealtime() - tapAt
                Log.d(TAG, "Load \"${current?.title}\": find audio ${linkMs} ms + buffer ${total - linkMs} ms = ${total} ms")
                tapAt = 0
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                val d = exo.duration
                if (d != C.TIME_UNSET && d > 0) durationSeconds = d / 1000f
            }
            if (state == Player.STATE_ENDED) {
                // (Loop-this-song is handled by ExoPlayer's repeat mode, so ENDED means move on.)
                positionSeconds = durationSeconds
                next()
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val track = current ?: return
            if (retriedVideoId != track.videoId) {
                // Most often an expired link: fetch a fresh one and carry on from here.
                retriedVideoId = track.videoId
                audio.forget(track.videoId)
                playAt(index, resumeAtMs = exo.currentPosition)
            } else {
                fail(PlayerError.Other)
            }
        }
    }
}

private const val TAG = "MewZ"

private const val POLL_MS = 250L
/** Already-played audio kept in memory for instant rewinds: about 5 MB at 128 kbps. */
private const val BACK_BUFFER_MS = 5 * 60_000
/** While playing, save where we are this often (so a sudden power-off loses little). */
private const val SAVE_EVERY_MS = 5_000L
private const val KEY_STATE = "state"
private const val KEEP_BEHIND = 50
private const val KEEP_AHEAD = 250
/** A song goes into History once it has played this long. */
private const val COUNTS_AS_PLAYED_MS = 3_000L

/** Load the song that's playing this far ahead (the whole song for most). */
private const val AHEAD_BUFFER_MS = 5 * 60_000
/** ...topping it up only once it's this much below that. */
private const val TOP_UP_GAP_MS = 60_000
/** Memory allowed for that (5 min at 128 kbps is ~5 MB; some streams run higher). */
private const val TARGET_BUFFER_BYTES = 16 * 1024 * 1024
/**
 * The song after next: ~5 seconds of audio (~100 KB at 160 kbps) plus room for
 * the file's header, enough to start straight away.
 */
private const val SONG_START_BYTES = 160L * 1024
/** How much of the next song to download ahead of time. */
private const val NEXT_SONG_MS = 5 * 60_000
/** Room for the file's header / index at the start. */
private const val HEADER_BYTES = 64L * 1024
private val CLEN = Regex("[?&]clen=(\\d+)")
private val DUR = Regex("[?&]dur=([\\d.]+)")

private val ITAG = Regex("[?&]itag=(\\d+)")
