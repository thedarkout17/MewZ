package com.headunit.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.R
import com.headunit.music.data.Library
import com.headunit.music.ui.theme.LocalDesignScale

private const val ROW = 152f

/**
 * Opened by + on Now Playing: your libraries (Liked isn't listed; adding
 * anywhere likes the song anyway), a tick on the ones that already have this
 * song, and "New library" at the bottom.
 */
@Composable
fun AddToLibraryPanel(
    libraries: List<Library>,
    contains: (Library) -> Boolean,
    onPick: (Library) -> Unit,
    onNewLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(64.u)
        // The same frosted glass as the library tiles and the hold menu.
        FrostedCard(modifier = modifier.width(1000.u), shape = shape) {
            Text(
                stringResource(R.string.add_to_library),
                style = TextStyle(Color.White, fontSize = 56.usp, fontWeight = FontWeight.SemiBold, shadow = LibraryTextShadow),
                modifier = Modifier.padding(start = 64.u, top = 48.u, bottom = 20.u),
            )
            LazyColumn(Modifier.heightIn(max = (ROW * 4).u)) {
                items(libraries, key = { it.id }) { lib ->
                    PanelRow(
                        icon = R.drawable.ic_music_note,
                        title = lib.name,
                        subtitle = songCount(lib.tracks.size),
                        checked = contains(lib),
                        onClick = { onPick(lib) },
                    )
                }
            }
            PanelRow(
                icon = R.drawable.ic_add,
                title = stringResource(R.string.new_library),
                subtitle = null,
                checked = false,
                onClick = onNewLibrary,
            )
            Spacer(Modifier.height(32.u))
        }
    }
}

/** One choice: its own pane of glass, like the hold menu's options. A tick on ones that already have the song. */
@Composable
private fun PanelRow(icon: Int, title: String, subtitle: String?, checked: Boolean, onClick: () -> Unit) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(40.u)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.u, vertical = 8.u)
                .height((ROW - 16f).u)
                .clip(shape)
                // Ones that already have the song are a little brighter.
                .background(if (checked) GlassOptionLit else GlassOption)
                .border(1.5f.u, LibraryGlass.Edge, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                )
                .padding(horizontal = 32.u),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.u)
                    .clip(RoundedCornerShape(24.u))
                    .background(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))),
            ) {
                Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(50.u))
            }
            Spacer(Modifier.width(32.u))
            Column(Modifier.weight(1f)) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White, fontSize = 50.usp, fontWeight = FontWeight.Medium, shadow = LibraryTextShadow),
                )
                if (subtitle != null) {
                    Text(subtitle, style = TextStyle(LibrarySecondary, fontSize = 36.usp, shadow = LibraryTextShadow))
                }
            }
            if (checked) {
                Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(56.u))
            }
        }
    }
}

/** A checked option: the same glass, a touch brighter. */
private val GlassOptionLit = Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x24FFFFFF)))
