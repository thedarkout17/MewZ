package com.headunit.music.phone.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * The square from the dead centre of a YouTube picture: the iPhone's version of
 * the headunit's CenterSquareCrop (which works on Android bitmaps).
 *
 * Cropping a picture to a square box keeps its full height. For YouTube's 4:3
 * thumbnails with black bars baked in ([letterboxed]), the real picture is only
 * the middle 3/4 of that height, so it's zoomed by 4/3 to cut the bars off.
 */
@Composable
fun SquareArtwork(
    url: String,
    letterboxed: Boolean,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    Box(modifier.clipToBounds()) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            colorFilter = colorFilter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (letterboxed) {
                        scaleX = 4f / 3f
                        scaleY = 4f / 3f
                    }
                },
        )
    }
}
