package com.headunit.music.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow

/** White text on the photo / blurred backgrounds gets this soft shadow so it stays readable. */
val TextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)

/** Second lines: artist · length, song counts, hints. */
val SecondaryText = Color.White.copy(alpha = 0.72f)
