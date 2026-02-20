package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackReporterTest {

    @Test
    fun `convertMsToTicks converts correctly`() {
        // Jellyfin uses ticks (100ns units), 1ms = 10000 ticks
        assertEquals(10_000L, PlaybackReporter.msToTicks(1L))
        assertEquals(600_000_000L, PlaybackReporter.msToTicks(60_000L))
    }
}
