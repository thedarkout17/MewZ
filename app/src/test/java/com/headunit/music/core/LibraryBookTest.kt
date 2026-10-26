package com.headunit.music.core

import com.headunit.music.core.library.LibraryBook
import com.headunit.music.core.model.HISTORY_ID
import com.headunit.music.core.model.LIKED_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBookTest {
    private val book = LibraryBook().create("r", "Road trip").create("c", "Chill")

    @Test fun newestLibraryFirst() {
        assertEquals(listOf("c", "r"), book.custom.map { it.id })
    }

    @Test fun playingFromALibraryMovesItToTheFront() {
        assertEquals(listOf("r", "c"), book.touch("r").custom.map { it.id })
    }

    @Test fun addingToALibraryLikesTheSongOnce() {
        val b = book.addTo("r", song("a")).addTo("r", song("a"))
        assertEquals(listOf("a"), b.byId("r")!!.tracks.ids)
        assertTrue(b.isLiked(song("a")))
        assertEquals(1, b.liked.size)
    }

    @Test fun removingFromALibraryKeepsItLiked() {
        val b = book.addTo("r", song("a")).removeFrom("r", song("a"))
        assertTrue(b.byId("r")!!.tracks.isEmpty())
        assertTrue(b.isLiked(song("a")))
    }

    @Test fun historyIsNewestFirstWithoutRepeatsAndCapped() {
        var b = LibraryBook()
        (1..60).forEach { b = b.played(song("s$it")) }
        b = b.played(song("s10"))
        assertEquals(LibraryBook.HISTORY_SIZE, b.history.size)
        assertEquals("s10", b.history.first().videoId)
        assertEquals(1, b.history.count { it.videoId == "s10" })
    }

    @Test fun likedAndHistoryCantBeDeleted() {
        assertEquals(book, book.delete(LIKED_ID).delete(HISTORY_ID))
        assertEquals(listOf("r"), book.delete("c").custom.map { it.id })
    }

    @Test fun blankRenameIsIgnored() {
        assertEquals("Road trip", book.rename("r", "  ").byId("r")!!.name)
        assertEquals("Drive", book.rename("r", " Drive ").byId("r")!!.name)
    }

    @Test fun coverSpotsSwapWhenASongIsPickedTwice() {
        var b = book
        songs("a", "b", "c").forEach { b = b.addTo("r", it) }
        assertEquals(listOf("a", "b", "c"), b.byId("r")!!.coverTracks.ids)
        b = b.setCover("r", 0, song("c"))
        assertEquals(listOf("c", "b", "a"), b.byId("r")!!.coverTracks.ids)
    }

    @Test fun toggleLike() {
        val b = LibraryBook().toggleLike(song("a"))
        assertTrue(b.isLiked(song("a")))
        assertFalse(b.toggleLike(song("a")).isLiked(song("a")))
    }
}
