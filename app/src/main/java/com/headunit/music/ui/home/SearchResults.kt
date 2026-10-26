package com.headunit.music.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
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
    data class Done(
        val query: String,
        val tracks: List<Track>,
        /** For the next page of results; null when YouTube has no more. */
        val nextPage: String? = null,
        val loadingMore: Boolean = false,
        /** Why the last "load more" failed (scrolling stops asking until Try again). */
        val moreFailed: Reason? = null,
    ) : ResultsState
    data class Failed(val reason: Reason) : ResultsState
}

/** Start fetching the next page when this many rows are left below the screen. */
private const val LOAD_MORE_AHEAD = 4

private const val PLAYED_ALPHA = 0.7f
private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val PlayedTitle = Color(0xFFB8B8B8)
private val PlayedDetail = Color(0xFF8E8E8E)

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
    /** A song was held: add it to the queue. */
    onQueue: (Track) -> Unit = {},
    /** Scrolled near the end of the results: fetch the next page. */
    onLoadMore: () -> Unit = {},
    /** A tap on empty space (not a song or button): back to the home screen. */
    onTapEmptySpace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tapEmpty by rememberUpdatedState(onTapEmptySpace)
    // Keyed by kind, so more results arriving just extend the list (no crossfade, no jump to the top).
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
        contentKey = { it::class },
        label = "results",
        // Rows and buttons take their own taps; anything left over is empty space.
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { tapEmpty() }) },
    ) { s ->
        when (s) {
            ResultsState.Loading -> LoadingRows()
            is ResultsState.Done ->
                if (s.tracks.isEmpty()) {
                    Message(
                        title = "No songs found for “${s.query}”",
                        detail = "Try a different spelling or the artist's name.",
                    )
                } else {
                    TrackList(s, onPlay, onQueue, onLoadMore)
                }
            is ResultsState.Failed -> FailureMessage(s.reason, onRetry)
        }
    }
}

@Composable
private fun TrackList(
    state: ResultsState.Done,
    onPlay: (Track) -> Unit,
    onQueue: (Track) -> Unit,
    onLoadMore: () -> Unit,
) {
    val tracks = state.tracks
    val list = rememberLazyListState()
    // Within a few rows of the end: ask for more (once per page; not after a failure).
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= list.layoutInfo.totalItemsCount - 1 - LOAD_MORE_AHEAD
        }
    }
    val canLoadMore = state.nextPage != null && !state.loadingMore && state.moreFailed == null
    LaunchedEffect(nearEnd, canLoadMore, tracks.size) {
        if (nearEnd && canLoadMore) onLoadMore()
    }

    with(LocalDesignScale.current) {
        val fade = 48.u
        LazyColumn(
            state = list,
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
                TrackRow(track, index, onClick = { onPlay(track) }, onLongClick = { onQueue(track) })
            }
            // Footer: spinner while the next page loads, or why it couldn't.
            if (state.loadingMore || state.moreFailed != null) {
                item(key = "more") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.u)
                            .then(
                                if (state.moreFailed != null) Modifier.clickable(onClick = onLoadMore) else Modifier
                            ),
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 5.u, modifier = Modifier.size(64.u))
                        } else {
                            Text(
                                when (state.moreFailed) {
                                    Reason.QuotaExceeded -> "Daily search limit reached; more results tomorrow"
                                    Reason.Offline -> "No connection · Tap to try again"
                                    else -> "Couldn't load more · Tap to try again"
                                },
                                style = TextStyle(Secondary, fontSize = 44.usp, shadow = TextShadow),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TrackRow(
    track: Track,
    index: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** Shown before the artist, e.g. "Now playing", "Queued", "Previous". */
    label: String? = null,
    /** Already played (queue): faded, greyed-out artwork and grey text. */
    played: Boolean = false,
    /** The song playing now (queue): a soft glass highlight behind the row. */
    highlighted: Boolean = false,
    /** Extra control at the end of the row (the queue's ×). */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Replaces the usual tap / hold handling (the queue does its own: tap,
     * double-tap and hold-to-drag). [onClick] and [onLongClick] are ignored then.
     */
    gestures: Modifier? = null,
    /** Song length after the artist (off where it doesn't matter, like picking a cover). */
    showDuration: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
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
                    alpha = anim.value * (if (played) PLAYED_ALPHA else 1f)
                    translationY = (1f - anim.value) * slide.toPx()
                }
                .clip(RoundedCornerShape(28.u))
                .then(if (highlighted) Modifier.background(Color(0x24FFFFFF)) else Modifier)
                .then(
                    gestures ?: Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = Color.White),
                        onClick = onClick,
                        // Hold: add to the queue.
                        onLongClick = onLongClick?.let { hold ->
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                hold()
                            }
                        },
                    )
                )
                .padding(horizontal = 16.u),
        ) {
            // 16:9 crop also trims the black bars YouTube bakes into 4:3 thumbnails.
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (played) Greyscale else null,
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
                        color = if (played) PlayedTitle else Color.White,
                        fontSize = 66.usp,
                        fontWeight = FontWeight.Medium,
                        shadow = TextShadow,
                    ),
                )
                Text(
                    text = listOfNotNull(label, track.artist, track.durationText?.takeIf { showDuration }).joinToString("  ·  "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (played) PlayedDetail else Secondary, fontSize = 46.usp, shadow = TextShadow),
                )
            }
            trailing?.invoke()
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
        Reason.MissingKey -> Message(
            title = "Add a YouTube API key to search",
            detail = "Put youtube.apiKey=… in local.properties, then rebuild. See the README.",
        )
        Reason.BadKey -> Message(
            title = "YouTube didn't accept the API key",
            detail = "Check the key, and that YouTube Data API v3 is enabled for it.",
        )
        Reason.QuotaExceeded -> Message(
            title = "Search limit reached for today",
            detail = "YouTube's free search allowance resets at midnight Pacific time.",
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
internal fun Message(
    title: String,
    detail: String,
    action: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    with(LocalDesignScale.current) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.u),
            modifier = modifier.fillMaxWidth().padding(top = 200.u, start = 120.u, end = 120.u),
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
