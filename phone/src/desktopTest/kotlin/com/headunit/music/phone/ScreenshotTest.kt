package com.headunit.music.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import com.headunit.music.core.model.Track
import com.headunit.music.core.search.Album
import com.headunit.music.core.sources.AudioStreamSource
import com.headunit.music.core.sources.MixSource
import com.headunit.music.phone.data.LibraryStore
import com.headunit.music.phone.data.SearchHistory
import com.headunit.music.phone.data.YouTubeSearch
import com.headunit.music.phone.player.FakeAudioEngine
import com.headunit.music.phone.player.PlayerController
import com.headunit.music.phone.res.Res
import com.headunit.music.phone.res.home_background_blur
import com.headunit.music.phone.ui.home.HomeScreen
import com.headunit.music.phone.ui.home.ResultsState
import com.headunit.music.phone.ui.home.SearchResults
import com.headunit.music.phone.ui.player.PlayerOverlay
import com.headunit.music.phone.ui.theme.DesignCanvas
import com.headunit.music.phone.ui.theme.HeadunitMusicTheme
import com.headunit.music.phone.ui.theme.LocalDesignScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Draws the iPhone screens to PNGs (iPhone 15 Pro size: 393 × 852 pt at 3x),
 * so the layout can be checked without a Mac. CI puts them on the "ci-android"
 * draft release. Real artwork loads from YouTube when the network allows.
 */
class ScreenshotTest {
    private val outDir = File(System.getProperty("mewz.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun t(id: String, title: String, artist: String, secs: Int) =
        Track(id, title, artist, "https://i.ytimg.com/vi/$id/hqdefault.jpg", "https://i.ytimg.com/vi/$id/hqdefault.jpg", true, secs)

    private val songs = listOf(
        t("34Na4j8AVgA", "Starboy", "The Weeknd ft. Daft Punk", 274),
        t("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", 202),
        t("XXYlFuWEuKI", "Save Your Tears", "The Weeknd", 216),
        t("yzTuBuRdAyA", "The Hills", "The Weeknd", 242),
        t("qFLhGq0060w", "I Feel It Coming", "The Weeknd ft. Daft Punk", 298),
        t("JZjAg6fK-BQ", "Reminder", "The Weeknd", 230),
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 1179, height = 2556, density = Density(3f)) {
            HeadunitMusicTheme { content() }
        }
        try {
            var image = scene.render(0)
            // A few more frames, with pauses, so animations settle and artwork arrives.
            for (frame in 1..12) {
                Thread.sleep(250)
                image = scene.render(frame * 500_000_000L)
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "iphone-$name.png").writeBytes(png)
            println("MEWZ: screenshot iphone-$name.png")
        } finally {
            scene.close()
        }
    }

    private val noAudio = object : AudioStreamSource {
        override suspend fun urlFor(videoId: String) = "https://example.invalid/$videoId"
        override fun forget(videoId: String) {}
    }
    private val noMix = object : MixSource { override suspend fun upNext(seed: Track) = songs.drop(1) }

    @Test fun screens() {
        val libraries = LibraryStore()
        songs.take(4).forEach(libraries::like)
        songs.forEach(libraries::recordPlayed)
        libraries.create("Road trip").let { lib -> songs.take(3).forEach { libraries.addTo(lib.id, it) } }
        libraries.create("Late night")

        shoot("1-home") {
            HomeScreen(
                youtube = YouTubeSearch({ "" }), history = SearchHistory(), libraries = libraries,
                onPlay = { _, _, _ -> }, onCreateLibrary = {},
            )
        }

        shoot("2-results") {
            Box(Modifier.fillMaxSize()) {
                Image(painterResource(Res.drawable.home_background_blur), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                DesignCanvas(Modifier.fillMaxSize()) {
                    with(LocalDesignScale.current) {
                        SearchResults(
                            state = ResultsState.Done(
                                "the weeknd", songs,
                                albums = listOf(Album("https://music.youtube.com/playlist?list=x", "Starboy", "The Weeknd", "https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg")),
                            ),
                            onPlay = {}, onRetry = {},
                            modifier = Modifier.padding(top = 300.u, start = 60.u, end = 60.u),
                        )
                    }
                }
            }
        }

        val player = runBlocking(Dispatchers.Main) {
            PlayerController(FakeAudioEngine(), noAudio, noMix).apply { playQueue(songs, 0, name = "Road trip", wrap = true) }
        }
        shoot("3-now-playing") {
            PlayerOverlay(player = player, dockHidden = false, libraries = libraries, onNewLibrary = {})
        }

        runBlocking(Dispatchers.Main) { player.expanded = false }
        shoot("4-home-with-dock") {
            Box(Modifier.fillMaxSize()) {
                HomeScreen(
                    youtube = YouTubeSearch({ "" }), history = SearchHistory(), libraries = libraries,
                    onPlay = { _, _, _ -> }, onCreateLibrary = {}, dockInset = 280f,
                )
                PlayerOverlay(player = player, dockHidden = false, libraries = libraries, onNewLibrary = {})
            }
        }

        shoot("5-missing-key") {
            Box(Modifier.fillMaxSize()) {
                Image(painterResource(Res.drawable.home_background_blur), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                DesignCanvas(Modifier.fillMaxSize()) {
                    with(LocalDesignScale.current) {
                        SearchResults(
                            state = ResultsState.Failed(com.headunit.music.core.search.SearchResult.Reason.MissingKey),
                            onPlay = {}, onRetry = {},
                            modifier = Modifier.padding(top = 300.u),
                        )
                    }
                }
            }
        }
    }
}
