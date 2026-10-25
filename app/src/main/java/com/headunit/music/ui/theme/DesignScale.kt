package com.headunit.music.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The UI is laid out on a fixed 2000 x 1200 design canvas (the headunit's resolution)
 * and scaled to whatever the real screen is. Headunits report all sorts of densities
 * (160, 240, 320 dpi...), so plain dp values look huge on one unit and tiny on the next.
 * Measuring in "design pixels" keeps the screen looking exactly like the mockup.
 */
const val DESIGN_WIDTH = 2000f
const val DESIGN_HEIGHT = 1200f

@Immutable
class DesignScale(
    /** Real pixels per design pixel. */
    private val pxPerUnit: Float,
    private val density: Float,
    private val fontScale: Float,
) {
    /** Design pixels → Dp. */
    val Number.u: Dp get() = Dp(toFloat() * pxPerUnit / density)

    /** Design pixels → sp, ignoring the system font-size setting so text stays in its box. */
    val Number.usp: TextUnit get() = (toFloat() * pxPerUnit / density / fontScale).sp
}

val LocalDesignScale = compositionLocalOf { DesignScale(1f, 1f, 1f) }

/** Fills the screen and provides a [DesignScale] that fits the 2000x1200 canvas inside it. */
@Composable
fun DesignCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val d = LocalDensity.current
        val pxPerUnit = minOf(
            constraints.maxWidth / DESIGN_WIDTH,
            constraints.maxHeight / DESIGN_HEIGHT,
        )
        CompositionLocalProvider(
            LocalDesignScale provides DesignScale(pxPerUnit, d.density, d.fontScale)
        ) {
            content()
        }
    }
}
