package com.headunit.music.phone.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

import org.jetbrains.compose.resources.DrawableResource
import com.headunit.music.phone.ui.components.BackHandler
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track
import com.headunit.music.phone.data.LibraryStore
import com.headunit.music.phone.ui.components.FrostedCard
import com.headunit.music.phone.ui.components.Glass
import com.headunit.music.phone.ui.components.GlassOption
import com.headunit.music.phone.ui.components.GlassOptionDanger
import com.headunit.music.phone.ui.components.GlassOptionDangerEdge
import com.headunit.music.phone.ui.components.GlassPane
import com.headunit.music.phone.ui.components.fadeUnderHeader
import com.headunit.music.phone.ui.components.rememberHeaderCollapse
import com.headunit.music.phone.ui.home.TrackRow
import com.headunit.music.phone.ui.theme.DesignCanvas
import com.headunit.music.phone.ui.theme.LocalDesignScale
import com.headunit.music.phone.ui.theme.SecondaryText
import com.headunit.music.phone.ui.theme.TextShadow
import kotlin.math.roundToInt

/** A library that was held on the home screen, and where its tile is (screen px). */
data class HeldLibrary(val id: String, val tile: Rect)

private enum class Stage { Menu, Cover, Pick }

/** Delete's soft pink. */
private val DeleteText = Color(0xFFFFC2D4)

/**
 * What holding one of your libraries brings up:
 * - **Menu**: Move To Front, Rename Library, Change Cover Songs, Delete Library (pink).
 * - **Change Cover Songs**: the tile's cover grows out of the tile into the
 *   middle of the screen, "Select Song To Replace" above it. Tap one of its
 *   squares: the cover moves to the right and the library's songs fill the rest
 *   of the screen like the queue; pick one and its artwork takes that spot.
 * A tap on empty space or Back steps back (song list → cover → closed).
 */
@Composable
fun LibraryOptions(
    held: HeldLibrary?,
    libraries: LibraryStore,
    onDelete: (Library) -> Unit,
    /** Rename Library: the caller shows the naming sheet. */
    onRename: (Library) -> Unit,
    onClose: () -> Unit,
) {
    // Keep the last one while everything fades / shrinks away.
    var last by remember { mutableStateOf<HeldLibrary?>(null) }
    var stage by remember { mutableStateOf(Stage.Menu) }
    var slot by remember { mutableStateOf(0) }
    LaunchedEffect(held) {
        if (held != null) {
            last = held
            stage = Stage.Menu
        }
    }
    val open = held != null
    val p by animateFloatAsState(if (open) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "options")
    // 0 = cover sitting on its tile, 1 = big in the middle.
    val grow by animateFloatAsState(
        if (open && stage != Stage.Menu) 1f else 0f,
        tween(420, easing = FastOutSlowInEasing),
        label = "coverGrow",
    )
    // 0 = cover centred, 1 = moved left to make room for the song list.
    val aside by animateFloatAsState(
        if (open && stage == Stage.Pick) 1f else 0f,
        tween(360, easing = FastOutSlowInEasing),
        label = "coverAside",
    )
    val h = held ?: last ?: return
    if (p == 0f && grow == 0f) return
    val library = libraries.byId(h.id) ?: return

    val close by rememberUpdatedState(onClose)
    fun back() {
        when (stage) {
            Stage.Pick -> stage = Stage.Cover
            else -> close()
        }
    }
    BackHandler(enabled = open) { back() }

    DesignCanvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p }
            // Darker behind the song list so it reads like a page of its own.
            .background(Color.Black.copy(alpha = 0.6f + 0.25f * aside))
            // A tap on empty space steps back (and nothing underneath reacts).
            .pointerInput(Unit) { detectTapGestures(onTap = { back() }) }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        with(LocalDesignScale.current) {
            val density = LocalDensity.current
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()

            // ---- Menu ----
            val menuP = p * (1f - grow)
            if (menuP > 0.01f) {
                MenuPanel(
                    library = library,
                    alpha = menuP,
                    onMoveToFront = { libraries.touch(library.id); close() },
                    onRename = { onRename(library) },
                    onChangeCover = { if (library.tracks.isNotEmpty()) stage = Stage.Cover },
                    onDelete = { onDelete(library) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // ---- Cover, growing from its tile into the middle ----
            if (grow > 0f) {
                // Big in the middle; while choosing a song, smaller at the top so it stays
                // in view above the list.
                val big = with(density) { (COVER_BIG + (COVER_SIDE - COVER_BIG) * aside).u.toPx() }
                val topY = with(density) { (COVER_TOP + COVER_SIDE / 2f).u.toPx() }
                val centerX = screenW / 2f
                val centerY = screenH / 2f + (topY - screenH / 2f) * aside
                val target = Rect(centerX - big / 2f, centerY - big / 2f, centerX + big / 2f, centerY + big / 2f)
                val r = lerp(h.tile, target, grow)
                val sizeDp = with(density) { r.width.toDp() }
                val shape = RoundedCornerShape(sizeDp * (72f / LIBRARY_TILE))

                Text(
                    "Select Song To Replace",
                    textAlign = TextAlign.Center,
                    style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.SemiBold, shadow = TextShadow),
                    modifier = Modifier
                        .offset { IntOffset((r.center.x - screenW / 2f).roundToInt(), (r.top - with(density) { 120.u.toPx() }).roundToInt()) }
                        .fillMaxWidth()
                        .graphicsLayer { alpha = grow * (1f - aside) },
                )
                Box(
                    Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(sizeDp)
                        .clip(shape)
                        // Taps between the squares don't step back.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    GlassPane(Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().border(sizeDp * (3f / LIBRARY_TILE), Glass.Edge, shape))
                    CoverCollage(
                        tracks = library.coverTracks,
                        modifier = Modifier.fillMaxSize(),
                        onSlot = { k -> slot = k; stage = Stage.Pick },
                        selected = if (stage == Stage.Pick) slot else null,
                    )
                }
            }

            // ---- Songs to choose from: a page like the queue, under the cover ----
            if (aside > 0f) {
                SongPickerPage(
                    tracks = library.tracks,
                    current = library.coverTracks.getOrNull(slot),
                    onPick = { track ->
                        libraries.setCover(library.id, slot, track)
                        stage = Stage.Cover
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = (COVER_TOP + COVER_SIDE + 10f).u)
                        .graphicsLayer {
                            alpha = aside
                            translationY = (1f - aside) * 80.u.toPx()
                        },
                )
            }
        }
    }
}

private const val COVER_BIG = 1000f
/** The cover's size, and its top, while the song list is open (under it). */
private const val COVER_SIDE = 520f
private const val COVER_TOP = 60f

// Song list header (design px), like the queue's: big title shrinking as you scroll.
private const val PICK_HEADER_TALL = 340f
private const val PICK_HEADER_SHORT = 170f

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

@Composable
private fun MenuPanel(
    library: Library,
    alpha: Float,
    onMoveToFront: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(64.u)
        FrostedCard(
            modifier = modifier
                .width(1260.u)
                .graphicsLayer {
                    this.alpha = alpha
                    val s = 0.94f + 0.06f * alpha
                    scaleX = s; scaleY = s
                }
                // Taps on the panel itself don't close it.
                .pointerInput(Unit) { detectTapGestures { } },
            shape = shape,
        ) {
            Text(
                library.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.SemiBold, shadow = TextShadow),
                modifier = Modifier.padding(start = 64.u, end = 64.u, top = 52.u, bottom = 24.u),
            )
            MenuRow(Res.drawable.ic_arrow_up, "Move To Front", onClick = onMoveToFront)
            MenuRow(Res.drawable.ic_edit, "Rename Library", onClick = onRename)
            MenuRow(
                Res.drawable.ic_music_note, "Change Cover Songs",
                enabled = library.tracks.isNotEmpty(),
                onClick = onChangeCover,
            )
            MenuRow(Res.drawable.ic_trash, "Delete Library", danger = true, onClick = onDelete)
            Spacer(Modifier.height(32.u))
        }
    }
}

@Composable
private fun MenuRow(icon: DrawableResource, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.u, vertical = 8.u)
                .height(136.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .clip(RoundedCornerShape(40.u))
                // Each option is its own pane of glass; Delete's has a pink hue.
                .background(if (danger) GlassOptionDanger else GlassOption)
                .border(1.5f.u, if (danger) GlassOptionDangerEdge else Glass.Edge, RoundedCornerShape(40.u))
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(horizontal = 40.u),
        ) {
            Icon(painterResource(icon), null, tint = if (danger) DeleteText else Color.White, modifier = Modifier.size(56.u))
            Spacer(Modifier.width(36.u))
            Text(
                text,
                style = TextStyle(if (danger) DeleteText else Color.White, fontSize = 52.usp, fontWeight = FontWeight.Medium, shadow = TextShadow),
            )
        }
    }
}

/**
 * The library's songs, laid out like the queue: a big "Choose A Song" that
 * shrinks as you scroll, then the songs. The one in the chosen spot now is
 * highlighted. Tap one to put its artwork there.
 */
@Composable
private fun SongPickerPage(tracks: List<Track>, current: Track?, onPick: (Track) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    with(LocalDesignScale.current) {
        val c by rememberHeaderCollapse(list, PICK_HEADER_TALL, PICK_HEADER_SHORT)
        val headerH = PICK_HEADER_TALL + (PICK_HEADER_SHORT - PICK_HEADER_TALL) * c
        Box(modifier) {
            LazyColumn(
                state = list,
                verticalArrangement = Arrangement.spacedBy(20.u),
                contentPadding = PaddingValues(top = PICK_HEADER_TALL.u, bottom = 64.u, start = 60.u, end = 60.u),
                modifier = Modifier
                    .fillMaxSize()
                    // Rows dissolve as they slide up under the title.
                    .fadeUnderHeader(headerH.u, 30.u),
            ) {
                itemsIndexed(tracks, key = { _, t -> t.videoId }) { i, track ->
                    val isCurrent = track.videoId == current?.videoId
                    TrackRow(
                        track = track,
                        index = i,
                        onClick = { onPick(track) },
                        label = if (isCurrent) "On cover" else null,
                        highlighted = isCurrent,
                        showDuration = false,
                    )
                }
            }
            Column(Modifier.offset(x = 96.u, y = (110f + (40f - 110f) * c).u)) {
                Text(
                    "Choose A Song",
                    maxLines = 1,
                    style = TextStyle(
                        Color.White,
                        fontSize = (132f + (76f - 132f) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = TextShadow,
                    ),
                )
                Text(
                    "Its artwork replaces the outlined square",
                    style = TextStyle(SecondaryText, fontSize = 46.usp, shadow = TextShadow),
                    modifier = Modifier.graphicsLayer { alpha = 1f - c },
                )
            }
        }
    }
}
