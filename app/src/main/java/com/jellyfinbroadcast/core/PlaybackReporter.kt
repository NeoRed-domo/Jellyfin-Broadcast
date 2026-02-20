package com.jellyfinbroadcast.core

import kotlinx.coroutines.*
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

class PlaybackReporter(private val api: ApiClient) {

    companion object {
        const val REPORT_INTERVAL_MS = 10_000L
        const val TICKS_PER_MS = 10_000L

        fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
    }

    private val playstateApi = PlayStateApi(api)
    private var reportingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentItemId: UUID? = null
    private var getPositionMs: (() -> Long)? = null

    fun reportPlaybackStart(itemId: UUID, positionMs: Long) {
        currentItemId = itemId
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = false,
                        item = null,
                        itemId = itemId,
                        sessionId = null,
                        mediaSourceId = null,
                        audioStreamIndex = null,
                        subtitleStreamIndex = null,
                        isPaused = false,
                        isMuted = false,
                        positionTicks = msToTicks(positionMs),
                        playbackStartTimeTicks = null,
                        volumeLevel = null,
                        brightness = null,
                        aspectRatio = null,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        liveStreamId = null,
                        playSessionId = null,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        nowPlayingQueue = emptyList(),
                        playlistItemId = null
                    )
                )
            }
        }
    }

    fun startPeriodicReporting(getPosition: () -> Long) {
        getPositionMs = getPosition
        reportingJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportProgress()
            }
        }
    }

    fun reportProgress() {
        val itemId = currentItemId ?: return
        val posMs = getPositionMs?.invoke() ?: return
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        canSeek = false,
                        item = null,
                        itemId = itemId,
                        sessionId = null,
                        mediaSourceId = null,
                        audioStreamIndex = null,
                        subtitleStreamIndex = null,
                        isPaused = false,
                        isMuted = false,
                        positionTicks = msToTicks(posMs),
                        playbackStartTimeTicks = null,
                        volumeLevel = null,
                        brightness = null,
                        aspectRatio = null,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        liveStreamId = null,
                        playSessionId = null,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        nowPlayingQueue = emptyList(),
                        playlistItemId = null
                    )
                )
            }
        }
    }

    fun reportPlaybackStop(positionMs: Long) {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        item = null,
                        itemId = itemId,
                        sessionId = null,
                        mediaSourceId = null,
                        positionTicks = msToTicks(positionMs),
                        liveStreamId = null,
                        playSessionId = null,
                        failed = false,
                        nextMediaType = null,
                        playlistItemId = null,
                        nowPlayingQueue = emptyList()
                    )
                )
            }
        }
    }
}
