package com.headunit.music.phone.ui.player

import kotlin.math.max
import kotlin.math.min

// ---- Portrait layout (design px on the 1500-wide canvas) ----
//
//              ━━━━                        ☰       ← grabber · queue          top + 40
//        “Road trip” — 3 of 20
//   ┌────────────────────────────────┐
//   │                                │
//   │      artwork (or lyrics)       │                                       ART square
//   │                                │
//   └────────────────────────────────┘
//               Song title
//                 Artist
//   0:12 ━━━━━━━━━━━━━━━━━━━━━━━━━━ -2:48
//            ⏮       ⏸       ⏭
//    [shuffle] [loop] [♡] [ + ] [lyrics]
//
internal const val SIDE = 110f            // left/right margin; artwork and seek bar span between them
internal const val ART_MINI = 200f        // artwork square in the dock
internal const val ART_MINI_X = 48f

/** Space from the artwork's bottom edge to the bottom of the tiles row. */
private const val BELOW_ART = 1000f

/** Where Now Playing's pieces go on a [width] × [height] screen with the given safe-area insets. */
internal class NowPlayingGeometry(width: Float, height: Float, val top: Float, bottom: Float) {
    /** Artwork side: full width between the margins, smaller only on short screens. */
    val art: Float = min(width - 2 * SIDE, height - top - bottom - BELOW_ART - 330f)
    val artX: Float = (width - art) / 2f
    /** Any height left over goes partly above the artwork, so the block sits a little low of centre. */
    private val spare = max(0f, height - top - bottom - (230f + art + BELOW_ART + 60f))
    val buttonsTop: Float = top + 30f
    val artTop: Float = top + 230f + spare * 0.4f
    val artBottom: Float = artTop + art
    val titleTop: Float = artBottom + 70f + spare * 0.15f
    val seekTop: Float = titleTop + 270f
    val transportTop: Float = seekTop + 120f
    val tilesTop: Float = transportTop + 280f + spare * 0.15f
}
