package com.headunit.music.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.wrapContentHeight
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

/**
 * White suggestion lines under the search bar. Rows from [fogFromIndex] down sit
 * behind the keyboard glass: they blur (Android 12+) and dim by [fog] (0..1).
 * On older Android, Modifier.blur does nothing and the dimming alone reads as frost.
 *
 * New lines fade and slide in,
 * staggered top to bottom; lines that stay put don't re-animate as you type.
 */
@Composable
fun SearchSuggestions(
    query: String,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    fog: Float = 0f,
    fogFromIndex: Int = Int.MAX_VALUE,
) {
    Column(modifier) {
        suggestions.forEachIndexed { index, text ->
            key(text) {
                SuggestionRow(
                    query = query,
                    text = text,
                    index = index,
                    fog = if (index >= fogFromIndex) fog else 0f,
                    onClick = { onPick(text) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(query: String, text: String, index: Int, fog: Float, onClick: () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 45L)
        anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    with(LocalDesignScale.current) {
        val slide = 24.u
        Text(
            text = buildAnnotatedString {
                // Typed part regular, the completion bold, like YouTube/Google.
                val typed = query.trim()
                if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
                    append(text.substring(0, typed.length))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(typed.length))
                    }
                } else {
                    append(text)
                }
            },
            style = TextStyle(
                color = Color.White,
                fontSize = 76.usp,
                // Soft shadow keeps white readable over the light peach corner.
                shadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                .padding(horizontal = 24.u)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}
