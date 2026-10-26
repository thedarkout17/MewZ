package com.headunit.music.phone.player

/** One song as the audio engine sees it: where to stream it from, and what to show on the lock screen. */
data class EngineItem(
    val videoId: String,
    val url: String,
    val userAgent: String,
    val title: String,
    val artist: String,
    /** Library / album being played, shown as the "album" on the lock screen and in the car. */
    val album: String?,
    val artworkUrl: String,
    val artworkLetterboxed: Boolean,
    val durationHintSeconds: Float,
)

/** What the engine is doing right now (read a few times a second). */
data class EngineState(
    val currentId: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionSeconds: Float,
    /** Null until the stream says how long it is. */
    val durationSeconds: Float?,
)

/** Lock screen, Control Center, headphones, Bluetooth and the car's buttons. */
enum class RemoteCommand { Play, Pause, TogglePlayPause, Next, Previous }

/**
 * Plays audio: AVPlayer on the iPhone. The queue's rules live in
 * [PlayerController] (and the shared core's QueueState); the engine only plays
 * the song it's given, keeps the next one ready so it follows on without a gap
 * (even with the screen locked), and reports what happens.
 */
interface AudioEngine {
    interface Listener {
        /** [videoId] played to its end (with loop off). */
        fun onEnded(videoId: String)
        /** [videoId] couldn't be played (often an expired link). */
        fun onFailed(videoId: String, message: String?)
        fun onRemoteCommand(command: RemoteCommand)
        /** Scrubbing from the lock screen / Control Center. */
        fun onRemoteSeek(seconds: Float)
        /** A call or Siri took the audio (true) or gave it back (false). */
        fun onInterrupted(began: Boolean, shouldResume: Boolean)
        /** Headphones / Bluetooth / the car disconnected: pause, like every music app. */
        fun onOutputLost()
    }

    var listener: Listener?

    /**
     * Makes [item] the song playing, from [startAtSeconds]. If it's the song
     * queued with [prepareNext] (or it already moved on to it by itself), that
     * one is used, already buffered. [autoPlay] false loads it paused.
     */
    fun play(item: EngineItem, startAtSeconds: Float, autoPlay: Boolean = true)

    /** The song that comes next, buffered ahead and played straight after the current one; null clears it. */
    fun prepareNext(item: EngineItem?)

    fun resume()
    fun pause()
    fun stop()
    fun seekTo(seconds: Float)

    /** Replay the current song when it ends instead of moving on. */
    var loop: Boolean

    fun state(): EngineState

    /** Lock screen / Control Center / car display: what's playing and where it's at. */
    fun updateNowPlaying(item: EngineItem?, positionSeconds: Float, durationSeconds: Float, playing: Boolean, hasPrevious: Boolean, hasNext: Boolean)
}

/** The platform's engine (AVPlayer on iPhone; a silent stand-in on desktop). */
expect fun createAudioEngine(): AudioEngine
