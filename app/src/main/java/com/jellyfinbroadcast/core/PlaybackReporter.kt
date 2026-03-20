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
    private var playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
    private var playSessionId: String? = null,
    private var subtitleStreamIndex: Int? = null
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
    @Volatile var lastKnownPositionMs: Long = 0
        private set

    /** Set internal state without sending a network report. Use for silent fallbacks. */
    fun setCurrentItem(itemId: UUID, positionMs: Long) {
        currentItemId = itemId
        lastKnownPositionMs = positionMs
    }

    /** Update session info for silent fallbacks (codec change without stop/start) */
    fun updateSessionInfo(playMethod: PlayMethod, playSessionId: String?, subtitleStreamIndex: Int? = null) {
        this.playMethod = playMethod
        this.playSessionId = playSessionId
        this.subtitleStreamIndex = subtitleStreamIndex
    }

    /** Suspend: waits for the START report to reach the server before returning. */
    suspend fun reportPlaybackStart(itemId: UUID, positionMs: Long) {
        currentItemId = itemId
        lastKnownPositionMs = positionMs
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                playstateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = true,
                        item = null,
                        itemId = itemId,
                        sessionId = null,
                        mediaSourceId = null,
                        audioStreamIndex = null,
                        subtitleStreamIndex = this@PlaybackReporter.subtitleStreamIndex,
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

    /**
     * Transition from one item to another: STOP(old) then START(new), sequentially.
     * Uses the SAME reporter — no race conditions from multiple reporters.
     */
    suspend fun transitionToItem(
        newItemId: UUID,
        newPlayMethod: PlayMethod,
        newPlaySessionId: String?,
        newSubtitleStreamIndex: Int? = null
    ) {
        val oldItemId = currentItemId
        val endPos = lastKnownPositionMs
        // Stop periodic reporting during transition
        reportingJob?.cancel()
        // Report stop for old item
        if (oldItemId != null) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    playstateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            item = null,
                            itemId = oldItemId,
                            sessionId = null,
                            mediaSourceId = null,
                            positionTicks = msToTicks(endPos),
                            liveStreamId = null,
                            playSessionId = this@PlaybackReporter.playSessionId,
                            failed = false,
                            nextMediaType = null,
                            playlistItemId = null,
                            nowPlayingQueue = emptyList()
                        )
                    )
                }.onFailure { Log.w(TAG, "transitionToItem: stop failed: ${it.message}") }
            }
        }
        // Update session info for new item
        playMethod = newPlayMethod
        playSessionId = newPlaySessionId
        subtitleStreamIndex = newSubtitleStreamIndex
        // Report start for new item
        reportPlaybackStart(newItemId, 0)
        // Resume periodic reporting
        if (getPositionMs != null) {
            reportingJob = scope.launch {
                while (isActive) {
                    delay(REPORT_INTERVAL_MS)
                    reportProgress()
                }
            }
        }
    }

    fun stopPeriodicReporting() {
        reportingJob?.cancel()
        reportingJob = null
    }

    fun startPeriodicReporting(getPosition: () -> Long, getIsPaused: () -> Boolean = { false }) {
        getPositionMs = getPosition
        getIsPausedState = getIsPaused
        reportingJob?.cancel()
        reportingJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportProgress()
            }
        }
    }

    private suspend fun reportProgress() {
        val itemId = currentItemId ?: return
        val (posMs, isPaused) = withContext(Dispatchers.Main) {
            val pos = getPositionMs?.invoke() ?: 0L
            val paused = getIsPausedState?.invoke() ?: false
            pos to paused
        }
        lastKnownPositionMs = posMs
        runCatching {
            playstateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    canSeek = true,
                    item = null,
                    itemId = itemId,
                    sessionId = null,
                    mediaSourceId = null,
                    audioStreamIndex = null,
                    subtitleStreamIndex = this@PlaybackReporter.subtitleStreamIndex,
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
        withContext(NonCancellable + Dispatchers.IO) {
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
    }

    fun reportProgressNow() {
        scope.launch { reportProgress() }
    }

    fun release() {
        reportingJob?.cancel()
        scope.cancel()
        getPositionMs = null
        getIsPausedState = null
        currentItemId = null
    }
}
