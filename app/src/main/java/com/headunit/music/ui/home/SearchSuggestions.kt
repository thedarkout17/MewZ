package com.headunit.music.ui.home

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.headunit.music.R
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

/** One line in the list under the bar: a past search, or a suggestion. */
data class SuggestionItem(val text: String, val fromHistory: Boolean)

/**
 * White lines under the search bar: recent searches (clock icon, × to remove)
 * and suggestions (faint magnifier). Icons sit directly under the bar's
 * magnifier, and the text lines up with the typed text.
 *
 * The list scrolls (a long search history can run well past the screen).
 * [bottomPadding] lets the last rows scroll up clear of the keyboard.
 *
 * Rows that pass behind the keyboard glass (below [glassTopPx], in window
 * pixels) blur on Android 12+ and dim on every version, smoothly as they
 * scroll. Older Android can't blur, so the dimming alone reads as frost.
 *
 * New lines fade and slide in, staggered top to bottom; lines that stay put
 * don't re-animate as you type.
 */
@Composable
fun SearchSuggestions(
    query: String,
    items: List<SuggestionItem>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    glassTopPx: () -> Float = { Float.MAX_VALUE },
    bottomPadding: Dp = 0.dp,
    onTapEmptySpace: () -> Unit = {},
    /** The user started dragging the list (used to put the keyboard away). */
    onUserScroll: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    // New search text → start from the top again.
    LaunchedEffect(query.isBlank()) { listState.scrollToItem(0) }
    // Only a finger drag counts; our own scrollToItem doesn't.
    val onScroll by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) onScroll()
        }
    }

    with(LocalDesignScale.current) {
        val fade = 40.u
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding),
            modifier = modifier
                // The list covers the screen, so taps between/below rows act like
                // taps on empty space (rows handle their own taps first).
                .pointerInput(Unit) { detectTapGestures { onTapEmptySpace() } }
                // Soft top edge so rows dissolve as they scroll up under the bar.
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
            itemsIndexed(items, key = { _, item -> "${item.fromHistory}:${item.text}" }) { index, item ->
                SuggestionRow(
                    query = query,
                    item = item,
                    index = index,
                    glassTopPx = glassTopPx,
                    onClick = { onPick(item.text) },
                    onRemove = { onRemove(item.text) },
                )
            }
        }
    }
}

private val TextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)

@Composable
private fun SuggestionRow(
    query: String,
    item: SuggestionItem,
    index: Int,
    glassTopPx: () -> Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    // Only the first screenful staggers in; rows reached by scrolling just appear.
    val anim = remember { Animatable(if (index < 6) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            delay(index * 45L)
            anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    // Where this row is on screen, updated as it scrolls (read only while drawing,
    // so scrolling doesn't recompose).
    var topY by remember { mutableFloatStateOf(0f) }

    with(LocalDesignScale.current) {
        val slide = 24.u
        val maxBlur = 22.u
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.u)
                .onGloballyPositioned { topY = it.positionInWindow().y }
                .graphicsLayer {
                    // 0 = fully above the glass, 1 = fully behind it; eases across the edge.
                    val center = topY + size.height / 2f
                    val fog = ((center - glassTopPx()) / (size.height * 0.6f) + 0.5f).coerceIn(0f, 1f)
                    alpha = anim.value * (1f - 0.45f * fog)
                    translationY = (1f - anim.value) * slide.toPx()
                    renderEffect = if (fog > 0.01f && Build.VERSION.SDK_INT >= 31) {
                        val r = maxBlur.toPx() * fog
                        BlurEffect(r, r, TileMode.Decal)
                    } else null
                }
                .clip(RoundedCornerShape(24.u))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(start = 24.u),
        ) {
            // Icon slot is as wide as the bar's magnifier, so icons stack under it.
            Box(Modifier.width(84.u), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (item.fromHistory) R.drawable.ic_history else R.drawable.ic_search),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (item.fromHistory) 0.85f else 0.45f),
                    modifier = Modifier.size(if (item.fromHistory) 60.u else 52.u),
                )
            }
            Spacer(Modifier.width(30.u))
            Text(
                text = buildAnnotatedString {
                    // Typed part regular, the rest bold, like YouTube/Google.
                    val typed = query.trim()
                    if (typed.isNotEmpty() && item.text.startsWith(typed, ignoreCase = true)) {
                        append(item.text.substring(0, typed.length))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(item.text.substring(typed.length))
                        }
                    } else {
                        append(item.text)
                    }
                },
                style = TextStyle(color = Color.White, fontSize = 76.usp, shadow = TextShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.fromHistory) {
                // Remove from history; lines up under the bar's mic button.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(130.u)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = Color.White),
                            onClick = onRemove,
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.remove_from_history),
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.u),
                    )
                }
            }
        }
    }
}
