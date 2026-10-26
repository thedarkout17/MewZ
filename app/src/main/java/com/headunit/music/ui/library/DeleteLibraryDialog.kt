package com.headunit.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.headunit.music.core.model.Library
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.LocalDesignScale

/**
 * "Delete “Road trip”?" — shown when a library you made is held on the home
 * screen. Cancel, a tap outside, or Back keeps it.
 */
@Composable
fun DeleteLibraryDialog(
    library: Library?,
    onDelete: (Library) -> Unit,
    onCancel: () -> Unit,
) {
    // Keep showing the last one while it fades out.
    var shown by remember { mutableStateOf<Library?>(null) }
    SideEffect { if (library != null) shown = library }
    val cancel by rememberUpdatedState(onCancel)
    val p by animateFloatAsState(
        targetValue = if (library != null) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "deleteLibrary",
    )
    val lib = library ?: shown
    if (p == 0f || lib == null) return
    BackHandler(enabled = library != null, onBack = onCancel)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p }
            .background(Color(0x99000000))
            // A tap outside the card cancels (and nothing underneath reacts).
            .pointerInput(Unit) { detectTapGestures { cancel() } },
    ) {
        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                val shape = RoundedCornerShape(64.u)
                FrostedCard(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1040.u)
                        .graphicsLayer {
                            val s = 0.94f + 0.06f * p
                            scaleX = s; scaleY = s
                        }
                        // Taps on the card itself don't cancel.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = shape,
                ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(start = 72.u, end = 72.u, top = 72.u, bottom = 56.u),
                ) {
                    Text(
                        "Delete “${lib.name}”?",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = TextStyle(Color.White, fontSize = 68.usp, fontWeight = FontWeight.SemiBold, shadow = LibraryTextShadow),
                    )
                    Spacer(Modifier.height(20.u))
                    Text(
                        "This is not reversible, but songs in the library are still liked.",
                        textAlign = TextAlign.Center,
                        style = TextStyle(LibrarySecondary, fontSize = 44.usp),
                    )
                    Spacer(Modifier.height(56.u))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.u),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        DialogButton("Cancel", danger = false, onClick = onCancel, modifier = Modifier.weight(1f))
                        DialogButton("Delete", danger = true, onClick = { onDelete(lib) }, modifier = Modifier.weight(1f))
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, danger: Boolean, onClick: () -> Unit, modifier: Modifier) {
    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(percent = 50)
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .height(150.u)
                .clip(shape)
                .then(
                    if (danger) Modifier.background(Brand.Red)
                    else Modifier
                        .background(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF))))
                        .border(1.5f.u, LibraryGlass.Edge, shape)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick,
                ),
        ) {
            Text(text, style = TextStyle(Color.White, fontSize = 54.usp, fontWeight = FontWeight.Medium))
        }
    }
}
