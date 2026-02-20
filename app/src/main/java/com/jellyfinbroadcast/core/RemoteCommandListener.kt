package com.jellyfinbroadcast.core

import kotlinx.coroutines.*
import org.jellyfin.sdk.api.client.ApiClient

sealed class RemoteCommand {
    data class Play(val itemId: String, val positionMs: Long = 0) : RemoteCommand()
    object Pause : RemoteCommand()
    object Resume : RemoteCommand()
    object Stop : RemoteCommand()
    data class Seek(val positionMs: Long) : RemoteCommand()
    object PlayNext : RemoteCommand()
    object PlayPrevious : RemoteCommand()
}

class RemoteCommandListener(
    private val api: ApiClient,
    private val onCommand: (RemoteCommand) -> Unit
) {
    companion object {
        const val MAX_RECONNECT_DELAY_MS = 30_000L

        fun parseSeekPositionMs(ms: Long): Long = ms
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job = scope.launch {
            var delay = 1000L
            while (isActive) {
                try {
                    listenForCommands()
                    delay = 1000L // reset on clean exit
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(delay)
                    delay = minOf(delay * 2, MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private suspend fun listenForCommands() {
        // WebSocket connection via Jellyfin SDK
        // The SDK websocket is accessed via api.ws (WebSocketApi)
        // Commands arrive as IncomingSocketMessage subtypes
        // This is a placeholder — actual WebSocket integration is in Task 11 (TvActivity)
        // where the full session context is available
        delay(Long.MAX_VALUE) // keep alive until cancelled
    }

    fun stop() {
        job?.cancel()
    }
}
