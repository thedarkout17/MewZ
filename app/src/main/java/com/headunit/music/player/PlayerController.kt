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
import com.headunit.music.data.toJson
import com.headunit.music.data.toTracks
import org.json.JSONArray
import org.json.JSONObject
import com.headunit.music.data.youtube.AudioStreams
import com.headunit.music.data.youtube.AudioUnavailableException
import com.headunit.music.data.youtube.YouTubeMix
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
 * played with Media3 ExoPlayer. A song played from search is followed by its
 * YouTube Mix ([YouTubeMix]), like YouTube's autoplay. While a song plays, the next one is looked up
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
    /** Libraries loop both ways: back from the first song goes to the last. */
    val hasPrevious: Boolean by derivedStateOf { index > 0 || (wrapQueue && queue.size > 1) }
    val hasNext: Boolean by derivedStateOf {
        index in 0 until queue.size - 1 || (wrapQueue && queue.isNotEmpty()) || autoMix
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

    /**
     * Playing a song from search: the queue is that song's YouTube Mix (like
     * YouTube's autoplay), topped up as it runs low so it never ends.
     */
    var autoMix by mutableStateOf(false)
        private set
    private var mixJob: Job? = null
    /** Songs already in this mix queue, so top-ups don't repeat them. */
    private val mixSeen = HashSet<String>()
    /** Reached the end while a top-up was still loading: play on once it arrives. */
    private var advanceWhenMixArrives = false

    /** Songs added with a hold that haven't played yet; they line up right after the current song. */
    private val heldQueued = HashSet<String>()

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
     * [wrap]: start over after the last song (libraries / playlists pass true).
     */
    fun playQueue(tracks: List<Track>, startIndex: Int, name: String? = null, wrap: Boolean = false) {
        if (startIndex !in tracks.indices) return
        stopMix()
        heldQueued.clear()
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

    /**
     * Plays [track] (tapped in search results), followed by YouTube's Mix for it
     * rather than the other search results.
     */
    fun playWithMix(track: Track) {
        stopMix()
        heldQueued.clear()
        autoMix = true
        mixSeen += track.videoId
        originalOrder = listOf(track)
        queueName = null
        wrapQueue = false
        queue = listOf(track)
        playAt(0) // also starts loading the mix
        expanded = true
    }

    /**
     * Hold-to-queue: [track] plays after the current song (and after anything
     * queued before it), ahead of the rest of the list or mix. If nothing is
     * playing it just starts playing. Returns true if it was queued.
     */
    fun addToQueue(track: Track): Boolean {
        val now = current
        if (now == null) {
            playWithMix(track)
            return false
        }
        // Already coming up later? Move it forward rather than playing it twice.
        val behind = queue.take(index + 1)
        val ahead = queue.drop(index + 1).filter { it.videoId != track.videoId }
        val queuedFirst = ahead.takeWhile { it.videoId in heldQueued }.size
        queue = behind + ahead.take(queuedFirst) + track + ahead.drop(queuedFirst)

        // Same spot in the unshuffled order, so turning shuffle off keeps it.
        val base = originalOrder.filter { it.videoId != track.videoId || it == now }
        val at = (base.indexOf(now) + 1).coerceAtLeast(0)
        val afterNow = base.drop(at)
        val q = afterNow.takeWhile { it.videoId in heldQueued }.size
        originalOrder = base.take(at) + afterNow.take(q) + track + afterNow.drop(q)

        heldQueued += track.videoId
        mixSeen += track.videoId
        if (queuedFirst == 0) preloadNext() // it's the very next song now
        return true
    }

    /** True if [track] was added with a hold and hasn't played yet. */
    fun isHeldQueued(track: Track): Boolean = track.videoId in heldQueued

    /** Queue list: jump straight to the song at [i]. Held songs passed over stay where they are. */
    fun skipTo(i: Int) {
        if (i !in queue.indices || i == index) return
        playAt(i)
    }

    /** Queue page drag: move the upcoming song at [from] to [to] (both after the current song). */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to || from <= index || to <= index || from !in queue.indices || to !in queue.indices) return
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        keepOrderInSync()
        if (from == index + 1 || to == index + 1) preloadNext()
    }

    /**
     * Queue page double-tap: the song at [i] joins the songs you've queued, at
     * the end of them (like hold-to-queue), ahead of the rest of the list or mix.
     * Double-tapping a song that's already one of them moves it to the very front.
     */
    fun playNext(i: Int) {
        if (i !in queue.indices || i == index) return
        val track = queue[i]
        val list = queue.toMutableList()
        list.removeAt(i)
        val now = if (i < index) index - 1 else index
        val alreadyQueued = i > index && track.videoId in heldQueued
        val queuedAhead = if (alreadyQueued) 0
            else list.drop(now + 1).takeWhile { it.videoId in heldQueued }.size
        val at = now + 1 + queuedAhead
        list.add(at, track)
        queue = list
        index = now
        heldQueued += track.videoId
        keepOrderInSync()
        if (at == now + 1 || i == now + 1) preloadNext()
    }

    /** Hand-arranged queue: unshuffled, the "original" order is simply what's shown. */
    private fun keepOrderInSync() {
        if (!shuffle) originalOrder = queue
    }

    /** Queue list: take the upcoming song at [i] out. */
    fun removeFromQueue(i: Int) {
        if (i <= index || i !in queue.indices) return
        val track = queue[i]
        queue = queue.toMutableList().apply { removeAt(i) }
        val o = originalOrder.indexOfLast { it.videoId == track.videoId }
        if (o >= 0) originalOrder = originalOrder.toMutableList().apply { removeAt(o) }
        heldQueued -= track.videoId
        if (i == index + 1) preloadNext()
        current?.let { if (autoMix && index >= queue.size - 3) extendMix(it) }
    }

    private fun stopMix() {
        mixJob?.cancel()
        mixJob = null
        autoMix = false
        advanceWhenMixArrives = false
        mixSeen.clear()
    }

    /** Append [seed]'s Mix to the queue (only new songs). */
    private fun extendMix(seed: Track) {
        if (mixJob?.isActive == true) return
        mixJob = scope.launch {
            val more = YouTubeMix.upNext(seed).filter { mixSeen.add(it.videoId) }
            if (!autoMix || more.isEmpty()) return@launch
            val hadNext = index < queue.size - 1
            originalOrder = originalOrder + more
            queue = queue + if (shuffle) more.shuffled() else more
            if (!hadNext) preloadNext()
            if (advanceWhenMixArrives) {
                advanceWhenMixArrives = false
                next()
            }
        }
    }

    /** Shuffle the songs after the current one; turning it off restores the original order. */
    fun toggleShuffle() {
        val now = current
        shuffle = !shuffle
        if (now == null) return
        if (autoMix) {
            // A mix keeps what's been played behind you; only the songs ahead move.
            val played = queue.take(index + 1)
            val ahead = originalOrder.filter { it !in played }
            queue = played + if (shuffle) ahead.shuffled() else ahead
            preloadNext()
            return
        }
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
        // First Play after reopening the app: load last time's song where it was left.
        restoredAtMs?.let { at ->
            playAt(index, resumeAtMs = at)
            return
        }
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun next() {
        when {
            index < queue.size - 1 -> playAt(index + 1)
            wrapQueue && queue.isNotEmpty() -> playAt(0) // back to the start
            autoMix -> current?.let { // more songs on the way (or try again if the last top-up failed)
                advanceWhenMixArrives = true
                extendMix(it)
            }
        }
    }

    /**
     * Like most players: restarts the song if you're past 2.5 seconds, else goes back one.
     * In a library (which loops), back from the first song goes to the last.
     */
    fun previous() {
        when {
            positionSeconds > 2.5f || !hasPrevious -> seekTo(0f)
            index > 0 -> playAt(index - 1)
            else -> playAt(queue.size - 1) // first song of a looping library → the last
        }
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
        val now = current
        if (now == null) {
            saved.edit().clear().apply()
            return
        }
        // Keep the file small: at most a few hundred songs around the current one.
        val from = (index - KEEP_BEHIND).coerceAtLeast(0)
        val to = (index + KEEP_AHEAD).coerceAtMost(queue.size)
        val kept = queue.subList(from, to)
        val keptIds = kept.mapTo(HashSet()) { it.videoId }
        val positionMs = restoredAtMs
            ?: if (exo.mediaItemCount > 0) exo.currentPosition else (positionSeconds * 1000).toLong()
        val state = JSONObject()
            .put("queue", kept.toJson())
            .put("original", originalOrder.filter { it.videoId in keptIds }.toJson())
            .put("index", index - from)
            .put("positionMs", positionMs)
            .put("durationSeconds", durationSeconds.toDouble())
            .put("wrap", wrapQueue)
            .put("autoMix", autoMix)
            .put("shuffle", shuffle)
            .put("loop", loopSong)
            .put("held", JSONArray(heldQueued.filter { it in keptIds }))
            .apply { queueName?.let { put("name", it) } }
        saved.edit().putString(KEY_STATE, state.toString()).apply()
    }

    /** Puts last time's song back in the dock, paused where it was left. Nothing loads until Play. */
    private fun restoreState() {
        val json = saved.getString(KEY_STATE, null) ?: return
        runCatching {
            val o = JSONObject(json)
            val q = o.getJSONArray("queue").toTracks()
            val i = o.getInt("index")
            if (i !in q.indices) return
            queue = q
            originalOrder = o.optJSONArray("original")?.toTracks()?.takeIf { it.isNotEmpty() } ?: q
            index = i
            queueName = if (o.has("name")) o.getString("name") else null
            wrapQueue = o.optBoolean("wrap")
            autoMix = o.optBoolean("autoMix")
            if (autoMix) q.mapTo(mixSeen) { it.videoId }
            shuffle = o.optBoolean("shuffle")
            loopSong = o.optBoolean("loop")
            exo.repeatMode = if (loopSong) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            o.optJSONArray("held")?.let { arr -> for (k in 0 until arr.length()) heldQueued += arr.getString(k) }
            val at = o.optLong("positionMs")
            restoredAtMs = at
            positionSeconds = at / 1000f
            durationSeconds = o.optDouble("durationSeconds", 0.0).toFloat()
                .takeIf { it > 0f } ?: (q[i].durationSeconds ?: 0).toFloat()
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
        index = i
        error = null
        heldQueued -= track.videoId
        advanceWhenMixArrives = false
        // Mix running low: fetch more, seeded by this song so it keeps drifting naturally.
        if (autoMix && i >= queue.size - 3) extendMix(track)
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
/** While playing, save where we are this often (so a sudden power-off loses little). */
private const val SAVE_EVERY_MS = 5_000L
private const val KEY_STATE = "state"
private const val KEEP_BEHIND = 50
private const val KEEP_AHEAD = 250
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
