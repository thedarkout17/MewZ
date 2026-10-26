package com.headunit.music.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.min

/**
 * Cuts the artwork square from the dead centre of a YouTube thumbnail and
 * outputs exactly [side] x [side] pixels (720 x 720 by default).
 *
 * - 16:9 images (maxres 1280x720): the full height is kept and the same amount
 *   is trimmed off each side, a 720x720 cut with no scaling at all.
 * - 4:3 images with bars baked in ([letterboxed] = true: standard 640x480,
 *   high 480x360): the picture is the 16:9 band in the middle, so the square
 *   comes from that band (bars excluded) and is scaled up to [side].
 */
class CenterSquareCrop(
    private val letterboxed: Boolean,
    private val side: Int = 720,
) : Transformation {

    override val cacheKey: String = "center-square-$side-$letterboxed"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        // Height of the real picture: a 16:9 band in the middle of a 4:3 image.
        val pictureH = if (letterboxed) min(h, w * 9 / 16) else h
        val square = min(w, pictureH)
        val x = (w - square) / 2
        val y = (h - square) / 2
        val cropped = Bitmap.createBitmap(input, x, y, square, square)
        return if (square == side) cropped
        else Bitmap.createScaledBitmap(cropped, side, side, true)
    }
}
