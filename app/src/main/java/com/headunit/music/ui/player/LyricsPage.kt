package com.headunit.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.data.Lyrics
import com.headunit.music.data.LyricsRepository
import com.headunit.music.data.Track
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.library.LibrarySecondary
import com.headunit.music.ui.library.LibraryTextShadow
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException

// Header (design px), the same as the queue's: big title shrinking as you scroll.
private const val HEADER_TALL = 470f
private const val HEADER_SHORT = 190f
private const val TITLE_BIG = 132f
private const val TITLE_SMALL = 76f
/** After you scroll the lyrics yourself, wait this long before following the song again. */
private const val FOLLOW_AGAIN_MS = 4_000L

private sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object None : LyricsState
    data object Offline : LyricsState
}

/**
 * The song's lyrics, laid out like the queue over Now Playing's blurred artwork.
 * Synced lyrics follow along: the line being sung is bright and kept near the
 * top, the rest dimmed; tap a line to jump the song there. (Scroll yourself and
 * it stops following for a few seconds.) Plain lyrics just scroll. A tap on
 * empty space or Back returns to Now Playing.
 */
@Composable
fun LyricsPage(player: PlayerController, track: Track, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var state by remember(track.videoId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(track.videoId) {
        state = try {
            LyricsRepository.find(track)?.let { LyricsState.Found(it) } ?: LyricsState.None
        } catch (e: IOException) {
            LyricsState.Offline
        }
    }

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

        // A tap on empty space (not a line) goes back to Now Playing.
        Box(modifier.fillMaxSize().pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) }) {
            when (val s = state) {
                LyricsState.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 5.u,
                    modifier = Modifier.align(Alignment.Center).size(72.u),
                )
                LyricsState.None -> Note("No lyrics found for this song", Modifier.align(Alignment.Center))
                LyricsState.Offline -> Note("Couldn't load lyrics · no connection", Modifier.align(Alignment.Center))
                is LyricsState.Found -> LyricsList(player, s.lyrics, list, headerH)
            }

            // "Lyrics" and the song, shrinking into the top-left as you scroll.
            Column(Modifier.offset(x = 96.u, y = (226f + (64f - 226f) * c).u).padding(end = 96.u)) {
                Text(
                    "Lyrics",
                    maxLines = 1,
                    style = TextStyle(
                        Color.White,
                        fontSize = (TITLE_BIG + (TITLE_SMALL - TITLE_BIG) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = LibraryTextShadow,
                    ),
                )
                Text(
                    "${track.title}  ·  ${track.artist}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(LibrarySecondary, fontSize = 46.usp, shadow = LibraryTextShadow),
                    modifier = Modifier.graphicsLayer { alpha = 1f - c },
                )
            }
        }
    }
}

@Composable
private fun LyricsList(
    player: PlayerController,
    lyrics: Lyrics,
    list: androidx.compose.foundation.lazy.LazyListState,
    headerH: Float,
) {
    val current by remember(lyrics) { derivedStateOf { lyrics.lineAt(player.positionSeconds) } }
    // When you last scrolled by hand (the list stops following for a moment).
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling && !followScrolling) touchedAt = System.currentTimeMillis()
        }
    }
    val density = LocalDensity.current
    with(LocalDesignScale.current) {
        // Keep the line being sung just under the shrunk title.
        LaunchedEffect(current) {
            if (!lyrics.synced || current < 0) return@LaunchedEffect
            if (System.currentTimeMillis() - touchedAt < FOLLOW_AGAIN_MS) return@LaunchedEffect
            followScrolling = true
            try {
                // Two lines of lead-in above it, where they fit.
                val target = (current - 1).coerceAtLeast(0)
                list.animateScrollToItem(target, -with(density) { (HEADER_SHORT - HEADER_TALL + 40f).u.roundToPx() })
            } finally {
                followScrolling = false
            }
        }

        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(28.u),
            contentPadding = PaddingValues(top = HEADER_TALL.u, bottom = 520.u, start = 80.u, end = 160.u),
            modifier = Modifier
                .fillMaxSize()
                // Lines dissolve as they slide up under the title.
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
            itemsIndexed(lyrics.lines) { i, line ->
                val sung = lyrics.synced && i < current
                val now = lyrics.synced && i == current
                val color by animateColorAsState(
                    when {
                        !lyrics.synced || now -> Color.White
                        sung -> Color.White.copy(alpha = 0.45f)
                        else -> Color.White.copy(alpha = 0.6f)
                    },
                    animationSpec = tween(250),
                    label = "lyricLine",
                )
                val text = line.text.ifBlank { "♪" }
                Text(
                    text,
                    style = TextStyle(
                        color,
                        fontSize = (if (lyrics.synced) 76 else 64).usp,
                        lineHeight = (if (lyrics.synced) 92 else 80).usp,
                        fontWeight = if (now) FontWeight.Bold else FontWeight.SemiBold,
                        shadow = LibraryTextShadow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.u))
                        .then(
                            // Synced: tap a line to jump the song there.
                            if (lyrics.synced && line.time != null) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(color = Color.White),
                            ) {
                                touchedAt = 0L // follow from the new spot straight away
                                player.seekTo(line.time)
                            } else Modifier.pointerInput(Unit) { detectTapGestures { } } // not "empty space"
                        )
                        .padding(horizontal = 16.u, vertical = 6.u),
                )
            }
        }
    }
}

/** Our own scrolls (following the song) shouldn't count as you scrolling. */
private var followScrolling = false

@Composable
private fun Note(text: String, modifier: Modifier) {
    with(LocalDesignScale.current) {
        Text(
            text,
            style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.Medium, shadow = LibraryTextShadow),
            modifier = modifier,
        )
    }
}
