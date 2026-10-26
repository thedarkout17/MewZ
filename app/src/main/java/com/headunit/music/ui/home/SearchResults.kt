package com.headunit.music.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.headunit.music.data.Track
import com.headunit.music.data.youtube.SearchResult.Reason
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

/** What the results area is showing. */
sealed interface ResultsState {
    data object Loading : ResultsState
    data class Done(val query: String, val tracks: List<Track>) : ResultsState
    data class Failed(val reason: Reason) : ResultsState
}

private val TextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)
private val Secondary = Color.White.copy(alpha = 0.72f)

/**
 * Search results under the bar: big rows (thumbnail, song, artist · length),
 * plus loading, empty and error states. Rows soften into the top edge as they
 * scroll up under the search bar.
 */
@Composable
fun SearchResults(
    state: ResultsState,
    onPlay: (Track) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(targetState = state, animationSpec = tween(220), label = "results", modifier = modifier) { s ->
        when (s) {
            ResultsState.Loading -> LoadingRows()
            is ResultsState.Done ->
                if (s.tracks.isEmpty()) {
                    Message(
                        title = "No songs found for “${s.query}”",
                        detail = "Try a different spelling or the artist's name.",
                    )
                } else {
                    TrackList(s.tracks, onPlay)
                }
            is ResultsState.Failed -> FailureMessage(s.reason, onRetry)
        }
    }
}

@Composable
private fun TrackList(tracks: List<Track>, onPlay: (Track) -> Unit) {
    with(LocalDesignScale.current) {
        val fade = 48.u
        LazyColumn(
            // Rows are 220px plus 20px gaps: four fit, the fifth peeks in to hint at scrolling.
            verticalArrangement = Arrangement.spacedBy(20.u),
            contentPadding = PaddingValues(top = 12.u, bottom = 48.u),
            modifier = Modifier
                .fillMaxSize()
                // Soft top edge so rows dissolve as they slide under the bar.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            (fade.toPx() / size.height) to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(tracks, key = { _, t -> t.videoId }) { index, track ->
                TrackRow(track, index, onClick = { onPlay(track) })
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, index: Int, onClick: () -> Unit) {
    // First screenful staggers in; rows further down just appear as you scroll.
    val anim = remember { Animatable(if (index < 6) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            delay(index * 50L)
            anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }

    with(LocalDesignScale.current) {
        val slide = 28.u
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.u)
                .graphicsLayer {
                    alpha = anim.value
                    translationY = (1f - anim.value) * slide.toPx()
                }
                .clip(RoundedCornerShape(28.u))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(horizontal = 16.u),
        ) {
            // 16:9 crop also trims the black bars YouTube bakes into 4:3 thumbnails.
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(348.u)
                    .height(196.u)
                    .clip(RoundedCornerShape(22.u))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.5f.u, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.u)),
            )
            Spacer(Modifier.width(48.u))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.u)) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 66.usp,
                        fontWeight = FontWeight.Medium,
                        shadow = TextShadow,
                    ),
                )
                Text(
                    text = listOfNotNull(track.artist, track.durationText).joinToString("  ·  "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Secondary, fontSize = 46.usp, shadow = TextShadow),
                )
            }
        }
    }
}

/** Placeholder rows that gently pulse while YouTube answers. */
@Composable
private fun LoadingRows() {
    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    with(LocalDesignScale.current) {
        Column(Modifier.fillMaxSize().padding(top = 12.u), verticalArrangement = Arrangement.spacedBy(20.u)) {
            repeat(5) { i ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.u)
                        .padding(horizontal = 16.u)
                        .graphicsLayer { alpha = 1f - i * 0.15f },
                ) {
                    Box(
                        Modifier.width(348.u).height(196.u)
                            .background(Color.White.copy(alpha = pulse), RoundedCornerShape(22.u))
                    )
                    Spacer(Modifier.width(48.u))
                    Column(verticalArrangement = Arrangement.spacedBy(26.u)) {
                        Box(
                            Modifier.width((720 - i * 60).u).height(50.u)
                                .background(Color.White.copy(alpha = pulse), RoundedCornerShape(22.u))
                        )
                        Box(
                            Modifier.width(400.u).height(36.u)
                                .background(Color.White.copy(alpha = pulse * 0.7f), RoundedCornerShape(16.u))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureMessage(reason: Reason, onRetry: () -> Unit) {
    when (reason) {
        Reason.RateLimited -> Message(
            title = "YouTube is limiting searches right now",
            detail = "Too many requests from this connection. Wait a few minutes, then try again.",
            action = "Try again" to onRetry,
        )
        Reason.ExtractorOutdated -> Message(
            title = "YouTube changed something",
            detail = "Search needs an app update to catch up (NewPipeExtractor).",
            action = "Try again" to onRetry,
        )
        Reason.Offline -> Message(
            title = "No internet connection",
            detail = "Check the headunit's Wi-Fi or phone hotspot, then try again.",
            action = "Try again" to onRetry,
        )
        Reason.Other -> Message(
            title = "Couldn't search YouTube",
            detail = "Something went wrong on YouTube's side.",
            action = "Try again" to onRetry,
        )
    }
}

@Composable
private fun Message(title: String, detail: String, action: Pair<String, () -> Unit>? = null) {
    with(LocalDesignScale.current) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.u),
            modifier = Modifier.fillMaxWidth().padding(top = 200.u, start = 120.u, end = 120.u),
        ) {
            Text(
                title,
                textAlign = TextAlign.Center,
                style = TextStyle(Color.White, fontSize = 64.usp, fontWeight = FontWeight.Medium, shadow = TextShadow),
            )
            Text(
                detail,
                textAlign = TextAlign.Center,
                style = TextStyle(Secondary, fontSize = 42.usp, shadow = TextShadow),
            )
            if (action != null) {
                Spacer(Modifier.size(8.u))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(120.u)
                        .clip(RoundedCornerShape(60.u))
                        .background(
                            Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x14FFFFFF)))
                        )
                        .border(
                            1.5f.u,
                            Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF))),
                            RoundedCornerShape(60.u),
                        )
                        .clickable(onClick = action.second)
                        .padding(horizontal = 72.u),
                ) {
                    Text(action.first, style = TextStyle(Color.White, fontSize = 46.usp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}
