package com.headunit.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.headunit.music.R
import com.headunit.music.data.Track
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.library.LibraryGlass
import com.headunit.music.ui.library.LibrarySecondary
import com.headunit.music.ui.theme.LocalDesignScale

/** Width of the queue sheet (design px). */
const val QUEUE_PANEL_WIDTH = 860f
private const val ROW = 150f

/**
 * Opened by the queue button on Now Playing: opens on the song playing now,
 * with everything coming up below it. Scroll up to see what already played
 * (faded and greyed out). Tap a song to jump to it; × takes an upcoming one
 * out. Songs added with a hold are marked "Queued".
 */
@Composable
fun QueuePanel(player: PlayerController, modifier: Modifier = Modifier) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(56.u)
        val queue = player.queue
        val now = player.index
        val shown = queue.indices.toList()
        // Opens with the current song at the top; earlier songs are above it.
        val list = rememberLazyListState(initialFirstVisibleItemIndex = now.coerceAtLeast(0))
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(now) {
            // Follow along when the song changes while the sheet is open.
            if (opened && now >= 0) list.animateScrollToItem(now) else opened = true
        }

        Column(
            modifier = modifier
                .width(QUEUE_PANEL_WIDTH.u)
                .fillMaxHeight()
                .clip(shape)
                .background(LibraryGlass.Panel)
                .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x08FFFFFF))))
                .border(2.u, LibraryGlass.Edge, shape),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(start = 56.u, end = 56.u, top = 44.u, bottom = 16.u),
            ) {
                Text(
                    stringResource(R.string.up_next),
                    style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                // How many are lined up: in a mix only the songs you added count
                // (the mix itself is endless); in a library, everything still to come.
                val ahead = queue.drop(now + 1)
                val count = if (player.autoMix) ahead.count { player.isHeldQueued(it) } else ahead.size
                if (count > 0) {
                    Text(
                        if (count == 1) "1 song" else "$count songs",
                        style = TextStyle(LibrarySecondary, fontSize = 40.usp),
                    )
                }
            }

            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 28.u),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(shown, key = { i -> "$i:${queue[i].videoId}" }) { i ->
                    val track = queue[i]
                    QueueRow(
                        track = track,
                        playing = i == now,
                        played = i < now,
                        held = i > now && player.isHeldQueued(track),
                        onClick = { player.skipTo(i) },
                        onRemove = if (i > now) ({ player.removeFromQueue(i) }) else null,
                    )
                }
                if (player.autoMix) {
                    item(key = "mix") {
                        Text(
                            "More from the mix keeps coming…",
                            style = TextStyle(LibrarySecondary, fontSize = 36.usp),
                            modifier = Modifier.padding(start = 56.u, top = 20.u),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    playing: Boolean,
    /** Already played: shown faded and greyed out. */
    played: Boolean,
    held: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    with(LocalDesignScale.current) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW.u)
                .graphicsLayer { alpha = if (played) 0.75f else 1f }
                .then(if (playing) Modifier.background(Color(0x1FFFFFFF)) else Modifier)
                .clickable(
                    enabled = !playing,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(start = 40.u, end = 20.u),
        ) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (played) Desaturated else null,
                modifier = Modifier
                    .width(184.u)
                    .height(104.u)
                    .clip(RoundedCornerShape(18.u))
                    .background(Color.White.copy(alpha = 0.10f)),
            )
            Spacer(Modifier.width(32.u))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.u)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White, fontSize = 48.usp, fontWeight = FontWeight.Medium),
                )
                val label = when {
                    playing -> "Now playing"
                    held -> "Queued"
                    else -> null
                }
                Text(
                    listOfNotNull(label, track.artist, track.durationText).joinToString("  ·  "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(LibrarySecondary, fontSize = 36.usp),
                )
            }
            if (onRemove != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(110.u)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = Color.White),
                            onClick = onRemove,
                        ),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        stringResource(R.string.remove_from_queue),
                        tint = LibrarySecondary,
                        modifier = Modifier.size(44.u),
                    )
                }
            } else {
                Spacer(Modifier.width(110.u))
            }
        }
    }
}

/** Mostly grey (20% of the colour left) for songs that already played. */
private val Desaturated = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })
