package com.headunit.music.phone.ui.player

import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.core.model.Track
import com.headunit.music.phone.data.LibraryStore
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.ui.theme.LocalDesignScale

@Composable
internal fun NowPlaying(
    player: PlayerController,
    libraries: LibraryStore,
    width: Float,
    geo: NowPlayingGeometry,
    alpha: Float,
    onNewLibrary: (Track) -> Unit,
    onOpenQueue: () -> Unit,
    /** The + tile: open the Add To Library page. */
    onOpenAdd: () -> Unit,
    /** That page is open (the + tile shows as on). */
    adding: Boolean,
    /** The lyrics tile: show lyrics in the artwork's place. */
    onOpenLyrics: () -> Unit,
    showingLyrics: Boolean,
    /** 0 = artwork, 1 = lyrics in its place. */
    lyricsP: Float,
    /** The grabber: minimize (a tap on empty space or a swipe down does too). */
    onMinimize: () -> Unit,
) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val fullW = width - 2 * SIDE

        // Grabber, top centre: the iOS "this sheet pulls down" handle.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = (width / 2f - 150f).u, y = geo.buttonsTop.u)
                .size(300.u, 130.u)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onMinimize)
                .graphicsLayer { this.alpha = alpha },
        ) {
            Box(Modifier.size(150.u, 18.u).background(Color(0x8CFFFFFF), CircleShape))
        }

        // Queue (top-right).
        GlassCircleButton(
            icon = Res.drawable.ic_queue,
            contentDescription = stringResource(Res.string.queue),
            size = 130f, glyph = 64f,
            onClick = onOpenQueue,
            modifier = Modifier
                .offset(x = (width - SIDE - 130f).u, y = geo.buttonsTop.u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Where we are in a library / album: "Road trip — 3 of 20". Nothing for search plays.
        player.queueName?.let { name ->
            Text(
                text = "$name — ${player.index + 1} of ${player.queue.size}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(PlayerTertiary, fontSize = 46.usp, shadow = PlayerTextShadow),
                modifier = Modifier
                    .offset(x = SIDE.u, y = (geo.artTop - 100f).u)
                    .width(fullW.u)
                    .graphicsLayer { this.alpha = alpha },
            )
        }

        // Song, under the artwork.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x = SIDE.u, y = geo.titleTop.u)
                .width(fullW.u)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = (1f - alpha) * 60.u.toPx()
                },
        ) {
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    Color.White, fontSize = 80.usp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 92.usp, shadow = PlayerTextShadow,
                ),
            )
            Spacer(Modifier.height(12.u))
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(PlayerSecondary, fontSize = 58.usp, shadow = PlayerTextShadow),
            )
        }

        // Seek bar, margin to margin.
        SeekBar(
            player,
            Modifier
                .offset(x = SIDE.u, y = geo.seekTop.u)
                .width(fullW.u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Transport: bare white glyphs with big touch areas.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(110.u, Alignment.CenterHorizontally),
            modifier = Modifier
                .offset(y = geo.transportTop.u)
                .width(width.u)
                .graphicsLayer { this.alpha = alpha },
        ) {
            GlyphButton(Res.drawable.ic_skip_previous, stringResource(Res.string.previous_song), 220f, 140f, onClick = player::previous)
            GlyphButton(
                if (player.isPlaying) Res.drawable.ic_pause else Res.drawable.ic_play,
                stringResource(if (player.isPlaying) Res.string.pause else Res.string.play),
                250f, 180f, onClick = player::togglePlay,
            )
            GlyphButton(
                Res.drawable.ic_skip_next, stringResource(Res.string.next_song), 220f, 140f,
                enabled = player.hasNext, onClick = player::next,
            )
        }

        // Shuffle · Loop · Like · Add · Lyrics.
        Row(
            horizontalArrangement = Arrangement.spacedBy(40.u, Alignment.CenterHorizontally),
            modifier = Modifier
                .offset(y = geo.tilesTop.u)
                .width(width.u)
                .graphicsLayer { this.alpha = alpha },
        ) {
            GlassTile(
                icon = Res.drawable.ic_shuffle,
                activeIcon = Res.drawable.ic_shuffle_bold,
                contentDescription = stringResource(Res.string.shuffle),
                active = player.shuffle,
                onClick = player::toggleShuffle,
                onAnimation = { t -> ShuffleGlyph(t) },
            )
            GlassTile(
                icon = Res.drawable.ic_repeat,
                activeIcon = Res.drawable.ic_repeat_bold,
                contentDescription = stringResource(Res.string.loop_song),
                active = player.loopSong,
                onClick = player::toggleLoop,
                onAnimation = { t -> LoopGlyph(t) },
            )
            GlassTile(
                icon = Res.drawable.ic_heart,
                activeIcon = Res.drawable.ic_heart_filled,
                contentDescription = stringResource(Res.string.like),
                active = libraries.isLiked(track),
                onClick = { libraries.toggleLike(track) },
                onAnimation = { t -> HeartGlyph(t) },
            )
            GlassTile(
                icon = Res.drawable.ic_add,
                contentDescription = stringResource(Res.string.add_to_library),
                active = adding,
                onClick = onOpenAdd,
            )
            GlassTile(
                icon = Res.drawable.ic_lyrics,
                activeIcon = Res.drawable.ic_lyrics_bold,
                contentDescription = stringResource(Res.string.lyrics),
                active = showingLyrics,
                onClick = onOpenLyrics,
                onAnimation = { t -> MicNotesGlyph(t) },
            )
        }
    }
}
