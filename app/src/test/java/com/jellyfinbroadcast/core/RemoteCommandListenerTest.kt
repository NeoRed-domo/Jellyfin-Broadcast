package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class RemoteCommandListenerTest {

    @Test
    fun `parseSeekPositionMs returns value unchanged`() {
        val ms = RemoteCommandListener.parseSeekPositionMs(10000L)
        assertEquals(10000L, ms)
    }

    @Test
    fun `reconnect delay doubles up to max`() {
        val expected = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L)
        var delay = 1000L
        val results = mutableListOf<Long>()
        repeat(7) {
            results.add(delay)
            delay = minOf(delay * 2, RemoteCommandListener.MAX_RECONNECT_DELAY_MS)
        }
        assertEquals(expected, results)
    }
}
