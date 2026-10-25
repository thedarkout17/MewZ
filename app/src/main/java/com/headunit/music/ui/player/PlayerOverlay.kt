package com.headunit.music.ui.player

import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.headunit.music.R
import com.headunit.music.player.PlayerController
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale

/** Height of the mini-player dock (design px). HomeScreen keeps content clear of it. */
const val DOCK_HEIGHT = 280f

private val TextShadow = Shadow(Color(0x55000000), Offset(0f, 2f), blurRadius = 8f)
private val Secondary = Color.White.copy(alpha = 0.72f)

private object PlayerGlass {
    val SheetTint = Color(0x47300008)
    val SheetSheen = Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x10FFFFFF)))
    val Edge = Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF)))
    val Button = Brush.verticalGradient(listOf(Color(0x21FFFFFF), Color(0x0AFFFFFF)))
}

/**
 * The player, drawn over everything:
 * - **Now Playing** (expanded): video large on the left, song, seek bar and
 *   controls on the right, "Up next" under the video.
 * - **Dock** (minimized): a glass bar along the bottom with the small video,
 *   song and controls. Tap it to open Now Playing.
 *
 * The same video moves and resizes between the two, so playback never restarts.
 * [dockHidden] slides the dock away (e.g. while our keyboard is up).
 */
@Composable
fun PlayerOverlay(player: PlayerController, dockHidden: Boolean) {
    val track = player.current ?: return

    val e by animateFloatAsState(
        targetValue = if (player.expanded) 1f else 0f,
        animationSpec = tween(440, easing = FastOutSlowInEasing),
        label = "expand",
    )
    val away by animateFloatAsState(
        targetValue = if (dockHidden && !player.expanded) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "dockAway",
    )

    BackHandler(enabled = player.expanded) { player.expanded = false }

    Box(Modifier.fillMaxSize()) {
        // ---- Now Playing backdrop ----
        if (e > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = e }
                    // Swallow taps so nothing underneath reacts.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Image(
                    painter = painterResource(R.drawable.home_background_blur),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // The song's own colors, washed through the glass (Android 12+ can blur it).
                if (Build.VERSION.SDK_INT >= 31) {
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = 0.45f
                                renderEffect = BlurEffect(140f, 140f, TileMode.Clamp)
                            },
                    )
                }
                Box(Modifier.fillMaxSize().background(Color(0x47200005)))
            }
        }

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                val w = maxWidth / 1.u
                val h = maxHeight / 1.u
                fun lerp(a: Float, b: Float) = a + (b - a) * e
                val dockTop = h - DOCK_HEIGHT
                val awayShift = away * (DOCK_HEIGHT + 40f)

                // ---- Dock (minimized) ----
                if (e < 1f) {
                    Dock(
                        player = player,
                        width = w,
                        modifier = Modifier
                            .offset(y = dockTop.u)
                            .graphicsLayer {
                                alpha = 1f - e
                                translationY = awayShift.u.toPx()
                            },
                    )
                }

                // ---- Now Playing (expanded) ----
                if (e > 0f) {
                    NowPlaying(player = player, width = w, height = h, alpha = e)
                }

                // ---- The video: one view that glides between dock and Now Playing ----
                val vw = lerp(448f, 1040f)
                val vh = lerp(252f, 585f)
                val vx = lerp(64f, 120f)
                val vy = lerp(dockTop + 14f, (h - 585f) / 2f - 40f)
                val radius = lerp(24f, 40f)
                Box(
                    modifier = Modifier
                        .offset(x = vx.u, y = vy.u)
                        .graphicsLayer { translationY = (awayShift * (1f - e)).u.toPx() }
                        .width(vw.u)
                        .height(vh.u)
                        .clip(RoundedCornerShape(radius.u))
                        .background(Color.Black)
                        .border(1.5f.u, PlayerGlass.Edge, RoundedCornerShape(radius.u)),
                ) {
                    AndroidView(
                        factory = { player.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    VideoOverlay(player)
                }
            }
        }
    }
}

/** Buffering spinner or YouTube's refusal message, over the video. */
@Composable
private fun VideoOverlay(player: PlayerController) {
    with(LocalDesignScale.current) {
        val error = player.error
        when {
            error != null -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).padding(32.u),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.u)) {
                    Text(
                        error.message,
                        textAlign = TextAlign.Center,
                        style = TextStyle(Color.White, fontSize = 40.usp, fontWeight = FontWeight.Medium),
                    )
                    if (error.skippable && player.hasNext) {
                        Text("Skipping to the next song…", style = TextStyle(Secondary, fontSize = 32.usp))
                    }
                }
            }
            player.isBuffering && !player.isPlaying -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 5.u, modifier = Modifier.size(72.u))
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.NowPlaying(player: PlayerController, width: Float, height: Float, alpha: Float) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val videoTop = (height - 585f) / 2f - 40f
        val colX = 1240f
        val colW = width - colX - 120f

        // Minimize
        GlassCircleButton(
            icon = R.drawable.ic_keyboard_hide,
            contentDescription = stringResource(R.string.minimize_player),
            size = 130f, glyph = 64f,
            onClick = { player.expanded = false },
            modifier = Modifier.offset(x = 56.u, y = 48.u).graphicsLayer { this.alpha = alpha },
        )

        // Song, seek bar, controls — centred on the video's height
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .offset(x = colX.u, y = videoTop.u)
                .width(colW.u)
                .height(585.u)
                .graphicsLayer {
                    this.alpha = alpha
                    translationX = (1f - alpha) * 60.u.toPx()
                },
        ) {
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Color.White, fontSize = 80.usp, fontWeight = FontWeight.Medium, lineHeight = 92.usp, shadow = TextShadow),
            )
            Spacer(Modifier.height(16.u))
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Secondary, fontSize = 50.usp, shadow = TextShadow),
            )
            Spacer(Modifier.height(64.u))
            SeekBar(player, Modifier.fillMaxWidth())
            Spacer(Modifier.height(48.u))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(56.u, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassCircleButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_song), 150f, 72f, onClick = player::previous)
                GlassCircleButton(
                    if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                    210f, 100f, accent = true, onClick = player::togglePlay,
                )
                GlassCircleButton(
                    R.drawable.ic_skip_next, stringResource(R.string.next_song), 150f, 72f,
                    enabled = player.hasNext, onClick = player::next,
                )
            }
        }

        // Up next, under the video
        val next = player.upNext
        if (next != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .offset(x = 120.u, y = (videoTop + 585f + 48f).u)
                    .width(1040.u)
                    .graphicsLayer { this.alpha = alpha },
            ) {
                Text("UP NEXT", style = TextStyle(Secondary, fontSize = 34.usp, fontWeight = FontWeight.Medium, letterSpacing = 3.usp))
                Spacer(Modifier.width(28.u))
                Text(
                    "${next.title}  ·  ${next.artist}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White, fontSize = 44.usp, shadow = TextShadow),
                )
            }
        }
    }
}

@Composable
private fun Dock(player: PlayerController, width: Float, modifier: Modifier) {
    val track = player.current ?: return
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(topStart = 56.u, topEnd = 56.u)
        Box(
            modifier = modifier
                .width(width.u)
                .height(DOCK_HEIGHT.u)
                .clip(shape)
                .background(PlayerGlass.SheetTint)
                .background(PlayerGlass.SheetSheen)
                .border(2.u, PlayerGlass.Edge, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { player.expanded = true },
                ),
        ) {
            // Thin progress line along the top edge
            val fraction = if (player.durationSeconds > 0f) player.positionSeconds / player.durationSeconds else 0f
            Box(
                Modifier
                    .padding(horizontal = 56.u)
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.u)
                    .background(Brand.Red, RoundedCornerShape(3.u)),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    // Video sits at 64..512; text starts after it.
                    .padding(start = 560.u, end = 64.u),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.u)) {
                    Text(
                        track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(Color.White, fontSize = 60.usp, fontWeight = FontWeight.Medium, shadow = TextShadow),
                    )
                    Text(
                        track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(Secondary, fontSize = 44.usp, shadow = TextShadow),
                    )
                }
                Spacer(Modifier.width(40.u))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.u)) {
                    GlassCircleButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_song), 124f, 60f, onClick = player::previous)
                    GlassCircleButton(
                        if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                        160f, 80f, accent = true, onClick = player::togglePlay,
                    )
                    GlassCircleButton(
                        R.drawable.ic_skip_next, stringResource(R.string.next_song), 124f, 60f,
                        enabled = player.hasNext, onClick = player::next,
                    )
                }
            }
        }
    }
}

/** Glass track with a red fill. Tap or drag anywhere along it to seek. */
@Composable
private fun SeekBar(player: PlayerController, modifier: Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationSeconds
    val shown = dragFraction ?: if (duration > 0f) player.positionSeconds / duration else 0f

    with(LocalDesignScale.current) {
        Column(modifier) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.u) // tall touch target around a thin bar
                    .pointerInput(duration) {
                        detectTapGestures { pos ->
                            if (duration > 0f) player.seekTo((pos.x / size.width).coerceIn(0f, 1f) * duration)
                        }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos -> dragFraction = (pos.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { if (duration > 0f) player.seekTo(it * duration) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                Box(Modifier.fillMaxWidth().height(12.u).background(Color(0x33FFFFFF), CircleShape))
                Box(
                    Modifier
                        .fillMaxWidth(shown.coerceIn(0f, 1f))
                        .height(12.u)
                        .background(Brand.Red, CircleShape),
                )
                // Thumb
                Box(
                    Modifier
                        .fillMaxWidth(shown.coerceIn(0f, 1f))
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .offset(x = 18.u)
                            .size(if (dragFraction != null) 48.u else 36.u)
                            .background(Color.White, CircleShape),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val timeStyle = TextStyle(
                    Secondary, fontSize = 38.usp,
                    fontFeatureSettings = "tnum", // digits don't jiggle as they change
                )
                Text(formatTime(shown * duration), style = timeStyle)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = timeStyle)
            }
        }
    }
}

@Composable
private fun GlassCircleButton(
    icon: Int,
    contentDescription: String,
    size: Float,
    glyph: Float,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size.u)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .clip(CircleShape)
                .then(
                    if (accent) Modifier.background(Brand.Red)
                    else Modifier.background(PlayerGlass.Button).border(1.5f.u, PlayerGlass.Edge, CircleShape)
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(glyph.u))
        }
    }
}

private fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
