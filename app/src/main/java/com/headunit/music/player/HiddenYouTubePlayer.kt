package com.headunit.music.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * One YouTube player (IFrame Player API in a WebView) that's kept tiny and
 * hidden behind the artwork. Small players make YouTube pick its lowest video
 * quality, so data use stays close to audio-only.
 *
 * It can either play a song ([load]) or get one ready in silence ([preload]):
 * load it muted, let it start buffering, then pause it at 0:00 so [activate]
 * starts it instantly later.
 */
@SuppressLint("SetJavaScriptEnabled")
class HiddenYouTubePlayer(context: Context, private val events: Events) {

    /** Callbacks, always on the main thread. */
    interface Events {
        fun onState(player: HiddenYouTubePlayer, state: Int)
        fun onTime(player: HiddenYouTubePlayer, position: Double, duration: Double)
        fun onError(player: HiddenYouTubePlayer, code: Int)
    }

    private val main = Handler(Looper.getMainLooper())

    /** The page loads as soon as the player is created, so it's warm by the first song. */
    val view: WebView = WebView(context.applicationContext).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Start songs without a tap inside the player.
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
        addJavascriptInterface(Bridge(), "Android")
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        loadDataWithBaseURL(BASE_URL, PAGE, "text/html", "utf-8", null)
    }

    /** The video loaded in this player (playing, or preloaded), if any. */
    var videoId: String? = null
        private set

    /** True once [preload]ed and paused at 0:00, ready for [activate]. */
    var preloaded = false
        private set

    private var ready = false
    /** Last command sent before the page was ready; replayed once it is. */
    private var pending: String? = null

    /** Play [id] with sound, now. */
    fun load(id: String) {
        videoId = id
        preloaded = false
        run("load(${JSONObject.quote(id)})")
    }

    /** Load [id] muted, buffer its start, then hold at 0:00. */
    fun preload(id: String) {
        videoId = id
        preloaded = false
        run("preload(${JSONObject.quote(id)})")
    }

    /** Start the preloaded song from the beginning, with sound. */
    fun activate() = run("activate()")

    /** Stop and forget the current video (also stops it downloading). */
    fun stop() {
        videoId = null
        preloaded = false
        run("stop()")
    }

    fun play() = run("play()")
    fun pause() = run("pause()")
    fun seek(seconds: Float) = run("seek($seconds)")

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        view.destroy()
    }

    private fun run(call: String) {
        if (ready) view.evaluateJavascript(call, null) else pending = call
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = main.post {
            ready = true
            pending?.let { view.evaluateJavascript(it, null) }
            pending = null
        }

        @JavascriptInterface
        fun onState(state: Int) = main.post { events.onState(this@HiddenYouTubePlayer, state) }

        @JavascriptInterface
        fun onTime(position: Double, duration: Double) =
            main.post { events.onTime(this@HiddenYouTubePlayer, position, duration) }

        @JavascriptInterface
        fun onError(code: Int) = main.post { events.onError(this@HiddenYouTubePlayer, code) }

        @JavascriptInterface
        fun onPreloaded(id: String) = main.post {
            if (id == videoId) preloaded = true
        }

        @JavascriptInterface
        fun onPreloadFailed(id: String) = main.post {
            // Couldn't get it ready; it'll just load normally when its turn comes.
            if (id == videoId) videoId = null
        }
    }

    companion object {
        const val STATE_UNSTARTED = -1
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_BUFFERING = 3

        /**
         * The page needs a real https address. YouTube rejects embeds that arrive
         * with no origin/referrer (error 152/153), which is what a bare local page sends.
         */
        private const val BASE_URL = "https://com.headunit.music/"

        private val PAGE: String = run {
            val origin = BASE_URL.trimEnd('/')
            // No video yet: songs are loaded with loadVideoById.
            val src = "https://www.youtube.com/embed/?enablejsapi=1&controls=0&disablekb=1" +
                "&fs=0&iv_load_policy=3&playsinline=1&rel=0&origin=$origin"
            """
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
              var player, preloadingId = null;
              function onYouTubeIframeAPIReady() {
                player = new YT.Player('p', { events: {
                  onReady: function () { Android.onReady(); },
                  onStateChange: function (e) {
                    if (preloadingId !== null) {
                      // Getting a song ready: as soon as it starts, hold it at 0:00.
                      if (e.data === 1) {
                        var id = preloadingId;
                        preloadingId = null;
                        player.pauseVideo();
                        player.seekTo(0, true);
                        Android.onPreloaded(id);
                      }
                      return;
                    }
                    Android.onState(e.data);
                  },
                  onError: function (e) {
                    if (preloadingId !== null) {
                      var id = preloadingId;
                      preloadingId = null;
                      Android.onPreloadFailed(id);
                      return;
                    }
                    Android.onError(e.data);
                  }
                }});
              }
              function load(id) { preloadingId = null; player.unMute(); player.loadVideoById(id); }
              function preload(id) { preloadingId = id; player.mute(); player.loadVideoById(id); }
              function activate() { preloadingId = null; player.unMute(); player.seekTo(0, true); player.playVideo(); }
              function stop() { preloadingId = null; player.mute(); player.stopVideo(); }
              function play() { player.playVideo(); }
              function pause() { player.pauseVideo(); }
              function seek(s) { player.seekTo(s, true); }
              setInterval(function () {
                if (player && player.getCurrentTime && preloadingId === null) {
                  Android.onTime(player.getCurrentTime() || 0, player.getDuration() || 0);
                }
              }, 250);
            </script>
            </body></html>
            """.trimIndent()
        }
    }
}
