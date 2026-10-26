package com.headunit.music.ui.player

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.R
import com.headunit.music.core.model.Track
import com.headunit.music.data.LibraryStore
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.theme.LocalDesignScale

@Composable
internal fun NowPlaying(
    player: PlayerController,
    libraries: LibraryStore,
    width: Float,
    screenHeight: Float,
    shiftY: Float,
    alpha: Float,
    onNewLibrary: (Track) -> Unit,
    onOpenQueue: () -> Unit,
    /** The + tile: open the Add To Library page. */
    onOpenAdd: () -> Unit,
    /** That page is open (the + tile shows as on). */
    adding: Boolean,
    /** The lyrics tile: open the Lyrics page. */
    onOpenLyrics: () -> Unit,
    showingLyrics: Boolean,
    /** 0 = artwork, 1 = lyrics in its place. */
    lyricsP: Float,
) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val colX = ART_FULL_X + ART_FULL + COLUMN_GAP
        val colW = width - colX - SIDE
        val fullW = width - 2 * SIDE

        // (No minimize button: a tap on empty space, or Back, minimizes.)

        // Queue (top-right).
        GlassCircleButton(
            icon = R.drawable.ic_queue,
            contentDescription = stringResource(R.string.queue),
            size = 130f, glyph = 64f,
            onClick = onOpenQueue,
            modifier = Modifier
                .offset(x = (width - SIDE - 130f).u, y = (48f + shiftY).u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Where we are in a library / playlist, left of the queue button: "Road trip — 3 of 20".
        // Nothing for search results, which aren't a collection.
        player.queueName?.let { name ->
            Text(
                text = "$name — ${player.index + 1} of ${player.queue.size}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                style = TextStyle(PlayerTertiary, fontSize = 44.usp, shadow = PlayerTextShadow),
                modifier = Modifier
                    .offset(x = (width / 2f).u, y = (82f + shiftY).u)
                    .width((width / 2f - SIDE - 130f - 40f).u)
                    .graphicsLayer { this.alpha = alpha },
            )
        }

        // Beside the artwork: song, then transport, spread over the artwork's height
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x = colX.u, y = (ART_TOP + shiftY).u)
                .width(colW.u)
                .height(ART_FULL.u)
                .graphicsLayer {
                    this.alpha = alpha
                    translationX = (1f - alpha) * 60.u.toPx()
                },
        ) {
            Spacer(Modifier.height(10.u))
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    Color.White, fontSize = 88.usp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 100.usp, shadow = PlayerTextShadow,
                ),
            )
            Spacer(Modifier.height(14.u))
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(PlayerSecondary, fontSize = 56.usp, shadow = PlayerTextShadow),
            )

            Spacer(Modifier.weight(1f))

            // Transport: bare white glyphs (big touch areas around them)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(150.u, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlyphButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_song), 200f, 140f, onClick = player::previous)
                GlyphButton(
                    if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                    240f, 172f, onClick = player::togglePlay,
                )
                GlyphButton(
                    R.drawable.ic_skip_next, stringResource(R.string.next_song), 200f, 140f,
                    enabled = player.hasNext, onClick = player::next,
                )
            }
            // Lifts the transport a little off the artwork's bottom edge.
            Spacer(Modifier.height(60.u))
        }

        // Seek bar across the whole screen, margin to margin
        SeekBar(
            player,
            Modifier
                .offset(x = SIDE.u, y = (SEEK_TOP + shiftY).u)
                .width(fullW.u)
                .graphicsLayer { this.alpha = alpha },
        )

        // Shuffle · Loop · Like · Add · Lyrics, centred under the bar
        Row(
            horizontalArrangement = Arrangement.spacedBy(48.u, Alignment.CenterHorizontally),
            modifier = Modifier
                .offset(y = (TILES_TOP + shiftY).u)
                .width(width.u)
                .graphicsLayer { this.alpha = alpha },
        ) {
            GlassTile(
                icon = R.drawable.ic_shuffle,
                activeIcon = R.drawable.ic_shuffle_bold,
                contentDescription = stringResource(R.string.shuffle),
                active = player.shuffle,
                onClick = player::toggleShuffle,
                onAnimation = { t -> ShuffleGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_repeat,
                activeIcon = R.drawable.ic_repeat_bold,
                contentDescription = stringResource(R.string.loop_song),
                active = player.loopSong,
                onClick = player::toggleLoop,
                onAnimation = { t -> LoopGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_heart,
                activeIcon = R.drawable.ic_heart_filled,
                contentDescription = stringResource(R.string.like),
                active = libraries.isLiked(track),
                onClick = { libraries.toggleLike(track) },
                onAnimation = { t -> HeartGlyph(t) },
            )
            GlassTile(
                icon = R.drawable.ic_add,
                contentDescription = stringResource(R.string.add_to_library),
                active = adding,
                onClick = onOpenAdd,
            )
            GlassTile(
                icon = R.drawable.ic_lyrics,
                activeIcon = R.drawable.ic_lyrics_bold,
                contentDescription = stringResource(R.string.lyrics),
                active = showingLyrics,
                onClick = onOpenLyrics, // lyrics show straight away; the notes play alongside
                onAnimation = { t -> MicNotesGlyph(t) },
            )
        }
    }
}
