package com.headunit.music.phone

import com.headunit.music.core.model.Track
import com.headunit.music.core.search.AlbumMatching
import com.headunit.music.phone.data.Http
import com.headunit.music.phone.data.LrcLibLyrics
import com.headunit.music.phone.data.YouTubeAlbums
import com.headunit.music.phone.data.YouTubeAudio
import com.headunit.music.phone.data.YouTubeMix
import com.headunit.music.phone.data.YouTubeSuggestions
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the iPhone's YouTube code against the real YouTube, from the CI machine.
 * Only runs with -Plive (see phone/build.gradle.kts). Prints "MEWZ:" lines, which
 * CI turns into notes on the run's page.
 *
 * GitHub's machines are in data centres that YouTube sometimes treats as bots,
 * so a failure here doesn't always mean the phone will fail.
 */
class LiveYouTubeTest {
    private val live = System.getProperty("mewz.live") == "true"
    private val starboy = Track("34Na4j8AVgA", "Starboy", "The Weeknd", "https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg")

    private fun report(what: String, block: suspend () -> String) = runBlocking {
        val result = runCatching { block() }
        println("MEWZ: $what: " + result.fold({ "OK, $it" }, { "FAILED, ${it::class.simpleName}: ${it.message}" }))
        result.isSuccess
    }

    @Test fun youTubeFromThePhoneCode() {
        if (!live) return
        val results = listOf(
            report("Audio link (VISIONOS)") {
                val url = YouTubeAudio.urlFor(starboy.videoId)
                val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)
                // Fetch the first 64 KB like AVPlayer would (with a Range request).
                val r = Http.client.get(url) {
                    header("User-Agent", YouTubeAudio.USER_AGENT)
                    header("Range", "bytes=0-65535")
                }
                val bytes = r.readRawBytes()
                val box = bytes.copyOfRange(4, 8).decodeToString()
                check(r.status.value in 200..299) { "stream HTTP ${r.status.value}" }
                "itag $itag, stream HTTP ${r.status.value}, ${bytes.size} bytes, first MP4 box '$box'"
            },
            report("Mix") {
                val mix = YouTubeMix.upNext(starboy)
                check(mix.size >= 10) { "only ${mix.size} songs" }
                "${mix.size} songs, e.g. " + mix.take(3).joinToString("; ") { "${it.title} by ${it.artist} (${it.durationText})" }
            },
            report("Album search") {
                val albums = YouTubeAlbums.search("starboy")
                val kept = AlbumMatching.relevant(albums, "starboy", listOf(starboy))
                check(albums.isNotEmpty()) { "no albums" }
                "${albums.size} found (${albums.take(3).joinToString { "${it.title} by ${it.artist}" }}); shown: ${kept.joinToString { it.title }}"
            },
            report("Album tracks") {
                val album = YouTubeAlbums.search("starboy the weeknd").first()
                val tracks = YouTubeAlbums.tracks(album)
                check(tracks.size >= 5) { "only ${tracks.size} tracks" }
                "\"${album.title}\": ${tracks.size} tracks, first: ${tracks.first().title} by ${tracks.first().artist}"
            },
            report("Suggestions") {
                val s = YouTubeSuggestions().suggest("starb", 5)
                check(s.isNotEmpty()) { "none" }
                s.joinToString()
            },
            report("Lyrics (LRCLIB)") {
                val l = LrcLibLyrics.find(starboy.copy(durationSeconds = 274))
                "${if (l?.synced == true) "synced" else if (l != null) "plain" else "none"}, ${l?.lines?.size ?: 0} lines"
            },
        )
        // The audio link is the one that matters most; the rest are reported as notes.
        assertTrue(results.first(), "Audio link lookup failed (see MEWZ lines)")
    }
}
