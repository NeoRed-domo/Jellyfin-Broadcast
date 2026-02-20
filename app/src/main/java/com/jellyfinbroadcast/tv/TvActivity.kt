package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.core.AppEvent
import com.jellyfinbroadcast.core.AppState
import com.jellyfinbroadcast.core.AppStateMachine
import com.jellyfinbroadcast.core.JellyfinSession
import com.jellyfinbroadcast.server.ConfigPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TvActivity : AppCompatActivity() {

    private val stateMachine = AppStateMachine()
    private val jellyfinSession by lazy { JellyfinSession(this) }
    private var discoveredHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        stateMachine.transition(AppEvent.StartDiscovery)
        showQrCodeScreen()
    }

    private fun showQrCodeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TvQrCodeFragment())
            .commit()
    }

    private fun showPlayerScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TvPlayerFragment())
            .commit()
    }

    suspend fun onConfigReceived(payload: ConfigPayload): Boolean {
        val success = jellyfinSession.authenticate(payload)
        if (success) {
            stateMachine.transition(AppEvent.ConfigReceived)
            withContext(Dispatchers.Main) { showPlayerScreen() }
        }
        return success
    }

    fun onServerDiscovered(host: String, port: Int) {
        discoveredHost = host
        stateMachine.transition(AppEvent.ServerFound(host))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Long press center D-pad from valid states → show QR code for reconfiguration
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            event.isLongPress &&
            (stateMachine.currentState is AppState.CONFIGURED ||
             stateMachine.currentState is AppState.PLAYING ||
             stateMachine.currentState is AppState.PAUSED)
        ) {
            stateMachine.transition(AppEvent.ShowQrCode)
            showQrCodeScreen()
            return true
        }
        // Delegate to player fragment if playing
        val playerFragment = supportFragmentManager.findFragmentById(R.id.container) as? TvPlayerFragment
        if (playerFragment?.onKeyDown(keyCode, event) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}
