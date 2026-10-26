package com.headunit.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.R
import com.headunit.music.core.model.HISTORY_ID
import com.headunit.music.core.model.LIKED_ID
import com.headunit.music.core.model.Library
import com.headunit.music.core.model.Track
import com.headunit.music.ui.home.Message
import com.headunit.music.ui.home.TrackRow
import com.headunit.music.ui.theme.LocalDesignScale

// Header (design px): big name when you open the library, shrinking as the songs scroll up.
private const val HEADER_TALL = 470f
private const val HEADER_SHORT = 190f
private const val TITLE_BIG = 132f
private const val TITLE_SMALL = 76f

/**
 * A library's songs, laid out like search results but with the library's name
 * where the search bar would be. Nothing plays until you pick a song.
 */
@Composable
fun LibraryPage(
    library: Library,
    onBack: () -> Unit,
    /** A song was tapped: its position in [library]. */
    onPlay: (index: Int) -> Unit,
    /** A song was held: add it to the queue. */
    onQueue: (Track) -> Unit = {},
    /** If set, each row gets an × (like the queue's) that takes the song out of this library. */
    onRemove: ((Track) -> Unit)? = null,
    dockInset: Float,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    with(LocalDesignScale.current) {
        val collapseDistance = (HEADER_TALL - HEADER_SHORT).u
        val density = androidx.compose.ui.platform.LocalDensity.current
        // 0 = name at full size, 1 = shrunk (after scrolling ~140px).
        val c by remember {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else (list.firstVisibleItemScrollOffset / with(density) { collapseDistance.toPx() }).coerceIn(0f, 1f)
            }
        }
        val headerH = HEADER_TALL + (HEADER_SHORT - HEADER_TALL) * c

        // A tap on empty space (not a song) goes back home.
        Box(modifier.fillMaxSize().pointerInput(onBack) { detectTapGestures(onTap = { onBack() }) }) {
            if (library.tracks.isEmpty()) {
                Message(
                    title = "No songs yet",
                    detail = when (library.id) {
                        LIKED_ID -> "Tap the heart on Now Playing to like a song."
                        HISTORY_ID -> "Songs you play show up here."
                        else -> "Tap + on Now Playing to add the song that's playing."
                    },
                    modifier = Modifier.padding(top = 140.u),
                )
            } else {
                LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(20.u),
                    contentPadding = PaddingValues(
                        top = HEADER_TALL.u,
                        bottom = (dockInset + 48f).u,
                        start = 80.u,
                        end = 80.u,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        // Rows dissolve as they slide up under the name.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val top = headerH.u.toPx() - 30.u.toPx()
                            val solid = top + 60.u.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    (top / size.height) to Color.Transparent,
                                    (solid / size.height) to Color.Black,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    itemsIndexed(library.tracks, key = { _, t -> t.videoId }) { i, track ->
                        TrackRow(
                            track, i,
                            onClick = { onPlay(i) },
                            onLongClick = { onQueue(track) },
                            trailing = onRemove?.let { remove -> { RemoveButton { remove(track) } } },
                        )
                    }
                }
            }

            // Name (and song count), shrinking into the top-left as you scroll.
            Column(
                modifier = Modifier
                    .offset(
                        x = 96.u,
                        y = (226f + (64f - 226f) * c).u,
                    )
                    .padding(end = 120.u),
            ) {
                Text(
                    library.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        Color.White,
                        fontSize = (TITLE_BIG + (TITLE_SMALL - TITLE_BIG) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = LibraryTextShadow,
                    ),
                )
                Text(
                    songCount(library.tracks.size),
                    style = TextStyle(LibrarySecondary, fontSize = 46.usp, shadow = LibraryTextShadow),
                    modifier = Modifier.graphicsLayer { alpha = 1f - c },
                )
            }
        }
    }
}

/** The × at the end of a row (same as the queue's). */
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
                painterResource(R.drawable.ic_close),
                stringResource(R.string.remove_from_library),
                tint = LibrarySecondary,
                modifier = Modifier.size(52.u),
            )
        }
    }
}
