package com.headunit.music.phone.ui.theme

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
 * Same "design pixels" as the headunit (.u / .usp), on a portrait canvas
 * [DESIGN_WIDTH] wide and as tall as the phone is. At 1500 wide, the headunit's
 * values come out at iPhone-sized measurements (its 186 px search bar is
 * ~48 pt, its 66 px titles ~17 pt), so most sizes carry over unchanged. Every
 * iPhone shows the same layout, just scaled to its width.
 */
const val DESIGN_WIDTH = 1500f

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

/** Fills the screen and provides a [DesignScale] for a canvas [DESIGN_WIDTH] wide. */
@Composable
fun DesignCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val d = LocalDensity.current
        val pxPerUnit = constraints.maxWidth / DESIGN_WIDTH
        CompositionLocalProvider(
            LocalDesignScale provides DesignScale(pxPerUnit, d.density, d.fontScale)
        ) {
            content()
        }
    }
}
