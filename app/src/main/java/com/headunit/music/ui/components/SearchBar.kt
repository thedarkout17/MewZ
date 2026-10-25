package com.headunit.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.headunit.music.R
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.LocalDesignScale

/**
 * Frosted pill search bar from the mockup: magnifier, text field, clear, mic.
 * All sizes are design pixels on the 2000x1200 canvas.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChange: (Boolean) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current

    with(LocalDesignScale.current) {
        val textStyle = TextStyle(
            color = Brand.RedText,
            fontSize = 92.usp,
            fontWeight = FontWeight.Normal,
        )

        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(232.u)
                .clip(RoundedCornerShape(percent = 50))
                .background(Brand.Frost)
                // Tapping anywhere on the pill focuses the field: big target for driving.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusRequester.requestFocus() }
                .padding(start = 66.u, end = 52.u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = Brand.Red,
                modifier = Modifier.size(108.u),
            )

            Spacer(Modifier.width(36.u))

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(Brand.Red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) {
                        focusManager.clearFocus()
                        onSearch(query.trim())
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.search_hint), style = textStyle)
                        }
                        inner()
                    }
                },
            )

            // Clear: only while there's text.
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.6f),
                exit = fadeOut() + scaleOut(targetScale = 0.6f),
            ) {
                RoundIconButton(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.clear_search),
                    tint = Brand.RedMuted,
                    glyph = 80,
                    onClick = {
                        onQueryChange("")
                        focusRequester.requestFocus()
                    },
                )
            }

            RoundIconButton(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(R.string.voice_search),
                tint = Brand.RedMuted,
                glyph = 96,
                onClick = onVoiceSearch,
            )
        }
    }
}

/** 150px circular touch target around a smaller glyph. */
@Composable
private fun RoundIconButton(
    painter: Painter,
    contentDescription: String,
    tint: Color,
    glyph: Int,
    onClick: () -> Unit,
) {
    with(LocalDesignScale.current) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.u)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Brand.Red),
                    onClick = onClick,
                ),
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(glyph.u),
            )
        }
    }
}
