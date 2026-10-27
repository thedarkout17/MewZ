package com.headunit.music.phone.flow

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.headunit.music.core.search.AlbumMatching
import com.headunit.music.core.search.SearchResult
import com.headunit.music.phone.data.LibraryStore
import com.headunit.music.phone.data.SearchHistory
import com.headunit.music.phone.data.YouTubeAlbums
import com.headunit.music.phone.data.YouTubeAudio
import com.headunit.music.phone.data.YouTubeSearch
import com.headunit.music.phone.data.YouTubeSuggestions
import com.headunit.music.phone.log
import com.headunit.music.phone.readClipboardText
import com.headunit.music.phone.ui.home.HomeScreen
import com.headunit.music.phone.ui.theme.HeadunitMusicTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The search → "add an API key" flow that crashed on the iPhone, run on the
 * iPhone simulator: first the pieces on their own, then the real home screen.
 */
class SearchFlowTest {

    @Test fun piecesWorkOnIos() = runBlocking {
        log("plain log line, with a % sign and an album \"Starboy (Deluxe)\"")
        println("MEWZ: ios log OK")
        println("MEWZ: ios clipboard read OK (${readClipboardText()?.length ?: 0} chars)")

        val r = YouTubeSearch({ "" }).search("starboy", null)
        assertTrue(r is SearchResult.Failure && r.reason == SearchResult.Reason.MissingKey)

        val history = SearchHistory()
        history.add("starboy")
        LibraryStore().byId("liked")
        println("MEWZ: ios storage OK")

        val albums = runCatching { YouTubeAlbums.search("starboy") }
        println("MEWZ: ios album search: " + albums.fold({ "${it.size} albums" }, { "FAILED ${it::class.simpleName}: ${it.message}" }))
        val shown = AlbumMatching.relevant(albums.getOrDefault(emptyList()), "starboy", emptyList()) { log(it) }
        println("MEWZ: ios album matching + logging OK (${shown.size} shown)")

        println("MEWZ: ios suggestions: " + YouTubeSuggestions().suggest("starb", 5))
        val audio = runCatching { YouTubeAudio.urlFor("34Na4j8AVgA") }
        println("MEWZ: ios audio link: " + audio.fold({ "OK" }, { "FAILED ${it::class.simpleName}: ${it.message}" }))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun searchingWithoutAKeyShowsTheMessage() = runComposeUiTest {
        setContent {
            HeadunitMusicTheme {
                HomeScreen(
                    youtube = YouTubeSearch({ "" }),
                    history = SearchHistory(),
                    libraries = LibraryStore(),
                    onPlay = { _, _, _ -> },
                    onCreateLibrary = {},
                )
            }
        }
        waitForIdle()
        val field = onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("starboy")
        field.performImeAction()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Add a YouTube API key to search")).fetchSemanticsNodes().isNotEmpty() }
        println("MEWZ: ios UI: missing-key message shown")
        // Albums arrive a moment later and are matched (and logged): give them time.
        val until = kotlin.time.TimeSource.Monotonic.markNow()
        while (until.elapsedNow().inWholeMilliseconds < 6_000) {
            mainClock.advanceTimeBy(500)
            waitForIdle()
            runBlocking { kotlinx.coroutines.delay(500) }
        }
        onNode(hasText("Paste API key")).performClick()
        waitForIdle()
        println("MEWZ: ios UI: still running 6 s later, Paste tapped")
    }
}
