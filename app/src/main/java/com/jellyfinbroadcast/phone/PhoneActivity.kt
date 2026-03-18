package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private var playlistStreamInfos: MutableList<StreamInfo>? = null
    private var playlistItemIds: List<UUID>? = null
    private var playlistFailedCount = 0
    private var webSocketJobs: MutableList<Job> = mutableListOf()

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { url ->
            val regex = Regex("http://([^:]+):(\\d+)")
            val match = regex.find(url)
            if (match != null) {
                val tvIp = match.groupValues[1]
                val tvPort = match.groupValues[2].toIntOrNull() ?: 8765
                val token = android.net.Uri.parse(url).getQueryParameter("token") ?: ""
                showConfigForm(tvIp, tvPort, token = token)
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

    fun showConfigForm(tvIp: String?, tvPort: Int = 8765, prefilledHost: String? = null, token: String = "") {
        val fragment = ConfigFormFragment.newInstance(tvIp, tvPort, prefilledHost, token)
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
        webSocketJobs.forEach { it.cancel() }
        webSocketJobs.clear()

        val webSocket: SocketApi = api.webSocket

        webSocketJobs += lifecycleScope.launch(Dispatchers.IO) {
            var backoffMs = 1000L
            while (isActive) {
                try {
                    backoffMs = 1000L // reset on successful connection
                    webSocket.subscribe(PlayMessage::class).collectLatest { message ->
                        val data = message.data ?: return@collectLatest
                        val items = data.itemIds ?: return@collectLatest
                        Log.i(TAG, "Play command: ${data.playCommand}, items: $items")
                        if (data.playCommand == PlayCommand.PLAY_NOW && items.isNotEmpty()) {
                            val startPositionTicks = data.startPositionTicks ?: 0L
                            val startPositionMs = startPositionTicks / 10_000L
                            withContext(Dispatchers.Main) {
                                if (items.size == 1) {
                                    playItem(api, items.first(), startPositionMs)
                                } else {
                                    playPlaylist(api, items, startPositionMs)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "WebSocket Play listener error: ${e.message}, reconnecting in ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }

        webSocketJobs += lifecycleScope.launch(Dispatchers.IO) {
            var backoffMs = 1000L
            while (isActive) {
                try {
                    backoffMs = 1000L // reset on successful connection
                    webSocket.subscribePlayStateCommands().collectLatest { message ->
                        val data = message.data ?: return@collectLatest
                        val command = data.command
                        Log.i(TAG, "Playstate command: $command")
                        withContext(Dispatchers.Main) {
                            handlePlaystateCommand(command, data.seekPositionTicks)
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "WebSocket Playstate listener error: ${e.message}, reconnecting in ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun cleanupPlaybackState(mediaPlayer: MediaPlayer) {
        mediaPlayer.stop()
        mediaPlayer.onItemTransition = null
        mediaPlayer.onPlaybackEnded = null
        mediaPlayer.onError = null
        mediaPlayer.onSeekCompleted = null
        playbackReporter?.release()
        playbackReporter = null
        playlistStreamInfos = null
        playlistItemIds = null
        playlistFailedCount = 0
        // Phone doesn't use passthrough, no re-init needed
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

    private suspend fun playPlaylist(api: ApiClient, itemIds: List<UUID>, startPositionMs: Long) {
        val fragment = supportFragmentManager
            .findFragmentById(R.id.container) as? PhoneQrCodeFragment
            ?: run {
                supportFragmentManager.popBackStack()
                showIdleScreen()
                supportFragmentManager.executePendingTransactions()
                supportFragmentManager.findFragmentById(R.id.container) as? PhoneQrCodeFragment
            }
        val mediaPlayer = fragment?.getMediaPlayer() ?: return
        val serverUrl = jellyfinSession.getServerUrl() ?: return
        val token = api.accessToken ?: return

        cleanupPlaybackState(mediaPlayer)

        val streamInfos = withContext(Dispatchers.IO) {
            itemIds.map { itemId ->
                async {
                    try {
                        withTimeout(5000) {
                            resolveStreamInfo(api, itemId, serverUrl, token)
                        }
                    } catch (_: Exception) {
                        Log.w(TAG, "Timeout resolving stream for $itemId, using HLS fallback")
                        val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                        StreamInfo.HlsTranscode(url, null) as StreamInfo
                    }
                }
            }.awaitAll()
        }

        playlistStreamInfos = streamInfos.toMutableList()
        playlistItemIds = itemIds

        Log.i(TAG, "Playing playlist: ${itemIds.size} items")
        mediaPlayer.playPlaylist(streamInfos)
        if (startPositionMs > 0) {
            mediaPlayer.seekTo(startPositionMs)
        }

        fragment.onPlaybackStarted()

        val firstStream = streamInfos.first()
        val reportPlayMethod = when (firstStream) {
            is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
            is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
        }
        val reporter = PlaybackReporter(api, reportPlayMethod, firstStream.playSessionId, firstStream.subtitleStreamIndex)
        playbackReporter = reporter
        reporter.reportPlaybackStart(itemIds.first(), startPositionMs)
        reporter.startPeriodicReporting(
            getPosition = { mediaPlayer.getCurrentPosition() },
            getIsPaused = { !mediaPlayer.isPlaying() }
        )

        mediaPlayer.onItemTransition = { newIndex ->
            val ids = playlistItemIds
            val streams = playlistStreamInfos
            if (ids != null && streams != null && newIndex < ids.size) {
                val posMs = mediaPlayer.getCurrentPosition()
                val oldReporter = playbackReporter
                lifecycleScope.launch(Dispatchers.IO) {
                    oldReporter?.reportPlaybackStop(posMs)
                    oldReporter?.release()
                }

                val stream = streams[newIndex]
                val method = when (stream) {
                    is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
                    is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
                }
                val newReporter = PlaybackReporter(api, method, stream.playSessionId, stream.subtitleStreamIndex)
                playbackReporter = newReporter
                newReporter.reportPlaybackStart(ids[newIndex], 0)
                newReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlaying() }
                )
                Log.i(TAG, "Playlist transition: item ${newIndex + 1}/${ids.size}")
            }
        }

        mediaPlayer.onSeekCompleted = { playbackReporter?.reportProgressNow() }

        mediaPlayer.onPlaybackEnded = {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                playbackReporter?.reportPlaybackStop(posMs)
            }
            playlistStreamInfos = null
            playlistItemIds = null
        }

        mediaPlayer.onError = onError@{ error ->
            Log.e(TAG, "Playlist item error: ${error.message}", error)
            val currentIndex = mediaPlayer.getCurrentItemIndex()
            val ids = playlistItemIds
            val streams = playlistStreamInfos

            if (ids == null || streams == null || currentIndex >= streams.size) return@onError

            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                playbackReporter?.reportPlaybackStop(posMs)
            }
            playbackReporter?.release()

            val currentStream = streams[currentIndex]
            val itemId = ids[currentIndex]
            val hlsUrl = when (currentStream) {
                is StreamInfo.DirectPlay -> MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                is StreamInfo.HlsTranscode -> null
            }

            if (hlsUrl != null) {
                Log.i(TAG, "Replacing playlist item $currentIndex with HLS fallback")
                val hlsStream = StreamInfo.HlsTranscode(hlsUrl, currentStream.playSessionId)
                streams[currentIndex] = hlsStream
                mediaPlayer.replaceItem(currentIndex, hlsStream)

                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId, hlsStream.subtitleStreamIndex)
                playbackReporter = hlsReporter
                lifecycleScope.launch(Dispatchers.IO) {
                    hlsReporter.reportPlaybackStart(itemId, 0)
                }
                hlsReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlaying() }
                )
            } else {
                playlistFailedCount++
                if (playlistFailedCount >= ids.size) {
                    Log.e(TAG, "All playlist items failed")
                } else if (currentIndex + 1 < mediaPlayer.getItemCount()) {
                    Log.i(TAG, "Skipping failed item $currentIndex")
                    mediaPlayer.seekToItem(currentIndex + 1)
                }
            }
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

            // Find forced subtitle track
            val forcedSub = source?.mediaStreams
                ?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && it.isForced == true }
            val subIndex = forcedSub?.index
            if (subIndex != null) Log.i(TAG, "Found forced subtitle: index=$subIndex lang=${forcedSub.language} codec=${forcedSub.codec}")

            if (source?.supportsDirectPlay == true) {
                val sourceId = source.id ?: itemId.toString()
                val url = MediaPlayer.buildDirectPlayUrl(serverUrl, itemId.toString(), token, sourceId)
                val transcodeUrl = source.transcodingUrl?.let { "$serverUrl$it" }
                val extSubUrl = if (forcedSub?.deliveryMethod == org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.EXTERNAL && forcedSub.deliveryUrl != null) {
                    val subUrl = forcedSub.deliveryUrl!!
                    if (subUrl.startsWith("http")) subUrl else "$serverUrl$subUrl"
                } else null
                val extSubMime = when (forcedSub?.codec?.lowercase()) {
                    "srt", "subrip" -> "application/x-subrip"
                    "vtt", "webvtt" -> "text/vtt"
                    "ass", "ssa" -> "text/x-ssa"
                    else -> null
                }
                Log.i(TAG, "Using DirectPlay for $itemId (source=$sourceId, forcedSub=$subIndex, extSub=$extSubUrl)")
                StreamInfo.DirectPlay(url, playSessionId, subIndex, transcodeUrl, extSubUrl, extSubMime)
            } else if (source?.transcodingUrl != null) {
                var transcodeUrl = "$serverUrl${source.transcodingUrl}"
                if (subIndex != null && !transcodeUrl.contains("SubtitleStreamIndex")) {
                    transcodeUrl += "&SubtitleStreamIndex=$subIndex"
                }
                Log.i(TAG, "Using HLS transcode for $itemId (forcedSub=$subIndex)")
                StreamInfo.HlsTranscode(transcodeUrl, playSessionId, subIndex)
            } else {
                Log.w(TAG, "No direct play or transcode URL, using HLS fallback")
                val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, subtitleStreamIndex = subIndex)
                StreamInfo.HlsTranscode(url, playSessionId, subIndex)
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
        cleanupPlaybackState(mediaPlayer)

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
        val reporter = PlaybackReporter(api, reportPlayMethod, streamInfo.playSessionId, streamInfo.subtitleStreamIndex)
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
                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId, hlsStream.subtitleStreamIndex)
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
                mediaPlayer.onItemTransition = null
                mediaPlayer.onPlaybackEnded = null
                mediaPlayer.onError = null
                mediaPlayer.onSeekCompleted = null
                lifecycleScope.launch(Dispatchers.IO) {
                    playbackReporter?.reportPlaybackStop(posMs)
                    playbackReporter?.release()
                    playbackReporter = null
                }
                playlistStreamInfos = null
                playlistItemIds = null
                return
            }
            PlaystateCommand.SEEK -> {
                val posMs = (seekPositionTicks ?: 0L) / 10_000L
                mediaPlayer.seekTo(posMs)
                return // Progress reported via onSeekCompleted callback
            }
            PlaystateCommand.PLAY_PAUSE -> {
                if (mediaPlayer.isPlayWhenReady()) mediaPlayer.pause() else mediaPlayer.resume()
            }
            PlaystateCommand.NEXT_TRACK -> {
                if (mediaPlayer.getItemCount() > 1) {
                    mediaPlayer.getExoPlayer()?.seekToNextMediaItem()
                }
            }
            PlaystateCommand.PREVIOUS_TRACK -> {
                if (mediaPlayer.getItemCount() > 1) {
                    mediaPlayer.getExoPlayer()?.seekToPreviousMediaItem()
                }
            }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handlePlaystateCommand(PlaystateCommand.PLAY_PAUSE, null)
                }
                return true // consume both DOWN and UP
            }
        }
        return super.dispatchKeyEvent(event)
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
        webSocketJobs.forEach { it.cancel() }
        webSocketJobs.clear()
        playbackReporter?.release()
        playbackReporter = null
        playlistStreamInfos = null
        playlistItemIds = null
        super.onDestroy()
    }
}
