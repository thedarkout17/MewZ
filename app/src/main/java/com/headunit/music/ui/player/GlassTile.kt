package com.headunit.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.headunit.music.ui.theme.LocalDesignScale

/**
 * Shuffle / loop / add. Off: just the icon on the background. On: a frosted
 * glass box around it and a bolder icon ([activeIcon]).
 */
@Composable
internal fun GlassTile(
    icon: Int,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    activeIcon: Int = icon,
    /**
     * Played when the tile is tapped on (not off): drawn instead of the icon with
     * progress 0 → 1 (it should look like the icon again at both ends). The glass
     * snaps on once it's finished.
     */
    onAnimation: (@Composable (Float) -> Unit)? = null,
) {
    val anim = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    // Only for taps: e.g. skipping to an already-liked song turns the heart on quietly.
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        val fromTap = tapped
        tapped = false
        if (!active || !fromTap || onAnimation == null) return@LaunchedEffect
        animating = true
        try {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(TILE_ANIM_MS, easing = LinearEasing))
        } finally {
            animating = false
        }
    }
    val lit = active && !animating

    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(30.u)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(190.u)
                .height(116.u)
                .clip(shape)
                .then(
                    if (lit) Modifier.background(PlayerGlass.Tile).border(1.5f.u, PlayerGlass.Edge, shape)
                    else Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = { tapped = true; onClick() },
                ),
        ) {
            if (animating && onAnimation != null) {
                Box(Modifier.size(60.u)) { onAnimation(anim.value) }
            } else {
                Icon(
                    painterResource(if (active) activeIcon else icon),
                    contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(60.u),
                )
            }
        }
    }
}

/** How long a tile animation runs. */
internal const val TILE_ANIM_MS = 760
