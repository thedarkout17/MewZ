package com.headunit.music.core

import com.headunit.music.core.playback.QueueState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.random.Random

class QueueStateTest {
    private val abcde = songs("a", "b", "c", "d", "e")
    private fun library(at: Int = 0) = QueueState().start(abcde, at, name = "Road trip", wrap = true)!!

    @Test fun startPlaysFromTheTappedSong() {
        val q = library(2)
        assertEquals("c", q.current?.videoId)
        assertEquals("Road trip", q.name)
        assertEquals(abcde, q.original)
    }

    @Test fun startOutOfRangeIsNull() {
        assertNull(QueueState().start(abcde, 9))
    }

    @Test fun shuffledStartPutsTheTappedSongFirst() {
        val q = QueueState(shuffle = true).start(abcde, 3, random = Random(1))!!
        assertEquals(0, q.index)
        assertEquals("d", q.current?.videoId)
        assertEquals(abcde.ids.toSet(), q.tracks.ids.toSet())
    }

    @Test fun librariesWrapForwardAndBack() {
        val last = library(4)
        assertEquals(QueueState.Next.PlayAt(0), last.next())
        val first = library(0)
        assertEquals(4, first.previous(positionSeconds = 1f))
    }

    @Test fun previousRestartsAfterTwoAndAHalfSeconds() {
        val q = library(2)
        assertNull(q.previous(positionSeconds = 2.6f))
        assertEquals(1, q.previous(positionSeconds = 2.4f))
    }

    @Test fun searchQueueDoesNotWrapBack() {
        val q = QueueState().startMix(song("x"))
        assertNull(q.previous(positionSeconds = 0f)) // first song of a mix: just restart
        assertFalse(q.hasPrevious)
    }

    @Test fun endOfMixWaitsForMore() {
        val q = QueueState().startMix(song("x"))
        assertTrue(q.hasNext)
        assertEquals(QueueState.Next.AwaitMix, q.next())
        assertTrue(q.needsMoreMix)
    }

    @Test fun mixTopUpsSkipSongsAlreadyInIt() {
        val q = QueueState().startMix(song("x")).withMix(songs("x", "y", "z")).withMix(songs("y", "w"))
        assertEquals(listOf("x", "y", "z", "w"), q.tracks.ids)
    }

    @Test fun mixTopUpIgnoredOnceItsNoLongerAMix() {
        val q = library().withMix(songs("y"))
        assertEquals(abcde, q.tracks)
    }

    @Test fun enqueueGoesAfterEarlierQueuedSongs() {
        val q = library(0).enqueue(song("q1")).enqueue(song("q2"))
        assertEquals(listOf("a", "q1", "q2", "b", "c", "d", "e"), q.tracks.ids)
        assertTrue(q.isHeld(song("q1")))
    }

    @Test fun enqueueMovesASongForwardInsteadOfRepeatingIt() {
        val q = library(0).enqueue(song("d"))
        assertEquals(listOf("a", "d", "b", "c", "e"), q.tracks.ids)
    }

    @Test fun enqueueWithNothingPlayingChangesNothing() {
        assertEquals(QueueState(), QueueState().enqueue(song("x")))
    }

    @Test fun playingAQueuedSongClearsItsQueuedMark() {
        val q = library(0).enqueue(song("q1"))
        val playing = q.at(1)
        assertFalse(playing.isHeld(song("q1")))
    }

    @Test fun doubleTapGoesToTheEndOfQueuedSongs() {
        val q = library(0).enqueue(song("q1")).playNext(4) // [a, q1, b, c, d, e]: "d" is at 4
        assertEquals(listOf("a", "q1", "d", "b", "c", "e"), q.tracks.ids)
    }

    @Test fun doubleTapOnAQueuedSongMovesItToTheFront() {
        val q = library(0).enqueue(song("q1")).enqueue(song("q2")).playNext(2) // q2
        assertEquals(listOf("a", "q2", "q1", "b", "c", "d", "e"), q.tracks.ids)
    }

    @Test fun doubleTapOnAPreviousSongBringsItBackNext() {
        val q = library(3).playNext(0) // "a" already played
        assertEquals("d", q.current?.videoId)
        assertEquals(listOf("b", "c", "d", "a", "e"), q.tracks.ids)
    }

    @Test fun dragOnlyMovesUpcomingSongs() {
        val q = library(1)
        assertEquals(listOf("a", "b", "d", "c", "e"), q.move(3, 2).tracks.ids)
        assertEquals(q, q.move(0, 3)) // can't drag a played song
        assertEquals(q, q.move(3, 1)) // or above the one playing
    }

    @Test fun removeOnlyTakesOutUpcomingSongs() {
        val q = library(1)
        assertEquals(listOf("a", "b", "d", "e"), q.remove(2).tracks.ids)
        assertEquals(q, q.remove(0))
    }

    @Test fun shuffleOffRestoresTheOriginalOrderAndKeepsTheSong() {
        val on = library(2).toggledShuffle(Random(3))
        assertTrue(on.shuffle)
        assertEquals("c", on.current?.videoId)
        val off = on.toggledShuffle()
        assertEquals(abcde, off.tracks)
        assertEquals("c", off.current?.videoId)
    }

    @Test fun shuffleInAMixOnlyMovesSongsAhead() {
        val mix = QueueState().startMix(song("x")).withMix(songs("p", "q", "r", "s")).at(2) // playing q
        val on = mix.toggledShuffle(Random(5))
        assertEquals(listOf("x", "p", "q"), on.tracks.take(3).ids)
        assertEquals(setOf("r", "s"), on.tracks.drop(3).ids.toSet())
    }

    @Test fun aheadLooksPastTheEndOnlyInLibraries() {
        assertEquals("a", library(4).ahead(1)?.videoId)
        assertEquals("b", library(4).ahead(2)?.videoId)
        assertNull(QueueState().startMix(song("x")).ahead(1))
    }

    @Test fun renamedOnlyChangesTheMatchingName() {
        assertEquals("Drive", library().renamed("Road trip", "Drive").name)
        assertEquals("Road trip", library().renamed("Other", "Drive").name)
    }

    @Test fun savingKeepsAWindowAroundTheCurrentSong() {
        val many = (1..400).map { song("s$it") }
        val q = QueueState().start(many, 100)!!.forSaving(behind = 50, ahead = 250)
        assertEquals(300, q.tracks.size)
        assertEquals("s101", q.current?.videoId)
        val back = q.restored()!!
        assertEquals("s101", back.current?.videoId)
    }

    @Test fun restoredMixRemembersWhatsInIt() {
        val saved = QueueState().startMix(song("x")).withMix(songs("y")).forSaving()
        val back = saved.restored()!!
        assertEquals(setOf("x", "y"), back.mixSeen)
        assertEquals(listOf("x", "y", "z"), back.withMix(songs("y", "z")).tracks.ids) // y not repeated
    }
}
