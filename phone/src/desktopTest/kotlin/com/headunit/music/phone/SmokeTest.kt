package com.headunit.music.phone

import com.headunit.music.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test fun sharedCoreIsReachable() {
        println("MEWZ: phone module sees the shared core")
        assertEquals("3:05", Track("id", "t", "a", "", durationSeconds = 185).durationText)
    }
}
