package com.headunit.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.headunit.music.R
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

/** Bar height at rest and in search mode (design px). HomeScreen uses these to place things below it. */
const val BAR_HEIGHT_IDLE = 232f
const val BAR_HEIGHT_COMPACT = 168f

/**
 * Frosted pill search bar from the mockup: magnifier, query, clear, mic.
 * All sizes are design pixels on the 2000x1200 canvas.
 *
 * It's a display, not a system text field: typing comes from our own
 * GlassKeyboard, so Android's keyboard never pops up. [active] (our keyboard
 * is up) shows the blinking cursor and a white outline.
 *
 * [compact] runs 0 → 1 as search mode opens: the bar gets thinner and its
 * text and icons shrink with it.
 */
@Composable
fun SearchBar(
    query: String,
    active: Boolean,
    onActivate: () -> Unit,
    onClear: () -> Unit,
    onVoiceSearch: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Float = 0f,
) {
    fun shrink(idle: Float, small: Float) = idle + (small - idle) * compact
    val typing by animateFloatAsState(if (active) 1f else 0f, tween(200), label = "searchOutline")

    with(LocalDesignScale.current) {
        val fontSize = shrink(92f, 72f)
        val textStyle = TextStyle(
            color = Brand.RedText,
            fontSize = fontSize.usp,
            fontWeight = FontWeight.Normal,
        )

        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(shrink(BAR_HEIGHT_IDLE, BAR_HEIGHT_COMPACT).u)
                .clip(RoundedCornerShape(percent = 50))
                .background(Brand.Frost)
                // White outline while typing, fading in and out.
                .border(3.5f.u, Color.White.copy(alpha = typing), RoundedCornerShape(percent = 50))
                // Tapping anywhere on the pill opens the keyboard: big target for driving.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onActivate,
                )
                .semantics {
                    role = Role.Button
                    stateDescription = query.ifEmpty { "empty" }
                }
                .padding(start = shrink(66f, 54f).u, end = shrink(52f, 40f).u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = Brand.Red,
                modifier = Modifier.size(shrink(108f, 84f).u),
            )

            Spacer(Modifier.width(shrink(36f, 30f).u))

            // Query text with the cursor at the end. Long queries scroll so the end stays visible.
            val scroll = rememberScrollState()
            LaunchedEffect(query) { scroll.scrollTo(scroll.maxValue) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scroll, enabled = false),
            ) {
                val cursor: @Composable () -> Unit = {
                    BlinkingCursor(visible = active, restartKey = query, height = fontSize * 1.1f)
                }
                if (query.isEmpty()) {
                    // Like the mockup: cursor sits in front of the placeholder.
                    cursor()
                    Text(stringResource(R.string.search_hint), style = textStyle, maxLines = 1)
                } else {
                    Text(query, style = textStyle, maxLines = 1, softWrap = false)
                    cursor()
                }
            }

            // Clear: only while there's text.
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.6f),
                exit = fadeOut() + scaleOut(targetScale = 0.6f),
            ) {
                RoundIconButton(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.clear_search),
                    tint = Brand.RedMuted,
                    glyph = shrink(80f, 66f),
                    target = shrink(150f, 130f),
                    onClick = onClear,
                )
            }

            RoundIconButton(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(R.string.voice_search),
                tint = Brand.RedMuted,
                glyph = shrink(96f, 80f),
                target = shrink(150f, 130f),
                onClick = onVoiceSearch,
            )
        }
    }
}

/** Red text cursor. Blinks every 530ms (Android's rate) and stays solid right after each keystroke. */
@Composable
internal fun BlinkingCursor(visible: Boolean, restartKey: Any, height: Float) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(visible, restartKey) {
        on = true
        while (visible) {
            delay(530)
            on = !on
        }
    }
    with(LocalDesignScale.current) {
        Box(
            Modifier
                .padding(horizontal = 4.u)
                .width(7.u)
                .height(height.u)
                .alpha(if (visible && on) 1f else 0f)
                .background(Brand.Red, RoundedCornerShape(4.u)),
        )
    }
}

/** Circular touch target ([target] px, 150 at rest) around a smaller glyph. */
@Composable
private fun RoundIconButton(
    painter: Painter,
    contentDescription: String,
    tint: Color,
    glyph: Float,
    target: Float,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(target.u)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Brand.Red),
                    onClick = onClick,
                ),
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(glyph.u),
            )
        }
    }
}
