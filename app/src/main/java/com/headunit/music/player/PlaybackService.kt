package com.headunit.music.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.headunit.music.MainActivity

/**
 * Keeps the music going with the app in the background (e.g. while Maps is up),
 * and lets everything outside the app control it through a media session:
 * steering-wheel buttons, Bluetooth, the headunit's own "now playing" widget,
 * the notification and voice assistants. They all see the song's title,
 * artist and artwork.
 *
 * It wraps the app's one [PlayerController]; while music plays, Media3 runs
 * this as a foreground service with a media notification, so Android won't
 * close it. Tapping the notification (or the headunit's widget) opens the app.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerController.get(this)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player.sessionPlayer)
            .setSessionActivity(openApp)
            .setCallback(SteeringWheelKeys(player))
            .build()
    }

    /**
     * Steering-wheel / Bluetooth next and previous go straight to our queue.
     * (Left to Media3, they're checked against what ExoPlayer itself thinks it
     * can skip to, which is nothing, since it only ever holds the one song
     * playing; so the buttons did nothing.) Everything else (play / pause, …)
     * is handled by Media3 as usual.
     */
    private class SteeringWheelKeys(private val player: PlayerController) : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return false
            // Filter Logcat by "MuZ" to see which button the car sends.
            Log.d("MuZ", "Media button: ${KeyEvent.keyCodeToString(key.keyCode)} (repeat ${key.repeatCount}) from ${controllerInfo.packageName}")
            return when (key.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (key.repeatCount == 0) player.next() // holding the button skips once, not repeatedly
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    if (key.repeatCount == 0) player.previous()
                    true
                }
                else -> false
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app was swiped away: keep playing if music's on, otherwise shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Only the session goes. The player stays: Android stops idle background services
        // (e.g. paused for a while), and the screen may still be using it. The app starts
        // this service again whenever it comes back to the front.
        session?.release()
        session = null
        super.onDestroy()
    }
}
