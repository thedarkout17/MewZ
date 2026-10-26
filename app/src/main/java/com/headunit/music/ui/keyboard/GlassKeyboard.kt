package com.headunit.music.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.headunit.music.R
import com.headunit.music.ui.theme.Brand
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keyboard sheet height on the 2000x1200 canvas. HomeScreen uses it to place the sheet. */
const val KEYBOARD_HEIGHT = 540f

private object KeyboardGlass {
    /** Sheet: dark-red tint for contrast, then a soft white sheen from the top. */
    val SheetTint = Color(0x47300008)
    val SheetSheen = Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x10FFFFFF)))
    val SheetEdge = Color(0x66FFFFFF)

    /** Keys: mostly clear, a faint sheen that fades toward the bottom. */
    val Key = Brush.verticalGradient(listOf(Color(0x21FFFFFF), Color(0x0AFFFFFF)))
    val KeyPressed = Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x2EFFFFFF)))
    /** Edge catches light at the top and fades out, like a glass bevel. */
    val KeyEdge = Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF)))
    val Label = Color.White
}

private const val LETTERS_1 = "qwertyuiop"
private const val LETTERS_2 = "asdfghjkl"
private const val LETTERS_3 = "zxcvbnm"
private const val SYMBOLS_1 = "1234567890"
private const val SYMBOLS_2 = "-/:;()\$&@\""
private const val SYMBOLS_3 = ".,?!'#+"

/**
 * In-app glass keyboard. Lowercase only (search doesn't need capitals),
 * with a 123 page for digits and punctuation. Keys are ~100px tall: big enough to hit, small enough to leave three suggestions in view.
 */
@Composable
fun GlassKeyboard(
    onType: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSearch: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    /** The red key: "Search" by default; naming a library uses "Create". */
    actionLabel: String = stringResource(R.string.key_search),
    actionIcon: Int? = R.drawable.ic_search,
    /** Start with Shift on, so the first letter is a capital (naming a library). */
    startCapitalized: Boolean = false,
) {
    var symbols by rememberSaveable { mutableStateOf(false) }
    // Shift: one tap = the next letter is a capital; double-tap = caps lock; tap again = off.
    var shift by remember { mutableStateOf(if (startCapitalized) Shift.Once else Shift.Off) }
    var lastShiftTap by remember { mutableStateOf(0L) }
    val typeChar: (Char) -> Unit = { c ->
        if (shift != Shift.Off && c.isLetter()) {
            onType(c.uppercaseChar())
            if (shift == Shift.Once) shift = Shift.Off
        } else onType(c)
    }
    fun tapShift() {
        val now = android.os.SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.Once && now - lastShiftTap < 400 -> Shift.Lock
            shift == Shift.Off -> Shift.Once
            else -> Shift.Off
        }
        lastShiftTap = now
    }
    val upper = shift != Shift.Off

    with(LocalDesignScale.current) {
        val keyGap = 16.u
        Column(
            verticalArrangement = Arrangement.spacedBy(16.u),
            modifier = modifier
                .fillMaxWidth()
                .height(KEYBOARD_HEIGHT.u)
                .clip(RoundedCornerShape(topStart = 56.u, topEnd = 56.u))
                .background(KeyboardGlass.SheetTint)
                .background(KeyboardGlass.SheetSheen)
                .border(
                    width = 2.u,
                    color = KeyboardGlass.SheetEdge,
                    shape = RoundedCornerShape(topStart = 56.u, topEnd = 56.u),
                )
                // Swallow taps on the gaps so they don't fall through to the screen behind.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 96.u, end = 96.u, top = 26.u, bottom = 30.u),
        ) {
            val rowModifier = Modifier.fillMaxWidth().weight(1f)
            val r1 = if (symbols) SYMBOLS_1 else LETTERS_1
            val r2 = if (symbols) SYMBOLS_2 else LETTERS_2
            val r3 = if (symbols) SYMBOLS_3 else LETTERS_3

            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                r1.forEach { c -> CharKey(c, typeChar, upper) }
            }
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                // Nine letters sit half a key in, like a real keyboard.
                if (r2.length < 10) Spacer(Modifier.weight(0.5f))
                r2.forEach { c -> CharKey(c, typeChar, upper) }
                if (r2.length < 10) Spacer(Modifier.weight(0.5f))
            }
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                // Shift (letters page); the symbols page has nothing to capitalise.
                GlassKey(
                    weight = 1.5f,
                    label = stringResource(R.string.key_shift),
                    onPress = { if (!symbols) tapShift() },
                    lit = shift != Shift.Off && !symbols,
                ) {
                    if (!symbols) KeyIcon(
                        when (shift) {
                            Shift.Off -> R.drawable.ic_shift
                            Shift.Once -> R.drawable.ic_shift_on
                            Shift.Lock -> R.drawable.ic_caps_lock
                        },
                        58.u,
                    )
                }
                r3.forEach { c -> CharKey(c, typeChar, upper) }
                GlassKey(
                    weight = 1.5f,
                    label = stringResource(R.string.key_backspace),
                    repeatOnHold = true,
                    onPress = onBackspace,
                ) { KeyIcon(R.drawable.ic_backspace, 62.u) }
            }
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                GlassKey(weight = 1.25f, label = stringResource(R.string.key_hide), onPress = onHide) {
                    KeyIcon(R.drawable.ic_keyboard_hide, 62.u)
                }
                GlassKey(weight = 1.25f, label = if (symbols) "abc" else "123", onPress = { symbols = !symbols }) {
                    KeyLabel(if (symbols) "abc" else "123", small = true)
                }
                CharKey(',', typeChar, upper)
                GlassKey(weight = 3.5f, label = stringResource(R.string.key_space), onPress = { onType(' ') }) {
                    KeyLabel(stringResource(R.string.key_space), small = true, faded = true)
                }
                CharKey('.', typeChar, upper)
                GlassKey(
                    weight = 2f,
                    label = actionLabel,
                    accent = true,
                    onPress = onSearch,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (actionIcon != null) {
                            KeyIcon(actionIcon, 46.u)
                            Spacer(Modifier.width(16.u))
                        }
                        KeyLabel(actionLabel, small = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CharKey(c: Char, onType: (Char) -> Unit, upper: Boolean) {
    // Letters show as capitals while Shift is on.
    val shown = if (upper) c.uppercaseChar() else c
    GlassKey(weight = 1f, label = shown.toString(), onPress = { onType(c) }) {
        KeyLabel(shown.toString())
    }
}

private enum class Shift { Off, Once, Lock }

/**
 * One frosted tile. Fires on release (so a slide-off cancels), except
 * [repeatOnHold] keys, which fire on press and repeat while held, like backspace.
 */
@Composable
private fun RowScope.GlassKey(
    weight: Float,
    label: String,
    onPress: () -> Unit,
    accent: Boolean = false,
    repeatOnHold: Boolean = false,
    /** Brighter glass, for a key that's switched on (Shift). */
    lit: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    // Keep the gesture detector alive across recompositions; always call the latest action.
    val action by rememberUpdatedState(onPress)
    val squish by animateFloatAsState(if (pressed) 0.94f else 1f, tween(90), label = "keyPress")

    with(LocalDesignScale.current) {
        val shape = RoundedCornerShape(26.u)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(weight)
                .fillMaxHeight()
                .graphicsLayer { scaleX = squish; scaleY = squish }
                .clip(shape)
                .then(
                    if (accent) Modifier.background(if (pressed) Brand.Red.copy(alpha = 0.75f) else Brand.Red)
                    else Modifier.background(if (pressed || lit) KeyboardGlass.KeyPressed else KeyboardGlass.Key)
                )
                .border(1.5f.u, if (accent) SolidColor(Color(0x40FFFFFF)) else KeyboardGlass.KeyEdge, shape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick { action(); true }
                }
                .pointerInput(repeatOnHold) {
                    detectTapGestures(onPress = {
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val repeater = if (repeatOnHold) {
                            action()
                            scope.launch {
                                delay(450)
                                while (true) { action(); delay(60) }
                            }
                        } else null
                        val released = tryAwaitRelease()
                        repeater?.cancel()
                        pressed = false
                        if (released && !repeatOnHold) action()
                    })
                },
        ) { content() }
    }
}

@Composable
private fun KeyLabel(text: String, small: Boolean = false, faded: Boolean = false) {
    with(LocalDesignScale.current) {
        Text(
            text = text,
            style = TextStyle(
                color = KeyboardGlass.Label.copy(alpha = if (faded) 0.7f else 1f),
                fontSize = (if (small) 44 else 56).usp,
                fontWeight = if (small) FontWeight.Medium else FontWeight.Normal,
                shadow = Shadow(Color(0x40000000), Offset(0f, 1.5f), blurRadius = 6f),
            ),
        )
    }
}

@Composable
private fun KeyIcon(res: Int, size: Dp) {
    Icon(painterResource(res), contentDescription = null, tint = KeyboardGlass.Label, modifier = Modifier.size(size))
}
