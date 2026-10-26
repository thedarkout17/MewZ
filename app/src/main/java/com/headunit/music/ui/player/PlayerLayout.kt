package com.headunit.music.ui.player

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ---- Layout (design px on the 2000x1200 canvas), after the CarPlay Now Playing ----
//   ⌄                                         “starboy” — 3 of 20     ← y 48–178
//   ┌──────────┐        Song title
//   │ artwork  │        Artist
//   │  600×600 │      ⏮     ⏸     ⏭                                  ← top row y 210–810
//   └──────────┘
//   0:12 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ -2:48    ← full width, y ~900
//                     [shuffle]  [loop]  [ + ]                         ← y ~1010
internal const val SIDE = 96f            // left/right margin; the seek bar spans between them
internal const val ART_FULL = 600f       // artwork square on Now Playing
internal const val ART_FULL_X = SIDE
internal const val ART_TOP = 210f
internal const val COLUMN_GAP = 96f      // artwork → title/transport column
internal const val SEEK_TOP = 862f
internal const val TILES_TOP = 1006f
internal const val ART_MINI = 224f       // artwork square in the dock
internal const val ART_MINI_X = 56f

/** The transport row's width: previous (200) + play (240) + next (200) and two 150 gaps. */
internal const val TRANSPORT_W = 200f + 240f + 200f + 2 * 150f

/**
 * Where the lyrics card ends on a screen [width] wide: just short of the
 * previous button (the transport is centred in the column beside the artwork).
 */
internal fun lyricsRight(width: Float): Float {
    val colX = ART_FULL_X + ART_FULL + COLUMN_GAP
    val colW = width - colX - SIDE
    return colX + (colW - TRANSPORT_W) / 2f - 16f
}
