package com.headunit.music.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.headunit.music.R
import com.headunit.music.data.LocalSuggestionProvider
import com.headunit.music.data.SuggestionProvider
import com.headunit.music.ui.components.BAR_HEIGHT_COMPACT
import com.headunit.music.ui.components.BAR_HEIGHT_IDLE
import com.headunit.music.ui.components.SearchBar
import com.headunit.music.ui.keyboard.GlassKeyboard
import com.headunit.music.ui.keyboard.KEYBOARD_HEIGHT
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.HeadunitMusicTheme
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

private const val MAX_SUGGESTIONS = 5
private const val SUGGEST_DEBOUNCE_MS = 120L

/** Suggestions from this row down sit behind the keyboard glass (rows 4–5). */
private const val FIRST_ROW_UNDER_GLASS = 3

/**
 * Home screen.
 *
 * Idle: gradient background, search pill, script "Listen..." in the middle.
 *
 * Searching (bar tapped): "Listen..." zooms out, the background frosts over,
 * the bar rises and slims down, and our glass keyboard slides up. Suggestions
 * appear as you type: three above the keyboard, the last two blurred behind it.
 * The ⌄ key or a tap on empty space puts the keyboard away to show all five;
 * tap the bar to bring it back.
 */
@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    voiceResult: String? = null,
    onVoiceResultConsumed: () -> Unit = {},
    suggestionProvider: SuggestionProvider = remember { LocalSuggestionProvider() },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }       // search panel open
    var keyboard by rememberSaveable { mutableStateOf(false) }   // our keyboard showing
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    val searching = open || query.isNotEmpty()
    val keyboardShown = open && keyboard

    val progress by animateFloatAsState(
        targetValue = if (searching) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "searchMode",
    )
    val kb by animateFloatAsState(
        targetValue = if (keyboardShown) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "keyboard",
    )

    fun activate() { open = true; keyboard = true }
    fun submit(text: String) {
        query = text
        open = false
        keyboard = false
        onSearch(text)
    }
    fun tapOutside() {
        if (keyboard) keyboard = false   // first tap: put the keyboard away
        else open = false                // second tap: close suggestions
        if (query.isEmpty()) open = false // nothing typed → back to idle
    }

    // When the mic returns text, drop it in the box and search straight away.
    LaunchedEffect(voiceResult) {
        if (!voiceResult.isNullOrBlank()) {
            submit(voiceResult)
            onVoiceResultConsumed()
        }
    }

    // Fetch suggestions as the query changes (short debounce so fast typing doesn't thrash).
    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
        } else {
            delay(SUGGEST_DEBOUNCE_MS)
            suggestions = suggestionProvider.suggest(query, MAX_SUGGESTIONS)
        }
    }

    // Back leaves search mode instead of closing the app.
    BackHandler(enabled = searching) {
        query = ""
        open = false
        keyboard = false
    }

    Box(Modifier.fillMaxSize()) {
        // Sharp background, always there.
        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Frosted glass: a pre-blurred copy (works on every Android version,
        // unlike Modifier.blur which needs Android 12) plus a light veil.
        if (progress > 0f) {
            Image(
                painter = painterResource(R.drawable.home_background_blur),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress
                        // Settles from slightly zoomed to rest, so the glass feels like it lands.
                        val s = 1.04f - 0.04f * progress
                        scaleX = s; scaleY = s
                    },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
                    .background(Color(0x1F2A0006)), // faint dark-red tint helps white text
            )
        }

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                // Bar rises and slims down as search opens; suggestions follow it.
                fun lerp(idle: Float, searching: Float) = idle + (searching - idle) * progress
                val barTop = lerp(64f, 28f)
                val barHeight = lerp(BAR_HEIGHT_IDLE, BAR_HEIGHT_COMPACT)

                // Taps on empty space while searching.
                if (searching) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = ::tapOutside,
                            ),
                    )
                }

                // "Listen..." zooms out and fades away.
                if (progress < 1f) {
                    Image(
                        painter = painterResource(R.drawable.listen_title),
                        contentDescription = "Listen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 20.u)
                            .width(1090.u)
                            .graphicsLayer {
                                val s = 1f - 0.35f * progress
                                scaleX = s; scaleY = s
                                alpha = 1f - progress
                            },
                    )
                }

                // Suggestions line up with the text inside the bar.
                SearchSuggestions(
                    query = query,
                    suggestions = if (open) suggestions else emptyList(),
                    onPick = ::submit,
                    fog = kb,
                    fogFromIndex = FIRST_ROW_UNDER_GLASS,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            top = (barTop + barHeight + lerp(40f, 24f)).u,
                            start = lerp(282f, 240f).u, // in line with the typed text
                            end = 300.u,
                        ),
                )

                SearchBar(
                    query = query,
                    // Blinking cursor while typing, and on the idle screen like the mockup.
                    active = keyboardShown || !searching,
                    onActivate = ::activate,
                    onClear = { query = ""; activate() },
                    onVoiceSearch = onVoiceSearch,
                    compact = progress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = barTop.u, start = 96.u, end = 96.u),
                )

                // Glass keyboard slides up from the bottom edge.
                if (kb > 0f) {
                    GlassKeyboard(
                        onType = { c ->
                            // No leading spaces or double spaces.
                            if (c != ' ' || (query.isNotEmpty() && !query.endsWith(' '))) query += c
                        },
                        onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
                        onSearch = { if (query.isNotBlank()) submit(query.trim()) },
                        onHide = { keyboard = false },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                translationY = (1f - kb) * KEYBOARD_HEIGHT.u.toPx()
                                alpha = 0.4f + 0.6f * kb
                            },
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 1000, heightDp = 600, showBackground = true)
@Composable
private fun HomeScreenPreview() {
    HeadunitMusicTheme {
        HomeScreen(onSearch = {}, onVoiceSearch = {})
    }
}
