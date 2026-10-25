package com.headunit.music.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.min

/**
 * Removes black bars baked into YouTube thumbnails, then crops to [aspect].
 *
 * YouTube images often carry bars: 4:3 thumbnails letterbox every 16:9 video,
 * and cinema-format videos (2.39:1) have bars even in the 16:9 image. This scans
 * in from each edge while the rows/columns are (near) solid black, trims them,
 * and centre-crops what's left. Pictures with no bars are just centre-cropped.
 */
class TrimBarsTransformation(private val aspect: Float = 1f) : Transformation {

    override val cacheKey: String = "trim-bars-v1-$aspect"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 8 || h < 8) return input

        // A line counts as "bar" when every sampled pixel is very dark.
        fun dark(color: Int): Boolean =
            Color.red(color) < DARK && Color.green(color) < DARK && Color.blue(color) < DARK

        fun rowIsBar(y: Int, x0: Int, x1: Int): Boolean {
            val span = x1 - x0
            for (i in 0..SAMPLES) if (!dark(input.getPixel(x0 + span * i / SAMPLES, y))) return false
            return true
        }

        fun colIsBar(x: Int, y0: Int, y1: Int): Boolean {
            val span = y1 - y0
            for (i in 0..SAMPLES) if (!dark(input.getPixel(x, y0 + span * i / SAMPLES))) return false
            return true
        }

        // Never trim more than 30% from any side (a genuinely dark picture isn't a bar).
        var top = 0
        while (top < h * 3 / 10 && rowIsBar(top, 0, w - 1)) top++
        var bottom = h - 1
        while (bottom > h * 7 / 10 && rowIsBar(bottom, 0, w - 1)) bottom--
        var left = 0
        while (left < w * 3 / 10 && colIsBar(left, top, bottom)) left++
        var right = w - 1
        while (right > w * 7 / 10 && colIsBar(right, top, bottom)) right--

        // Bars fade into the picture over a pixel or two; step past that edge.
        if (top > 0) top = min(top + EDGE, h / 2)
        if (bottom < h - 1) bottom = max(bottom - EDGE, h / 2)
        if (left > 0) left = min(left + EDGE, w / 2)
        if (right < w - 1) right = max(right - EDGE, w / 2)

        val cw = right - left + 1
        val ch = bottom - top + 1
        if (cw <= 0 || ch <= 0) return input

        // Centre-crop the picture area to the wanted shape.
        val (outW, outH) = if (cw.toFloat() / ch > aspect) {
            (ch * aspect).toInt().coerceAtLeast(1) to ch
        } else {
            cw to (cw / aspect).toInt().coerceAtLeast(1)
        }
        val x = left + (cw - outW) / 2
        val y = top + (ch - outH) / 2
        return Bitmap.createBitmap(input, x, y, outW, outH)
    }

    private companion object {
        const val DARK = 28      // 0..255; YouTube bars are ~0–16, JPEG noise adds a little
        const val SAMPLES = 24   // points checked per row/column
        const val EDGE = 2       // extra px trimmed past a detected bar
    }
}
