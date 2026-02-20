package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class AppStateTest {

    @Test
    fun `initial state is INIT`() {
        val machine = AppStateMachine()
        assertEquals(AppState.INIT, machine.currentState)
    }

    @Test
    fun `INIT transitions to DISCOVERY`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        assertEquals(AppState.DISCOVERY, machine.currentState)
    }

    @Test
    fun `DISCOVERY transitions to QR_CODE on timeout`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.DiscoveryTimeout)
        assertEquals(AppState.QR_CODE(), machine.currentState)
    }

    @Test
    fun `DISCOVERY transitions to QR_CODE on server found`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.ServerFound("192.168.1.10"))
        assertEquals(AppState.QR_CODE(prefilledHost = "192.168.1.10"), machine.currentState)
    }

    @Test
    fun `QR_CODE transitions to CONFIGURED on config received`() {
        val machine = AppStateMachine()
        machine.transition(AppEvent.StartDiscovery)
        machine.transition(AppEvent.DiscoveryTimeout)
        machine.transition(AppEvent.ConfigReceived)
        assertEquals(AppState.CONFIGURED, machine.currentState)
    }

    @Test
    fun `CONFIGURED transitions to PLAYING on play`() {
        val machine = AppStateMachine()
        machine.setState(AppState.CONFIGURED)
        machine.transition(AppEvent.Play)
        assertEquals(AppState.PLAYING, machine.currentState)
    }

    @Test
    fun `PLAYING transitions to PAUSED`() {
        val machine = AppStateMachine()
        machine.setState(AppState.PLAYING)
        machine.transition(AppEvent.Pause)
        assertEquals(AppState.PAUSED, machine.currentState)
    }

    @Test
    fun `PLAYING or CONFIGURED transitions to QR_CODE on show qr`() {
        val machine = AppStateMachine()
        machine.setState(AppState.CONFIGURED)
        machine.transition(AppEvent.ShowQrCode)
        assertEquals(AppState.QR_CODE(), machine.currentState)
    }

    @Test
    fun `PAUSED transitions to CONFIGURED on stop`() {
        val machine = AppStateMachine()
        machine.setState(AppState.PAUSED)
        machine.transition(AppEvent.Stop)
        assertEquals(AppState.CONFIGURED, machine.currentState)
    }

    @Test
    fun `BUFFERING transitions to CONFIGURED on stop`() {
        val machine = AppStateMachine()
        machine.setState(AppState.BUFFERING)
        machine.transition(AppEvent.Stop)
        assertEquals(AppState.CONFIGURED, machine.currentState)
    }

    @Test
    fun `invalid event is silently ignored`() {
        val machine = AppStateMachine()
        // INIT state + Play event = no transition
        machine.transition(AppEvent.Play)
        assertEquals(AppState.INIT, machine.currentState)
    }
}
