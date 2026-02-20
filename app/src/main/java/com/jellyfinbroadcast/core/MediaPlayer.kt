package com.jellyfinbroadcast.core

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MediaPlayer(private val context: Context) {

    companion object {
        /**
         * Builds the stream URL for a Jellyfin media item.
         * @param transcode If true, requests HLS transcoded stream (master.m3u8).
         *                  If false, requests direct play (Static=true).
         */
        fun buildStreamUrl(
            serverUrl: String,
            itemId: String,
            token: String,
            transcode: Boolean
        ): String {
            return if (transcode) {
                "$serverUrl/Videos/$itemId/master.m3u8?api_key=$token&AudioCodec=aac&VideoCodec=h264"
            } else {
                "$serverUrl/Videos/$itemId/stream?Static=true&api_key=$token"
            }
        }
    }

    private var player: ExoPlayer? = null

    var onPlaybackEnded: (() -> Unit)? = null
    var onError: ((PlaybackException) -> Unit)? = null

    fun initialize() {
        player?.release() // guard against double-init
        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onPlaybackEnded?.invoke()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke(error)
                }
            })
        }
    }

    fun play(url: String) {
        player?.run {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun stop() { player?.stop() }
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun getExoPlayer(): ExoPlayer? = player

    fun release() {
        player?.release()
        player = null
    }
}
