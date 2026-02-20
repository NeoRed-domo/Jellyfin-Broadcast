package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class JellyfinSessionTest {

    @Test
    fun `buildServerUrl formats with http when no scheme`() {
        val url = JellyfinSession.buildServerUrl("192.168.1.10", 8096)
        assertEquals("http://192.168.1.10:8096", url)
    }

    @Test
    fun `buildServerUrl preserves existing http scheme`() {
        val url = JellyfinSession.buildServerUrl("http://myserver.local", 8096)
        assertEquals("http://myserver.local:8096", url)
    }

    @Test
    fun `buildServerUrl preserves existing https scheme`() {
        val url = JellyfinSession.buildServerUrl("https://myserver.local", 8096)
        assertEquals("https://myserver.local:8096", url)
    }

    @Test
    fun `buildServerUrl uses default port 8096 when port is 0`() {
        val url = JellyfinSession.buildServerUrl("192.168.1.10", 0)
        assertEquals("http://192.168.1.10:8096", url)
    }

    @Test
    fun `buildServerUrl does not append port when host already has one`() {
        val url = JellyfinSession.buildServerUrl("http://myserver.local:9000", 8096)
        assertEquals("http://myserver.local:9000", url)
    }
}
