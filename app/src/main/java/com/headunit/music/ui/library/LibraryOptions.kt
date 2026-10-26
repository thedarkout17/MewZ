package com.headunit.music.ui.library

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import com.headunit.music.R
import com.headunit.music.data.Library
import com.headunit.music.data.LibraryStore
import com.headunit.music.data.Track
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale
import kotlin.math.roundToInt

/** A library that was held on the home screen, and where its tile is (screen px). */
data class HeldLibrary(val id: String, val tile: Rect)

private enum class Stage { Menu, Cover, Pick }

/** Delete's soft pink. */
private val DeleteTint = Color(0x40FF5C8A)
private val DeleteText = Color(0xFFFFC2D4)

/**
 * What holding one of your libraries brings up:
 * - **Menu**: Move To Front, Change Cover Songs, Delete Library (pink).
 * - **Change Cover Songs**: the tile's cover grows out of the tile into the
 *   middle of the screen, "Select Song To Replace" above it. Tap one of its
 *   squares and the library's songs appear beside it; pick one and its artwork
 *   takes that spot.
 * A tap on empty space or Back steps back (song list → cover → closed).
 */
@Composable
fun LibraryOptions(
    held: HeldLibrary?,
    libraries: LibraryStore,
    onDelete: (Library) -> Unit,
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
            .background(Color(0x99000000))
            // A tap on empty space steps back (and nothing underneath reacts).
            .pointerInput(Unit) { detectTapGestures(onTap = { back() }) },
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
                    onChangeCover = { if (library.tracks.isNotEmpty()) stage = Stage.Cover },
                    onDelete = { onDelete(library) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // ---- Cover, growing from its tile into the middle ----
            if (grow > 0f) {
                val big = with(density) { COVER_BIG.u.toPx() }
                val centerX = screenW / 2f - aside * screenW * 0.2f
                val centerY = screenH / 2f + with(density) { 40.u.toPx() }
                val target = Rect(centerX - big / 2f, centerY - big / 2f, centerX + big / 2f, centerY + big / 2f)
                val r = lerp(h.tile, target, grow)
                val sizeDp = with(density) { r.width.toDp() }
                val shape = RoundedCornerShape(sizeDp * (72f / LIBRARY_TILE))

                Text(
                    "Select Song To Replace",
                    textAlign = TextAlign.Center,
                    style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.SemiBold, shadow = LibraryTextShadow),
                    modifier = Modifier
                        .offset { IntOffset((r.center.x - screenW / 2f).roundToInt(), (r.top - with(density) { 120.u.toPx() }).roundToInt()) }
                        .fillMaxWidth()
                        .graphicsLayer { alpha = grow },
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
                    Box(Modifier.fillMaxSize().border(sizeDp * (3f / LIBRARY_TILE), LibraryGlass.Edge, shape))
                    CoverCollage(
                        tracks = library.coverTracks,
                        modifier = Modifier.fillMaxSize(),
                        onSlot = { k -> slot = k; stage = Stage.Pick },
                        selected = if (stage == Stage.Pick) slot else null,
                    )
                }
            }

            // ---- Songs to choose from, beside the cover ----
            if (aside > 0f) {
                SongPicker(
                    tracks = library.tracks,
                    current = library.coverTracks.getOrNull(slot),
                    onPick = { track ->
                        libraries.setCover(library.id, slot, track)
                        stage = Stage.Cover
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 64.u, top = 64.u, bottom = 64.u)
                        .graphicsLayer {
                            alpha = aside
                            translationX = (1f - aside) * 80.u.toPx()
                        },
                )
            }
        }
    }
}

private const val COVER_BIG = 760f

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
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(64.u)
        Column(
            modifier = modifier
                .width(900.u)
                .graphicsLayer {
                    this.alpha = alpha
                    val s = 0.94f + 0.06f * alpha
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .background(LibraryGlass.Panel)
                .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x08FFFFFF))))
                .border(2.u, LibraryGlass.Edge, shape)
                // Taps on the panel itself don't close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 32.u),
        ) {
            Text(
                library.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 64.u, end = 64.u, top = 12.u, bottom = 20.u),
            )
            MenuRow(R.drawable.ic_arrow_up, "Move To Front", onClick = onMoveToFront)
            MenuRow(
                R.drawable.ic_music_note, "Change Cover Songs",
                enabled = library.tracks.isNotEmpty(),
                onClick = onChangeCover,
            )
            MenuRow(R.drawable.ic_trash, "Delete Library", danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun MenuRow(icon: Int, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.u, vertical = 6.u)
                .height(140.u)
                .clip(RoundedCornerShape(36.u))
                .then(if (danger) Modifier.background(DeleteTint) else Modifier)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
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
                style = TextStyle(if (danger) DeleteText else Color.White, fontSize = 52.usp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/** The library's songs, to pick one for the chosen cover spot. */
@Composable
private fun SongPicker(tracks: List<Track>, current: Track?, onPick: (Track) -> Unit, modifier: Modifier) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(56.u)
        Column(
            modifier = modifier
                .width(820.u)
                .fillMaxHeight()
                .clip(shape)
                .background(LibraryGlass.Panel)
                .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x08FFFFFF))))
                .border(2.u, LibraryGlass.Edge, shape)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                "Choose a song",
                style = TextStyle(Color.White, fontSize = 56.usp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 56.u, top = 44.u, bottom = 16.u),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.u)) {
                items(tracks, key = { it.videoId }) { track ->
                    val isCurrent = track.videoId == current?.videoId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.u)
                            .then(if (isCurrent) Modifier.background(Color(0x1FFFFFFF)) else Modifier)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = Color.White),
                            ) { onPick(track) }
                            .padding(horizontal = 40.u),
                    ) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(184.u)
                                .height(104.u)
                                .clip(RoundedCornerShape(18.u))
                                .background(Color.White.copy(alpha = 0.10f)),
                        )
                        Spacer(Modifier.width(32.u))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(Color.White, fontSize = 46.usp, fontWeight = FontWeight.Medium),
                            )
                            Text(
                                track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(LibrarySecondary, fontSize = 36.usp),
                            )
                        }
                        if (isCurrent) {
                            Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(52.u))
                        }
                    }
                }
            }
        }
    }
}
