package com.headunit.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException

/** After you scroll the lyrics yourself, go back to the current line this long after you let go. */
private const val FOLLOW_AGAIN_MS = 2_500L

private sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object None : LyricsState
    data object Offline : LyricsState
}

/**
 * The song's lyrics, in place of the artwork on Now Playing, straight on the
 * blurred background, reaching across to the transport. Synced lyrics follow along: the line
 * being sung is bright and kept a third of the way down, the rest dimmed; tap a
 * line to jump the song there. Scroll yourself and it stops following for a few
 * seconds. Plain lyrics just scroll.
 */
@Composable
fun LyricsPanel(player: PlayerController, track: Track, modifier: Modifier = Modifier) {
    var state by remember(track.videoId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(track.videoId) {
        state = try {
            LyricsRepository.find(track)?.let { LyricsState.Found(it) } ?: LyricsState.None
        } catch (e: IOException) {
            LyricsState.Offline
        }
    }

    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(56.u)
        BoxWithConstraints(
            modifier
                .clip(shape)
                // Taps on the card aren't "empty space" (which would minimize the player).
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            when (val s = state) {
                LyricsState.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 5.u,
                    modifier = Modifier.align(Alignment.Center).size(72.u),
                )
                LyricsState.None -> Note("No lyrics found for this song", Modifier.align(Alignment.Center))
                LyricsState.Offline -> Note("Couldn't load lyrics · no connection", Modifier.align(Alignment.Center))
                is LyricsState.Found -> LyricsList(player, s.lyrics, maxHeight)
            }
        }
    }
}

@Composable
private fun LyricsList(player: PlayerController, lyrics: Lyrics, height: androidx.compose.ui.unit.Dp) {
    val list = rememberLazyListState()
    val current by remember(lyrics) { derivedStateOf { lyrics.lineAt(player.positionSeconds) } }
    // When you last scrolled by hand (the list stops following for a moment).
    var touchedAt by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling && !following) manual = true
            // Counts from when your scroll (fling included) comes to rest.
            if (manual) touchedAt = System.currentTimeMillis()
            if (!scrolling) manual = false
        }
    }
    // ...and then, if you haven't touched it again, back to the line being sung.
    LaunchedEffect(touchedAt) {
        if (touchedAt == 0L || !lyrics.synced) return@LaunchedEffect
        delay(FOLLOW_AGAIN_MS)
        if (list.isScrollInProgress || current < 0) return@LaunchedEffect
        following = true
        try {
            list.animateScrollToItem(current)
        } finally {
            following = false
        }
    }
    // Keep the line being sung a third of the way down (where the top padding puts it).
    LaunchedEffect(current) {
        if (!lyrics.synced || current < 0) return@LaunchedEffect
        if (System.currentTimeMillis() - touchedAt < FOLLOW_AGAIN_MS) return@LaunchedEffect
        following = true
        try {
            list.animateScrollToItem(current)
        } finally {
            following = false
        }
    }

    with(LocalDesignScale.current) {
        val fade = 64.u
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(22.u),
            contentPadding = PaddingValues(
                top = height * 0.3f,
                bottom = height * 0.6f,
                start = 48.u,
                end = 48.u,
            ),
            modifier = Modifier
                .fillMaxSize()
                // Lines fade in and out at the card's top and bottom edges.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val f = fade.toPx() / size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            f to Color.Black,
                            (1f - f) to Color.Black,
                            1f to Color.Transparent,
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
                        sung -> Color.White.copy(alpha = 0.42f)
                        else -> Color.White.copy(alpha = 0.58f)
                    },
                    animationSpec = tween(250),
                    label = "lyricLine",
                )
                Text(
                    line.text.ifBlank { "♪" },
                    style = TextStyle(
                        color,
                        fontSize = (if (lyrics.synced) 68 else 58).usp,
                        lineHeight = (if (lyrics.synced) 82 else 72).usp,
                        fontWeight = if (now) FontWeight.Bold else FontWeight.SemiBold,
                        shadow = LibraryTextShadow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.u))
                        .then(
                            // Synced: tap a line to jump the song there.
                            if (lyrics.synced && line.time != null) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(color = Color.White),
                            ) {
                                touchedAt = 0L // follow from the new spot straight away
                                player.seekTo(line.time)
                            } else Modifier
                        )
                        .padding(horizontal = 12.u, vertical = 4.u),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier) {
    with(LocalDesignScale.current) {
        Text(
            text,
            style = TextStyle(Color.White, fontSize = 50.usp, fontWeight = FontWeight.Medium, shadow = LibraryTextShadow),
            modifier = modifier.padding(horizontal = 48.u),
        )
    }
}
