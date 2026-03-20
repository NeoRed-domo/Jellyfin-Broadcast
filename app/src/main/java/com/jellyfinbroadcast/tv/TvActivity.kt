package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.core.AppEvent
import com.jellyfinbroadcast.core.AppState
import com.jellyfinbroadcast.core.AppStateMachine
import com.jellyfinbroadcast.core.DeviceProfileFactory
import com.jellyfinbroadcast.core.JellyfinSession
import com.jellyfinbroadcast.core.MediaPlayer
import com.jellyfinbroadcast.core.PlaybackReporter
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

class TvActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvActivity"
    }

    private val stateMachine = AppStateMachine()
    val jellyfinSession by lazy { JellyfinSession(this) }
    private var discoveredHost: String? = null
    private var playbackReporter: PlaybackReporter? = null
    private var lastBackPressTime = 0L
    private var playlistStreamInfos: MutableList<StreamInfo>? = null
    private var playlistItemIds: List<UUID>? = null
    private var playlistFailedCount = 0
    private var webSocketJobs: MutableList<Job> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)

        lifecycleScope.launch {
            val restored = jellyfinSession.restoreSession()
            if (restored) {
                Log.i(TAG, "Session restored, going to player")
                stateMachine.setState(AppState.CONFIGURED)
                val api = jellyfinSession.getApi()!!
                postCapabilities(api)
                withContext(Dispatchers.Main) { showPlayerScreen() }
                startWebSocketListener(api)
            } else {
                stateMachine.transition(AppEvent.StartDiscovery)
                showQrCodeScreen()
            }
        }
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

    private fun showQrCodeOverlay() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TvQrCodeFragment())
            .addToBackStack("qr_overlay")
            .commit()
    }

    suspend fun onConfigReceived(payload: ConfigPayload): String? {
        val error = jellyfinSession.authenticate(payload)
        if (error == null) {
            stateMachine.transition(AppEvent.ConfigReceived)
            val api = jellyfinSession.getApi()!!
            postCapabilities(api)
            startWebSocketListener(api)
            // Schedule navigation AFTER this function returns so the HTTP response is sent first
            lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(200)
                supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                showPlayerScreen()
            }
        }
        return error
    }

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
                    deviceProfile = DeviceProfileFactory.build(this@TvActivity),
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
        // Cancel any existing WebSocket listeners before creating new ones
        webSocketJobs.forEach { it.cancel() }
        webSocketJobs.clear()

        val webSocket: SocketApi = api.webSocket

        // Listen for Play commands (play an item)
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

        // Listen for Playstate commands (pause, stop, seek, etc.)
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

    /**
     * Clean up all playback state and report stop to Jellyfin.
     * Suspend version: waits for the stop report to reach the server before returning.
     * This ensures correct ordering: stop(old) completes THEN start(new) is sent.
     */
    private suspend fun cleanupPlaybackStateAndReport(mediaPlayer: MediaPlayer) {
        val posMs = mediaPlayer.getCurrentPosition()
        val previousReporter = playbackReporter
        mediaPlayer.stop()
        mediaPlayer.onItemTransition = null
        mediaPlayer.onPlaybackEnded = null
        mediaPlayer.onPlaybackReady = null
        mediaPlayer.onError = null
        mediaPlayer.onSeekCompleted = null
        playbackReporter = null
        playlistStreamInfos = null
        playlistItemIds = null
        playlistFailedCount = 0
        // Send stop for previous item and WAIT for it to complete
        if (previousReporter != null) {
            withContext(Dispatchers.IO) {
                previousReporter.reportPlaybackStop(posMs)
                previousReporter.release()
            }
        }
    }

    /** Quick cleanup without reporting (for STOP command which reports separately) */
    private fun cleanupPlaybackState(mediaPlayer: MediaPlayer) {
        mediaPlayer.stop()
        mediaPlayer.onItemTransition = null
        mediaPlayer.onPlaybackEnded = null
        mediaPlayer.onPlaybackReady = null
        mediaPlayer.onError = null
        mediaPlayer.onSeekCompleted = null
        playbackReporter = null
        playlistStreamInfos = null
        playlistItemIds = null
        playlistFailedCount = 0
    }

    private suspend fun playItem(api: ApiClient, itemId: UUID, startPositionMs: Long) {
        val playerFragment = supportFragmentManager
            .findFragmentById(R.id.container) as? TvPlayerFragment
        if (playerFragment == null) {
            showPlayerScreen()
            // Post to play after fragment is created
            supportFragmentManager.executePendingTransactions()
            val newFragment = supportFragmentManager
                .findFragmentById(R.id.container) as? TvPlayerFragment
            newFragment?.let { startPlayback(it, api, itemId, startPositionMs) }
        } else {
            startPlayback(playerFragment, api, itemId, startPositionMs)
        }
    }

    private suspend fun playPlaylist(api: ApiClient, itemIds: List<UUID>, startPositionMs: Long) {
        val playerFragment = supportFragmentManager
            .findFragmentById(R.id.container) as? TvPlayerFragment
            ?: run {
                showPlayerScreen()
                supportFragmentManager.executePendingTransactions()
                supportFragmentManager.findFragmentById(R.id.container) as? TvPlayerFragment
            }
        val mediaPlayer = playerFragment?.getMediaPlayer() ?: return
        val serverUrl = jellyfinSession.getServerUrl() ?: return
        val token = api.accessToken ?: return

        // Stop previous playback and report stop to Jellyfin (waits for completion)
        cleanupPlaybackStateAndReport(mediaPlayer)

        // Resolve all streams in parallel with 5s per-item timeout
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

        // Prepare reporter but DON'T report start yet
        val firstStream = streamInfos.first()
        val reportPlayMethod = when (firstStream) {
            is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
            is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
        }
        val reporter = PlaybackReporter(api, reportPlayMethod, firstStream.playSessionId, firstStream.subtitleStreamIndex)
        playbackReporter = reporter

        // Report start only when ExoPlayer is actually ready
        mediaPlayer.onPlaybackReady = {
            Log.i(TAG, "Playlist player ready, reporting start to Jellyfin")
            reporter.reportPlaybackStart(itemIds.first(), mediaPlayer.getCurrentPosition())
            reporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlayWhenReady() }
            )
            stateMachine.transition(AppEvent.Play)
        }

        Log.i(TAG, "Playing playlist: ${itemIds.size} items")
        mediaPlayer.playPlaylist(streamInfos, startPositionMs)

        // Item transition handler — new reporter per item
        // STOP must complete before START to ensure correct ordering at the server
        mediaPlayer.onItemTransition = { newIndex ->
            val ids = playlistItemIds
            val streams = playlistStreamInfos
            if (ids != null && streams != null && newIndex < ids.size) {
                val oldReporter = playbackReporter
                val endPosMs = oldReporter?.lastKnownPositionMs ?: 0L
                // Sequential: STOP(old) → wait → START(new)
                lifecycleScope.launch(Dispatchers.IO) {
                    oldReporter?.reportPlaybackStop(endPosMs)
                    oldReporter?.release()
                    // Now start the new reporter on main thread
                    withContext(Dispatchers.Main) {
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
                            getIsPaused = { !mediaPlayer.isPlayWhenReady() }
                        )
                        Log.i(TAG, "Playlist transition: item ${newIndex + 1}/${ids.size} (${ids[newIndex]})")
                    }
                }
            }
        }

        mediaPlayer.onSeekCompleted = {
            playbackReporter?.reportProgressNow()
        }

        // Playlist ended (last item finished)
        mediaPlayer.onPlaybackEnded = {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                playbackReporter?.reportPlaybackStop(posMs)
            }
            playlistStreamInfos = null
            playlistItemIds = null
            stateMachine.transition(AppEvent.Stop)
        }

        // Per-item error fallback — NO stop report during internal fallback
        mediaPlayer.onError = onError@{ error ->
            Log.e(TAG, "Playlist item error: ${error.message}", error)
            val currentIndex = mediaPlayer.getCurrentItemIndex()
            val ids = playlistItemIds
            val streams = playlistStreamInfos

            if (ids == null || streams == null || currentIndex >= streams.size) {
                stateMachine.transition(AppEvent.Stop)
                return@onError
            }

            val posMs = mediaPlayer.getCurrentPosition()
            val currentStream = streams[currentIndex]
            val itemId = ids[currentIndex]

            // Try HLS fallback for this item (unless already HLS)
            val hlsUrl = when (currentStream) {
                is StreamInfo.DirectPlay -> currentStream.serverTranscodeUrl
                    ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                is StreamInfo.HlsTranscode -> null
            }

            if (hlsUrl != null) {
                // Silent fallback — server sees continuous playback, no stop report
                Log.i(TAG, "Silently replacing playlist item $currentIndex with HLS fallback")
                val hlsStream = StreamInfo.HlsTranscode(hlsUrl, currentStream.playSessionId)
                streams[currentIndex] = hlsStream
                playbackReporter?.release()
                mediaPlayer.replaceItem(currentIndex, hlsStream, posMs)

                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId, hlsStream.subtitleStreamIndex)
                playbackReporter = hlsReporter
                hlsReporter.reportPlaybackStart(itemId, posMs)
                hlsReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlayWhenReady() }
                )
            } else {
                // No fallback possible — report stop and skip
                lifecycleScope.launch(Dispatchers.IO) {
                    playbackReporter?.reportPlaybackStop(posMs)
                }
                playbackReporter?.release()
                playlistFailedCount++
                if (playlistFailedCount >= ids.size) {
                    Log.e(TAG, "All playlist items failed, stopping")
                    stateMachine.transition(AppEvent.Stop)
                } else if (currentIndex + 1 < mediaPlayer.getItemCount()) {
                    Log.i(TAG, "Skipping failed item $currentIndex, advancing to next")
                    mediaPlayer.seekToItem(currentIndex + 1)
                } else {
                    Log.e(TAG, "Last playlist item failed, stopping")
                    stateMachine.transition(AppEvent.Stop)
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
            val profile = DeviceProfileFactory.build(this@TvActivity)
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
                val container = source.container
                val url = MediaPlayer.buildDirectPlayUrl(serverUrl, itemId.toString(), token, sourceId, container)
                val transcodeUrl = source.transcodingUrl?.let { "$serverUrl$it" }
                // For direct play, check if forced sub is delivered externally
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
                Log.i(TAG, "Using DirectPlay for $itemId (source=$sourceId, container=$container, forcedSub=$subIndex, extSub=$extSubUrl)")
                StreamInfo.DirectPlay(url, playSessionId, subIndex, transcodeUrl, extSubUrl, extSubMime)
            } else if (source?.transcodingUrl != null) {
                // Append forced subtitle index to transcode URL if not already present
                var transcodeUrl = "$serverUrl${source.transcodingUrl}"
                if (subIndex != null && !transcodeUrl.contains("SubtitleStreamIndex")) {
                    transcodeUrl += "&SubtitleStreamIndex=$subIndex"
                }
                Log.i(TAG, "Using HLS transcode for $itemId (forcedSub=$subIndex)")
                StreamInfo.HlsTranscode(transcodeUrl, playSessionId, subIndex)
            } else {
                Log.w(TAG, "No direct play or transcode URL, using HLS fallback")
                val sourceId = source?.id ?: itemId.toString().replace("-", "")
                val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId, subIndex)
                StreamInfo.HlsTranscode(url, playSessionId, subIndex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get playback info: ${e.message}", e)
            // Try DirectPlay first (device likely supports the codecs), with HLS as fallback
            val sourceId = itemId.toString().replace("-", "")
            val url = MediaPlayer.buildDirectPlayUrl(serverUrl, itemId.toString(), token, sourceId)
            val hlsFallback = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId)
            Log.i(TAG, "API failed, trying DirectPlay for $itemId (source=$sourceId)")
            StreamInfo.DirectPlay(url, null, serverTranscodeUrl = hlsFallback)
        }
    }

    private suspend fun startPlayback(
        fragment: TvPlayerFragment,
        api: ApiClient,
        itemId: UUID,
        startPositionMs: Long
    ) {
        val serverUrl = jellyfinSession.getServerUrl() ?: return
        val token = api.accessToken ?: return
        val mediaPlayer = fragment.getMediaPlayer() ?: return

        // Stop previous playback and report stop to Jellyfin (waits for completion)
        cleanupPlaybackStateAndReport(mediaPlayer)

        // Restore passthrough if it was downgraded during previous error recovery
        if (!mediaPlayer.isPassthroughEnabled()) {
            Log.i(TAG, "Restoring passthrough for new playback")
            mediaPlayer.initialize(enablePassthrough = true)
            fragment.rebindPlayer(mediaPlayer)
        }

        // Determine playback method (direct play vs HLS transcode)
        val streamInfo = withContext(Dispatchers.IO) {
            resolveStreamInfo(api, itemId, serverUrl, token)
        }

        // Prepare reporter but DON'T report start yet — wait until player is actually ready
        val reportPlayMethod = when (streamInfo) {
            is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
            is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
        }
        val reporter = PlaybackReporter(api, reportPlayMethod, streamInfo.playSessionId, streamInfo.subtitleStreamIndex)
        playbackReporter = reporter

        // Report start only when ExoPlayer is actually ready to play
        mediaPlayer.onPlaybackReady = {
            Log.i(TAG, "Player ready, reporting start to Jellyfin")
            reporter.reportPlaybackStart(itemId, mediaPlayer.getCurrentPosition())
            reporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlayWhenReady() }
            )
            stateMachine.transition(AppEvent.Play)
        }

        Log.i(TAG, "Playing: ${streamInfo.url} (${streamInfo::class.simpleName})")
        mediaPlayer.play(streamInfo, startPositionMs)

        // Report progress after seek completes (not immediately)
        mediaPlayer.onSeekCompleted = {
            reporter.reportProgressNow()
        }

        // Callbacks run on main thread (ExoPlayer listener) — read position HERE, then report on IO
        mediaPlayer.onPlaybackEnded = {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                reporter.reportPlaybackStop(posMs)
            }
            stateMachine.transition(AppEvent.Stop)
        }

        mediaPlayer.onError = { error ->
            Log.e(TAG, "Playback error: ${error.message}", error)
            val posMs = mediaPlayer.getCurrentPosition()

            if (mediaPlayer.isPassthroughEnabled() && MediaPlayer.isAudioTrackError(error)) {
                // Audio passthrough failed → silent retry with PCM (NO stop report to server)
                Log.i(TAG, "Audio passthrough failed (${error.errorCode}), silently retrying with PCM")
                reporter.release()
                mediaPlayer.initialize(enablePassthrough = false)
                val playerFrag = supportFragmentManager
                    .findFragmentById(R.id.container) as? TvPlayerFragment
                playerFrag?.rebindPlayer(mediaPlayer)
                mediaPlayer.play(streamInfo, posMs)

                // Continue reporting with same play session — server sees no interruption
                val pcmReporter = PlaybackReporter(api, reportPlayMethod, streamInfo.playSessionId, streamInfo.subtitleStreamIndex)
                playbackReporter = pcmReporter
                pcmReporter.reportPlaybackStart(itemId, posMs)
                pcmReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlayWhenReady() }
                )
                mediaPlayer.onSeekCompleted = { pcmReporter.reportProgressNow() }
                mediaPlayer.onPlaybackEnded = {
                    val endPos = mediaPlayer.getCurrentPosition()
                    lifecycleScope.launch(Dispatchers.IO) {
                        pcmReporter.reportPlaybackStop(endPos)
                    }
                    stateMachine.transition(AppEvent.Stop)
                }
                mediaPlayer.onError = { pcmError ->
                    Log.e(TAG, "PCM also failed: ${pcmError.message}", pcmError)
                    val pcmPos = mediaPlayer.getCurrentPosition()
                    if (streamInfo is StreamInfo.DirectPlay) {
                        // Silent HLS fallback (NO stop report)
                        val sourceId = itemId.toString().replace("-", "")
                        val hlsUrl = streamInfo.serverTranscodeUrl
                            ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId)
                        Log.i(TAG, "Silently falling back to HLS transcode")
                        val hlsStream = StreamInfo.HlsTranscode(hlsUrl, streamInfo.playSessionId)
                        mediaPlayer.play(hlsStream, pcmPos)
                        pcmReporter.release()
                        val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId, hlsStream.subtitleStreamIndex)
                        playbackReporter = hlsReporter
                        hlsReporter.reportPlaybackStart(itemId, pcmPos)
                        hlsReporter.startPeriodicReporting(
                            getPosition = { mediaPlayer.getCurrentPosition() },
                            getIsPaused = { !mediaPlayer.isPlayWhenReady() }
                        )
                        mediaPlayer.onSeekCompleted = { hlsReporter.reportProgressNow() }
                        mediaPlayer.onPlaybackEnded = {
                            val hlsEndPos = mediaPlayer.getCurrentPosition()
                            lifecycleScope.launch(Dispatchers.IO) { hlsReporter.reportPlaybackStop(hlsEndPos) }
                            stateMachine.transition(AppEvent.Stop)
                        }
                        mediaPlayer.onError = { hlsError ->
                            Log.e(TAG, "HLS also failed: ${hlsError.message}", hlsError)
                            lifecycleScope.launch(Dispatchers.IO) { hlsReporter.reportPlaybackStop(mediaPlayer.getCurrentPosition()) }
                            stateMachine.transition(AppEvent.Stop)
                        }
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) { pcmReporter.reportPlaybackStop(pcmPos) }
                        stateMachine.transition(AppEvent.Stop)
                    }
                }
            } else if (streamInfo is StreamInfo.DirectPlay) {
                // Silent HLS fallback (NO stop report — server sees continuous playback)
                val sourceId = itemId.toString().replace("-", "")
                val hlsUrl = streamInfo.serverTranscodeUrl
                    ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId)
                Log.i(TAG, "DirectPlay failed, silently falling back to HLS")
                val hlsStream = StreamInfo.HlsTranscode(hlsUrl, streamInfo.playSessionId)
                mediaPlayer.play(hlsStream, posMs)
                reporter.release()
                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId, hlsStream.subtitleStreamIndex)
                playbackReporter = hlsReporter
                hlsReporter.reportPlaybackStart(itemId, posMs)
                hlsReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlayWhenReady() }
                )
                mediaPlayer.onSeekCompleted = { hlsReporter.reportProgressNow() }
                mediaPlayer.onPlaybackEnded = {
                    val hlsEndPos = mediaPlayer.getCurrentPosition()
                    lifecycleScope.launch(Dispatchers.IO) { hlsReporter.reportPlaybackStop(hlsEndPos) }
                    stateMachine.transition(AppEvent.Stop)
                }
                mediaPlayer.onError = { hlsError ->
                    Log.e(TAG, "HLS also failed: ${hlsError.message}", hlsError)
                    lifecycleScope.launch(Dispatchers.IO) { hlsReporter.reportPlaybackStop(mediaPlayer.getCurrentPosition()) }
                    stateMachine.transition(AppEvent.Stop)
                }
            } else {
                // No fallback possible — report stop
                lifecycleScope.launch(Dispatchers.IO) { reporter.reportPlaybackStop(posMs) }
                stateMachine.transition(AppEvent.Stop)
            }
        }
    }

    private fun handlePlaystateCommand(command: PlaystateCommand, seekPositionTicks: Long?) {
        val playerFragment = supportFragmentManager
            .findFragmentById(R.id.container) as? TvPlayerFragment
        val mediaPlayer = playerFragment?.getMediaPlayer() ?: return

        when (command) {
            PlaystateCommand.PAUSE -> {
                mediaPlayer.pause()
                stateMachine.transition(AppEvent.Pause)
            }
            PlaystateCommand.UNPAUSE -> {
                mediaPlayer.resume()
                stateMachine.transition(AppEvent.Play)
            }
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
                stateMachine.transition(AppEvent.Stop)
                return // No need for immediate progress report on stop
            }
            PlaystateCommand.SEEK -> {
                val posMs = (seekPositionTicks ?: 0L) / 10_000L
                mediaPlayer.seekTo(posMs)
                return // Progress reported via onSeekCompleted callback
            }
            PlaystateCommand.PLAY_PAUSE -> {
                if (mediaPlayer.isPlayWhenReady()) {
                    mediaPlayer.pause()
                    stateMachine.transition(AppEvent.Pause)
                } else {
                    mediaPlayer.resume()
                    stateMachine.transition(AppEvent.Play)
                }
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

    fun onServerDiscovered(host: String, @Suppress("UNUSED_PARAMETER") port: Int) {
        discoveredHost = host
        stateMachine.transition(AppEvent.ServerFound(host))
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
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track DPAD_CENTER to distinguish short press (play/pause) from long press (QR overlay)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount == 0) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            stateMachine.currentState is AppState.CONFIGURED
        ) {
            showQrCodeOverlay()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Short press DPAD_CENTER → toggle play/pause
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            event.isTracking && !event.isCanceled
        ) {
            handlePlaystateCommand(PlaystateCommand.PLAY_PAUSE, null)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If a QR overlay is on the back stack, just pop it
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
        // Clean up before super (which destroys fragments)
        webSocketJobs.forEach { it.cancel() }
        webSocketJobs.clear()
        playbackReporter?.release()
        playbackReporter = null
        playlistStreamInfos = null
        playlistItemIds = null
        super.onDestroy()
    }
}
