package com.headunit.music.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.headunit.music.core.model.Track
import com.headunit.music.data.LibraryStore
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.components.CenterSquareCrop
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale

/** Height of the mini-player dock (design px). HomeScreen keeps content clear of it. */
const val DOCK_HEIGHT = 280f
/**
 * The player, drawn over everything.
 *
 * **Now Playing** (expanded), laid out like CarPlay's: artwork on the left,
 * song and transport beside it, a seek bar running the full width of the
 * screen (elapsed / remaining time at its ends), and shuffle / loop / add tiles
 * centred underneath. The background is the artwork itself, blurred.
 *
 * **Dock** (minimized): a glass bar along the bottom with the artwork, song and
 * controls. Tap it to open Now Playing.
 *
 * Audio only: there's no video. The artwork square glides between the dock
 * and Now Playing.
 * [dockHidden] slides the dock away (e.g. while our keyboard is up).
 */
@Composable
fun PlayerOverlay(
    player: PlayerController,
    dockHidden: Boolean,
    libraries: LibraryStore,
    /** "New library" in the + list: name one, then the song goes in it. */
    onNewLibrary: (Track) -> Unit,
    /** Tap on empty space or Back on Now Playing: shrink to the dock (the caller also goes back home). */
    onMinimize: () -> Unit = { player.expanded = false },
) {
    val track = player.current ?: return

    val e by animateFloatAsState(
        targetValue = if (player.expanded) 1f else 0f,
        animationSpec = tween(440, easing = FastOutSlowInEasing),
        label = "expand",
    )
    val away by animateFloatAsState(
        targetValue = if (dockHidden && !player.expanded) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "dockAway",
    )
    BackHandler(enabled = player.expanded) { onMinimize() }

    // Pages over Now Playing: the queue, or Add To Library (registered after the
    // player's Back, so Back closes them first).
    var showingQueue by remember { mutableStateOf(false) }
    var showingAdd by remember { mutableStateOf(false) }
    // Lyrics: shown in place of the artwork on Now Playing (not a page of its own).
    var showingLyrics by remember { mutableStateOf(false) }
    fun closePages() { showingQueue = false; showingAdd = false }
    // Minimizing closes them, so Now Playing opens normally next time.
    LaunchedEffect(player.expanded) { if (!player.expanded) { closePages(); showingLyrics = false } }
    BackHandler(enabled = showingQueue || showingAdd) { closePages() }
    val queueP by animateFloatAsState(
        targetValue = if (showingQueue) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "queue",
    )
    val addP by animateFloatAsState(
        targetValue = if (showingAdd) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "addToLibrary",
    )
    val lyricsP by animateFloatAsState(
        targetValue = if (showingLyrics && player.expanded) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyrics",
    )
    // How much a page has taken over the screen (Now Playing fades out under it).
    val q = maxOf(queueP, addP)

    Box(Modifier.fillMaxSize()) {
        if (e > 0f) {
            BlurredArtBackground(
                track = track,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = e }
                    // A tap on empty space minimizes (and nothing underneath reacts).
                    .pointerInput(onMinimize) { detectTapGestures(onTap = { onMinimize() }) },
            )
        }

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                val w = maxWidth / 1.u
                val h = maxHeight / 1.u
                fun lerp(a: Float, b: Float) = a + (b - a) * e
                val dockTop = h - DOCK_HEIGHT
                val awayShift = away * (DOCK_HEIGHT + 40f)
                // Taller/shorter screens than 1200: keep the block centred vertically.
                val shiftY = (h - 1200f) / 2f
                val artTopFull = ART_TOP + shiftY

                // ---- Dock (minimized) ----
                if (e < 1f) {
                    Dock(
                        player = player,
                        width = w,
                        modifier = Modifier
                            .offset(y = dockTop.u)
                            .graphicsLayer {
                                alpha = 1f - e
                                translationY = awayShift.u.toPx()
                            },
                    )
                }

                // ---- Now Playing (expanded) ----
                if (e > 0f && q < 1f) {
                    NowPlaying(
                        player = player, libraries = libraries, width = w, screenHeight = h, shiftY = shiftY,
                        alpha = e * (1f - q), onNewLibrary = onNewLibrary,
                        onOpenQueue = { showingQueue = true },
                        onOpenAdd = { showingAdd = true },
                        adding = showingAdd,
                        onOpenLyrics = { showingLyrics = !showingLyrics },
                        showingLyrics = showingLyrics,
                        lyricsP = lyricsP,
                    )
                }

                // ---- Artwork, with the YouTube player right behind it ----
                val artSize = lerp(ART_MINI, ART_FULL)
                val x = lerp(ART_MINI_X, ART_FULL_X)
                val y = lerp(dockTop + (DOCK_HEIGHT - ART_MINI) / 2f, artTopFull)
                val radius = lerp(44f, 72f)
                val shape = RoundedCornerShape(radius.u)
                Box(
                    modifier = Modifier
                        .offset(x = x.u, y = y.u)
                        .graphicsLayer {
                            translationY = (awayShift * (1f - e)).u.toPx()
                            // A page takes over the screen; lyrics take the artwork's place.
                            alpha = (1f - q) * (1f - lyricsP * e)
                        }
                        .size(artSize.u)
                        .clip(shape)
                        .background(Color.Black)
                        .border(1.5f.u, PlayerGlass.Edge, shape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (!player.expanded) player.expanded = true },
                ) {
                    run {
                        val context = LocalContext.current
                        val artRequest = remember(track.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(track.artworkUrl)
                                // Work from the full-size download, then cut an exact
                                // 720x720 square from the dead centre (bars skipped).
                                .size(coil.size.Size.ORIGINAL)
                                .transformations(CenterSquareCrop(letterboxed = track.artworkLetterboxed))
                                .allowHardware(false)
                                .build()
                        }
                        AsyncImage(
                            model = artRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ArtOverlay(player)
                }

                // ---- Lyrics, in the artwork's place: from its left edge across to the
                // previous button, over the artwork's height ----
                if (lyricsP > 0f) {
                    LyricsPanel(
                        player = player,
                        track = track,
                        modifier = Modifier
                            .offset(x = ART_FULL_X.u, y = artTopFull.u)
                            .width((lyricsRight(w) - ART_FULL_X).u)
                            .height(ART_FULL.u)
                            .graphicsLayer {
                                alpha = lyricsP * e * (1f - q)
                                val s = 0.96f + 0.04f * lyricsP
                                scaleX = s; scaleY = s
                            },
                    )
                }

                // ---- Pages, over everything (Now Playing's backdrop stays behind them) ----
                if (queueP > 0f) {
                    QueuePage(
                        player = player,
                        onClose = { showingQueue = false },
                        modifier = Modifier.graphicsLayer {
                            alpha = queueP * e
                            translationY = (1f - queueP) * 48.u.toPx()
                        },
                    )
                }
                if (addP > 0f) {
                    AddToLibraryPage(
                        track = track,
                        libraries = libraries,
                        onNewLibrary = {
                            showingAdd = false
                            onNewLibrary(track)
                        },
                        onClose = { showingAdd = false },
                        modifier = Modifier.graphicsLayer {
                            alpha = addP * e
                            translationY = (1f - addP) * 48.u.toPx()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Full-screen backdrop made from the song's artwork. It's loaded tiny (24x14 px)
 * and stretched, which blurs it on every Android version; Android 12+ adds a
 * real blur on top so it's silky. A dark wash keeps the white text readable.
 */
@Composable
internal fun BlurredArtBackground(track: Track, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(track.thumbnailUrl) {
        // Thumbnail at 24x14: stretched, it's soft everywhere (as blurred as the mini player used to be).
        ImageRequest.Builder(context).data(track.thumbnailUrl).size(24, 14).build()
    }
    Box(modifier.background(Color(0xFF1A0508))) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Slight zoom hides the soft edges the blur leaves.
                    scaleX = 1.25f; scaleY = 1.25f
                    if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(90f, 90f, TileMode.Clamp)
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x47000000), Color(0x80000000)))),
        )
    }
}

/** Buffering spinner or YouTube's refusal message, over the artwork. */
@Composable
internal fun ArtOverlay(player: PlayerController) {
    with(LocalDesignScale.current) {
        val error = player.error
        when {
            error != null -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).padding(32.u),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.u)) {
                    Text(
                        error.message,
                        textAlign = TextAlign.Center,
                        style = TextStyle(Color.White, fontSize = 40.usp, fontWeight = FontWeight.Medium),
                    )
                    if (error.skippable && player.hasNext) {
                        Text("Skipping to the next song…", style = TextStyle(PlayerSecondary, fontSize = 32.usp))
                    }
                }
            }
            player.isBuffering && !player.isPlaying -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 5.u, modifier = Modifier.size(72.u))
            }
        }
    }
}
