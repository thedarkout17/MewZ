package com.headunit.music.core

import com.headunit.music.core.model.Lrc
import com.headunit.music.core.model.Lyrics
import com.headunit.music.core.search.Album
import com.headunit.music.core.search.AlbumMatching
import com.headunit.music.core.search.ResultRow
import com.headunit.music.core.search.mixInAlbums
import com.headunit.music.core.text.SongText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndSearchTest {
    @Test fun titlesLoseYouTubeClutter() {
        assertEquals("Starboy", SongText.cleanTitle("Starboy (Official Video)"))
        assertEquals("Starboy", SongText.cleanTitle("Starboy [Official Audio]"))
        assertEquals("The Weeknd", SongText.cleanArtist("The Weeknd - Topic"))
    }

    @Test fun artistDashSongIsSplit() {
        assertEquals("Starboy" to "The Weeknd", SongText.splitArtist("The Weeknd - Starboy", "XO"))
        assertEquals("Starboy" to "XO", SongText.splitArtist("Starboy", "XO"))
    }

    @Test fun featuredArtistsMoveToTheArtist() {
        assertEquals("Starboy" to "The Weeknd ft. Daft Punk", SongText.moveFeaturedArtists("Starboy ft. Daft Punk", "The Weeknd"))
        assertEquals("Song" to "A ft. B, C", SongText.moveFeaturedArtists("Song (feat. B & C)", "A"))
    }

    @Test fun isoDurations() {
        assertEquals(3723, SongText.parseIsoDuration("PT1H2M3S"))
        assertEquals(230, SongText.parseIsoDuration("PT3M50S"))
    }

    @Test fun lrcParsesAndFindsTheLineBeingSung() {
        val lines = Lrc.parse("[00:01.00] one\n[00:05.50] two\n[00:09.00][00:20.00] chorus")
        assertEquals(listOf("one", "two", "chorus", "chorus"), lines.map { it.text })
        val lyrics = Lyrics(lines, synced = true)
        assertEquals(-1, lyrics.lineAt(0.5f))
        assertEquals(1, lyrics.lineAt(6f))
        assertEquals(3, lyrics.lineAt(25f))
    }

    private val weeknd = Album("u1", "Starboy", "The Weeknd", "c")
    private val deluxe = Album("u2", "Starboy (Deluxe)", "The Weeknd", "c")
    private val nobody = Album("u3", "Starboy", "DJ Nobody", "c")
    private val cosmo = Album("u4", "Starboy", "Kid Cosmo", "c")
    private val afterHours = Album("u5", "After Hours", "The Weeknd", "c")
    private val all = listOf(weeknd, deluxe, nobody, cosmo, afterHours)
    private val topSongs = listOf(song("1", "The Weeknd ft. Daft Punk"), song("2", "The Weeknd"), song("3", "Daft Punk"))

    private fun shown(query: String) = AlbumMatching.relevant(all, query, topSongs)

    @Test fun albumsOnlyShowWhenTheyFit() {
        assertEquals(listOf(weeknd), shown("starboy"))
        assertEquals(listOf(weeknd, deluxe), shown("starboy album"))
        assertEquals(listOf(weeknd, deluxe), shown("starboy albu")) // still typing
        assertEquals(listOf(weeknd, cosmo), shown("starboy kid cosmo"))
        assertEquals(listOf(weeknd, afterHours), shown("the weeknd"))
        assertTrue(shown("blinding lights").isEmpty())
    }

    @Test fun albumsGoAfterTheFirstSongByTheirArtist() {
        val rows = mixInAlbums(listOf(song("1", "Drake"), song("2", "The Weeknd"), song("3", "X")), listOf(weeknd))
        assertEquals(listOf("1", "2", "album:u1", "3"), rows.map { it.key })
        assertTrue(rows[2] is ResultRow.AlbumItem)
    }
}
