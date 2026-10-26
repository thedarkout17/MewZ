package com.headunit.music.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.headunit.music.ui.theme.LocalDesignScale

/*
 * The pages with a big title that shrinks as you scroll (a library, an album,
 * the queue, Add To Library, the cover song picker) share these two pieces.
 */

/**
 * How far the big title has shrunk: 0 = full size, 1 = small. It shrinks over
 * the first ([tall] - [short]) design px of scrolling [list].
 */
@Composable
fun rememberHeaderCollapse(list: LazyListState, tall: Float, short: Float): State<Float> {
    val scale = LocalDesignScale.current
    val distancePx = with(LocalDensity.current) { with(scale) { (tall - short).u }.toPx() }
    return remember(list, distancePx) {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f
            else (list.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
    }
}

/**
 * Rows dissolve as they slide up under the title: fully clear above
 * [headerHeight] - [edge], fully solid from [headerHeight] + [edge].
 */
fun Modifier.fadeUnderHeader(headerHeight: Dp, edge: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = headerHeight.toPx() - edge.toPx()
        val solid = top + edge.toPx() * 2
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                (top / size.height) to Color.Transparent,
                (solid / size.height) to Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
