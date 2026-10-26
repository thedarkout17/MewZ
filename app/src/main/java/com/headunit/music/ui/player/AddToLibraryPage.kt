package com.headunit.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.R
import com.headunit.music.data.LIKED_ID
import com.headunit.music.data.Library
import com.headunit.music.data.LibraryStore
import com.headunit.music.data.Track
import com.headunit.music.ui.library.CoverCollage
import com.headunit.music.ui.library.GlassPane
import com.headunit.music.ui.library.LibraryGlass
import com.headunit.music.ui.library.LibrarySecondary
import com.headunit.music.ui.library.LibraryTextShadow
import com.headunit.music.ui.library.iconFor
import com.headunit.music.ui.library.songCount
import com.headunit.music.ui.theme.LocalDesignScale

// Header (design px), the same as the queue's: big title shrinking as you scroll.
private const val HEADER_TALL = 470f
private const val HEADER_SHORT = 190f
private const val TITLE_BIG = 132f
private const val TITLE_SMALL = 76f
private const val ROW_HEIGHT = 220f
private const val TILE = 196f

/**
 * "Add To Library", laid out like the queue over Now Playing's blurred artwork:
 * a big title (with the song under it), then New Library, Liked and every
 * library you've made, each with its cover. Ones that already have the song
 * are ticked. Tap one to add the song (back to Now Playing), or a ticked one to
 * take it out. A tap on empty space or Back returns to Now Playing.
 */
@Composable
fun AddToLibraryPage(
    track: Track,
    libraries: LibraryStore,
    onNewLibrary: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    with(LocalDesignScale.current) {
        val collapseDistance = (HEADER_TALL - HEADER_SHORT).u
        val c by remember {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else (list.firstVisibleItemScrollOffset / with(density) { collapseDistance.toPx() }).coerceIn(0f, 1f)
            }
        }
        val headerH = HEADER_TALL + (HEADER_SHORT - HEADER_TALL) * c
        // Liked first (adding anywhere likes the song anyway), then the ones you made.
        val choices: List<Library> = listOfNotNull(libraries.byId(LIKED_ID)) + libraries.custom

        // A tap on empty space (not a row) goes back to Now Playing.
        Box(modifier.fillMaxSize().pointerInput(onClose) { detectTapGestures(onTap = { onClose() }) }) {
            LazyColumn(
                state = list,
                verticalArrangement = Arrangement.spacedBy(20.u),
                contentPadding = PaddingValues(top = HEADER_TALL.u, bottom = 64.u, start = 80.u, end = 80.u),
                modifier = Modifier
                    .fillMaxSize()
                    // Rows dissolve as they slide up under the title.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val top = headerH.u.toPx() - 30.u.toPx()
                        val solid = top + 60.u.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                (top / size.height) to Color.Transparent,
                                (solid / size.height) to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                item(key = "new") {
                    LibraryChoiceRow(
                        library = null,
                        title = stringResource(R.string.new_library),
                        subtitle = "Make one and put this song in it",
                        checked = false,
                        onClick = onNewLibrary,
                    )
                }
                items(choices, key = { it.id }) { lib ->
                    val has = if (lib.id == LIKED_ID) libraries.isLiked(track) else libraries.contains(lib.id, track)
                    LibraryChoiceRow(
                        library = lib,
                        title = lib.name,
                        subtitle = songCount(lib.tracks.size),
                        checked = has,
                        onClick = {
                            when {
                                // Ticked ones come back out (and stay here, so you can see it).
                                has && lib.id == LIKED_ID -> libraries.unlike(track)
                                has -> libraries.removeFrom(lib.id, track)
                                // Otherwise in it goes (which likes it too), and back to Now Playing.
                                lib.id == LIKED_ID -> { libraries.like(track); onClose() }
                                else -> { libraries.addTo(lib.id, track); onClose() }
                            }
                        },
                    )
                }
            }

            // "Add To Library" and the song, shrinking into the top-left as you scroll.
            Column(Modifier.offset(x = 96.u, y = (226f + (64f - 226f) * c).u).padding(end = 96.u)) {
                Text(
                    "Add To Library",
                    maxLines = 1,
                    style = TextStyle(
                        Color.White,
                        fontSize = (TITLE_BIG + (TITLE_SMALL - TITLE_BIG) * c).usp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = LibraryTextShadow,
                    ),
                )
                Text(
                    "${track.title}  ·  ${track.artist}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(LibrarySecondary, fontSize = 46.usp, shadow = LibraryTextShadow),
                    modifier = Modifier.graphicsLayer { alpha = 1f - c },
                )
            }
        }
    }
}

/**
 * A library to put the song in, in the queue's row size: its glass tile (the
 * cover art, or its icon), name and song count, and a tick if the song's in it.
 * [library] null = "New Library".
 */
@Composable
private fun LibraryChoiceRow(library: Library?, title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT.u)
                .clip(RoundedCornerShape(28.u))
                .then(if (checked) Modifier.background(Color(0x24FFFFFF)) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(horizontal = 16.u),
        ) {
            val shape = RoundedCornerShape(36.u)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(TILE.u).clip(shape),
            ) {
                GlassPane(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().border(2.u, LibraryGlass.Edge, shape))
                val covers = library?.takeIf { it.id != LIKED_ID }?.coverTracks.orEmpty()
                if (covers.isNotEmpty()) {
                    CoverCollage(covers, Modifier.fillMaxSize())
                } else {
                    Icon(
                        painterResource(if (library == null) R.drawable.ic_add else iconFor(library)),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(84.u),
                    )
                }
            }
            Spacer(Modifier.width(48.u))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.u)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White, fontSize = 66.usp, fontWeight = FontWeight.Medium, shadow = LibraryTextShadow),
                )
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(LibrarySecondary, fontSize = 46.usp, shadow = LibraryTextShadow),
                )
            }
            if (checked) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(end = 32.u).size(64.u),
                )
            }
        }
    }
}
