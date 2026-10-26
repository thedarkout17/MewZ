package com.headunit.music.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.R
import com.headunit.music.data.HISTORY_ID
import com.headunit.music.data.LIKED_ID
import com.headunit.music.data.Library
import com.headunit.music.ui.theme.LocalDesignScale

/** Library squares on the home screen (design px). Two side by side. */
const val LIBRARY_TILE = 760f
const val LIBRARY_TILE_GAP = 80f
/** Tile plus the name and song count under it. */
const val LIBRARY_ROW_HEIGHT = LIBRARY_TILE + 190f

internal val LibraryTextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)
internal val LibrarySecondary = Color.White.copy(alpha = 0.72f)

internal object LibraryGlass {
    /** Light catching the top of the glass, fading down. */
    val Sheen = Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x0FFFFFFF), Color(0x1AFFFFFF)))
    val Edge = Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x26FFFFFF)))
    val Panel = Color(0xF21E070B)
}

/** What a tile on the home grid stands for. */
sealed interface LibraryTileItem {
    data class Open(val library: Library) : LibraryTileItem
    data object Create : LibraryTileItem
}

/**
 * The grid under "Listen...": Liked, History, your libraries, then a + tile
 * to make a new one. Two large glass squares per row.
 */
@Composable
fun LibraryGrid(
    liked: Library,
    history: Library,
    custom: List<Library>,
    onOpen: (Library) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(LibraryTileItem.Open(liked), LibraryTileItem.Open(history)) +
        custom.map { LibraryTileItem.Open(it) } + LibraryTileItem.Create

    with(LocalDesignScale.current) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(0.u)) {
            items.chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LIBRARY_TILE_GAP.u),
                    modifier = Modifier.height(LIBRARY_ROW_HEIGHT.u),
                ) {
                    row.forEach { item ->
                        when (item) {
                            is LibraryTileItem.Open -> LibraryTile(
                                icon = iconFor(item.library),
                                title = item.library.name,
                                subtitle = songCount(item.library.tracks.size),
                                onClick = { onOpen(item.library) },
                            )
                            LibraryTileItem.Create -> LibraryTile(
                                icon = R.drawable.ic_add,
                                title = stringResource(R.string.new_library),
                                subtitle = null,
                                onClick = onCreate,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun iconFor(library: Library): Int = when (library.id) {
    LIKED_ID -> R.drawable.ic_heart_filled
    HISTORY_ID -> R.drawable.ic_clock
    else -> R.drawable.ic_music_note
}

fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"

/**
 * One library square: a pane of frosted glass (a blurred piece of the
 * background behind a sheen and a bright top edge) with a big icon in the
 * middle; the name and song count sit underneath.
 */
@Composable
private fun LibraryTile(icon: Int, title: String, subtitle: String?, onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(72.u)
        Column(Modifier.width(LIBRARY_TILE.u)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(LIBRARY_TILE.u)
                    .clip(shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = Color.White),
                        onClick = onClick,
                    ),
            ) {
                GlassPane(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().border(2.u, LibraryGlass.Edge, shape))
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(250.u),
                )
            }
            Spacer(Modifier.height(30.u))
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White, fontSize = 66.usp, fontWeight = FontWeight.Medium, shadow = LibraryTextShadow),
                modifier = Modifier.padding(start = 12.u),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    maxLines = 1,
                    style = TextStyle(LibrarySecondary, fontSize = 46.usp, shadow = LibraryTextShadow),
                    modifier = Modifier.padding(start = 12.u, top = 4.u),
                )
            }
        }
    }
}

/** Static frosted glass, made from the blurred home background so it matches the screen. */
@Composable
internal fun GlassPane(modifier: Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.home_background_blur),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(LibraryGlass.Sheen))
    }
}
