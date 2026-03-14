package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.core.DeviceProfileFactory
import com.jellyfinbroadcast.core.JellyfinSession
import com.jellyfinbroadcast.core.MediaPlayer
import com.jellyfinbroadcast.core.PlaybackReporter
import com.jellyfinbroadcast.core.SessionStore
import com.jellyfinbroadcast.core.StreamInfo
import com.jellyfinbroadcast.server.ConfigPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.SessionApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.subscribePlayStateCommands
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import java.util.UUID

class PhoneActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhoneActivity"
    }

    val jellyfinSession by lazy { JellyfinSession(this) }
    private var playbackReporter: PlaybackReporter? = null
    private var lastBackPressTime = 0L

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { url ->
            val regex = Regex("http://([^:]+):(\\d+)")
            val match = regex.find(url)
            if (match != null) {
                val tvIp = match.groupValues[1]
                val tvPort = match.groupValues[2].toIntOrNull() ?: 8765
                showConfigForm(tvIp, tvPort)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_phone)
        if (savedInstanceState == null) {
            val sessionStore = SessionStore(this)
            if (sessionStore.hasSavedSession()) {
                showIdleScreen()
                // Restore session and register as cast target
                lifecycleScope.launch {
                    if (jellyfinSession.restoreSession()) {
                        val api = jellyfinSession.getApi()!!
                        postCapabilities(api)
                        startWebSocketListener(api)
                    }
                }
            } else {
                showQrCodeScreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showQrCodeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PhoneQrCodeFragment.newInstance(configured = false))
            .commit()
    }

    private fun showIdleScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PhoneQrCodeFragment.newInstance(configured = true))
            .commit()
    }

    fun showConfigForm(tvIp: String?, tvPort: Int = 8765, prefilledHost: String? = null) {
        val fragment = ConfigFormFragment.newInstance(tvIp, tvPort, prefilledHost)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Cadrez le QR code de la TV")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        }
        qrScanLauncher.launch(options)
    }

    fun onLocalConfigSuccess() {
        supportFragmentManager.popBackStack()
        showIdleScreen()
        enableImmersiveMode()
        // Register as cast target
        lifecycleScope.launch {
            if (jellyfinSession.restoreSession()) {
                val api = jellyfinSession.getApi()!!
                postCapabilities(api)
                startWebSocketListener(api)
            }
        }
    }

    fun onTvConfigSuccess() {
        supportFragmentManager.popBackStack()
        // Only show "Mode diffusion" if this phone has its own saved Jellyfin session
        val configured = SessionStore(this).hasSavedSession()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PhoneQrCodeFragment.newInstance(configured = configured, showMenu = true))
            .commit()
    }

    suspend fun onConfigReceived(payload: ConfigPayload): String? = "Non implémenté sur téléphone"

    // --- Jellyfin session / cast target ---

    private suspend fun postCapabilities(api: ApiClient) {
        try {
            val sessionApi = SessionApi(api)
            sessionApi.postFullCapabilities(
                data = ClientCapabilitiesDto(
                    playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
                    supportedCommands = listOf(
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_VOLUME
                    ),
                    supportsMediaControl = true,
                    supportsPersistentIdentifier = true,
                    deviceProfile = DeviceProfileFactory.build(this@PhoneActivity),
                    appStoreUrl = null,
                    iconUrl = null,
                    supportsContentUploading = null,
                    supportsSync = null
                )
            )
            Log.i(TAG, "Session capabilities posted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post capabilities: ${e.message}")
        }
    }

    private fun startWebSocketListener(api: ApiClient) {
        val webSocket: SocketApi = api.webSocket

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                webSocket.subscribe(PlayMessage::class).collectLatest { message ->
                    val data = message.data ?: return@collectLatest
                    val items = data.itemIds ?: return@collectLatest
                    Log.i(TAG, "Play command: ${data.playCommand}, items: $items")
                    if (data.playCommand == PlayCommand.PLAY_NOW && items.isNotEmpty()) {
                        val itemId = items.first()
                        val startPositionTicks = data.startPositionTicks ?: 0L
                        val startPositionMs = startPositionTicks / 10_000L
                        withContext(Dispatchers.Main) {
                            playItem(api, itemId, startPositionMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket Play listener error: ${e.message}")
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                webSocket.subscribePlayStateCommands().collectLatest { message ->
                    val data = message.data ?: return@collectLatest
                    val command = data.command
                    Log.i(TAG, "Playstate command: $command")
                    withContext(Dispatchers.Main) {
                        handlePlaystateCommand(command, data.seekPositionTicks)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket Playstate listener error: ${e.message}")
            }
        }
    }

    private suspend fun playItem(api: ApiClient, itemId: UUID, startPositionMs: Long) {
        val fragment = supportFragmentManager
            .findFragmentById(R.id.container) as? PhoneQrCodeFragment
        if (fragment == null) {
            // If we're on a different fragment (e.g. config form), navigate back first
            supportFragmentManager.popBackStack()
            showIdleScreen()
            supportFragmentManager.executePendingTransactions()
            val newFragment = supportFragmentManager
                .findFragmentById(R.id.container) as? PhoneQrCodeFragment
            newFragment?.let { startPlayback(it, api, itemId, startPositionMs) }
        } else {
            startPlayback(fragment, api, itemId, startPositionMs)
        }
    }

    private suspend fun resolveStreamInfo(
        api: ApiClient,
        itemId: UUID,
        serverUrl: String,
        token: String
    ): StreamInfo {
        val userId = jellyfinSession.getUserId()
        if (userId == null) {
            Log.w(TAG, "No userId available, falling back to HLS")
            val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
            return StreamInfo.HlsTranscode(url, null)
        }
        return try {
            val mediaInfoApi = MediaInfoApi(api)
            val profile = DeviceProfileFactory.build(this@PhoneActivity)
            val response = mediaInfoApi.getPostedPlaybackInfo(
                itemId = itemId,
                data = PlaybackInfoDto(
                    userId = userId,
                    maxStreamingBitrate = 120_000_000,
                    enableDirectPlay = true,
                    enableDirectStream = true,
                    enableTranscoding = true,
                    allowVideoStreamCopy = true,
                    allowAudioStreamCopy = true,
                    autoOpenLiveStream = true,
                    deviceProfile = profile
                )
            )
            val source = response.content.mediaSources.firstOrNull()
            val playSessionId = response.content.playSessionId
            if (source?.supportsDirectPlay == true) {
                val sourceId = source.id ?: itemId.toString()
                val url = MediaPlayer.buildDirectPlayUrl(serverUrl, itemId.toString(), token, sourceId)
                val transcodeUrl = source.transcodingUrl?.let { "$serverUrl$it" }
                Log.i(TAG, "Using DirectPlay for $itemId (source=$sourceId, fallback=$transcodeUrl)")
                StreamInfo.DirectPlay(url, playSessionId, transcodeUrl)
            } else if (source?.transcodingUrl != null) {
                Log.i(TAG, "Using HLS transcode for $itemId")
                StreamInfo.HlsTranscode("$serverUrl${source.transcodingUrl}", playSessionId)
            } else {
                Log.w(TAG, "No direct play or transcode URL, using HLS fallback")
                val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                StreamInfo.HlsTranscode(url, playSessionId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get playback info, falling back to HLS: ${e.message}")
            val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
            StreamInfo.HlsTranscode(url, null)
        }
    }

    private suspend fun startPlayback(
        fragment: PhoneQrCodeFragment,
        api: ApiClient,
        itemId: UUID,
        startPositionMs: Long
    ) {
        val serverUrl = jellyfinSession.getServerUrl() ?: return
        val token = api.accessToken ?: return
        val mediaPlayer = fragment.getMediaPlayer() ?: return

        // Stop previous playback cleanly before starting new
        mediaPlayer.stop()
        playbackReporter?.release()
        playbackReporter = null

        // Determine playback method (direct play vs HLS transcode)
        val streamInfo = withContext(Dispatchers.IO) {
            resolveStreamInfo(api, itemId, serverUrl, token)
        }

        Log.i(TAG, "Playing: ${streamInfo.url} (${streamInfo::class.simpleName})")
        mediaPlayer.play(streamInfo)
        if (startPositionMs > 0) {
            mediaPlayer.seekTo(startPositionMs)
        }

        // Hide overlay to show video
        fragment.onPlaybackStarted()

        // Start playback reporter with correct play method
        val reportPlayMethod = when (streamInfo) {
            is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
            is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
        }
        val reporter = PlaybackReporter(api, reportPlayMethod, streamInfo.playSessionId)
        playbackReporter = reporter
        reporter.reportPlaybackStart(itemId, startPositionMs)
        reporter.startPeriodicReporting(
            getPosition = { mediaPlayer.getCurrentPosition() },
            getIsPaused = { !mediaPlayer.isPlaying() }
        )

        // Report progress after seek completes (not immediately)
        mediaPlayer.onSeekCompleted = {
            reporter.reportProgressNow()
        }

        // Callbacks run on main thread — read position HERE, then report on IO
        mediaPlayer.onPlaybackEnded = {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                reporter.reportPlaybackStop(posMs)
            }
        }

        mediaPlayer.onError = { error ->
            Log.e(TAG, "Playback error: ${error.message}", error)
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                reporter.reportPlaybackStop(posMs)
            }

            // Silent fallback: retry with simple H.264+AAC HLS transcode if DirectPlay failed
            if (streamInfo is StreamInfo.DirectPlay) {
                Log.i(TAG, "DirectPlay failed, falling back to H.264+AAC HLS transcode")
                val hlsUrl = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                val hlsStream = StreamInfo.HlsTranscode(hlsUrl, streamInfo.playSessionId)
                Log.i(TAG, "Retrying with HLS fallback: $hlsUrl")
                mediaPlayer.play(hlsStream)
                if (startPositionMs > 0) {
                    mediaPlayer.seekTo(startPositionMs)
                }
                reporter.release()
                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId)
                playbackReporter = hlsReporter
                lifecycleScope.launch(Dispatchers.IO) {
                    hlsReporter.reportPlaybackStart(itemId, startPositionMs)
                }
                hlsReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlaying() }
                )
                mediaPlayer.onSeekCompleted = { hlsReporter.reportProgressNow() }
                mediaPlayer.onError = { hlsError ->
                    Log.e(TAG, "HLS fallback also failed: ${hlsError.message}", hlsError)
                    val hlsPos = mediaPlayer.getCurrentPosition()
                    lifecycleScope.launch(Dispatchers.IO) {
                        hlsReporter.reportPlaybackStop(hlsPos)
                    }
                }
            }
        }
    }

    private fun handlePlaystateCommand(command: PlaystateCommand, seekPositionTicks: Long?) {
        val fragment = supportFragmentManager
            .findFragmentById(R.id.container) as? PhoneQrCodeFragment
        val mediaPlayer = fragment?.getMediaPlayer() ?: return

        when (command) {
            PlaystateCommand.PAUSE -> mediaPlayer.pause()
            PlaystateCommand.UNPAUSE -> mediaPlayer.resume()
            PlaystateCommand.STOP -> {
                val posMs = mediaPlayer.getCurrentPosition()
                mediaPlayer.stop()
                lifecycleScope.launch(Dispatchers.IO) {
                    playbackReporter?.reportPlaybackStop(posMs)
                }
                return
            }
            PlaystateCommand.SEEK -> {
                val posMs = (seekPositionTicks ?: 0L) / 10_000L
                mediaPlayer.seekTo(posMs)
                return // Progress reported via onSeekCompleted callback
            }
            PlaystateCommand.PLAY_PAUSE -> {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause() else mediaPlayer.resume()
            }
            PlaystateCommand.NEXT_TRACK -> {}
            PlaystateCommand.PREVIOUS_TRACK -> {}
            PlaystateCommand.REWIND -> {
                val pos = mediaPlayer.getCurrentPosition()
                mediaPlayer.seekTo(maxOf(0, pos - 10_000))
                return // Progress reported via onSeekCompleted callback
            }
            PlaystateCommand.FAST_FORWARD -> {
                val pos = mediaPlayer.getCurrentPosition()
                mediaPlayer.seekTo(pos + 30_000)
                return // Progress reported via onSeekCompleted callback
            }
        }
        // Report state change immediately for non-seek commands
        playbackReporter?.reportProgressNow()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If a fragment is on the back stack (e.g. config form), just pop it
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000) {
            super.onBackPressed()
        } else {
            lastBackPressTime = now
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackReporter?.release()
    }
}
