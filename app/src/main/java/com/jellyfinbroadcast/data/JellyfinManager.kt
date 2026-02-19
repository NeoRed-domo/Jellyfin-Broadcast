package com.jellyfinbroadcast.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.InboundWebSocketMessage
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import java.util.UUID

private const val TAG = "JellyfinManager"

data class PlaybackCommand(
    val type: PlaybackCommandType,
    val itemIds: List<UUID> = emptyList(),
    val startPositionTicks: Long? = null
)

enum class PlaybackCommandType {
    PLAY, PAUSE, UNPAUSE, STOP, SEEK, PLAY_PAUSE
}

class JellyfinManager(private val context: Context) {

    private val _playbackCommands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 10)
    val playbackCommands: SharedFlow<PlaybackCommand> = _playbackCommands.asSharedFlow()

    private var jellyfin: Jellyfin? = null
    private var apiClient: ApiClient? = null
    private var sessionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("jellyfin_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private val deviceName: String by lazy {
        val model = android.os.Build.MODEL ?: "Android"
        "Jellyfin Broadcast - $model"
    }

    fun getApiClient(): ApiClient? = apiClient

    suspend fun authenticate(
        serverHost: String,
        serverPort: String,
        username: String,
        password: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = buildBaseUrl(serverHost, serverPort)

            // Direct HTTP authentication (bypasses SDK auth header issues)
            val authUrl = "$baseUrl/Users/AuthenticateByName"
            val authHeader = "MediaBrowser Client=\"Jellyfin Broadcast\", Device=\"$deviceName\", DeviceId=\"$deviceId\", Version=\"1.1.0\""
            val jsonBody = """{"Username":"$username","Pw":"$password"}"""

            val url = java.net.URL(authUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Emby-Authorization", authHeader)
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorMsg = when (responseCode) {
                    400, 401 -> "Identifiants incorrects, vérifiez le nom d'utilisateur et le mot de passe"
                    403 -> "Accès refusé par le serveur"
                    404 -> "Service Jellyfin introuvable, vérifiez l'adresse et le port"
                    in 500..599 -> "Erreur interne du serveur Jellyfin ($responseCode)"
                    else -> "Erreur serveur ($responseCode)"
                }
                throw Exception(errorMsg)
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val jsonResponse = org.json.JSONObject(responseBody)
            val accessToken = jsonResponse.getString("AccessToken")
            val userId = jsonResponse.getJSONObject("User").getString("Id")

            Log.i(TAG, "Authenticated successfully as $username (userId=$userId)")

            // Now create the SDK API client with the obtained token
            val jf = createJellyfin {
                clientInfo = ClientInfo(
                    name = "Jellyfin Broadcast",
                    version = "1.1.0"
                )
                this.context = this@JellyfinManager.context
            }
            jellyfin = jf

            val api = jf.createApi(
                baseUrl = baseUrl,
                accessToken = accessToken,
                deviceInfo = DeviceInfo(
                    id = deviceId,
                    name = deviceName
                )
            )
            apiClient = api

            reportCapabilities()

            Result.success(Pair(accessToken, userId))
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Connection refused", e)
            Result.failure(Exception("Le serveur ne répond pas, vérifiez l'adresse du serveur"))
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Connection timeout", e)
            Result.failure(Exception("Le serveur ne répond pas, vérifiez l'adresse du serveur"))
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Unknown host", e)
            Result.failure(Exception("Adresse du serveur introuvable, vérifiez l'adresse"))
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            Result.failure(e)
        }
    }

    suspend fun reconnect(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!config.isConfigured) return@withContext false

            val jf = createJellyfin {
                clientInfo = ClientInfo(
                    name = "Jellyfin Broadcast",
                    version = "1.1.0"
                )
                this.context = this@JellyfinManager.context
            }
            jellyfin = jf

            val api = jf.createApi(
                baseUrl = config.baseUrl,
                accessToken = config.accessToken,
                deviceInfo = DeviceInfo(
                    id = config.deviceId.ifBlank { deviceId },
                    name = deviceName
                )
            )
            apiClient = api

            val user by api.userApi.getCurrentUser()
            Log.i(TAG, "Reconnected as ${user.name}")

            reportCapabilities()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed", e)
            false
        }
    }

    private suspend fun reportCapabilities() {
        try {
            val api = apiClient ?: return
            api.sessionApi.postCapabilities(
                playableMediaTypes = listOf(
                    org.jellyfin.sdk.model.api.MediaType.VIDEO,
                    org.jellyfin.sdk.model.api.MediaType.AUDIO
                ),
                supportedCommands = listOf(
                    GeneralCommandType.PLAY,
                    GeneralCommandType.PLAY_STATE,
                    GeneralCommandType.MUTE,
                    GeneralCommandType.UNMUTE,
                    GeneralCommandType.SET_VOLUME,
                    GeneralCommandType.TOGGLE_MUTE
                ),
                supportsMediaControl = true,
                supportsPersistentIdentifier = true
            )
            Log.i(TAG, "Capabilities reported to server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report capabilities", e)
        }
    }

    fun startListening() {
        val api = apiClient ?: run {
            Log.w(TAG, "Cannot start listening: no API client")
            return
        }

        sessionJob?.cancel()
        sessionJob = scope.launch {
            try {
                api.webSocket.subscribeAll().collect { message ->
                    when (message) {
                        is PlayMessage -> handlePlayMessage(message)
                        is PlaystateMessage -> handlePlaystateMessage(message)
                        is GeneralCommandMessage -> handleGeneralCommand(message)
                        else -> { /* Ignore other message types */ }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket error", e)
                delay(5000)
                startListening()
            }
        }
    }

    private suspend fun handlePlayMessage(message: PlayMessage) {
        val data = message.data
        Log.i(TAG, "Received Play command: ${data?.playCommand}, items: ${data?.itemIds}")
        val itemIds = data?.itemIds ?: return
        val playCommand = data.playCommand ?: PlayCommand.PLAY_NOW

        _playbackCommands.emit(
            PlaybackCommand(
                type = PlaybackCommandType.PLAY,
                itemIds = itemIds,
                startPositionTicks = data.startPositionTicks
            )
        )
    }

    private suspend fun handlePlaystateMessage(message: PlaystateMessage) {
        val data = message.data
        Log.i(TAG, "Received PlayState command: ${data?.command}")
        when (data?.command) {
            PlaystateCommand.PAUSE -> {
                _playbackCommands.emit(PlaybackCommand(type = PlaybackCommandType.PAUSE))
            }
            PlaystateCommand.UNPAUSE -> {
                _playbackCommands.emit(PlaybackCommand(type = PlaybackCommandType.UNPAUSE))
            }
            PlaystateCommand.STOP -> {
                _playbackCommands.emit(PlaybackCommand(type = PlaybackCommandType.STOP))
            }
            PlaystateCommand.SEEK -> {
                _playbackCommands.emit(
                    PlaybackCommand(
                        type = PlaybackCommandType.SEEK,
                        startPositionTicks = data.seekPositionTicks
                    )
                )
            }
            PlaystateCommand.PLAY_PAUSE -> {
                _playbackCommands.emit(PlaybackCommand(type = PlaybackCommandType.PLAY_PAUSE))
            }
            PlaystateCommand.NEXT_TRACK -> {
                Log.i(TAG, "Next track (not implemented)")
            }
            PlaystateCommand.PREVIOUS_TRACK -> {
                Log.i(TAG, "Previous track (not implemented)")
            }
            PlaystateCommand.REWIND -> {
                Log.i(TAG, "Rewind (not implemented)")
            }
            PlaystateCommand.FAST_FORWARD -> {
                Log.i(TAG, "Fast forward (not implemented)")
            }
            else -> Log.w(TAG, "Unhandled playstate command: ${data?.command}")
        }
    }

    private suspend fun handleGeneralCommand(message: GeneralCommandMessage) {
        val command = message.data
        Log.i(TAG, "Received GeneralCommand: ${command?.name}")
        when (command?.name) {
            GeneralCommandType.PLAY_STATE,
            GeneralCommandType.TOGGLE_MUTE -> {
                // Toggle mute if needed in the future
                Log.d(TAG, "Toggle mute command received")
            }
            else -> {
                // Check string name for PlayPause and other commands
                val commandStr = command?.name?.toString() ?: ""
                Log.i(TAG, "GeneralCommand string: $commandStr")
                if (commandStr.contains("PlayPause", ignoreCase = true)) {
                    _playbackCommands.emit(PlaybackCommand(type = PlaybackCommandType.PLAY_PAUSE))
                } else if (commandStr.contains("Mute", ignoreCase = true)) {
                    Log.d(TAG, "Mute/Unmute command")
                }
            }
        }
    }

    fun stopListening() {
        sessionJob?.cancel()
        sessionJob = null
    }

    fun getStreamUrl(itemId: UUID): String? {
        val api = apiClient ?: return null
        val baseUrl = api.baseUrl ?: return null
        val token = api.accessToken ?: return null
        return "${baseUrl}/Videos/$itemId/stream?static=true&api_key=$token"
    }

    fun getAudioStreamUrl(itemId: UUID): String? {
        val api = apiClient ?: return null
        val baseUrl = api.baseUrl ?: return null
        val token = api.accessToken ?: return null
        return "${baseUrl}/Audio/$itemId/stream?static=true&api_key=$token"
    }

    fun destroy() {
        stopListening()
        scope.cancel()
    }

    // --- Playback Reporting ---

    fun reportPlaybackStart(itemId: UUID) {
        scope.launch {
            postToServer("/Sessions/Playing", """
                {
                    "ItemId": "$itemId",
                    "CanSeek": true,
                    "IsPaused": false,
                    "IsMuted": false,
                    "PlayMethod": "DirectStream",
                    "RepeatMode": "RepeatNone"
                }
            """.trimIndent())
        }
    }

    fun reportPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean) {
        scope.launch {
            postToServer("/Sessions/Playing/Progress", """
                {
                    "ItemId": "$itemId",
                    "PositionTicks": $positionTicks,
                    "CanSeek": true,
                    "IsPaused": $isPaused,
                    "IsMuted": false,
                    "PlayMethod": "DirectStream",
                    "RepeatMode": "RepeatNone"
                }
            """.trimIndent())
        }
    }

    fun reportPlaybackStopped(itemId: UUID, positionTicks: Long) {
        scope.launch {
            postToServer("/Sessions/Playing/Stopped", """
                {
                    "ItemId": "$itemId",
                    "PositionTicks": $positionTicks
                }
            """.trimIndent())
        }
    }

    private suspend fun postToServer(path: String, jsonBody: String) = withContext(Dispatchers.IO) {
        try {
            val api = apiClient ?: return@withContext
            val baseUrl = api.baseUrl ?: return@withContext
            val token = api.accessToken ?: return@withContext

            val url = java.net.URL("$baseUrl$path")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Emby-Authorization",
                "MediaBrowser Client=\"Jellyfin Broadcast\", Device=\"$deviceName\", DeviceId=\"$deviceId\", Version=\"1.1.0\", Token=\"$token\"")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                Log.d(TAG, "Report $path: OK")
            } else {
                Log.w(TAG, "Report $path: HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report $path", e)
        }
    }

    private fun buildBaseUrl(host: String, port: String): String {
        val trimmedHost = host.trim()
        return when {
            trimmedHost.startsWith("http://") || trimmedHost.startsWith("https://") -> {
                if (port.isNotBlank() && port != "80" && port != "443") "$trimmedHost:$port"
                else trimmedHost
            }
            else -> "http://$trimmedHost:$port"
        }
    }
}
