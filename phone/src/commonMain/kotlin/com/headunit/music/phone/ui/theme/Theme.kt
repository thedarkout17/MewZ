package com.headunit.music.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Brand {
    /** Primary red used for the search icon, text and cursor. */
    val Red = Color(0xFFDB0A20)
    /** Slightly softer red for the "Search" placeholder. */
    val RedText = Color(0xFFE5213A)
    /** Translucent red for the secondary mic icon. */
    val RedMuted = Color(0x8CE0283C)
    /** Frosted pill fill for the search bar (sits over the gradient). */
    val Frost = Color(0xE6FADADA)
}

private val Colors = darkColorScheme(
    primary = Brand.Red,
    onPrimary = Color.White,
    background = Color(0xFFC4001A),
    onBackground = Color.White,
)

@Composable
fun HeadunitMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
