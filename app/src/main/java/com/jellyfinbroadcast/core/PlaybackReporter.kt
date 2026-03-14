package com.jellyfinbroadcast.core

import android.util.Log
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

class PlaybackReporter(
    private val api: ApiClient,
    private val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
    private val playSessionId: String? = null
) {

    companion object {
        private const val TAG = "PlaybackReporter"
        const val REPORT_INTERVAL_MS = 10_000L
        const val TICKS_PER_MS = 10_000L

        fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS
    }

    private val playstateApi = PlayStateApi(api)
    private var reportingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentItemId: UUID? = null
    private var getPositionMs: (() -> Long)? = null
    private var getIsPausedState: (() -> Boolean)? = null

    fun reportPlaybackStart(itemId: UUID, positionMs: Long) {
        currentItemId = itemId
        scope.launch {
            runCatching {
                playstateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = true,
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
                        playMethod = this@PlaybackReporter.playMethod,
                        liveStreamId = null,
                        playSessionId = this@PlaybackReporter.playSessionId,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        nowPlayingQueue = emptyList(),
                        playlistItemId = null
                    )
                )
            }.onFailure { Log.w(TAG, "reportPlaybackStart failed: ${it.message}") }
        }
    }

    fun startPeriodicReporting(getPosition: () -> Long, getIsPaused: () -> Boolean = { false }) {
        getPositionMs = getPosition
        getIsPausedState = getIsPaused
        reportingJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportProgress()
            }
        }
    }

    private suspend fun reportProgress() {
        val itemId = currentItemId ?: return
        // Read ExoPlayer state on main thread (required by ExoPlayer)
        val (posMs, isPaused) = withContext(Dispatchers.Main) {
            val pos = getPositionMs?.invoke() ?: 0L
            val paused = getIsPausedState?.invoke() ?: false
            pos to paused
        }
        runCatching {
            playstateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    canSeek = true,
                    item = null,
                    itemId = itemId,
                    sessionId = null,
                    mediaSourceId = null,
                    audioStreamIndex = null,
                    subtitleStreamIndex = null,
                    isPaused = isPaused,
                    isMuted = false,
                    positionTicks = msToTicks(posMs),
                    playbackStartTimeTicks = null,
                    volumeLevel = null,
                    brightness = null,
                    aspectRatio = null,
                    playMethod = this@PlaybackReporter.playMethod,
                    liveStreamId = null,
                    playSessionId = this@PlaybackReporter.playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    nowPlayingQueue = emptyList(),
                    playlistItemId = null
                )
            )
        }.onFailure { Log.w(TAG, "reportProgress failed: ${it.message}") }
    }

    suspend fun reportPlaybackStop(positionMs: Long) {
        val itemId = currentItemId ?: return
        reportingJob?.cancel()
        runCatching {
            playstateApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    item = null,
                    itemId = itemId,
                    sessionId = null,
                    mediaSourceId = null,
                    positionTicks = msToTicks(positionMs),
                    liveStreamId = null,
                    playSessionId = this@PlaybackReporter.playSessionId,
                    failed = false,
                    nextMediaType = null,
                    playlistItemId = null,
                    nowPlayingQueue = emptyList()
                )
            )
        }.onFailure { Log.w(TAG, "reportPlaybackStop failed: ${it.message}") }
    }

    /** Send a progress report immediately (e.g. after pause/resume/seek) */
    fun reportProgressNow() {
        scope.launch { reportProgress() }
    }

    fun release() {
        reportingJob?.cancel()
        scope.cancel()
    }
}
