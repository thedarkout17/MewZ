package com.headunit.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.headunit.music.core.model.Track
import com.headunit.music.core.search.Album
import com.headunit.music.ui.theme.LocalDesignScale

/** An album's songs: loading, loaded, or why not. */
sealed interface AlbumTracks {
    data object Loading : AlbumTracks
    data class Done(val tracks: List<Track>) : AlbumTracks
    data object Failed : AlbumTracks
}

// Header (design px), like an open library's, with the cover beside the title.
private const val HEADER_TALL = 470f
private const val HEADER_SHORT = 190f
private const val TITLE_BIG = 120f
private const val TITLE_SMALL = 72f
private const val COVER_BIG = 300f
private const val COVER_SMALL = 120f

private val AlbumTextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)
private val AlbumSecondary = Color.White.copy(alpha = 0.72f)

/**
 * An album opened from search, laid out like a library: cover and title at the
 * top (shrinking as you scroll), then its songs in order. Tap a song to play
 * the album from there; hold to queue. A tap on empty space goes back to the
 * results.
 */
@Composable
fun AlbumPage(
    album: Album,
    state: AlbumTracks,
    onBack: () -> Unit,
    onPlay: (tracks: List<Track>, index: Int) -> Unit,
    onQueue: (Track) -> Unit,
    onRetry: () -> Unit,
    dockInset: Float,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    with(LocalDesignScale.current) {
        val collapseDistance = (HEADER_TALL - HEADER_SHORT).u
        val c by remember {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else (list.firstVisibleItemScrollOffset / with(density) { collapseDistance.toPx() }).coerceIn(0f, 1f)
            }
        }
        val headerH = HEADER_TALL + (HEADER_SHORT - HEADER_TALL) * c

        Box(modifier.fillMaxSize().pointerInput(onBack) { detectTapGestures(onTap = { onBack() }) }) {
            when (state) {
                AlbumTracks.Loading -> Box(Modifier.padding(top = HEADER_TALL.u, start = 80.u, end = 80.u)) { LoadingRows() }
                AlbumTracks.Failed -> Message(
                    title = "Couldn't open this album",
                    detail = "Check the connection and try again.",
                    action = "Try again" to onRetry,
                    modifier = Modifier.padding(top = 260.u),
                )
                is AlbumTracks.Done -> LazyColumn(
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
                        // Rows dissolve as they slide up under the title.
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
                    itemsIndexed(state.tracks, key = { _, t -> t.videoId }) { i, track ->
                        TrackRow(
                            track, i,
                            onClick = { onPlay(state.tracks, i) },
                            onLongClick = { onQueue(track) },
                        )
                    }
                }
            }

            // Cover and title, shrinking into the top-left as you scroll.
            val cover = COVER_BIG + (COVER_SMALL - COVER_BIG) * c
            val shape = RoundedCornerShape((cover * 0.12f).u)
            AsyncImage(
                model = album.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = 96.u, y = (120f + (36f - 120f) * c).u)
                    .size(cover.u)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.5f.u, Color.White.copy(alpha = 0.25f), shape),
            )
            Column(
                Modifier
                    .offset(x = (96f + cover + 48f).u, y = (180f + (56f - 180f) * c).u)
                    .padding(end = 96.u),
            ) {
                Text(
                    album.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        Color.White,
                        fontSize = (TITLE_BIG + (TITLE_SMALL - TITLE_BIG) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = AlbumTextShadow,
                    ),
                )
                val count = (state as? AlbumTracks.Done)?.tracks?.size
                Text(
                    listOfNotNull(album.artist.ifBlank { null }, count?.let { if (it == 1) "1 song" else "$it songs" })
                        .joinToString("  ·  "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(AlbumSecondary, fontSize = 46.usp, shadow = AlbumTextShadow),
                    modifier = Modifier.graphicsLayer { alpha = 1f - c },
                )
            }
        }
    }
}
