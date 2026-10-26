package com.headunit.music.data.youtube

import com.headunit.music.core.sources.AudioStreamSource
import com.headunit.music.core.playback.AudioUnavailableException
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the audio-only stream for a YouTube video, so no video is downloaded.
 *
 * Picks a progressive (plain file) stream around 128 kbps, preferring AAC/M4A,
 * which every Android version plays. Results are cached, so looking up the next
 * song ahead of time makes skipping near-instant. YouTube's stream links expire
 * after about 6 hours; cached ones are dropped well before that.
 */
object AudioStreams : AudioStreamSource {

    private data class Entry(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private const val MAX_AGE_MS = 4 * 60 * 60 * 1000L // 4 hours
    private const val TARGET_KBPS = 128

    /** Audio stream URL for [videoId]. Throws [AudioUnavailableException]. */
    override suspend fun urlFor(videoId: String): String = withContext(Dispatchers.IO) {
        cache[videoId]?.let { if (SystemClock.elapsedRealtime() - it.at < MAX_AGE_MS) return@withContext it.url }
        val url = resolve(videoId)
        cache[videoId] = Entry(url, SystemClock.elapsedRealtime())
        url
    }

    /** Drop a cached link (e.g. it expired and playback failed) so the next lookup is fresh. */
    override fun forget(videoId: String) {
        cache.remove(videoId)
    }

    /** Look up [videoId] in the background so it's ready when needed; failures are ignored. */
    suspend fun prefetch(videoId: String) {
        runCatching { urlFor(videoId) }
    }

    private fun resolve(videoId: String): String {
        NewPipeDownloader.init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: AgeRestrictedContentException) {
            throw AudioUnavailableException(AudioUnavailableException.Reason.AgeRestricted, e.message)
        } catch (e: ContentNotAvailableException) {
            throw AudioUnavailableException(AudioUnavailableException.Reason.Unavailable, e.message)
        } catch (e: ReCaptchaException) {
            throw AudioUnavailableException(AudioUnavailableException.Reason.RateLimited, e.message)
        } catch (e: IOException) {
            throw AudioUnavailableException(AudioUnavailableException.Reason.Offline, e.message)
        } catch (e: Exception) {
            throw AudioUnavailableException(AudioUnavailableException.Reason.ExtractorOutdated, e.message)
        }

        val best = pick(info.audioStreams)
            ?: throw AudioUnavailableException(AudioUnavailableException.Reason.Unavailable, "No audio stream")
        return best.content
    }

    /**
     * Plain-file audio streams, scored: closest to 128 kbps wins, M4A preferred
     * over WebM/Opus on a tie (it plays everywhere and seeks reliably).
     */
    private fun pick(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .minByOrNull { s ->
                val kbps = if (s.averageBitrate > 0) s.averageBitrate else TARGET_KBPS
                val formatPenalty = if (s.format == MediaFormat.M4A) 0 else 8
                kotlin.math.abs(kbps - TARGET_KBPS) + formatPenalty
            }
}
