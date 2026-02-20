package com.jellyfinbroadcast.server

import org.junit.Assert.*
import org.junit.Test

class ConfigServerTest {

    @Test
    fun `ConfigPayload validates non-empty fields`() {
        val valid = ConfigPayload("192.168.1.10", 8096, "user", "pass")
        assertTrue(valid.isValid())
    }

    @Test
    fun `ConfigPayload rejects empty host`() {
        val invalid = ConfigPayload("", 8096, "user", "pass")
        assertFalse(invalid.isValid())
    }

    @Test
    fun `ConfigPayload rejects empty username`() {
        val invalid = ConfigPayload("192.168.1.10", 8096, "", "pass")
        assertFalse(invalid.isValid())
    }

    @Test
    fun `ConfigServer finds fallback port when primary is busy`() {
        // Pre-bind 8765 to force fallback to 8766+
        java.net.ServerSocket(8765).use { _ ->
            val port = ConfigServer.findAvailablePort(startPort = 8765)
            assertTrue("Expected port > 8765, got $port", port in 8766..8775)
        }
    }

    @Test
    fun `ConfigServer throws when all ports are busy`() {
        // Bind all ports in a tiny range
        val sockets = (9900..9902).map { java.net.ServerSocket(it) }
        try {
            assertThrows(IllegalStateException::class.java) {
                ConfigServer.findAvailablePort(startPort = 9900, maxPort = 9902)
            }
        } finally {
            sockets.forEach { it.close() }
        }
    }
}
