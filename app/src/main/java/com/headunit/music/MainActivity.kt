package com.headunit.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import com.headunit.music.player.PlaybackService
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.headunit.music.data.LibraryStore
import com.headunit.music.data.SearchHistory
import com.headunit.music.data.Track
import com.headunit.music.ui.library.NewLibrarySheet
import com.headunit.music.ui.library.DeleteLibraryDialog
import com.headunit.music.ui.library.HeldLibrary
import com.headunit.music.ui.library.LibraryOptions
import com.headunit.music.data.Library
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.player.DOCK_HEIGHT
import com.headunit.music.ui.player.PlayerOverlay
import com.headunit.music.data.youtube.NewPipeDownloader
import com.headunit.music.data.youtube.YouTubeSearch
import com.headunit.music.ui.home.HomeScreen
import com.headunit.music.ui.theme.HeadunitMusicTheme

class MainActivity : ComponentActivity() {

    /** The app's one player: it outlives this screen (see PlaybackService). */
    private lateinit var player: PlayerController
    private lateinit var libraries: LibraryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()
        NewPipeDownloader.init()
        player = PlayerController.get(this)
        libraries = LibraryStore.get(this)
        // Android 13+: allow the media notification (with play / pause / skip) to show.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // History: songs that played for 3 seconds or more.
        player.onPlayedLongEnough = libraries::recordPlayed

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
                var goHomeRequests by remember { mutableIntStateOf(0) }
                // "New library" sheet; the song to put in it when opened from Now Playing's + list.
                var naming by remember { mutableStateOf(false) }
                var namingFor by remember { mutableStateOf<Track?>(null) }
                // A library held on the home screen (its options), and one being confirmed for deleting.
                var held by remember { mutableStateOf<HeldLibrary?>(null) }
                var deleting by remember { mutableStateOf<Library?>(null) }
                // A library being renamed (from the hold menu).
                var renaming by remember { mutableStateOf<Library?>(null) }
                var lastRenaming by remember { mutableStateOf<Library?>(null) }

                // Keep the screen on while music plays (headunits shouldn't dim mid-song).
                LaunchedEffect(player.isPlaying) {
                    if (player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        youtube = youtube,
                        history = history,
                        libraries = libraries,
                        // A search result plays followed by its YouTube Mix (like autoplay);
                        // libraries play their own songs, loop, and show their name.
                        onPlay = { tracks, index, collection ->
                            if (collection == null) player.playWithMix(tracks[index])
                            else player.playQueue(tracks, index, name = collection, wrap = true)
                        },
                        onCreateLibrary = { namingFor = null; naming = true },
                        onHoldLibrary = { lib, tile -> held = HeldLibrary(lib.id, tile) },
                        onQueue = { track ->
                            val msg = if (player.addToQueue(track)) "Added to queue: ${track.title}" else "Playing ${track.title}"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        },
                        dockInset = if (player.current != null) DOCK_HEIGHT else 0f,
                        onKeyboardShownChange = { keyboardUp = it },
                        goHomeRequests = goHomeRequests,
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
                                    this@MainActivity, "No voice recognizer on this headunit", Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        voiceResult = voiceResult,
                        onVoiceResultConsumed = { voiceResult = null },
                    )
                    // Now Playing / mini player, over everything.
                    PlayerOverlay(
                        player = player,
                        dockHidden = keyboardUp || naming,
                        libraries = libraries,
                        onNewLibrary = { track -> namingFor = track; naming = true },
                        // Minimizing goes to the "Listen..." home, with the song in the dock.
                        onMinimize = {
                            player.expanded = false
                            goHomeRequests++
                        },
                    )
                    NewLibrarySheet(
                        visible = renaming != null,
                        // (Keeps the old name on screen while the sheet slides away.)
                        initialName = (renaming ?: lastRenaming)?.name.orEmpty(),
                        title = getString(R.string.rename_library),
                        actionLabel = getString(R.string.key_save),
                        onCreate = { name ->
                            renaming?.let {
                                libraries.rename(it.id, name)
                                player.renameQueue(it.name, name.trim())
                            }
                            renaming = null
                        },
                        onCancel = { renaming = null },
                    )
                    NewLibrarySheet(
                        visible = naming,
                        onCreate = { name ->
                            val lib = libraries.create(name)
                            namingFor?.let { libraries.addTo(lib.id, it) }
                            naming = false
                        },
                        onCancel = { naming = false },
                    )
                    LibraryOptions(
                        held = held,
                        libraries = libraries,
                        onDelete = { lib -> held = null; deleting = lib },
                        onRename = { lib -> held = null; renaming = lib; lastRenaming = lib },
                        onClose = { held = null },
                    )
                    DeleteLibraryDialog(
                        library = deleting,
                        onDelete = { lib ->
                            libraries.delete(lib.id)
                            deleting = null
                        },
                        onCancel = { deleting = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The playback service: keeps music going in the background and lets the steering
        // wheel, Bluetooth and the headunit's media controls reach the player. (Started each
        // time the app comes to the front, in case Android stopped it while idle.)
        startService(Intent(this, PlaybackService::class.java))
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (or it's about to be closed): remember the song and where it's at.
        player.saveState()
    }

    // (No player.release() here: the music carries on in the background through
    // PlaybackService, which releases the player when it shuts down.)

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
