package com.headunit.music.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.headunit.music.R
import com.headunit.music.data.LibraryStore
import com.headunit.music.ui.library.AddToLibraryPanel
import com.headunit.music.data.Track
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.components.CenterSquareCrop
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale

/** Height of the mini-player dock (design px). HomeScreen keeps content clear of it. */
const val DOCK_HEIGHT = 280f

private val TextShadow = Shadow(Color(0x66000000), Offset(0f, 2f), blurRadius = 10f)
private val Secondary = Color.White.copy(alpha = 0.78f)
private val Tertiary = Color.White.copy(alpha = 0.62f)

private object PlayerGlass {
    /** Dock artwork wash: ~12% brighter, plus a lift so dark covers don't go muddy. */
    val DockBrighten = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 22f,
                0f, 1.12f, 0f, 0f, 22f,
                0f, 0f, 1.12f, 0f, 22f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    /** Light along the top of the glass, fading quickly, with a faint lift at the bottom. */
    val DockSheen = Brush.verticalGradient(
        0f to Color(0x38FFFFFF), 0.35f to Color(0x0DFFFFFF), 0.85f to Color(0x05FFFFFF), 1f to Color(0x12FFFFFF),
    )
    /** Diagonal glint, like the library tiles. */
    val DockGlint = Brush.linearGradient(
        0f to Color(0x00FFFFFF), 0.45f to Color(0x00FFFFFF), 0.5f to Color(0x14FFFFFF), 0.55f to Color(0x00FFFFFF),
    )
    val Edge = Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF)))
    val Tile = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))
}

// ---- Layout (design px on the 2000x1200 canvas), after the CarPlay Now Playing ----
//   ⌄                                         “starboy” — 3 of 20     ← y 48–178
//   ┌──────────┐        Song title
//   │ artwork  │        Artist
//   │  600×600 │      ⏮     ⏸     ⏭                                  ← top row y 210–810
//   └──────────┘
//   0:12 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ -2:48    ← full width, y ~900
//                     [shuffle]  [loop]  [ + ]                         ← y ~1010
private const val SIDE = 96f            // left/right margin; the seek bar spans between them
private const val ART_FULL = 600f       // artwork square on Now Playing
private const val ART_FULL_X = SIDE
private const val ART_TOP = 210f
private const val COLUMN_GAP = 96f      // artwork → title/transport column
private const val SEEK_TOP = 862f
private const val TILES_TOP = 1006f
private const val ART_MINI = 224f       // artwork square in the dock
private const val ART_MINI_X = 56f

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
                if (e > 0f) {
                    NowPlaying(
                        player = player, libraries = libraries, width = w, screenHeight = h, shiftY = shiftY,
                        alpha = e, onNewLibrary = onNewLibrary,
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
                        .graphicsLayer { translationY = (awayShift * (1f - e)).u.toPx() }
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
            }
        }
    }
}

/**
 * Full-screen backdrop made from the song's artwork. It's loaded tiny (48x27 px)
 * and stretched, which blurs it on every Android version; Android 12+ adds a
 * real blur on top so it's silky. A dark wash keeps the white text readable.
 */
@Composable
private fun BlurredArtBackground(track: Track, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(track.thumbnailUrl) {
        // medium/high thumbnail at 48x27: plenty for a blur, and stretched it's soft everywhere.
        ImageRequest.Builder(context).data(track.thumbnailUrl).size(48, 27).build()
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
                    scaleX = 1.15f; scaleY = 1.15f
                    if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(60f, 60f, TileMode.Clamp)
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
private fun ArtOverlay(player: PlayerController) {
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
                        Text("Skipping to the next song…", style = TextStyle(Secondary, fontSize = 32.usp))
                    }
                }
            }
            player.isBuffering && !player.isPlaying -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 5.u, modifier = Modifier.size(72.u))
            }
        }
    }
}

@Composable
private fun NowPlaying(
    player: PlayerController,
    libraries: LibraryStore,
    width: Float,
    screenHeight: Float,
    shiftY: Float,
    alpha: Float,
    onNewLibrary: (Track) -> Unit,
) {
    val track = player.current ?: return
    var picking by remember { mutableStateOf(false) }
    var showingQueue by remember { mutableStateOf(false) }
    // Back closes the queue first (registered after the player's own Back, so it wins).
    BackHandler(enabled = showingQueue) { showingQueue = false }
    val queueIn by animateFloatAsState(
        targetValue = if (showingQueue) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "queue",
    )
    with(LocalDesignScale.current) {
        val colX = ART_FULL_X + ART_FULL + COLUMN_GAP
        val colW = width - colX - SIDE
        val fullW = width - 2 * SIDE

        // (No minimize button: a tap on empty space, or Back, minimizes.)

        // Queue (top-right).
        GlassCircleButton(
            icon = R.drawable.ic_queue,
            contentDescription = stringResource(R.string.queue),
            size = 130f, glyph = 64f,
            onClick = { picking = false; showingQueue = !showingQueue },
            modifier = Modifier
                .offset(x = (width - SIDE - 130f).u, y = (48f + shiftY).u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Where we are in a library / playlist, left of the queue button: "Road trip — 3 of 20".
        // Nothing for search results, which aren't a collection.
        player.queueName?.let { name ->
            Text(
                text = "$name — ${player.index + 1} of ${player.queue.size}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                style = TextStyle(Tertiary, fontSize = 44.usp, shadow = TextShadow),
                modifier = Modifier
                    .offset(x = (width / 2f).u, y = (82f + shiftY).u)
                    .width((width / 2f - SIDE - 130f - 40f).u)
                    .graphicsLayer { this.alpha = alpha },
            )
        }

        // Beside the artwork: song, then transport, spread over the artwork's height
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x = colX.u, y = (ART_TOP + shiftY).u)
                .width(colW.u)
                .height(ART_FULL.u)
                .graphicsLayer {
                    this.alpha = alpha
                    translationX = (1f - alpha) * 60.u.toPx()
                },
        ) {
            Spacer(Modifier.height(10.u))
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    Color.White, fontSize = 88.usp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 100.usp, shadow = TextShadow,
                ),
            )
            Spacer(Modifier.height(14.u))
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(Secondary, fontSize = 56.usp, shadow = TextShadow),
            )

            Spacer(Modifier.weight(1f))

            // Transport: bare white glyphs (big touch areas around them)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(150.u, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlyphButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_song), 200f, 140f, onClick = player::previous)
                GlyphButton(
                    if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                    240f, 172f, onClick = player::togglePlay,
                )
                GlyphButton(
                    R.drawable.ic_skip_next, stringResource(R.string.next_song), 200f, 140f,
                    enabled = player.hasNext, onClick = player::next,
                )
            }
            // Lifts the transport a little off the artwork's bottom edge.
            Spacer(Modifier.height(60.u))
        }

        // Seek bar across the whole screen, margin to margin
        SeekBar(
            player,
            Modifier
                .offset(x = SIDE.u, y = (SEEK_TOP + shiftY).u)
                .width(fullW.u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Shuffle · Loop · Like · Add, centred under the bar
        Row(
            horizontalArrangement = Arrangement.spacedBy(48.u, Alignment.CenterHorizontally),
            modifier = Modifier
                .offset(y = (TILES_TOP + shiftY).u)
                .width(width.u)
                .graphicsLayer { this.alpha = alpha },
        ) {
            GlassTile(
                icon = R.drawable.ic_shuffle,
                activeIcon = R.drawable.ic_shuffle_bold,
                contentDescription = stringResource(R.string.shuffle),
                active = player.shuffle,
                onClick = player::toggleShuffle,
            )
            GlassTile(
                icon = R.drawable.ic_repeat,
                activeIcon = R.drawable.ic_repeat_bold,
                contentDescription = stringResource(R.string.loop_song),
                active = player.loopSong,
                onClick = player::toggleLoop,
            )
            GlassTile(
                icon = R.drawable.ic_heart,
                activeIcon = R.drawable.ic_heart_filled,
                contentDescription = stringResource(R.string.like),
                active = libraries.isLiked(track),
                onClick = { libraries.toggleLike(track) },
            )
            GlassTile(
                icon = R.drawable.ic_add,
                contentDescription = stringResource(R.string.add_to_library),
                active = picking,
                onClick = { picking = !picking },
            )
        }

        // + list: your libraries, just above the tiles. A tap anywhere else closes it.
        if (picking) {
            Box(
                contentAlignment = Alignment.BottomStart,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { picking = false }
                    // Just above the tiles, starting right of the artwork so it isn't covered.
                    .padding(
                        start = (ART_FULL_X + ART_FULL + 64f).u,
                        bottom = (screenHeight - (TILES_TOP - 28f + shiftY)).u,
                    )
                    .graphicsLayer { this.alpha = alpha },
            ) {
                AddToLibraryPanel(
                    libraries = libraries.custom,
                    contains = { libraries.contains(it.id, track) },
                    onPick = { lib ->
                        // Ticked ones come back out; otherwise in it goes (and it's liked too).
                        if (libraries.contains(lib.id, track)) {
                            libraries.removeFrom(lib.id, track)
                        } else {
                            libraries.addTo(lib.id, track)
                            picking = false
                        }
                    },
                    onNewLibrary = {
                        picking = false
                        onNewLibrary(track)
                    },
                    // Taps on the panel itself shouldn't close it.
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { },
                )
            }
        }

        // Queue sheet, sliding in from the right. A tap anywhere else closes it.
        if (queueIn > 0f) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showingQueue = false }
                    .padding(top = 40.u, bottom = 40.u, end = 40.u),
            ) {
                QueuePanel(
                    player = player,
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = queueIn
                            translationX = (1f - queueIn) * (QUEUE_PANEL_WIDTH + 40f).u.toPx()
                        }
                        // Taps on the sheet itself shouldn't close it.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                )
            }
        }
    }
}

@Composable
private fun Dock(player: PlayerController, width: Float, modifier: Modifier) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(topStart = 56.u, topEnd = 56.u)
        Box(
            modifier = modifier
                .width(width.u)
                .height(DOCK_HEIGHT.u)
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { player.expanded = true },
                ),
        ) {
            // Glass made from the song's artwork: loaded tiny and stretched (soft on
            // every Android version), heavily blurred on 12+, brightened, ~95% opaque.
            val context = LocalContext.current
            val artRequest = remember(track.thumbnailUrl) {
                ImageRequest.Builder(context).data(track.thumbnailUrl).size(24, 14).build()
            }
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                colorFilter = PlayerGlass.DockBrighten,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.95f
                        // Zoom hides the soft edges the blur leaves.
                        scaleX = 1.25f; scaleY = 1.25f
                        if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(90f, 90f, TileMode.Clamp)
                    },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(PlayerGlass.DockSheen)
                    .background(PlayerGlass.DockGlint)
                    .border(2.u, PlayerGlass.Edge, shape),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    // Artwork sits at 56..280; text starts after it.
                    .padding(start = (ART_MINI_X + ART_MINI + 48f).u, end = 64.u),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.u)) {
                    Text(
                        track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.Medium, shadow = TextShadow),
                    )
                    Text(
                        track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(Secondary, fontSize = 44.usp, shadow = TextShadow),
                    )
                }
                Spacer(Modifier.width(40.u))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.u)) {
                    GlassCircleButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_song), 124f, 60f, onClick = player::previous)
                    GlassCircleButton(
                        if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                        160f, 80f, onClick = player::togglePlay,
                    )
                    GlassCircleButton(
                        R.drawable.ic_skip_next, stringResource(R.string.next_song), 124f, 60f,
                        enabled = player.hasNext, onClick = player::next,
                    )
                }
            }
        }
    }
}

/** "0:12 ━━━━━━━━━━━ -2:48": elapsed, bar, time remaining. Tap or drag the bar to seek. */
@Composable
private fun SeekBar(player: PlayerController, modifier: Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationSeconds
    val shown = (dragFraction ?: if (duration > 0f) player.positionSeconds / duration else 0f).coerceIn(0f, 1f)

    with(LocalDesignScale.current) {
        val timeStyle = TextStyle(
            Color.White, fontSize = 42.usp, shadow = TextShadow,
            fontFeatureSettings = "tnum", // digits don't jiggle as they change
        )
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown * duration), style = timeStyle, modifier = Modifier.width(120.u))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(80.u) // tall touch target around a thin bar
                    .pointerInput(duration) {
                        detectTapGestures { pos ->
                            if (duration > 0f) player.seekTo((pos.x / size.width).coerceIn(0f, 1f) * duration)
                        }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos -> dragFraction = (pos.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { if (duration > 0f) player.seekTo(it * duration) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                val dragging = dragFraction != null
                val barH = if (dragging) 26f else 20f
                Box(Modifier.fillMaxWidth().height(barH.u).background(Color(0x40FFFFFF), CircleShape))
                Box(Modifier.fillMaxWidth(shown).height(barH.u).background(Color(0xD9FFFFFF), CircleShape))
            }
            Text(
                "-" + formatTime((duration - shown * duration).coerceAtLeast(0f)),
                style = timeStyle,
                textAlign = TextAlign.End,
                modifier = Modifier.width(140.u),
            )
        }
    }
}

/**
 * Shuffle / loop / add. Off: just the icon on the background. On: a frosted
 * glass box around it and a bolder icon ([activeIcon]).
 */
@Composable
private fun GlassTile(
    icon: Int,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    activeIcon: Int = icon,
) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(30.u)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(190.u)
                .height(116.u)
                .clip(shape)
                .then(
                    if (active) Modifier.background(PlayerGlass.Tile).border(1.5f.u, PlayerGlass.Edge, shape)
                    else Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(
                painterResource(if (active) activeIcon else icon),
                contentDescription,
                tint = Color.White,
                modifier = Modifier.size(60.u),
            )
        }
    }
}

/** A bare white glyph with a generous invisible touch area, like the reference design. */
@Composable
private fun GlyphButton(
    icon: Int,
    contentDescription: String,
    target: Float,
    glyph: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(target.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .clip(CircleShape)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(glyph.u))
        }
    }
}

@Composable
private fun GlassCircleButton(
    icon: Int,
    contentDescription: String,
    size: Float,
    glyph: Float,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .clip(CircleShape)
                .then(
                    if (accent) Modifier.background(Brand.Red)
                    else Modifier.background(PlayerGlass.Tile).border(1.5f.u, PlayerGlass.Edge, CircleShape)
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(glyph.u))
        }
    }
}

private fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
