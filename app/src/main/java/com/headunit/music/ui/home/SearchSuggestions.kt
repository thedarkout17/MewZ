package com.headunit.music.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * Rows from [fogFromIndex] down sit behind the keyboard glass: they blur
 * (Android 12+) and dim by [fog] (0..1). On older Android, Modifier.blur does
 * nothing and the dimming alone reads as frost.
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
    fog: Float = 0f,
    fogFromIndex: Int = Int.MAX_VALUE,
) {
    Column(modifier) {
        items.forEachIndexed { index, item ->
            key(item.fromHistory, item.text) {
                SuggestionRow(
                    query = query,
                    item = item,
                    index = index,
                    fog = if (index >= fogFromIndex) fog else 0f,
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
    fog: Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 45L)
        anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    with(LocalDesignScale.current) {
        val slide = 24.u
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(176.u)
                .then(if (fog > 0f) Modifier.blur((22f * fog).u, BlurredEdgeTreatment.Unbounded) else Modifier)
                .graphicsLayer {
                    alpha = anim.value * (1f - 0.45f * fog)
                    translationY = (1f - anim.value) * slide.toPx()
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
