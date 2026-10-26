package com.headunit.music.phone.player

import kotlin.time.TimeSource

actual fun createAudioEngine(): AudioEngine = FakeAudioEngine()

/** Desktop (CI only): pretends to play, so screens and the player's logic can be tested without sound. */
class FakeAudioEngine : AudioEngine {
    override var listener: AudioEngine.Listener? = null
    override var loop: Boolean = false

    private var item: EngineItem? = null
    private var next: EngineItem? = null
    private var playing = false
    private var base = 0f
    private var since = TimeSource.Monotonic.markNow()

    private fun position(): Float = base + if (playing) since.elapsedNow().inWholeMilliseconds / 1000f else 0f

    override fun play(item: EngineItem, startAtSeconds: Float, autoPlay: Boolean) {
        this.item = item
        if (next?.videoId == item.videoId) next = null
        base = startAtSeconds
        since = TimeSource.Monotonic.markNow()
        playing = autoPlay
    }

    override fun prepareNext(item: EngineItem?) { next = item }
    override fun resume() { base = position(); since = TimeSource.Monotonic.markNow(); playing = true }
    override fun pause() { base = position(); playing = false }
    override fun stop() { item = null; playing = false }
    override fun seekTo(seconds: Float) { base = seconds; since = TimeSource.Monotonic.markNow() }

    override fun state() = EngineState(item?.videoId, playing, false, position(), item?.durationHintSeconds?.takeIf { it > 0f })

    override fun updateNowPlaying(item: EngineItem?, positionSeconds: Float, durationSeconds: Float, playing: Boolean, hasPrevious: Boolean, hasNext: Boolean) {}
}
