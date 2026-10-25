package com.headunit.music.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.headunit.music.R
import com.headunit.music.ui.components.SearchBar
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.HeadunitMusicTheme
import com.headunit.music.ui.theme.LocalDesignScale

/**
 * Home screen: gradient background, search pill pinned to the top,
 * script "Listen..." centred in the open space.
 */
@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    voiceResult: String? = null,
    onVoiceResultConsumed: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // When the mic returns text, drop it in the box and search straight away.
    LaunchedEffect(voiceResult) {
        if (!voiceResult.isNullOrBlank()) {
            query = voiceResult
            onSearch(voiceResult)
            onVoiceResultConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Background always fills the real screen edge to edge, even if its
        // aspect ratio isn't exactly 5:3.
        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = onSearch,
                    onVoiceSearch = onVoiceSearch,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.u, start = 96.u, end = 96.u),
                )

                Image(
                    painter = painterResource(R.drawable.listen_title),
                    contentDescription = "Listen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 20.u)
                        .width(1090.u),
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
