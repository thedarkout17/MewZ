package com.headunit.music.phone.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow

// Now Playing and the dock: text colours and glass (the rest of the app uses ui/theme/Styles.kt).
internal val PlayerTextShadow = Shadow(Color(0x66000000), Offset(0f, 2f), blurRadius = 10f)
internal val PlayerSecondary = Color.White.copy(alpha = 0.78f)
internal val PlayerTertiary = Color.White.copy(alpha = 0.62f)

internal object PlayerGlass {
    /** Dock artwork wash: ~12% brighter, plus a lift so dark covers don't go muddy. */
    val DockBrighten = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 22f,
                0f, 1.12f, 0f, 0f, 22f,
                0f, 0f, 1.12f, 0f, 22f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    /** Light along the top of the glass, fading quickly, with a faint lift at the bottom. */
    val DockSheen = Brush.verticalGradient(
        0f to Color(0x38FFFFFF), 0.35f to Color(0x0DFFFFFF), 0.85f to Color(0x05FFFFFF), 1f to Color(0x12FFFFFF),
    )
    /** Diagonal glint, like the library tiles. */
    val DockGlint = Brush.linearGradient(
        0f to Color(0x00FFFFFF), 0.45f to Color(0x00FFFFFF), 0.5f to Color(0x14FFFFFF), 0.55f to Color(0x00FFFFFF),
    )
    val Edge = Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF)))
    val Tile = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))
}
