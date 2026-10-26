package com.headunit.music.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Audio focus for the YouTube players, which (unlike ExoPlayer) don't handle it
 * themselves: pause for calls and other apps' music, turn down for navigation
 * prompts, and pause when Bluetooth / headphones drop.
 */
class YouTubeAudioFocus(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onVolume: (percent: Int) -> Unit,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var held = false
    /** Paused by a short interruption (a call, a nav prompt that can't duck): play on after it. */
    private var resumeOnGain = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                onVolume(100)
                if (resumeOnGain) onResume()
                resumeOnGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onVolume(DUCK_PERCENT)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app's music: stop for good until Play is pressed again.
                held = false
                resumeOnGain = false
                onPause()
            }
        }
    }

    private val request: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onPause()
        }
    }
    private var noisyRegistered = false

    /** A YouTube song started playing. */
    fun acquire() {
        if (!held) {
            held = if (Build.VERSION.SDK_INT >= 26) {
                audio.requestAudioFocus(request!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    /** Switching to the audio-only player (which asks for focus itself), or shutting down. */
    fun release() {
        if (held) {
            if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(request!!)
            else @Suppress("DEPRECATION") audio.abandonAudioFocus(listener)
            held = false
        }
        resumeOnGain = false
        if (noisyRegistered) {
            runCatching { app.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
    }

    private companion object {
        const val DUCK_PERCENT = 30
    }
}
