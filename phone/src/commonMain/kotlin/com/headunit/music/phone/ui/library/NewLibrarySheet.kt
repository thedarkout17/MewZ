package com.headunit.music.phone.ui.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush

import com.headunit.music.phone.ui.components.BackHandler
import com.headunit.music.phone.ui.theme.TextShadow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.headunit.music.phone.res.*
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.ui.components.GlassNameField
import com.headunit.music.phone.ui.theme.Brand
import com.headunit.music.phone.ui.theme.DesignCanvas
import com.headunit.music.phone.ui.theme.LocalDesignScale

private const val MAX_NAME = 40

/**
 * "New library": type a name (iOS keyboard), then Create. A tap on
 * empty space (or a swipe in from the left edge) cancels.
 * Drawn over everything (it can be opened from Now Playing's + too).
 */
@Composable
fun NewLibrarySheet(
    visible: Boolean,
    onCreate: (name: String) -> Unit,
    onCancel: () -> Unit,
    /** The same sheet renames a library: its current name to start from, a title and a key label. */
    initialName: String = "",
    title: String? = null,
    actionLabel: String? = null,
) {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(visible) { if (visible) name = initialName.take(MAX_NAME) }

    val p by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "newLibrary",
    )
    if (p == 0f) return
    BackHandler(enabled = visible, onBack = onCancel)

    val cancel by rememberUpdatedState(onCancel)
    // Reads the name when Create is pressed (not when the screen was first drawn),
    // so the keyboard's remembered action never sees a stale, empty name.
    fun create() {
        val typed = name
        if (typed.isNotBlank()) onCreate(typed.trim())
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p }
            // A tap on empty space cancels (and nothing underneath reacts).
            .pointerInput(Unit) { detectTapGestures(onTap = { cancel() }) },
    ) {
        Image(
            painter = painterResource(Res.drawable.home_background_blur),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color(0x332A0006)))

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                Text(
                    title ?: stringResource(Res.string.new_library),
                    style = TextStyle(Color.White, fontSize = 80.usp, fontWeight = FontWeight.SemiBold, shadow = TextShadow),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.u),
                )

                // Name field: the same frosted pill as the search bar, on the iOS keyboard.
                val focus = remember { FocusRequester() }
                LaunchedEffect(visible) { if (visible) runCatching { focus.requestFocus() } }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(70.u),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 250.u, start = 90.u, end = 90.u)
                        // Taps on the field and button don't count as empty space.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    GlassNameField(
                        value = name,
                        onValueChange = { v -> name = v.trimStart().replace("  ", " ").take(MAX_NAME) },
                        placeholder = "Library name",
                        focusRequester = focus,
                        onDone = { create() },
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(150.u)
                            .clip(RoundedCornerShape(75.u))
                            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF))))
                            .border(2.u, Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x33FFFFFF))), RoundedCornerShape(75.u))
                            .clickable(enabled = name.isNotBlank()) { create() }
                            .padding(horizontal = 110.u)
                            .graphicsLayer { alpha = if (name.isNotBlank()) 1f else 0.5f },
                    ) {
                        Text(
                            actionLabel ?: stringResource(Res.string.key_create),
                            style = TextStyle(Color.White, fontSize = 62.usp, fontWeight = FontWeight.SemiBold, shadow = TextShadow),
                        )
                    }
                }
            }
        }
    }
}
