package com.jellyfinbroadcast.core

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class MediaPlayerTest {

    @Test
    fun `buildDirectPlayUrl uses static stream`() {
        val url = MediaPlayer.buildDirectPlayUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            mediaSourceId = "abc123"
        )
        assertTrue(url.contains("abc123"))
        assertTrue("Direct play should use static=true", url.contains("static=true"))
        assertFalse("Direct play should not use m3u8", url.contains("master.m3u8"))
    }

    @Test
    fun `buildHlsFallbackUrl uses transcode endpoint`() {
        val url = MediaPlayer.buildHlsFallbackUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken"
        )
        assertTrue("Expected master.m3u8 in transcode URL", url.contains("master.m3u8"))
        assertFalse("Transcode URL should not contain static=true", url.contains("static=true"))
    }

    @Test
    fun `buildDirectPlayUrl includes api token`() {
        val url = MediaPlayer.buildDirectPlayUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            mediaSourceId = "abc123"
        )
        assertTrue(url.contains("mytoken"))
    }

    @Test
    fun `buildHlsFallbackUrl includes api token`() {
        val url = MediaPlayer.buildHlsFallbackUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken"
        )
        assertTrue(url.contains("mytoken"))
    }

    @Test
    fun `buildDirectPlayUrl includes mediaSourceId`() {
        val url = MediaPlayer.buildDirectPlayUrl(
            serverUrl = "http://192.168.1.10:8096",
            itemId = "abc123",
            token = "mytoken",
            mediaSourceId = "source456"
        )
        assertTrue(url.contains("mediaSourceId=source456"))
    }

    @Test
    fun `isAudioTrackError returns true for AUDIO_TRACK_INIT_FAILED`() {
        val error = PlaybackException(
            "audio init failed",
            null,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        )
        assertTrue(MediaPlayer.isAudioTrackError(error))
    }

    @Test
    fun `isAudioTrackError returns true for AUDIO_TRACK_WRITE_FAILED`() {
        val error = PlaybackException(
            "audio write failed",
            null,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        )
        assertTrue(MediaPlayer.isAudioTrackError(error))
    }

    @Test
    fun `isAudioTrackError returns false for non-audio errors`() {
        val error = PlaybackException(
            "source error",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertFalse(MediaPlayer.isAudioTrackError(error))
    }
}
