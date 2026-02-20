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
        assertTrue(url.contains("master.m3u8") || url.contains("stream"))
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
