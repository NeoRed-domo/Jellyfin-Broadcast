package com.jellyfinbroadcast.core

import androidx.annotation.VisibleForTesting

sealed class AppState {
    object INIT : AppState()
    object DISCOVERY : AppState()
    data class QR_CODE(val prefilledHost: String? = null) : AppState()
    object CONFIGURED : AppState()
    object PLAYING : AppState()
    object PAUSED : AppState()
    object BUFFERING : AppState()
}

sealed class AppEvent {
    object StartDiscovery : AppEvent()
    object DiscoveryTimeout : AppEvent()
    data class ServerFound(val host: String) : AppEvent()
    object ConfigReceived : AppEvent()
    object Play : AppEvent()
    object Pause : AppEvent()
    object Stop : AppEvent()
    object ShowQrCode : AppEvent()
    object Buffering : AppEvent()
    object BufferingEnd : AppEvent()
}

class AppStateMachine {
    var currentState: AppState = AppState.INIT
        private set

    @VisibleForTesting
    fun setState(state: AppState) { currentState = state }

    fun transition(event: AppEvent) {
        currentState = when {
            currentState is AppState.INIT && event is AppEvent.StartDiscovery -> AppState.DISCOVERY
            currentState is AppState.DISCOVERY && event is AppEvent.DiscoveryTimeout -> AppState.QR_CODE()
            currentState is AppState.DISCOVERY && event is AppEvent.ServerFound ->
                AppState.QR_CODE(prefilledHost = event.host)
            currentState is AppState.QR_CODE && event is AppEvent.ConfigReceived -> AppState.CONFIGURED
            currentState is AppState.CONFIGURED && event is AppEvent.Play -> AppState.PLAYING
            currentState is AppState.PLAYING && event is AppEvent.Pause -> AppState.PAUSED
            currentState is AppState.PAUSED && event is AppEvent.Play -> AppState.PLAYING
            (currentState is AppState.PLAYING || currentState is AppState.PAUSED ||
             currentState is AppState.BUFFERING) && event is AppEvent.Stop -> AppState.CONFIGURED
            currentState is AppState.PLAYING && event is AppEvent.Buffering -> AppState.BUFFERING
            currentState is AppState.BUFFERING && event is AppEvent.BufferingEnd -> AppState.PLAYING
            (currentState is AppState.CONFIGURED || currentState is AppState.PLAYING ||
             currentState is AppState.PAUSED) && event is AppEvent.ShowQrCode -> AppState.QR_CODE()
            else -> currentState
        }
    }
}
