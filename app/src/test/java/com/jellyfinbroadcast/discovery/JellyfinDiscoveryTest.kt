package com.jellyfinbroadcast.discovery

import org.junit.Assert.*
import org.junit.Test

class JellyfinDiscoveryTest {

    @Test
    fun `parseServerResponse extracts host and port`() {
        val json = """{"Address":"http://192.168.1.10:8096"}"""
        val result = JellyfinDiscovery.parseServerResponse(json)
        assertNotNull(result)
        assertEquals("192.168.1.10", result!!.host)
        assertEquals(8096, result.port)
    }

    @Test
    fun `parseServerResponse uses default port 8096 when port is absent`() {
        val json = """{"Address":"http://192.168.1.10"}"""
        val result = JellyfinDiscovery.parseServerResponse(json)
        assertNotNull(result)
        assertEquals(8096, result!!.port)
    }
}
