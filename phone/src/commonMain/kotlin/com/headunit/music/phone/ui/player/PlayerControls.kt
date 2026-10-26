package com.headunit.music.phone.ui.player

import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.ui.theme.Brand
import com.headunit.music.phone.ui.theme.LocalDesignScale

/** "0:12 ━━━━━━━━━━━ -2:48": elapsed, bar, time remaining. Tap or drag the bar to seek. */
@Composable
internal fun SeekBar(player: PlayerController, modifier: Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationSeconds
    val shown = (dragFraction ?: if (duration > 0f) player.positionSeconds / duration else 0f).coerceIn(0f, 1f)

    with(LocalDesignScale.current) {
        val timeStyle = TextStyle(
            Color.White, fontSize = 42.usp, shadow = PlayerTextShadow,
            fontFeatureSettings = "tnum", // digits don't jiggle as they change
        )
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown * duration), style = timeStyle, modifier = Modifier.width(120.u))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(80.u) // tall touch target around a thin bar
                    .pointerInput(duration) {
                        detectTapGestures { pos ->
                            if (duration > 0f) player.seekTo((pos.x / size.width).coerceIn(0f, 1f) * duration)
                        }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos -> dragFraction = (pos.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { if (duration > 0f) player.seekTo(it * duration) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                val dragging = dragFraction != null
                val barH = if (dragging) 26f else 20f
                Box(Modifier.fillMaxWidth().height(barH.u).background(Color(0x40FFFFFF), CircleShape))
                Box(Modifier.fillMaxWidth(shown).height(barH.u).background(Color(0xD9FFFFFF), CircleShape))
            }
            Text(
                "-" + formatTime((duration - shown * duration).coerceAtLeast(0f)),
                style = timeStyle,
                textAlign = TextAlign.End,
                modifier = Modifier.width(140.u),
            )
        }
    }
}


/** A bare white glyph with a generous invisible touch area, like the reference design. */
@Composable
internal fun GlyphButton(
    icon: DrawableResource,
    contentDescription: String,
    target: Float,
    glyph: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(target.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .clip(CircleShape)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(glyph.u))
        }
    }
}

@Composable
internal fun GlassCircleButton(
    icon: DrawableResource,
    contentDescription: String,
    size: Float,
    glyph: Float,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .clip(CircleShape)
                .then(
                    if (accent) Modifier.background(Brand.Red)
                    else Modifier.background(PlayerGlass.Tile).border(1.5f.u, PlayerGlass.Edge, CircleShape)
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(glyph.u))
        }
    }
}

internal fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Int) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}
