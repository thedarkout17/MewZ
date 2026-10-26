package com.headunit.music.phone.ui.player

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.ui.components.Glass
import com.headunit.music.phone.ui.theme.LocalDesignScale

@Composable
internal fun Dock(player: PlayerController, width: Float, bottomInset: Float, modifier: Modifier) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(topStart = 56.u, topEnd = 56.u)
        Box(
            modifier = modifier
                .width(width.u)
                // The glass runs on under the home indicator; the controls stay above it.
                .height((DOCK_HEIGHT + bottomInset).u)
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { player.expanded = true },
                ),
        ) {
            // Glass made from the song's artwork: loaded tiny and stretched (soft on
            // every Android version), heavily blurred on 12+, brightened, ~95% opaque.
            val context = LocalPlatformContext.current
            val artRequest = remember(track.thumbnailUrl) {
                // Tinier still (12x7) and blurred harder than Now Playing: just a wash of colour.
                ImageRequest.Builder(context).data(track.thumbnailUrl).size(12, 7).build()
            }
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                colorFilter = PlayerGlass.DockBrighten,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.95f
                        // Zoom hides the soft edges the blur leaves.
                        scaleX = 1.4f; scaleY = 1.4f
                        renderEffect = BlurEffect(170f, 170f, TileMode.Clamp)
                    },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(PlayerGlass.DockSheen)
                    .background(PlayerGlass.DockGlint)
                    .border(2.u, PlayerGlass.Edge, shape),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DOCK_HEIGHT.u)
                    // Text starts after the artwork.
                    .padding(start = (ART_MINI_X + ART_MINI + 44f).u, end = 40.u),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.u)) {
                    Text(
                        track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.Medium, shadow = PlayerTextShadow),
                    )
                    Text(
                        track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(PlayerSecondary, fontSize = 44.usp, shadow = PlayerTextShadow),
                    )
                }
                Spacer(Modifier.width(30.u))
                // (No previous button: there's no room on a phone. Now Playing and the lock screen have one.)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.u)) {
                    GlassCircleButton(
                        if (player.isPlaying) Res.drawable.ic_pause else Res.drawable.ic_play,
                        stringResource(if (player.isPlaying) Res.string.pause else Res.string.play),
                        160f, 80f, onClick = player::togglePlay,
                    )
                    GlassCircleButton(
                        Res.drawable.ic_skip_next, stringResource(Res.string.next_song), 124f, 60f,
                        enabled = player.hasNext, onClick = player::next,
                    )
                }
            }
        }
    }
}
