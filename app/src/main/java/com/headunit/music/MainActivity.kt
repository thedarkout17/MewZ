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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.headunit.music.data.SearchHistory
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.player.DOCK_HEIGHT
import com.headunit.music.ui.player.PlayerOverlay
import com.headunit.music.data.youtube.YouTubeSearch
import com.headunit.music.ui.home.HomeScreen
import com.headunit.music.ui.theme.HeadunitMusicTheme

class MainActivity : ComponentActivity() {

    /** Lives as long as the activity so music keeps going between screens. */
    private lateinit var player: PlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()
        player = PlayerController(this)

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

                val youtube = remember { YouTubeSearch(BuildConfig.YOUTUBE_API_KEY) }
                val history = remember { SearchHistory(applicationContext) }
                var keyboardUp by remember { mutableStateOf(false) }

                Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        youtube = youtube,
                        history = history,
                        onPlay = { tracks, index -> player.playQueue(tracks, index) },
                        dockInset = if (player.current != null) DOCK_HEIGHT else 0f,
                        onKeyboardShownChange = { keyboardUp = it },
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
                    // Now Playing / mini player, over everything.
                    PlayerOverlay(player = player, dockHidden = keyboardUp)
                }
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
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
