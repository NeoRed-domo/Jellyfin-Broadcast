package com.jellyfinbroadcast.core

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource

sealed class StreamInfo {
    abstract val url: String
    abstract val playSessionId: String?
    abstract val subtitleStreamIndex: Int?

    data class DirectPlay(
        override val url: String,
        override val playSessionId: String?,
        override val subtitleStreamIndex: Int? = null,
        val serverTranscodeUrl: String? = null,
        val externalSubtitleUrl: String? = null,
        val externalSubtitleMimeType: String? = null
    ) : StreamInfo()

    data class HlsTranscode(
        override val url: String,
        override val playSessionId: String?,
        override val subtitleStreamIndex: Int? = null
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
            mediaSourceId: String? = null,
            subtitleStreamIndex: Int? = null
        ): String {
            val sourceId = mediaSourceId ?: itemId.replace("-", "")
            var url = "$serverUrl/Videos/$itemId/master.m3u8" +
                "?api_key=$token" +
                "&MediaSourceId=$sourceId" +
                "&VideoCodec=h264" +
                "&AudioCodec=aac" +
                "&MaxStreamingBitrate=120000000" +
                "&MaxWidth=3840" +
                "&MaxHeight=2160" +
                "&TranscodingMaxAudioChannels=6" +
                "&SegmentContainer=ts"
            if (subtitleStreamIndex != null) {
                url += "&SubtitleStreamIndex=$subtitleStreamIndex"
            }
            return url
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
    var onItemTransition: ((Int) -> Unit)? = null

    private var currentSources: MutableList<MediaSource> = mutableListOf()
    private var passthroughEnabled = false

    fun isPassthroughEnabled(): Boolean = passthroughEnabled

    @OptIn(UnstableApi::class)
    fun initialize(enablePassthrough: Boolean = true) {
        player?.stop()
        player?.clearMediaItems()
        player?.release()
        player = null
        currentSources.clear()
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
                // Ensure forced subtitle tracks are selected even with undetermined language
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setSelectUndeterminedTextLanguage(true)
                    .build()

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

                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        @Player.MediaItemTransitionReason reason: Int
                    ) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        ) {
                            val index = player?.currentMediaItemIndex ?: 0
                            onItemTransition?.invoke(index)
                        }
                    }
                })
            }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaSource(streamInfo: StreamInfo): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(context)
        return when (streamInfo) {
            is StreamInfo.DirectPlay -> {
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamInfo.url))
                val extSubUrl = streamInfo.externalSubtitleUrl
                if (extSubUrl != null) {
                    val mimeType = streamInfo.externalSubtitleMimeType ?: MimeTypes.APPLICATION_SUBRIP
                    val subtitleSource = SingleSampleMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(extSubUrl))
                                .setMimeType(mimeType)
                                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                                .build(),
                            C.TIME_UNSET
                        )
                    MergingMediaSource(videoSource, subtitleSource)
                } else {
                    videoSource
                }
            }
            is StreamInfo.HlsTranscode -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(streamInfo.url))
        }
    }

    @OptIn(UnstableApi::class)
    fun play(streamInfo: StreamInfo, startPositionMs: Long = 0) {
        val mediaSource = buildMediaSource(streamInfo)
        currentSources = mutableListOf(mediaSource)
        player?.run {
            setMediaSource(mediaSource)
            if (startPositionMs > 0) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    @OptIn(UnstableApi::class)
    fun playPlaylist(streamInfos: List<StreamInfo>, startPositionMs: Long = 0) {
        currentSources = streamInfos.map { buildMediaSource(it) }.toMutableList()
        player?.run {
            setMediaSources(currentSources)
            if (startPositionMs > 0) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    @OptIn(UnstableApi::class)
    fun replaceItem(index: Int, streamInfo: StreamInfo, startPositionMs: Long = 0) {
        if (index < 0 || index >= currentSources.size) return
        currentSources[index] = buildMediaSource(streamInfo)
        player?.run {
            setMediaSources(currentSources, index, startPositionMs)
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
    fun isPlayWhenReady(): Boolean = player?.playWhenReady ?: false
    fun getCurrentItemIndex(): Int = player?.currentMediaItemIndex ?: 0
    fun seekToItem(index: Int) { player?.seekTo(index, 0) }
    fun getItemCount(): Int = player?.mediaItemCount ?: 0

    fun getExoPlayer(): ExoPlayer? = player

    fun release() {
        onPlaybackEnded = null
        onError = null
        onSeekCompleted = null
        onItemTransition = null
        player?.stop()
        player?.clearMediaItems()
        player?.release()
        player = null
        currentSources.clear()
    }
}
