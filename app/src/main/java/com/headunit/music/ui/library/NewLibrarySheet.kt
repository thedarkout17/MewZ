package com.headunit.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.headunit.music.R
import com.headunit.music.ui.components.BlinkingCursor
import com.headunit.music.ui.keyboard.GlassKeyboard
import com.headunit.music.ui.keyboard.KEYBOARD_HEIGHT
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale

private const val MAX_NAME = 40

/**
 * "New library": type a name on the glass keyboard, then Create.
 * Drawn over everything (it can be opened from Now Playing's + too).
 */
@Composable
fun NewLibrarySheet(
    visible: Boolean,
    onCreate: (name: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(visible) { if (visible) name = "" }

    val p by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "newLibrary",
    )
    if (p == 0f) return
    BackHandler(enabled = visible, onBack = onCancel)

    val shown = name
    fun create() {
        if (shown.isNotBlank()) onCreate(shown.trim())
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p }
            .pointerInput(Unit) { detectTapGestures { } }, // nothing underneath reacts
    ) {
        Image(
            painter = painterResource(R.drawable.home_background_blur),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color(0x332A0006)))

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                // Cancel, top-left
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = 56.u, y = 48.u)
                        .size(130.u)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF))))
                        .border(1.5f.u, LibraryGlass.Edge, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = Color.White),
                            onClick = onCancel,
                        ),
                ) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.back), tint = Color.White, modifier = Modifier.size(64.u))
                }

                Text(
                    stringResource(R.string.new_library),
                    style = TextStyle(Color.White, fontSize = 80.usp, fontWeight = FontWeight.SemiBold, shadow = LibraryTextShadow),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.u),
                )

                // Name field: the same frosted pill as the search bar.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 250.u, start = 200.u, end = 200.u)
                        .fillMaxWidth()
                        .height(200.u)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Brand.Frost)
                        .padding(horizontal = 80.u),
                ) {
                    val style = TextStyle(Brand.RedText, fontSize = 84.usp)
                    if (name.isEmpty()) {
                        BlinkingCursor(visible = true, restartKey = name, height = 92f)
                        Text("Library name", style = style.copy(color = Brand.RedText.copy(alpha = 0.45f)), maxLines = 1)
                    } else {
                        Text(shown, style = style, maxLines = 1, softWrap = false)
                        BlinkingCursor(visible = true, restartKey = name, height = 92f)
                    }
                }

                GlassKeyboard(
                    onType = { c ->
                        if (name.length < MAX_NAME && (c != ' ' || (name.isNotEmpty() && !name.endsWith(' ')))) name += c
                    },
                    onBackspace = { if (name.isNotEmpty()) name = name.dropLast(1) },
                    onSearch = ::create,
                    onHide = onCancel,
                    actionLabel = stringResource(R.string.key_create),
                    actionIcon = null,
                    // Shift starts on, so the name begins with a capital.
                    startCapitalized = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = (1f - p) * KEYBOARD_HEIGHT.u.toPx() },
                )
            }
        }
    }
}
