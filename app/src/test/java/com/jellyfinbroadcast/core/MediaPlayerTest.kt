package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class MediaPlayerTest {

    @Test
    fun `buildStreamUrl uses direct play by default`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = false
        )
        assertTrue(url.contains("abc123"))
        assertTrue(url.contains("Static"))
    }

    @Test
    fun `buildStreamUrl uses transcode endpoint when requested`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = true
        )
        assertTrue("Expected master.m3u8 in transcode URL", url.contains("master.m3u8"))
        assertFalse("Transcode URL should not contain Static=true", url.contains("Static=true"))
    }

    @Test
    fun `buildStreamUrl direct play does not use transcode endpoint`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = false
        )
        assertFalse("Direct play should not use m3u8", url.contains("master.m3u8"))
        assertTrue("Direct play should use Static=true", url.contains("Static=true"))
    }

    @Test
    fun `buildStreamUrl includes api token`() {
        val url = MediaPlayer.buildStreamUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            transcode = false
        )
        assertTrue(url.contains("mytoken"))
    }
}
