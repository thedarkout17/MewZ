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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.headunit.music.R
import com.headunit.music.data.LocalSuggestionProvider
import com.headunit.music.data.SuggestionProvider
import com.headunit.music.ui.components.SearchBar
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.HeadunitMusicTheme
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

private const val MAX_SUGGESTIONS = 6
private const val SUGGEST_DEBOUNCE_MS = 120L

/**
 * Home screen.
 * Idle: gradient background, search pill, script "Listen..." in the middle.
 * Searching (bar focused or has text): "Listen..." zooms out and fades, the
 * background frosts over, and suggestions appear under the bar as you type.
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
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val searching = focused || query.isNotEmpty()
    val progress by animateFloatAsState(
        targetValue = if (searching) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "searchMode",
    )

    fun submit(text: String) {
        query = text
        focusManager.clearFocus()
        onSearch(text)
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
        focusManager.clearFocus()
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
                // Tap empty space while searching → put the keyboard away.
                // If nothing is typed, that also returns to the idle home screen.
                if (searching) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { focusManager.clearFocus() },
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

                // Suggestions line up with the "Search" text inside the bar.
                SearchSuggestions(
                    query = query,
                    // Only while typing; once a search is sent they clear away.
                    suggestions = if (focused) suggestions else emptyList(),
                    onPick = ::submit,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 336.u, start = 282.u, end = 300.u),
                )

                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = ::submit,
                    onVoiceSearch = onVoiceSearch,
                    focusRequester = focusRequester,
                    onFocusChange = { focused = it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.u, start = 96.u, end = 96.u),
                )
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
