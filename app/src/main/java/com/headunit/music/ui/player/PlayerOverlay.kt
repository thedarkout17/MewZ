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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.atan2
import kotlin.math.PI
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
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

/** The transport row's width: previous (200) + play (240) + next (200) and two 150 gaps. */
private const val TRANSPORT_W = 200f + 240f + 200f + 2 * 150f

/**
 * Where the lyrics card ends on a screen [width] wide: just short of the
 * previous button (the transport is centred in the column beside the artwork).
 */
private fun lyricsRight(width: Float): Float {
    val colX = ART_FULL_X + ART_FULL + COLUMN_GAP
    val colW = width - colX - SIDE
    return colX + (colW - TRANSPORT_W) / 2f - 16f
}

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
    onOpenQueue: () -> Unit,
    /** The + tile: open the Add To Library page. */
    onOpenAdd: () -> Unit,
    /** That page is open (the + tile shows as on). */
    adding: Boolean,
    /** The lyrics tile: open the Lyrics page. */
    onOpenLyrics: () -> Unit,
    showingLyrics: Boolean,
    /** 0 = artwork, 1 = lyrics in its place (the song title moves over a little to make room). */
    lyricsP: Float,
) {
    val track = player.current ?: return
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
            onClick = onOpenQueue,
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
            // With lyrics showing, keep the song's name clear of the lyrics card.
            val clear = (lyricsRight(width) + 24f - colX).coerceAtLeast(0f) * lyricsP
            Text(
                track.title,
                modifier = Modifier.padding(start = clear.u),
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
                modifier = Modifier.padding(start = clear.u),
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

        // Shuffle · Loop · Like · Add · Lyrics, centred under the bar
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
                onAnimation = { t -> ShuffleGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_repeat,
                activeIcon = R.drawable.ic_repeat_bold,
                contentDescription = stringResource(R.string.loop_song),
                active = player.loopSong,
                onClick = player::toggleLoop,
                onAnimation = { t -> LoopGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_heart,
                activeIcon = R.drawable.ic_heart_filled,
                contentDescription = stringResource(R.string.like),
                active = libraries.isLiked(track),
                onClick = { libraries.toggleLike(track) },
                onAnimation = { t -> HeartGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_add,
                contentDescription = stringResource(R.string.add_to_library),
                active = adding,
                onClick = onOpenAdd,
            )
            GlassTile(
                icon = R.drawable.ic_lyrics,
                activeIcon = R.drawable.ic_lyrics_bold,
                contentDescription = stringResource(R.string.lyrics),
                active = showingLyrics,
                onClick = onOpenLyrics,
            )
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
    /**
     * Played when the tile is tapped on (not off): drawn instead of the icon with
     * progress 0 → 1 (it should look like the icon again at both ends). The glass
     * snaps on once it's finished.
     */
    onAnimation: (@Composable (Float) -> Unit)? = null,
) {
    val anim = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    // Only for taps: e.g. skipping to an already-liked song turns the heart on quietly.
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        val fromTap = tapped
        tapped = false
        if (!active || !fromTap || onAnimation == null) return@LaunchedEffect
        animating = true
        try {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(TILE_ANIM_MS, easing = LinearEasing))
        } finally {
            animating = false
        }
    }
    val lit = active && !animating

    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(30.u)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(190.u)
                .height(116.u)
                .clip(shape)
                .then(
                    if (lit) Modifier.background(PlayerGlass.Tile).border(1.5f.u, PlayerGlass.Edge, shape)
                    else Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = { tapped = true; onClick() },
                ),
        ) {
            if (animating && onAnimation != null) {
                Box(Modifier.size(60.u)) { onAnimation(anim.value) }
            } else {
                Icon(
                    painterResource(if (active) activeIcon else icon),
                    contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(60.u),
                )
            }
        }
    }
}

/** How long a tile animation runs. */
private const val TILE_ANIM_MS = 760

/**
 * The heart, beating once for its animation: a strong "lub" then a softer "dub"
 * (with a little dip between them), then it settles. It stays an outline until
 * the beat is done, then fills in.
 */
@Composable
private fun HeartGlyph(t: Float) {
    fun bump(at: Float, width: Float): Float {
        val x = (t - at) / width
        return exp(-0.5f * x * x)
    }
    // Starts from a slight squeeze, so the first beat has something to push off.
    val scale = 1f -
        0.10f * bump(0.04f, 0.035f) +
        0.34f * bump(0.17f, 0.055f) -
        0.08f * bump(0.29f, 0.04f) +
        0.22f * bump(0.42f, 0.06f)
    Icon(
        // Outline while it beats; it fills in (with the glass) once the beat is done.
        painterResource(R.drawable.ic_heart),
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

/**
 * The loop icon drawn live, for its animation: its two arrows sit on one rounded
 * loop (the icon's outline), and they chase each other once around it, back to
 * where they started. They keep their length, so the gap between each head and
 * the next tail stays the same all the way round. At [p] = 0 and 1 it's
 * the normal icon.
 */
@Composable
private fun LoopGlyph(p: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.minDimension / 24f // the icon's 24x24 grid
        // Regular weight while it moves; the bold icon (and the glass) come in once it's done.
        val stroke = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eased = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f
        val shift = LOOP_PERIMETER * eased
        // Each arrow at rest: tail 3 units up the left side, 17.7 long (the other is half a lap on).
        for (tail0 in floatArrayOf(3f, 3f + LOOP_PERIMETER / 2f)) {
            val head = tail0 + LOOP_ARROW + shift
            val tail = head - LOOP_ARROW
            val n = 60
            val pts = Array(n + 1) { i -> loopPoint(tail + (head - tail) * i / n) }
            val body = Path().apply {
                moveTo(pts[0].x * k, pts[0].y * k)
                for (i in 1..n) lineTo(pts[i].x * k, pts[i].y * k)
            }
            drawPath(body, Color.White, style = stroke)
            val a = pts[n - 2]; val h = pts[n]
            val angle = atan2(h.y - a.y, h.x - a.x)
            val wing = 3.5f
            val arrow = Path().apply {
                moveTo((h.x - wing * cos(angle - PI.toFloat() / 4f)) * k, (h.y - wing * sin(angle - PI.toFloat() / 4f)) * k)
                lineTo(h.x * k, h.y * k)
                lineTo((h.x - wing * cos(angle + PI.toFloat() / 4f)) * k, (h.y - wing * sin(angle + PI.toFloat() / 4f)) * k)
            }
            drawPath(arrow, Color.White, style = stroke)
        }
    }
}

/**
 * The loop icon's track: a rounded rectangle from (4,7) to (20,17), corner radius 3,
 * walked clockwise starting at (4,14), the bottom of its left side.
 */
private val LOOP_PERIMETER: Float = 2f * 4f + 2f * 10f + 2f * 3f * PI.toFloat() // two sides, top and bottom, four quarter-circles
/** Length of each arrow's line at rest. */
private val LOOP_ARROW: Float = 1f + 1.5f * PI.toFloat() + 12f

/** The point [distance] along the loop track (wraps around). */
private fun loopPoint(distance: Float): Offset {
    val quarter = 1.5f * PI.toFloat() // a quarter-circle of radius 3
    var d = ((distance % LOOP_PERIMETER) + LOOP_PERIMETER) % LOOP_PERIMETER
    fun arc(cx: Float, cy: Float, from: Float): Offset {
        val a = from + (d / quarter) * (PI.toFloat() / 2f)
        return Offset(cx + 3f * cos(a), cy + 3f * sin(a))
    }
    // left side up, top-left corner, top, top-right corner, right side down,
    // bottom-right corner, bottom, bottom-left corner
    if (d <= 4f) return Offset(4f, 14f - d); d -= 4f
    if (d <= quarter) return arc(7f, 10f, PI.toFloat()); d -= quarter
    if (d <= 10f) return Offset(7f + d, 7f); d -= 10f
    if (d <= quarter) return arc(17f, 10f, 1.5f * PI.toFloat()); d -= quarter
    if (d <= 4f) return Offset(20f, 10f + d); d -= 4f
    if (d <= quarter) return arc(17f, 14f, 0f); d -= quarter
    if (d <= 10f) return Offset(17f - d, 17f); d -= 10f
    return arc(7f, 14f, PI.toFloat() / 2f)
}

/**
 * The shuffle icon drawn live, for its animation. The two lines slither like
 * snakes: led by their arrowheads, each one bends across to where the other
 * line was and back, the movement rippling down its body from head to tail,
 * with a small wiggle travelling along it. At [p] = 0 and 1 it's the normal
 * (bold) icon.
 */
@Composable
private fun ShuffleGlyph(p: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.minDimension / 24f // the icon's 24x24 grid
        // Regular weight while it moves; the bold icon (and the glass) come in once it's done.
        val stroke = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Head (u = 1, the arrow) moves first; the tail (u = 0) follows this far behind.
        val lag = 0.55f
        // Wiggle: strongest mid-animation, gone at the start and end.
        val wiggle = 1.1f * sin(PI.toFloat() * p)

        fun smooth(x: Float) = x * x * (3f - 2f * x)

        /** One snake: at rest it runs from [yLeft] to [yRight]; it swings to [yLeft2] → [yRight2] and back. */
        fun snake(yLeft: Float, yRight: Float, yLeft2: Float, yRight2: Float, phase: Float) {
            val n = 48
            val xs = FloatArray(n + 1)
            val ys = FloatArray(n + 1)
            for (i in 0..n) {
                val u = i / n.toFloat()
                val x = 3f + 17f * u
                // This point's own progress: the head is ahead of the tail.
                val q = ((p * (1f + lag)) - lag * (1f - u)).coerceIn(0f, 1f)
                val swap = sin(PI.toFloat() * q) // 0 → 1 (swapped) → 0
                val a = yLeft + (yLeft2 - yLeft) * swap
                val b = yRight + (yRight2 - yRight) * swap
                val bend = smooth(((x - 6.5f) / 11f).coerceIn(0f, 1f))
                val base = a + (b - a) * bend
                // A ripple running from head to tail, fading out at the very tail.
                val ripple = wiggle * u * sin(2f * PI.toFloat() * (1.6f * u - 2.2f * p) + phase)
                xs[i] = x
                ys[i] = base + ripple
            }
            val body = Path().apply {
                moveTo(xs[0] * k, ys[0] * k)
                for (i in 1..n) lineTo(xs[i] * k, ys[i] * k)
            }
            drawPath(body, Color.White, style = stroke)
            // Arrowhead, pointing the way the head is going.
            val angle = atan2(ys[n] - ys[n - 2], xs[n] - xs[n - 2])
            val wing = 3.5f
            val hx = xs[n]; val hy = ys[n]
            val head = Path().apply {
                moveTo((hx - wing * cos(angle - PI.toFloat() / 4f)) * k, (hy - wing * sin(angle - PI.toFloat() / 4f)) * k)
                lineTo(hx * k, hy * k)
                lineTo((hx - wing * cos(angle + PI.toFloat() / 4f)) * k, (hy - wing * sin(angle + PI.toFloat() / 4f)) * k)
            }
            drawPath(head, Color.White, style = stroke)
        }
        // At rest: one line runs top-left (7) → bottom-right (16), the other bottom-left (17) →
        // top-right (7). Each slithers over to where the other was and back, in opposite phase.
        snake(7f, 16f, 17f, 7f, phase = 0f)
        snake(17f, 7f, 7f, 16f, phase = PI.toFloat())
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
