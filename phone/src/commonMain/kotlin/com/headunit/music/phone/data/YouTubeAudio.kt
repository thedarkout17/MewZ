package com.headunit.music.phone.data

import com.headunit.music.core.playback.AudioUnavailableException
import com.headunit.music.core.playback.AudioUnavailableException.Reason
import com.headunit.music.core.sources.AudioStreamSource
import com.headunit.music.phone.log
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Finds a song's audio-only stream straight from the phone (no server), so no
 * video is downloaded: YouTube's ~128 kbps AAC (M4A) stream, which AVPlayer
 * plays natively. Uses InnerTube's VISIONOS client (see [InnerTube]).
 *
 * Links are remembered for 4 hours (YouTube's expire after about 6), so looking
 * the next song up ahead of time makes skipping instant. Call from the main thread.
 */
object YouTubeAudio : AudioStreamSource {

    /** AVPlayer has to send this when fetching the stream (the links are tied to the client). */
    const val USER_AGENT = InnerTube.VISIONOS_USER_AGENT

    private class Entry(val url: String, val at: TimeMark)

    private val cache = HashMap<String, Entry>()
    private val MAX_AGE = 4.hours
    private const val TARGET_BPS = 128_000

    override suspend fun urlFor(videoId: String): String {
        cache[videoId]?.let { if (it.at.elapsedNow() < MAX_AGE) return it.url }
        val url = resolve(videoId)
        cache[videoId] = Entry(url, TimeSource.Monotonic.markNow())
        return url
    }

    override fun forget(videoId: String) {
        cache.remove(videoId)
    }

    private suspend fun resolve(videoId: String): String {
        val (response, cpn) = try {
            InnerTube.player(videoId)
        } catch (e: InnerTube.RateLimited) {
            throw AudioUnavailableException(Reason.RateLimited, e.message)
        } catch (e: AudioUnavailableException) {
            throw e
        } catch (e: Exception) {
            log("Audio lookup failed for $videoId: ${e::class.simpleName}: ${e.message}")
            throw AudioUnavailableException(if (isNetworkError(e)) Reason.Offline else Reason.ExtractorOutdated, e.message)
        }
        checkPlayable(response)
        val url = pick(response)
            ?: throw AudioUnavailableException(Reason.Unavailable, "No playable audio stream")
        return "$url&cpn=$cpn"
    }

    /** Turns YouTube's "can't play this" answers into reasons the player can show. */
    private fun checkPlayable(r: JsonObject) {
        val ps = r.obj("playabilityStatus")
        val status = ps.str("status") ?: return
        if (status.equals("OK", ignoreCase = true)) return
        val reason = ps.str("reason").orEmpty()
        val messages = ps.arr("messages")?.joinToString(" ") { it.toString() }.orEmpty()
        log("Not playable: $status \"$reason\" $messages")
        val why = when {
            reason.contains("inappropriate", true) || reason.contains("age", true) -> Reason.AgeRestricted
            reason.contains("bot", true) || messages.contains("bot", true) -> Reason.RateLimited
            status.equals("LOGIN_REQUIRED", true) -> Reason.AgeRestricted
            else -> Reason.Unavailable
        }
        throw AudioUnavailableException(why, "$status: $reason")
    }

    /**
     * Plain-link audio in an MP4 container (AAC, which AVPlayer plays; YouTube's
     * WebM/Opus streams it can't), closest to 128 kbps. If there's none, the
     * small 360p MP4 with sound (itag 18) still plays: its picture is just ignored.
     */
    private fun pick(r: JsonObject): String? {
        val sd = r.obj("streamingData") ?: return null
        val adaptive = sd.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        val audio = adaptive
            .filter { it.str("url") != null && it.str("mimeType").orEmpty().startsWith("audio/mp4") }
            // Skip YouTube's "dubbed"/"descriptive" extra audio tracks: keep the original.
            .filter { f -> f.obj("audioTrack")?.str("audioIsDefault") != "false" }
            .minByOrNull { f ->
                val bps = f.int("averageBitrate") ?: f.int("bitrate") ?: TARGET_BPS
                abs(bps - TARGET_BPS)
            }
        if (audio != null) return audio.str("url")
        val muxed = sd.arr("formats").orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("url") != null && it.str("mimeType").orEmpty().startsWith("video/mp4") }
        return muxed?.str("url")
    }
}
