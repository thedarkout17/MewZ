package com.headunit.music.phone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.ui.theme.LocalDesignScale

/**
 * The app's frosted glass: the library tiles, the hold menu, confirmations.
 * (Now Playing and the dock use their own, see ui/player/PlayerStyles.kt.)
 */
object Glass {
    /** Light catching the top of the glass, falling off quickly so the middle stays clear. */
    val Sheen = Brush.verticalGradient(
        0f to Color(0x52FFFFFF), 0.4f to Color(0x0AFFFFFF), 0.8f to Color(0x00FFFFFF), 1f to Color(0x1AFFFFFF),
    )
    /** Diagonal glint across the pane. */
    val Glint = Brush.linearGradient(
        0f to Color(0x00FFFFFF), 0.42f to Color(0x00FFFFFF), 0.5f to Color(0x30FFFFFF), 0.58f to Color(0x00FFFFFF),
    )
    val Edge = Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0x4DFFFFFF)))
}

/**
 * A card of the same frosted glass as the library tiles (the blurred, lifted
 * home background, sheen, glint and bright edge), with a light dark wash so
 * white text reads on it. Used for the hold menu and its confirmations.
 */
@Composable
fun FrostedCard(
    modifier: Modifier,
    shape: Shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(modifier.clip(shape)) {
            GlassPane(Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color(0x38140306)))
            Box(Modifier.matchParentSize().border(3.u, Glass.Edge, shape))
            Column(content = content)
        }
    }
}

/** Glass pill for options in a [FrostedCard], like Now Playing's tiles. */
val GlassOption = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)))
/** The same pill with a soft pink hue, for Delete. */
val GlassOptionDanger = Brush.verticalGradient(listOf(Color(0x66FF7FA0), Color(0x38FF5C8A)))
val GlassOptionDangerEdge = Brush.verticalGradient(listOf(Color(0xCCFFC2D4), Color(0x40FFC2D4)))

/**
 * Static frosted glass: the home background, heavily blurred and lifted toward
 * white (baked into library_glass.jpg so it looks the same on every Android
 * version), half see-through, with a sheen and a diagonal glint on top.
 */
@Composable
fun GlassPane(modifier: Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(Res.drawable.library_glass),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Mostly see-through so the background's colour comes through clear
            // rather than milky.
            alpha = 0.5f,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Glass.Sheen))
        Box(Modifier.fillMaxSize().background(Glass.Glint))
    }
}
