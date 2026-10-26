package com.headunit.music.phone.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/*
 * "Back" on the iPhone: there's no back button, so a swipe in from the left
 * edge does what Android's back does on the headunit (close the page on top).
 * The screens keep the same BackHandler calls as the headunit's.
 */

class BackDispatcher {
    internal class Entry(var enabled: Boolean, val onBack: () -> Unit)
    internal val entries = mutableStateListOf<Entry>()

    /** Runs the most recent enabled handler; false if there was none. */
    fun back(): Boolean {
        val e = entries.lastOrNull { it.enabled } ?: return false
        e.onBack()
        return true
    }
}

val LocalBackDispatcher = staticCompositionLocalOf { BackDispatcher() }

/** Like Android's BackHandler: [onBack] runs on a swipe in from the left edge while [enabled]. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    val latest by rememberUpdatedState(onBack)
    val entry = remember { BackDispatcher.Entry(enabled) { latest() } }
    entry.enabled = enabled
    DisposableEffect(dispatcher) {
        dispatcher.entries += entry
        onDispose { dispatcher.entries -= entry }
    }
}

/**
 * Watches for a swipe that starts at the left edge and moves right (iOS's back
 * gesture). It only watches: taps and scrolls underneath still work.
 */
fun Modifier.edgeSwipeBack(dispatcher: BackDispatcher): Modifier = pointerInput(dispatcher) {
    val edge = 28.dp.toPx()
    val distance = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edge) return@awaitEachGesture
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (kotlin.math.abs(dy) > distance && kotlin.math.abs(dy) > dx) break // a scroll, not a swipe
            if (dx > distance) {
                dispatcher.back()
                break
            }
        }
    }
}
