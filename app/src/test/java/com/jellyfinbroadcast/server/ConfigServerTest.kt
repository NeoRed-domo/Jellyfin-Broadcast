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
        val port = ConfigServer.findAvailablePort(startPort = 8765)
        assertTrue(port in 8765..8775)
    }
}
