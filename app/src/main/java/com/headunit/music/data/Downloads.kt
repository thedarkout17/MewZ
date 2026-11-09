package com.headunit.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.headunit.music.data.youtube.AudioStreams
import com.headunit.music.data.youtube.NewPipeDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Where a liked song's download is at. */
sealed interface DownloadState {
    /** Liked, waiting its turn. */
    data object Queued : DownloadState
    /** Downloading now; [fraction] 0..1 (0 until the size is known). */
    data class Downloading(val fraction: Float) : DownloadState
    /** Saved on the device: plays without internet. */
    data object Done : DownloadState
    /** Didn't finish (no connection, YouTube refused, …); tried again when the connection comes back. */
    data object Failed : DownloadState
}

/**
 * Liked songs, saved on the device so they play offline.
 *
 * Liking a song downloads its audio (the same ~128 kbps audio-only stream the
 * player uses, ~1 MB a minute) and its two pictures (list thumbnail and Now
 * Playing artwork). Unliking deletes them. Everything lives in the app's
 * private storage (`files/downloads`): other apps can't see it, and it goes
 * when the app is uninstalled.
 *
 * - One song at a time, in the order you liked them, in 1 MB pieces. While the
 *   song that's playing needs to load ([shouldYield]), the download waits, so
 *   it never makes your music stutter on a weak signal.
 * - A download cut off part-way (signal lost, app closed) carries on from
 *   where it stopped: on the next start, and whenever the connection comes back.
 * - Pictures: [localImage] tells the app's image loader to use the saved copy.
 */
class Downloads private constructor(context: Context) {

    companion object {
        @Volatile private var instance: Downloads? = null

        fun get(context: Context): Downloads =
            instance ?: synchronized(this) {
                instance ?: Downloads(context.applicationContext).also { instance = it }
            }

        private const val TAG = "MuZ"
        private const val CHUNK_BYTES = 1L shl 20 // 1 MB per request (YouTube slows down big single requests)
        private const val AUDIO = ".audio"
        private const val PART = ".part"
        private const val IMAGE = ".img"
    }

    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val client = NewPipeDownloader.instance.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Each liked song's download, by video id (Compose state: rows update by themselves). */
    val states = mutableStateMapOf<String, DownloadState>()

    /** Space the saved songs take, in bytes. */
    var bytesUsed by mutableLongStateOf(0L)
        private set

    /**
     * Set by the player: true while the song playing is loading. Downloads pause
     * for it. Called on the main thread.
     */
    var shouldYield: () -> Boolean = { false }

    /** Songs that should be on the device (liked). Anything else gets deleted. */
    private val wanted = HashMap<String, Track>()
    private val waiting = LinkedHashMap<String, Track>()
    private var currentId: String? = null
    private var currentJob: Job? = null
    /** Saved pictures (file names), so lookups don't touch the disk. */
    private val images: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        dir.listFiles().orEmpty().forEach { f ->
            when {
                f.name.endsWith(AUDIO) -> states[f.name.removeSuffix(AUDIO)] = DownloadState.Done
                f.name.endsWith(IMAGE) -> images += f.name
            }
        }
        updateSize()
        // Connection back (Wi-Fi, hotspot, signal): try the ones that didn't finish.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { retryFailed() }
                }
            })
        }.onFailure { Log.w(TAG, "No connectivity updates: ${it.message}") }
    }

    // ---- Asking ----

    fun isDownloaded(videoId: String): Boolean = states[videoId] == DownloadState.Done

    /** The saved audio, if this song is fully downloaded. */
    fun audioFile(videoId: String): File? =
        if (isDownloaded(videoId)) File(dir, videoId + AUDIO).takeIf { it.exists() } else null

    /** The saved copy of a picture (thumbnail / artwork URL), if there is one. */
    fun localImage(url: String): File? {
        val name = imageName(url)
        return if (name in images) File(dir, name) else null
    }

    // ---- Liked / unliked ----

    /**
     * App start: make the folder match Liked. Songs liked but not (fully) saved
     * start downloading; files for songs no longer liked are deleted.
     */
    fun sync(liked: List<Track>) {
        wanted.clear()
        liked.forEach { wanted[it.videoId] = it }
        val keepImages = liked.flatMap { listOf(it.thumbnailUrl, it.artworkUrl) }.mapTo(HashSet()) { imageName(it) }
        dir.listFiles().orEmpty().forEach { f ->
            val id = f.name.substringBefore('.')
            val stale = when {
                f.name.endsWith(IMAGE) -> f.name !in keepImages
                else -> id !in wanted
            }
            if (stale) {
                f.delete()
                images -= f.name
                states.remove(id)
            }
        }
        updateSize()
        // Oldest liked first, like they would have queued.
        liked.asReversed().forEach { if (!isDownloaded(it.videoId)) enqueue(it) }
    }

    /** A song was liked: save it on the device. */
    fun add(track: Track) {
        wanted[track.videoId] = track
        enqueue(track)
    }

    /** A song was unliked: stop any download and delete what's saved. */
    fun remove(track: Track) {
        val id = track.videoId
        wanted.remove(id)
        waiting.remove(id)
        if (currentId == id) currentJob?.cancel()
        deleteFiles(track)
        states.remove(id)
        updateSize()
    }

    /** The saved copy wouldn't play (damaged): delete it and download it again. */
    fun redownload(track: Track) {
        File(dir, track.videoId + AUDIO).delete()
        states.remove(track.videoId)
        updateSize()
        if (track.videoId in wanted) enqueue(track)
    }

    private fun retryFailed() {
        wanted.values.filter { states[it.videoId] == DownloadState.Failed }.forEach { enqueue(it) }
    }

    // ---- Downloading, one at a time ----

    private fun enqueue(track: Track) {
        val id = track.videoId
        if (isDownloaded(id) || id == currentId || id in waiting) return
        states[id] = DownloadState.Queued
        waiting[id] = track
        pump()
    }

    private fun pump() {
        if (currentJob?.isActive == true) return
        val track = waiting.values.firstOrNull() ?: return
        waiting.remove(track.videoId)
        currentId = track.videoId
        currentJob = scope.launch {
            val id = track.videoId
            try {
                states[id] = DownloadState.Downloading(0f)
                saveImages(track)
                saveAudio(track)
                states[id] = DownloadState.Done
                Log.d(TAG, "Downloaded \"${track.title}\"")
            } catch (e: CancellationException) {
                // Unliked meanwhile: remove() already cleaned up.
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for \"${track.title}\": ${e.message}")
                if (id in wanted) states[id] = DownloadState.Failed
            } finally {
                if (id !in wanted) deleteFiles(track) // unliked while its last piece was arriving
                updateSize()
                currentId = null
                currentJob = null
                pump()
            }
        }
    }

    /** The list thumbnail and the Now Playing artwork (failures just mean no offline picture). */
    private suspend fun saveImages(track: Track) = withContext(Dispatchers.IO) {
        listOf(track.thumbnailUrl, track.artworkUrl).distinct().forEach { url ->
            val name = imageName(url)
            if (name in images) return@forEach
            runCatching {
                val tmp = File(dir, "$name.tmp")
                client.newCall(Request.Builder().url(url).header("User-Agent", NewPipeDownloader.USER_AGENT).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body!!.byteStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                    }
                if (tmp.renameTo(File(dir, name))) images += name
            }
        }
    }

    /**
     * Downloads the audio in 1 MB pieces into `<id>.<format>.part`, carrying on
     * from whatever's there, then renames it `<id>.audio`. Expired link (403):
     * gets a fresh one once; if YouTube now serves a different format, starts over.
     */
    private suspend fun saveAudio(track: Track) {
        val id = track.videoId
        var url = AudioStreams.urlFor(id) // throws AudioUnavailableException (→ Failed)
        var refreshed = false
        var part = partFile(id, url)
        clearOtherParts(id, part)

        while (true) {
            // The song playing comes first.
            while (shouldYield()) delay(500)
            currentCoroutineEnsureActive()

            val from = part.length()
            val result = withContext(Dispatchers.IO) { fetchPiece(url, part, from) }
            when (result) {
                is Piece.Progress -> {
                    states[id] = DownloadState.Downloading(if (result.total > 0) result.got.toFloat() / result.total else 0f)
                    if (result.total > 0 && result.got >= result.total) break
                }
                Piece.Finished -> break
                Piece.Expired -> {
                    if (refreshed) throw IOException("Link refused twice")
                    refreshed = true
                    AudioStreams.forget(id)
                    url = AudioStreams.urlFor(id)
                    val fresh = partFile(id, url)
                    if (fresh.name != part.name) { part.delete(); part = fresh } // different format: start over
                    clearOtherParts(id, part)
                }
            }
        }
        currentCoroutineEnsureActive()
        val done = File(dir, id + AUDIO)
        if (!part.renameTo(done)) throw IOException("Couldn't save ${done.name}")
    }

    private suspend fun currentCoroutineEnsureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    private sealed interface Piece {
        data class Progress(val got: Long, val total: Long) : Piece
        data object Finished : Piece
        data object Expired : Piece
    }

    /** One 1 MB range request, appended to [part]. */
    private fun fetchPiece(url: String, part: File, from: Long): Piece {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Range", "bytes=$from-${from + CHUNK_BYTES - 1}")
            .build()
        client.newCall(request).execute().use { r ->
            when (r.code) {
                403, 410 -> return Piece.Expired
                416 -> return Piece.Finished // asked past the end: already have it all
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            if (r.code == 200) {
                // Server ignored the range and sent the whole file: take it from the start.
                body.byteStream().use { input -> FileOutputStream(part, false).use { input.copyTo(it) } }
                return Piece.Finished
            }
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
            body.byteStream().use { input -> FileOutputStream(part, true).use { input.copyTo(it) } }
            val got = part.length()
            return if (total < 0 && body.contentLength() in 0 until CHUNK_BYTES) Piece.Finished // short piece = the end
            else Piece.Progress(got, total)
        }
    }

    // ---- Files ----

    /** Partial download, named by format ("itag") so a resume never mixes two formats. */
    private fun partFile(id: String, url: String): File {
        val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1) ?: "audio"
        return File(dir, "$id.$itag$PART")
    }

    private fun clearOtherParts(id: String, keep: File) {
        dir.listFiles().orEmpty()
            .filter { it.name.startsWith("$id.") && it.name.endsWith(PART) && it.name != keep.name }
            .forEach { it.delete() }
    }

    private fun deleteFiles(track: Track) {
        val id = track.videoId
        dir.listFiles().orEmpty().filter { it.name.startsWith("$id.") }.forEach { it.delete() }
        listOf(track.thumbnailUrl, track.artworkUrl).forEach { url ->
            val name = imageName(url)
            File(dir, name).delete()
            images -= name
        }
    }

    private fun updateSize() {
        bytesUsed = dir.listFiles().orEmpty().filter { it.name.endsWith(AUDIO) }.sumOf { it.length() }
    }

    private fun imageName(url: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return hash.joinToString("") { "%02x".format(it) } + IMAGE
    }
}
