package com.headunit.music.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.headunit.music.data.Track
import org.json.JSONObject

/**
 * Plays songs through YouTube's official embedded player (IFrame Player API)
 * inside a WebView, and keeps a simple queue.
 *
 * The WebView is the video you see on the Now Playing screen and in the mini
 * player. YouTube's terms require the embedded player to stay visible while it
 * plays, so it's never hidden behind the UI.
 *
 * All state is Compose state, so the screens update on their own.
 */
class PlayerController(context: Context) {

    // ---- Queue ----
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    val current: Track? by derivedStateOf { queue.getOrNull(index) }
    val upNext: Track? by derivedStateOf { queue.getOrNull(index + 1) }
    val hasPrevious: Boolean by derivedStateOf { index > 0 }
    val hasNext: Boolean by derivedStateOf { index in 0 until queue.size - 1 }

    // ---- Playback (fed by the player's JavaScript) ----
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionSeconds by mutableFloatStateOf(0f)
        private set
    var durationSeconds by mutableFloatStateOf(0f)
        private set
    /** Set when YouTube refuses a video; cleared on the next song. */
    var error by mutableStateOf<PlayerError?>(null)
        private set

    /** Now Playing screen open (true) or minimized to the dock (false). */
    var expanded by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingVideoId: String? = null
    private var skipOnError: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context.applicationContext).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Start songs without a second tap inside the video.
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
        addJavascriptInterface(Bridge(), "Android")
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    // ---- Controls ----

    /** Plays [tracks] starting at [startIndex]; the rest become the queue. */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        queue = tracks
        playAt(startIndex)
        expanded = true
    }

    fun togglePlay() {
        if (current == null) return
        js(if (isPlaying) "pause()" else "play()")
    }

    fun next() {
        if (hasNext) playAt(index + 1)
    }

    /** Like most players: restarts the song if you're past 3 seconds, else goes back one. */
    fun previous() {
        if (positionSeconds > 3f || !hasPrevious) seekTo(0f) else playAt(index - 1)
    }

    fun seekTo(seconds: Float) {
        positionSeconds = seconds.coerceIn(0f, durationSeconds.coerceAtLeast(0f))
        js("seek(${positionSeconds})")
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        webView.destroy()
    }

    private fun playAt(i: Int) {
        val track = queue.getOrNull(i) ?: return
        skipOnError?.let { main.removeCallbacks(it) }
        index = i
        error = null
        positionSeconds = 0f
        durationSeconds = (track.durationSeconds ?: 0).toFloat()
        isBuffering = true
        if (!pageLoaded) {
            pageLoaded = true
            pendingVideoId = track.videoId
            webView.loadDataWithBaseURL(BASE_URL, playerHtml(track.videoId), "text/html", "utf-8", null)
        } else if (!ready) {
            pendingVideoId = track.videoId
        } else {
            js("load(${JSONObject.quote(track.videoId)})")
        }
    }

    private var pageLoaded = false

    private fun js(call: String) {
        if (ready) webView.evaluateJavascript(call, null)
    }

    // ---- Events from the page (arrive on a background thread) ----

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = main.post {
            ready = true
            // A song picked while the player was still loading.
            pendingVideoId?.let { id ->
                pendingVideoId = null
                if (id != current?.videoId) js("load(${JSONObject.quote(id)})")
            }
        }

        @JavascriptInterface
        fun onState(state: Int) = main.post {
            isPlaying = state == STATE_PLAYING
            isBuffering = state == STATE_BUFFERING || state == STATE_UNSTARTED
            webView.keepScreenOn = isPlaying
            if (state == STATE_ENDED) {
                if (hasNext) playAt(index + 1) else isBuffering = false
            }
        }

        @JavascriptInterface
        fun onTime(position: Double, duration: Double) = main.post {
            positionSeconds = position.toFloat()
            if (duration > 0) durationSeconds = duration.toFloat()
        }

        @JavascriptInterface
        fun onError(code: Int) = main.post {
            val e = PlayerError.from(code)
            error = e
            isPlaying = false
            isBuffering = false
            // Songs YouTube won't play here: move on by themselves.
            if (e.skippable && hasNext) {
                skipOnError = Runnable { next() }.also { main.postDelayed(it, 2500) }
            }
        }
    }

    companion object {
        /**
         * The page needs a real https address. YouTube rejects embeds that arrive
         * with no origin/referrer (error 152/153), which is what a bare local page sends.
         */
        private const val BASE_URL = "https://com.headunit.music/"

        private const val STATE_UNSTARTED = -1
        private const val STATE_ENDED = 0
        private const val STATE_PLAYING = 1
        private const val STATE_BUFFERING = 3

        private fun playerHtml(firstVideoId: String): String {
            val origin = BASE_URL.trimEnd('/')
            // controls=0: our own big buttons drive it. playsinline: no forced fullscreen.
            val src = "https://www.youtube.com/embed/$firstVideoId?enablejsapi=1&autoplay=1&controls=0" +
                "&disablekb=1&fs=0&iv_load_policy=3&playsinline=1&rel=0&origin=$origin"
            return """
                <!doctype html>
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>
                  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
                  iframe { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; }
                </style>
                </head><body>
                <iframe id="p" src="$src" allow="autoplay; encrypted-media"
                        referrerpolicy="strict-origin-when-cross-origin"></iframe>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                  var player;
                  function onYouTubeIframeAPIReady() {
                    player = new YT.Player('p', { events: {
                      onReady: function () { Android.onReady(); },
                      onStateChange: function (e) { Android.onState(e.data); },
                      onError: function (e) { Android.onError(e.data); }
                    }});
                  }
                  function load(id) { player.loadVideoById(id); }
                  function play() { player.playVideo(); }
                  function pause() { player.pauseVideo(); }
                  function seek(s) { player.seekTo(s, true); }
                  setInterval(function () {
                    if (player && player.getCurrentTime) {
                      Android.onTime(player.getCurrentTime() || 0, player.getDuration() || 0);
                    }
                  }, 250);
                </script>
                </body></html>
            """.trimIndent()
        }
    }
}

/** Why YouTube wouldn't play a video, in words for the screen. */
enum class PlayerError(val message: String, val skippable: Boolean) {
    NotAllowed("This song can't be played outside YouTube", skippable = true),
    Unavailable("This video was removed or is private", skippable = true),
    BadRequest("Couldn't load this song", skippable = true),
    PlayerConfig("YouTube blocked the player setup (error 153)", skippable = false),
    Other("Couldn't play this song", skippable = true);

    companion object {
        fun from(code: Int) = when (code) {
            101, 150 -> NotAllowed
            100 -> Unavailable
            2 -> BadRequest
            152, 153 -> PlayerConfig
            else -> Other
        }
    }
}
