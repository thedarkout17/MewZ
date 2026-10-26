package com.headunit.music.phone.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.core.model.Track
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.ui.components.Glass
import com.headunit.music.phone.ui.components.fadeUnderHeader
import com.headunit.music.phone.ui.components.rememberHeaderCollapse
import com.headunit.music.phone.ui.home.TrackRow
import com.headunit.music.phone.ui.theme.LocalDesignScale
import com.headunit.music.phone.ui.theme.SecondaryText
import com.headunit.music.phone.ui.theme.TextShadow

// Header (design px), the same as an open library: big "Up Next", shrinking as you scroll.
// Taller than a library's, with room for the hint line, so the header is an easy target to tap out on.
private const val HEADER_TALL = 560f
private const val HEADER_SHORT = 236f
private const val TITLE_BIG = 132f
private const val TITLE_SMALL = 76f
/** A row (220) plus the gap under it (20). */
private const val ROW_STEP = 240f
/** The tap-out zone at the top: the header's height times this. */
private const val TAP_OUT_BUFFER = 1.25f

/**
 * The queue, laid out like an open library over Now Playing's blurred artwork:
 * "Up Next" at the top, then the songs. Opens on the song playing now with
 * what's coming below; scroll up for what already played (greyed out, marked
 * "Previous"). Tap a song to play it and go back to Now Playing; double-tap
 * to make it play next (staying here); hold and drag an upcoming song to move
 * it; × takes one out. A tap on empty space (or Back) returns to Now Playing.
 */
@Composable
fun QueuePage(player: PlayerController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val queue = player.queue
    val now = player.index
    val density = LocalDensity.current

    with(LocalDesignScale.current) {
        // Where to put the current song: just under the shrunk header, with the
        // previous song tucked up behind it.
        fun startOf(i: Int): Pair<Int, Int> =
            if (i <= 0) 0 to 0
            else (i - 1) to with(density) { (ROW_STEP - HEADER_SHORT).u.roundToPx() }
        val (firstIndex, firstOffset) = remember { startOf(now) }
        val list = rememberLazyListState(firstIndex, firstOffset)
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(now) {
            // Follow along when the song changes while the queue is open.
            if (opened) startOf(now).let { (i, o) -> list.animateScrollToItem(i, o) } else opened = true
        }

        // Hold-to-drag: which song is being moved, and how far it's been pulled from its slot.
        var dragKey by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val stepPx = with(density) { ROW_STEP.u.toPx() }
        val haptics = LocalHapticFeedback.current

        /** Past halfway into a neighbour's slot: swap with it (repeats if pulled far). */
        fun settleDrag() {
            while (true) {
                val from = keysOf(player.queue).indexOf(dragKey)
                if (from < 0) return
                if (dragOffset > stepPx / 2 && from + 1 < player.queue.size) {
                    player.moveInQueue(from, from + 1)
                    dragOffset -= stepPx
                } else if (dragOffset < -stepPx / 2 && from - 1 > player.index) {
                    player.moveInQueue(from, from - 1)
                    dragOffset += stepPx
                } else return
            }
        }

        val c by rememberHeaderCollapse(list, HEADER_TALL, HEADER_SHORT)
        val headerH = HEADER_TALL + (HEADER_SHORT - HEADER_TALL) * c
        // Taps this close to the top count as "tap out", even on a song sliding under the
        // header: the header plus a quarter of its height again, so a stab at the top to
        // leave doesn't play a previous song by accident.
        val tapOutPx by rememberUpdatedState(with(density) { (headerH * TAP_OUT_BUFFER).u.toPx() })
        /** Where [localY] in row [key] sits, measured from the top of the page (px); null if not laid out. */
        fun pageY(key: String, localY: Float): Float? {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
            return item.offset - info.viewportStartOffset + localY
        }

        // A tap on empty space (not a song or button) goes back to Now Playing.
        Box(modifier.fillMaxSize().pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) }) {
            LazyColumn(
                state = list,
                verticalArrangement = Arrangement.spacedBy(20.u),
                contentPadding = PaddingValues(top = HEADER_TALL.u, bottom = 64.u, start = 80.u, end = 80.u),
                modifier = Modifier
                    .fillMaxSize()
                    // Rows dissolve as they slide up under the title.
                    .fadeUnderHeader(headerH.u, 30.u),
            ) {
                val keys = keysOf(queue)
                items(queue.indices.toList(), key = { i -> keys[i] }) { i ->
                    val track = queue[i]
                    val held = i > now && player.isHeldQueued(track)
                    val key = keys[i]
                    val dragging = dragKey == key
                    val canDrag = i > now // songs still to come can be rearranged
                    val at by rememberUpdatedState(i)
                    Box(
                        Modifier
                            .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                            .graphicsLayer {
                                if (dragging) {
                                    translationY = dragOffset
                                    scaleX = 1.03f; scaleY = 1.03f
                                    shadowElevation = 24.u.toPx()
                                    shape = RoundedCornerShape(28.u)
                                    clip = false
                                }
                            },
                    ) {
                    TrackRow(
                        track = track,
                        index = i - now, // only the first screenful from "now" staggers in
                        onClick = {},
                        gestures = Modifier
                            // Tap: play it and go back to Now Playing. Double-tap: play it next,
                            // staying here. (Hold is the drag below; it stops the tap firing.)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { pos ->
                                        val y = pageY(key, pos.y)
                                        if (y == null || y >= tapOutPx) {
                                            if (at != player.index) player.skipTo(at)
                                        }
                                        onClose()
                                    },
                                    onDoubleTap = { pos ->
                                        val y = pageY(key, pos.y)
                                        if (y != null && y < tapOutPx) {
                                            onClose()
                                            return@detectTapGestures
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        player.playNext(at)
                                    },
                                    onLongPress = { },
                                )
                            }
                            // Hold, then drag up or down to move it.
                            .pointerInput(canDrag) {
                                if (!canDrag) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragKey = key
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        settleDrag()
                                    },
                                    onDragEnd = { dragKey = null; dragOffset = 0f },
                                    onDragCancel = { dragKey = null; dragOffset = 0f },
                                )
                            },
                        label = when {
                            i < now -> "Previous"
                            i == now -> "Now playing"
                            held -> "Queued"
                            else -> null
                        },
                        played = i < now,
                        highlighted = i == now,
                        trailing = if (i > now) ({ RemoveButton { player.removeFromQueue(at) } }) else null,
                    )
                    }
                }
                if (player.autoMix) {
                    item(key = "mix") {
                        Text(
                            "More from the mix keeps coming…",
                            style = TextStyle(SecondaryText, fontSize = 44.usp, shadow = TextShadow),
                            modifier = Modifier.padding(start = 16.u, top = 24.u),
                        )
                    }
                }
            }

            // "Up Next", the gesture hint, and how many are lined up, shrinking into the
            // top-left as you scroll (the count fades; the hint stays).
            Column(Modifier.offset(x = 96.u, y = (226f + (44f - 226f) * c).u)) {
                Text(
                    stringResource(Res.string.up_next),
                    maxLines = 1,
                    style = TextStyle(
                        Color.White,
                        fontSize = (TITLE_BIG + (TITLE_SMALL - TITLE_BIG) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = TextShadow,
                    ),
                )
                Text(
                    "Double Tap To Queue  ·  Hold To Move",
                    maxLines = 1,
                    style = TextStyle(SecondaryText, fontSize = 40.usp, shadow = TextShadow),
                    modifier = Modifier.padding(top = 2.u, bottom = 6.u),
                )
                // In a mix only the songs you added count (the mix itself is endless);
                // in a library, everything still to come. Nothing shown at 0.
                val ahead = queue.drop(now + 1)
                val count = if (player.autoMix) ahead.count { player.isHeldQueued(it) } else ahead.size
                if (count > 0) {
                    Text(
                        if (count == 1) "1 song" else "$count songs",
                        style = TextStyle(SecondaryText, fontSize = 46.usp, shadow = TextShadow),
                        modifier = Modifier.graphicsLayer { alpha = 1f - c },
                    )
                }
            }

            // Queue button, lit, in the same spot as on Now Playing: tap to go back.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.u, end = 96.u)
                    .size(130.u)
                    .clip(CircleShape)
                    .background(Color(0x47FFFFFF))
                    .border(1.5f.u, Glass.Edge, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = Color.White),
                        onClick = onClose,
                    ),
            ) {
                Icon(painterResource(Res.drawable.ic_queue), stringResource(Res.string.queue), tint = Color.White, modifier = Modifier.size(64.u))
            }
        }
    }
}

/**
 * Stable keys for the queue's rows that survive reordering: the video id, plus
 * which repeat of it this is (a song can be in the queue more than once).
 */
private fun keysOf(queue: List<Track>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { t ->
        val n = (seen[t.videoId] ?: 0) + 1
        seen[t.videoId] = n
        "${t.videoId}#$n"
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(130.u)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(
                painterResource(Res.drawable.ic_close),
                stringResource(Res.string.remove_from_queue),
                tint = SecondaryText,
                modifier = Modifier.size(52.u),
            )
        }
    }
}
