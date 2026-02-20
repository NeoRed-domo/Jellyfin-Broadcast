package com.jellyfinbroadcast.discovery

import org.junit.Assert.*
import org.junit.Test

class JellyfinDiscoveryTest {

    @Test
    fun `parseServiceInfo extracts host and port`() {
        val result = JellyfinDiscovery.parseServiceInfo(
            host = "192.168.1.10",
            port = 8096
        )
        assertEquals("192.168.1.10", result.host)
        assertEquals(8096, result.port)
    }

    @Test
    fun `parseServiceInfo uses default port 8096 when port is 0`() {
        val result = JellyfinDiscovery.parseServiceInfo(
            host = "192.168.1.10",
            port = 0
        )
        assertEquals(8096, result.port)
    }
}
