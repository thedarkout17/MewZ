package com.headunit.music.phone.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.res.clear_search
import com.headunit.music.phone.res.ic_close
import com.headunit.music.phone.res.ic_search
import com.headunit.music.phone.res.search_hint
import com.headunit.music.phone.ui.theme.Brand
import com.headunit.music.phone.ui.theme.LocalDesignScale
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Bar height at rest and in search mode (design px). HomeScreen uses these to place things below it. */
const val BAR_HEIGHT_IDLE = 186f
const val BAR_HEIGHT_COMPACT = 150f

/**
 * Frosted pill search bar, as on the headunit: magnifier, query, clear. On the
 * iPhone it's a real text field using the iOS keyboard (whose own mic does voice
 * search), where the headunit draws its own glass keyboard.
 *
 * [active]: the field has the keyboard (white outline). [compact] runs 0 → 1
 * as search mode opens: the bar slims and its text and icon shrink with it.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    focusRequester: FocusRequester,
    onActivate: () -> Unit,
    onFocusLost: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Float = 0f,
) {
    fun shrink(idle: Float, small: Float) = idle + (small - idle) * compact
    val typing by animateFloatAsState(if (active) 1f else 0f, tween(200), label = "searchOutline")

    with(LocalDesignScale.current) {
        val textStyle = TextStyle(color = Brand.RedText, fontSize = shrink(72f, 62f).usp, fontWeight = FontWeight.Normal)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(shrink(BAR_HEIGHT_IDLE, BAR_HEIGHT_COMPACT).u)
                .clip(RoundedCornerShape(percent = 50))
                .background(Brand.Frost)
                .border(3.5f.u, Color.White.copy(alpha = typing), RoundedCornerShape(percent = 50))
                // Tapping anywhere on the pill opens the keyboard.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onActivate,
                )
                .padding(start = shrink(60f, 50f).u, end = shrink(30f, 24f).u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_search),
                contentDescription = null,
                tint = Brand.Red,
                modifier = Modifier.size(shrink(84f, 70f).u),
            )
            Spacer(Modifier.width(shrink(32f, 28f).u))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(stringResource(Res.string.search_hint), style = textStyle, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = { v -> onQueryChange(v.trimStart().replace("  ", " ")) },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(Brand.Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onActivate() else onFocusLost() },
                )
            }
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.6f),
                exit = fadeOut() + scaleOut(targetScale = 0.6f),
            ) {
                RoundIconButton(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = stringResource(Res.string.clear_search),
                    tint = Brand.RedMuted,
                    glyph = shrink(64f, 56f),
                    target = shrink(160f, 136f),
                    onClick = onClear,
                )
            }
        }
    }
}

/**
 * The frosted pill as a name field (new library / rename): iOS keyboard with
 * [actionLabel]-style Done key, words capitalised.
 */
@Composable
fun GlassNameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    with(LocalDesignScale.current) {
        val style = TextStyle(Brand.RedText, fontSize = 72.usp)
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = modifier
                .fillMaxWidth()
                .height(186.u)
                .clip(RoundedCornerShape(percent = 50))
                .background(Brand.Frost)
                .border(3.5f.u, Color.White, RoundedCornerShape(percent = 50))
                .padding(horizontal = 70.u),
        ) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = Brand.RedText.copy(alpha = 0.45f)), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Brand.Red),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

/** Circular touch target ([target] px) around a smaller glyph. */
@Composable
private fun RoundIconButton(
    painter: Painter,
    contentDescription: String,
    tint: Color,
    glyph: Float,
    target: Float,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(target.u)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Brand.Red),
                    onClick = onClick,
                ),
        ) {
            Icon(painter = painter, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(glyph.u))
        }
    }
}
