package com.headunit.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.headunit.music.ui.home.HomeScreen
import com.headunit.music.ui.theme.HeadunitMusicTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()

        setContent {
            HeadunitMusicTheme {
                var voiceResult by remember { mutableStateOf<String?>(null) }

                val voiceLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    voiceResult = result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                }

                HomeScreen(
                    onSearch = { query ->
                        // Next step: the results screen (YouTube search) goes here.
                        Toast.makeText(this, "Searching “$query”", Toast.LENGTH_SHORT).show()
                    },
                    onVoiceSearch = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
                        }
                        try {
                            voiceLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this, "No voice recognizer on this headunit", Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    voiceResult = voiceResult,
                    onVoiceResultConsumed = { voiceResult = null },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    /** Full-bleed: hide status/nav bars; swipe from an edge brings them back briefly. */
    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
