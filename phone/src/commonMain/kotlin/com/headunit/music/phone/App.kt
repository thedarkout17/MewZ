package com.headunit.music.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track
import com.headunit.music.phone.data.ApiKey
import com.headunit.music.phone.data.LibraryStore
import com.headunit.music.phone.data.SearchHistory
import com.headunit.music.phone.data.YouTubeSearch
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.res.key_save
import com.headunit.music.phone.res.rename_library
import com.headunit.music.phone.ui.components.BackDispatcher
import com.headunit.music.phone.ui.components.LocalBackDispatcher
import com.headunit.music.phone.ui.components.edgeSwipeBack
import com.headunit.music.phone.ui.home.HomeScreen
import com.headunit.music.phone.ui.library.DeleteLibraryDialog
import com.headunit.music.phone.ui.library.HeldLibrary
import com.headunit.music.phone.ui.library.LibraryOptions
import com.headunit.music.phone.ui.library.NewLibrarySheet
import com.headunit.music.phone.ui.player.DOCK_HEIGHT
import com.headunit.music.phone.ui.player.PlayerOverlay
import com.headunit.music.phone.ui.theme.DESIGN_WIDTH
import com.headunit.music.phone.ui.theme.HeadunitMusicTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The app's single long-lived pieces: one player and one library store, like
 * the headunit's (they outlive any screen). Created once, on first use.
 */
object AppState {
    val player by lazy { PlayerController() }
    val libraries by lazy { LibraryStore() }
}

/**
 * The whole iPhone app: the iPhone's version of the headunit's MainActivity,
 * with the same screens on top of each other (home, player, sheets, menus).
 */
@Composable
fun App() {
    val player = AppState.player
    val libraries = AppState.libraries
    LaunchedEffect(Unit) {
        // History: songs that played for 3 seconds or more.
        player.onPlayedLongEnough = libraries::recordPlayed
    }
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    HeadunitMusicTheme {
        val backs = remember { BackDispatcher() }
        val toast = remember { ToastState() }
        val youtube = remember { YouTubeSearch(apiKey = { ApiKey.current }) }
        val history = remember { SearchHistory() }
        var keyboardUp by remember { mutableStateOf(false) }
        var goHomeRequests by remember { mutableIntStateOf(0) }
        var naming by remember { mutableStateOf(false) }
        var namingFor by remember { mutableStateOf<Track?>(null) }
        var held by remember { mutableStateOf<HeldLibrary?>(null) }
        var deleting by remember { mutableStateOf<Library?>(null) }
        var renaming by remember { mutableStateOf<Library?>(null) }
        var lastRenaming by remember { mutableStateOf<Library?>(null) }

        // Content stays clear of the dock, which sits above the home indicator.
        val density = LocalDensity.current
        val bottomInsetDp = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
        CompositionLocalProvider(LocalBackDispatcher provides backs) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFC4001A))
                    .edgeSwipeBack(backs),
            ) {
                // The safe area at the bottom, in design px (1 design px = width / DESIGN_WIDTH).
                val bottomInsetU = bottomInsetDp / (maxWidth / DESIGN_WIDTH)

                HomeScreen(
                    youtube = youtube,
                    history = history,
                    libraries = libraries,
                    // A search result plays followed by its YouTube Mix; libraries play their own songs, loop, and show their name.
                    onPlay = { tracks, index, collection ->
                        if (collection == null) player.playWithMix(tracks[index])
                        else player.playQueue(tracks, index, name = collection, wrap = true)
                    },
                    onCreateLibrary = { namingFor = null; naming = true },
                    onHoldLibrary = { lib, tile -> held = HeldLibrary(lib.id, tile) },
                    onQueue = { track ->
                        toast.show(if (player.addToQueue(track)) "Added to queue: ${track.title}" else "Playing ${track.title}")
                    },
                    dockInset = if (player.current != null) DOCK_HEIGHT + bottomInsetU else bottomInsetU,
                    onKeyboardShownChange = { keyboardUp = it },
                    goHomeRequests = goHomeRequests,
                )
                PlayerOverlay(
                    player = player,
                    dockHidden = keyboardUp || naming,
                    libraries = libraries,
                    onNewLibrary = { track -> namingFor = track; naming = true },
                    onMinimize = {
                        player.expanded = false
                        goHomeRequests++
                    },
                )
                NewLibrarySheet(
                    visible = renaming != null,
                    initialName = (renaming ?: lastRenaming)?.name.orEmpty(),
                    title = stringResource(Res.string.rename_library),
                    actionLabel = stringResource(Res.string.key_save),
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
                ToastHost(toast, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** A short message at the top of the screen (Android's Toast, which iOS doesn't have). */
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    fun show(text: String) {
        message = text
        serial++
    }

    internal fun clear() { message = null }
}

@Composable
private fun ToastHost(state: ToastState, modifier: Modifier) {
    LaunchedEffect(state.serial) {
        if (state.message != null) {
            delay(2_000)
            state.clear()
        }
    }
    AnimatedVisibility(
        visible = state.message != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(
            Modifier
                .background(Color(0xE6202020), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                state.message.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White, fontSize = 15.sp),
            )
        }
    }
}
