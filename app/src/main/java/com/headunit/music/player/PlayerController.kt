@file:OptIn(UnstableApi::class)

package com.headunit.music.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.headunit.music.data.Track
import com.headunit.music.data.youtube.AudioStreams
import com.headunit.music.data.youtube.AudioUnavailableException
import com.headunit.music.data.youtube.NewPipeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays songs as audio only (no video is downloaded) and keeps a simple queue.
 *
 * Each song's audio stream is found with NewPipeExtractor ([AudioStreams]) and
 * played with Media3 ExoPlayer. While a song plays, the next one is looked up
 * in the background, so skipping is near-instant.
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
    private var loadJob: Job? = null
    private var skipJob: Job? = null
    /** One fresh-link retry per song (links expire after a few hours). */
    private var retriedVideoId: String? = null

    // Load timing for the log: tap → audio link found → first sound. Filter Logcat by "MuZ".
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

    init {
        // Seek bar / timer: read the position a few times a second while playing.
        scope.launch {
            while (isActive) {
                if (exo.isPlaying) {
                    positionSeconds = exo.currentPosition / 1000f
                    playedMs += POLL_MS
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
        preloadNext()
    }

    fun toggleLoop() {
        loopSong = !loopSong
        exo.repeatMode = if (loopSong) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun togglePlay() {
        if (current == null) return
        if (exo.isPlaying) exo.pause() else exo.play()
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
        exo.seekTo((positionSeconds * 1000).toLong())
    }

    fun release() {
        preloadWriter?.cancel()
        scope.cancel()
        exo.release()
    }

    private fun playAt(i: Int, resumeAtMs: Long = 0L) {
        val track = queue.getOrNull(i) ?: return
        if (track.videoId != retriedVideoId) retriedVideoId = null
        skipJob?.cancel()
        loadJob?.cancel()
        index = i
        error = null
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
                val url = AudioStreams.urlFor(track.videoId)
                linkMs = SystemClock.elapsedRealtime() - tapAt
                if (current?.videoId != track.videoId) return@launch // moved on meanwhile
                exo.setMediaItem(mediaItem(track.videoId, url), resumeAtMs)
                exo.prepare()
                exo.play()
                preloadNext()
            } catch (e: AudioUnavailableException) {
                fail(PlayerError.from(e.reason))
            }
        }
    }

    /**
     * While this song plays, get the next one ready: look up its audio link and
     * download its first ~5 seconds into the cache, so skipping starts instantly.
     */
    private fun preloadNext() {
        preloadJob?.cancel()
        preloadWriter?.cancel()
        val next = queue.getOrNull(index + 1) ?: if (wrapQueue) queue.firstOrNull() else null
        next ?: return
        preloadJob = scope.launch {
            val url = runCatching { AudioStreams.urlFor(next.videoId) }.getOrNull() ?: return@launch
            withContext(Dispatchers.IO) {
                val writer = CacheWriter(
                    dataSource.createDataSource(),
                    DataSpec.Builder()
                        .setUri(url)
                        .setKey(cacheKey(next.videoId, url)) // same key playback uses
                        .setPosition(0)
                        .setLength(PRELOAD_BYTES)
                        .build(),
                    /* temporaryBuffer = */ null,
                    /* progressListener = */ null,
                )
                preloadWriter = writer
                // Already cached (e.g. played recently) costs nothing; failures just mean no head start.
                runCatching { writer.cache() }
                if (preloadWriter === writer) preloadWriter = null
            }
        }
    }

    private fun mediaItem(videoId: String, url: String): MediaItem =
        MediaItem.Builder().setUri(url).setCustomCacheKey(cacheKey(videoId, url)).build()

    /**
     * Cache entries are keyed by video + audio format (YouTube's "itag"), not by
     * the stream link, which expires and changes on every lookup. The format is
     * part of the key so bytes from one format are never mixed with another.
     */
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
                if (hasNext) next()
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val track = current ?: return
            if (retriedVideoId != track.videoId) {
                // Most often an expired link: fetch a fresh one and carry on from here.
                retriedVideoId = track.videoId
                AudioStreams.forget(track.videoId)
                playAt(index, resumeAtMs = exo.currentPosition)
            } else {
                fail(PlayerError.Other)
            }
        }
    }
}

private const val TAG = "MuZ"

private const val POLL_MS = 250L
/** A song goes into History once it has played this long. */
private const val COUNTS_AS_PLAYED_MS = 3_000L

/**
 * How much of the next song to download ahead: ~5 s of 128 kbps audio (80 KB)
 * plus room for the file's header at the start (~48 KB).
 */
private const val PRELOAD_BYTES = 128L * 1024

private val ITAG = Regex("[?&]itag=(\\d+)")

/** Why a song couldn't be played, in words for the screen. */
enum class PlayerError(val message: String, val skippable: Boolean) {
    Unavailable("This song isn't available", skippable = true),
    AgeRestricted("This song is age-restricted on YouTube", skippable = true),
    Offline("No internet connection", skippable = false),
    RateLimited("YouTube is limiting requests; try again shortly", skippable = false),
    ExtractorOutdated("YouTube changed something; the app needs an update", skippable = false),
    Other("Couldn't play this song", skippable = true);

    companion object {
        fun from(reason: AudioUnavailableException.Reason) = when (reason) {
            AudioUnavailableException.Reason.Unavailable -> Unavailable
            AudioUnavailableException.Reason.AgeRestricted -> AgeRestricted
            AudioUnavailableException.Reason.Offline -> Offline
            AudioUnavailableException.Reason.RateLimited -> RateLimited
            AudioUnavailableException.Reason.ExtractorOutdated -> ExtractorOutdated
        }
    }
}
