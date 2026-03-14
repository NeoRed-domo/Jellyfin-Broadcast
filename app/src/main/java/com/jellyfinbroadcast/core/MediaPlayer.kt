package com.jellyfinbroadcast.core

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

sealed class StreamInfo {
    abstract val url: String
    abstract val playSessionId: String?

    data class DirectPlay(
        override val url: String,
        override val playSessionId: String?,
        val serverTranscodeUrl: String? = null
    ) : StreamInfo()

    data class HlsTranscode(
        override val url: String,
        override val playSessionId: String?
    ) : StreamInfo()
}

class MediaPlayer(private val context: Context) {

    companion object {
        fun buildDirectPlayUrl(
            serverUrl: String,
            itemId: String,
            token: String,
            mediaSourceId: String,
            container: String? = null
        ): String {
            val ext = if (container != null) ".$container" else ""
            return "$serverUrl/Videos/$itemId/stream$ext" +
                "?static=true" +
                "&api_key=$token" +
                "&mediaSourceId=$mediaSourceId"
        }

        fun buildHlsFallbackUrl(
            serverUrl: String,
            itemId: String,
            token: String,
            mediaSourceId: String? = null
        ): String {
            val sourceId = mediaSourceId ?: itemId.replace("-", "")
            return "$serverUrl/Videos/$itemId/master.m3u8" +
                "?api_key=$token" +
                "&MediaSourceId=$sourceId" +
                "&VideoCodec=h264" +
                "&AudioCodec=aac" +
                "&MaxStreamingBitrate=120000000" +
                "&MaxWidth=3840" +
                "&MaxHeight=2160" +
                "&TranscodingMaxAudioChannels=6" +
                "&SegmentContainer=ts"
        }

        private val AUDIO_TRACK_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
        )

        fun isAudioTrackError(error: PlaybackException): Boolean =
            error.errorCode in AUDIO_TRACK_ERROR_CODES
    }

    private var player: ExoPlayer? = null

    var onPlaybackEnded: (() -> Unit)? = null
    var onError: ((PlaybackException) -> Unit)? = null
    var onSeekCompleted: (() -> Unit)? = null

    private var passthroughEnabled = false

    fun isPassthroughEnabled(): Boolean = passthroughEnabled

    @OptIn(UnstableApi::class)
    fun initialize(enablePassthrough: Boolean = true) {
        player?.release()
        passthroughEnabled = enablePassthrough

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val ctx = context
        val renderersFactory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (enablePassthrough) {
                    builder.setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                }
                return builder.build()
            }
        }

        player = ExoPlayer.Builder(ctx, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) onPlaybackEnded?.invoke()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onError?.invoke(error)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            onSeekCompleted?.invoke()
                        }
                    }
                })
            }
    }

    fun play(streamInfo: StreamInfo) {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val mediaSource = when (streamInfo) {
            is StreamInfo.DirectPlay -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamInfo.url))
            }
            is StreamInfo.HlsTranscode -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(streamInfo.url))
            }
        }
        player?.run {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun getExoPlayer(): ExoPlayer? = player

    fun release() {
        player?.release()
        player = null
    }
}
