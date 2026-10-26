@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.headunit.music.phone.player

import com.headunit.music.phone.data.Http
import com.headunit.music.phone.log
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.*
import platform.darwin.NSObjectProtocol
import kotlin.math.abs
import kotlin.math.min

actual fun createAudioEngine(): AudioEngine = AvAudioEngine()

/**
 * AVQueuePlayer-based engine. The song playing and (when known) the next one
 * are both in the queue player, so the next one buffers ahead and starts the
 * moment this one ends: no gap, and nothing to look up while the phone is
 * locked (iOS can suspend an app that goes quiet between songs).
 */
private class AvAudioEngine : AudioEngine {

    override var listener: AudioEngine.Listener? = null

    private val player = AVQueuePlayer()

    /** The song we asked to play, and its AVPlayerItem. */
    private var cur: EngineItem? = null
    private var avCur: AVPlayerItem? = null
    /** The song queued behind it ([prepareNext]). */
    private var nxt: EngineItem? = null
    private var avNxt: AVPlayerItem? = null
    /** Item whose failure was already reported (state() is polled). */
    private var reportedFailure: AVPlayerItem? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        startAudioSession()
        player.actionAtItemEnd = AVPlayerActionAtItemEndAdvance
        player.automaticallyWaitsToMinimizeStalling = true

        val nc = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers += nc.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, main) { n ->
            onItemEnded(n?.`object` as? AVPlayerItem)
        }
        observers += nc.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, null, main) { n ->
            val item = n?.`object` as? AVPlayerItem
            val err = n?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
            reportFailure(item, err?.localizedDescription)
        }
        observers += nc.addObserverForName(AVAudioSessionInterruptionNotification, null, main) { n -> onInterruption(n) }
        observers += nc.addObserverForName(AVAudioSessionRouteChangeNotification, null, main) { n ->
            val reason = (n?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) listener?.onOutputLost()
        }
        setUpRemoteCommands()
    }

    // ---- Audio session: keeps playing in the background and with the screen locked ----

    private fun startAudioSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeDefault, 0uL, null)
        session.setActive(true, null)
        UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
    }

    private fun onInterruption(n: NSNotification?) {
        val info = n?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue ?: return
        if (type == AVAudioSessionInterruptionTypeBegan) {
            listener?.onInterrupted(began = true, shouldResume = false)
        } else {
            val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
            val resume = (options and AVAudioSessionInterruptionOptionShouldResume) != 0uL
            if (resume) AVAudioSession.sharedInstance().setActive(true, null)
            listener?.onInterrupted(began = false, shouldResume = resume)
        }
    }

    // ---- Playing ----

    private fun makeItem(item: EngineItem): AVPlayerItem? {
        val url = NSURL.URLWithString(item.url) ?: return null
        // The stream links only work with the same User-Agent as the client that made them.
        val asset = AVURLAsset.URLAssetWithURL(
            url,
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("User-Agent" to item.userAgent)),
        )
        return AVPlayerItem.playerItemWithAsset(asset).apply {
            // Buffer up to 5 minutes ahead (most of a song), so it plays on through dead zones.
            preferredForwardBufferDuration = 300.0
        }
    }

    override fun play(item: EngineItem, startAtSeconds: Float, autoPlay: Boolean) {
        endBackgroundTask()
        val playing = player.currentItem
        when {
            // Already this song (e.g. it just moved on to it by itself).
            cur?.videoId == item.videoId && playing != null && playing == avCur && playing.status != AVPlayerItemStatusFailed -> {
                cur = item
            }
            playing != null && playing == avNxt && nxt?.videoId == item.videoId -> promoteNext(item)
            // The queued next song, still waiting behind the current one: skip to it.
            nxt?.videoId == item.videoId && avNxt != null && avNxt?.status != AVPlayerItemStatusFailed -> {
                player.advanceToNextItem()
                promoteNext(item)
            }
            else -> {
                val av = makeItem(item) ?: run {
                    listener?.onFailed(item.videoId, "Bad stream address")
                    return
                }
                player.removeAllItems()
                player.insertItem(av, null)
                avCur = av
                cur = item
                avNxt = null
                nxt = null
            }
        }
        if (startAtSeconds > 0.5f) seekTo(startAtSeconds)
        if (autoPlay) {
            AVAudioSession.sharedInstance().setActive(true, null)
            player.play()
        }
        lastInfoId = null // refresh the lock screen
    }

    private fun promoteNext(item: EngineItem) {
        avCur = avNxt
        cur = item
        avNxt = null
        nxt = null
    }

    override fun prepareNext(item: EngineItem?) {
        if (item != null && nxt?.videoId == item.videoId && avNxt != null) return
        avNxt?.let { if (player.currentItem != it) player.removeItem(it) }
        avNxt = null
        nxt = null
        if (item == null || loop) return
        val current = avCur ?: return
        if (player.currentItem != current) return
        val av = makeItem(item) ?: return
        if (player.canInsertItem(av, afterItem = current)) {
            player.insertItem(av, afterItem = current)
            avNxt = av
            nxt = item
        }
    }

    private fun onItemEnded(item: AVPlayerItem?) {
        if (item == null || item != avCur) return
        val id = cur?.videoId ?: return
        if (loop) {
            player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
            player.play()
            return
        }
        // Give the app time to start whatever's next if nothing was queued (e.g. a Mix top-up),
        // since iOS may suspend an app that's gone quiet.
        if (avNxt == null) beginBackgroundTask()
        listener?.onEnded(id)
    }

    private fun reportFailure(item: AVPlayerItem?, message: String?) {
        if (item == null || item == reportedFailure) return
        reportedFailure = item
        val id = when (item) {
            avCur -> cur?.videoId
            avNxt -> nxt?.videoId.also { avNxt = null; nxt = null }
            else -> null
        } ?: return
        if (item == avCur) listener?.onFailed(id, message ?: item.error?.localizedDescription)
    }

    override fun resume() {
        AVAudioSession.sharedInstance().setActive(true, null)
        if (player.currentItem == null && avCur != null) cur?.let { play(it, 0f) } else player.play()
    }

    override fun pause() = player.pause()

    override fun stop() {
        player.pause()
        player.removeAllItems()
        avCur = null; cur = null; avNxt = null; nxt = null
    }

    override fun seekTo(seconds: Float) {
        player.seekToTime(CMTimeMakeWithSeconds(seconds.toDouble(), 600))
        lastInfoId = null
    }

    override var loop: Boolean = false
        set(value) {
            field = value
            player.actionAtItemEnd = if (value) AVPlayerActionAtItemEndNone else AVPlayerActionAtItemEndAdvance
            if (value) prepareNext(null)
        }

    override fun state(): EngineState {
        val item = player.currentItem
        if (item != null && item.status == AVPlayerItemStatusFailed) reportFailure(item, item.error?.localizedDescription)
        val id = when {
            item == null -> null
            item == avCur -> cur?.videoId
            item == avNxt -> nxt?.videoId
            else -> null
        }
        val status = player.timeControlStatus
        val duration = item?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { it.isFinite() && it > 0 }?.toFloat()
        val position = CMTimeGetSeconds(player.currentTime()).takeIf { it.isFinite() }?.toFloat() ?: 0f
        return EngineState(
            currentId = id,
            isPlaying = status == AVPlayerTimeControlStatusPlaying,
            isBuffering = status == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            positionSeconds = position.coerceAtLeast(0f),
            durationSeconds = duration,
        )
    }

    // ---- Lock screen, Control Center, car and headphone buttons ----

    private fun setUpRemoteCommands() {
        val cc = MPRemoteCommandCenter.sharedCommandCenter()
        fun on(command: platform.MediaPlayer.MPRemoteCommand, what: RemoteCommand) {
            command.enabled = true
            command.addTargetWithHandler { _ ->
                listener?.onRemoteCommand(what)
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        on(cc.playCommand, RemoteCommand.Play)
        on(cc.pauseCommand, RemoteCommand.Pause)
        on(cc.togglePlayPauseCommand, RemoteCommand.TogglePlayPause)
        on(cc.nextTrackCommand, RemoteCommand.Next)
        on(cc.previousTrackCommand, RemoteCommand.Previous)
        cc.changePlaybackPositionCommand.enabled = true
        cc.changePlaybackPositionCommand.addTargetWithHandler { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let { listener?.onRemoteSeek(it.positionTime.toFloat()) }
            MPRemoteCommandHandlerStatusSuccess
        }
        cc.skipForwardCommand.enabled = false
        cc.skipBackwardCommand.enabled = false
    }

    private var lastInfoId: String? = null
    private var lastInfoPlaying = false
    private var lastInfoPosition = 0f
    private var artwork: Pair<String, MPMediaItemArtwork>? = null
    private var artworkJob: Job? = null

    override fun updateNowPlaying(
        item: EngineItem?,
        positionSeconds: Float,
        durationSeconds: Float,
        playing: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ) {
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (item == null) {
            if (lastInfoId != null) center.nowPlayingInfo = null
            lastInfoId = null
            return
        }
        val cc = MPRemoteCommandCenter.sharedCommandCenter()
        cc.nextTrackCommand.enabled = hasNext
        // (Previous always works: it restarts the song.)

        // Only tell iOS when something changed: it extrapolates the time itself while playing.
        val expected = lastInfoPosition + if (lastInfoPlaying) 0.25f else 0f
        val changed = item.videoId != lastInfoId || playing != lastInfoPlaying || abs(positionSeconds - expected) > 1.5f
        lastInfoPosition = positionSeconds
        if (!changed) return
        lastInfoId = item.videoId
        lastInfoPlaying = playing

        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to item.title,
            MPMediaItemPropertyArtist to item.artist,
            MPMediaItemPropertyPlaybackDuration to (durationSeconds.takeIf { it > 0f } ?: item.durationHintSeconds).toDouble(),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to positionSeconds.toDouble(),
            MPNowPlayingInfoPropertyPlaybackRate to if (playing) 1.0 else 0.0,
        )
        item.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        val art = artwork
        if (art != null && art.first == item.videoId) info[MPMediaItemPropertyArtwork] = art.second
        else loadArtwork(item)
        center.nowPlayingInfo = info
    }

    /** Downloads the artwork, cuts the square out of it (like Now Playing does), and adds it to the lock screen. */
    private fun loadArtwork(item: EngineItem) {
        if (artworkJob?.isActive == true && artwork?.first != item.videoId) artworkJob?.cancel()
        artworkJob = scope.launch {
            val bytes = runCatching { Http.client.get(item.artworkUrl).readRawBytes() }.getOrNull() ?: return@launch
            val image = bytes.usePinned { pinned ->
                UIImage.imageWithData(NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()))
            } ?: return@launch
            val square = squareCrop(image, item.artworkLetterboxed)
            val size = square.size
            val art = MPMediaItemArtwork(boundsSize = size, requestHandler = { _ -> square })
            artwork = item.videoId to art
            if (lastInfoId == item.videoId) {
                val center = MPNowPlayingInfoCenter.defaultCenter()
                val info = center.nowPlayingInfo?.toMutableMap() ?: return@launch
                info[MPMediaItemPropertyArtwork] = art
                center.nowPlayingInfo = info
            }
        }
    }

    /**
     * The square from the middle of a YouTube picture. For 4:3 thumbnails with
     * black bars baked in, the square comes from the 16:9 picture between them.
     */
    private fun squareCrop(image: UIImage, letterboxed: Boolean): UIImage {
        val cg = image.CGImage ?: return image
        val w = CGImageGetWidth(cg).toDouble()
        val h = CGImageGetHeight(cg).toDouble()
        val pictureH = if (letterboxed) min(h, w * 9 / 16) else h
        val side = min(w, pictureH)
        val cropped = CGImageCreateWithImageInRect(cg, CGRectMake((w - side) / 2, (h - side) / 2, side, side)) ?: return image
        return UIImage.imageWithCGImage(cropped).also { CGImageRelease(cropped) }
    }

    // ---- Staying awake between songs ----

    private var backgroundTask = UIBackgroundTaskInvalid

    private fun beginBackgroundTask() {
        endBackgroundTask()
        backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler { endBackgroundTask() }
        scope.launch {
            delay(25_000)
            endBackgroundTask()
        }
    }

    private fun endBackgroundTask() {
        if (backgroundTask != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
            backgroundTask = UIBackgroundTaskInvalid
        }
    }
}
