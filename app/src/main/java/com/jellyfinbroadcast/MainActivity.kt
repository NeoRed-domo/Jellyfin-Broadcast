package com.jellyfinbroadcast

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jellyfinbroadcast.data.*
import com.jellyfinbroadcast.service.RemoteControlService
import com.jellyfinbroadcast.ui.ConfigScreen
import com.jellyfinbroadcast.ui.PlayerScreen
import com.jellyfinbroadcast.ui.QrCodeScreen
import com.jellyfinbroadcast.ui.QrScannerScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var appSettings: AppSettings
    private lateinit var jellyfinManager: JellyfinManager
    private var currentItemId: UUID? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var configServer: ConfigServer? = null
    private var isTvDevice: Boolean = false
    private var screenState: MutableState<Screen> = mutableStateOf(Screen.Loading)
    private var dpadCenterTracking: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appSettings = AppSettings(applicationContext)
        jellyfinManager = JellyfinManager(applicationContext)

        // Detect device type
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTvDevice = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        Log.i(TAG, "Device type: ${if (isTvDevice) "TV" else "Phone/Tablet"}")

        // Set orientation: free on phone/tablet, landscape on TV
        requestedOrientation = if (isTvDevice) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Tell the system we handle insets ourselves
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Immersive fullscreen
        enableImmersiveMode()

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            JellyfinBroadcastApp()
        }
    }

    @Composable
    private fun JellyfinBroadcastApp() {
        var currentScreen by remember { screenState }
        var isConnecting by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var successMessage by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<Player?>(null) }
        var configUrl by remember { mutableStateOf("") }
        var tvConfigUrl by remember { mutableStateOf<String?>(null) }

        // Load saved config on start
        LaunchedEffect(Unit) {
            val config = appSettings.serverConfig.first()
            if (config.isConfigured) {
                val success = jellyfinManager.reconnect(config)
                if (success) {
                    initializePlayer()
                    player = exoPlayer
                    startRemoteService()
                    jellyfinManager.startListening()
                    currentScreen = Screen.Player(isConfigured = true)
                    listenForCommands()
                } else {
                    currentScreen = if (isTvDevice) Screen.QrCode else Screen.Player(isConfigured = false)
                }
            } else {
                currentScreen = if (isTvDevice) Screen.QrCode else Screen.Player(isConfigured = false)
            }
        }

        when (val screen = currentScreen) {
            is Screen.Loading -> {
                PlayerScreen(
                    player = null,
                    isConfigured = true,
                    onConfigureClick = {}
                )
            }
            is Screen.QrCode -> {
                // Start config server for TV
                LaunchedEffect(Unit) {
                    if (configServer == null) {
                        val server = ConfigServer(applicationContext) { host, port, username, password ->
                            // Authenticate with Jellyfin
                            val result = jellyfinManager.authenticate(host, port, username, password)
                            result.fold(
                                onSuccess = { (token, userId) ->
                                    appSettings.saveConfig(
                                        serverHost = host,
                                        serverPort = port,
                                        username = username,
                                        accessToken = token,
                                        userId = userId,
                                        deviceId = jellyfinManager.deviceId
                                    )
                                    // Switch to player mode on success
                                    runOnUiThread {
                                        initializePlayer()
                                        player = exoPlayer
                                        startRemoteService()
                                        jellyfinManager.startListening()
                                        currentScreen = Screen.Player(isConfigured = true)
                                        listenForCommands()
                                    }
                                    Result.success("Configuration réussie !")
                                },
                                onFailure = { e ->
                                    Result.failure(e)
                                }
                            )
                        }
                        configUrl = server.start()
                        configServer = server
                    }
                }

                QrCodeScreen(configUrl = configUrl)
            }
            is Screen.QrScanner -> {
                QrScannerScreen(
                    onQrCodeScanned = { url ->
                        tvConfigUrl = url
                        currentScreen = Screen.RemoteConfig
                    },
                    onBack = {
                        currentScreen = Screen.Player(isConfigured = false)
                    }
                )
            }
            is Screen.RemoteConfig -> {
                ConfigScreen(
                    onConnect = { host, port, username, password ->
                        isConnecting = true
                        errorMessage = null
                        successMessage = null
                        val targetUrl = tvConfigUrl ?: return@ConfigScreen
                        lifecycleScope.launch {
                            try {
                                val result = sendConfigToTv(targetUrl, host, port, username, password)
                                if (result.isSuccess) {
                                    successMessage = "Configuration de la TV réussie !"
                                    isConnecting = false
                                    // After 2 seconds, go back
                                    kotlinx.coroutines.delay(2000)
                                    currentScreen = Screen.Player(isConfigured = false)
                                } else {
                                    errorMessage = result.exceptionOrNull()?.message ?: "Erreur inconnue"
                                    isConnecting = false
                                }
                            } catch (e: Exception) {
                                errorMessage = "Erreur : ${e.message}"
                                isConnecting = false
                            }
                        }
                    },
                    isConnecting = isConnecting,
                    errorMessage = errorMessage,
                    successMessage = successMessage,
                    title = "Configurer la TV",
                    buttonText = "Configurer la TV"
                )
            }
            is Screen.Config -> {
                ConfigScreen(
                    onConnect = { host, port, username, password ->
                        isConnecting = true
                        errorMessage = null
                        lifecycleScope.launch {
                            val result = jellyfinManager.authenticate(host, port, username, password)
                            result.fold(
                                onSuccess = { (token, userId) ->
                                    appSettings.saveConfig(
                                        serverHost = host,
                                        serverPort = port,
                                        username = username,
                                        accessToken = token,
                                        userId = userId,
                                        deviceId = jellyfinManager.deviceId
                                    )
                                    initializePlayer()
                                    player = exoPlayer
                                    startRemoteService()
                                    jellyfinManager.startListening()
                                    currentScreen = Screen.Player(isConfigured = true)
                                    listenForCommands()
                                    isConnecting = false
                                },
                                onFailure = { e ->
                                    errorMessage = "Connexion échouée : ${e.message}"
                                    isConnecting = false
                                }
                            )
                        }
                    },
                    isConnecting = isConnecting,
                    errorMessage = errorMessage
                )
            }
            is Screen.Player -> {
                if (!screen.isConfigured && !isTvDevice) {
                    // Phone/Tablet: Show 2 buttons
                    PhoneMenuScreen(
                        onConfigureClick = { currentScreen = Screen.Config },
                        onScanQrClick = { currentScreen = Screen.QrScanner }
                    )
                } else {
                    PlayerScreen(
                        player = player,
                        isConfigured = screen.isConfigured,
                        onConfigureClick = {
                            currentScreen = if (isTvDevice) Screen.QrCode else Screen.Config
                        },
                        onLongPress = if (!isTvDevice && screen.isConfigured) {
                            {
                                // Only show menu if no media is playing
                                val isPlaying = exoPlayer?.isPlaying == true || currentItemId != null
                                if (!isPlaying) {
                                    currentScreen = Screen.Player(isConfigured = false)
                                }
                            }
                        } else null
                    )
                }
            }
        }
    }

    @Composable
    private fun PhoneMenuScreen(
        onConfigureClick: () -> Unit,
        onScanQrClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF101828)
                )
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Jellyfin Broadcast",
                        color = Color(0xFF00A4DC),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Configure button
                    Button(
                        onClick = onConfigureClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A4DC)
                        )
                    ) {
                        Text(
                            text = "⚙️  Configurer cet appareil",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Scan QR button
                    OutlinedButton(
                        onClick = onScanQrClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF00A4DC)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp, Color(0xFF00A4DC)
                        )
                    ) {
                        Text(
                            text = "📷  Scanner un QR code (TV)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    private suspend fun sendConfigToTv(
        tvUrl: String,
        host: String,
        port: String,
        username: String,
        password: String
    ): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = java.net.URL("$tvUrl/configure")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val body = org.json.JSONObject().apply {
                put("host", host)
                put("port", port)
                put("username", username)
                put("password", password)
            }.toString()

            connection.outputStream.use { os ->
                os.write(body.toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Erreur serveur"
            }

            connection.disconnect()

            if (responseCode == 200) {
                val json = org.json.JSONObject(responseBody)
                if (json.optBoolean("success", false)) {
                    Result.success(json.optString("message", "OK"))
                } else {
                    Result.failure(Exception(json.optString("message", "Erreur de configuration")))
                }
            } else {
                // Try to extract the friendly error message from ConfigServer's JSON response
                val errorMsg = try {
                    val json = org.json.JSONObject(responseBody)
                    json.optString("message", "Erreur de configuration ($responseCode)")
                } catch (_: Exception) {
                    "Erreur de configuration ($responseCode)"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Failed to connect to TV", e)
            Result.failure(Exception("Impossible de contacter la TV, vérifiez que l'application est ouverte sur l'écran QR code"))
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout connecting to TV", e)
            Result.failure(Exception("La TV ne répond pas, vérifiez la connexion réseau"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config to TV", e)
            Result.failure(e)
        }
    }

    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                playWhenReady = true
            }
        }
    }

    private fun listenForCommands() {
        lifecycleScope.launch {
            jellyfinManager.playbackCommands.collect { command ->
                Log.i(TAG, "Processing command: ${command.type}")
                when (command.type) {
                    PlaybackCommandType.PLAY -> {
                        if (command.itemIds.isNotEmpty()) {
                            playMedia(command.itemIds.first(), command.startPositionTicks)
                        }
                    }
                    PlaybackCommandType.PAUSE -> {
                        exoPlayer?.pause()
                        reportProgressNow(isPaused = true)
                    }
                    PlaybackCommandType.UNPAUSE -> {
                        exoPlayer?.play()
                        reportProgressNow(isPaused = false)
                    }
                    PlaybackCommandType.STOP -> {
                        stopPlaybackAndReport()
                    }
                    PlaybackCommandType.SEEK -> {
                        command.startPositionTicks?.let { ticks ->
                            val positionMs = ticks / 10_000
                            exoPlayer?.seekTo(positionMs)
                            reportProgressNow(isPaused = exoPlayer?.isPlaying != true)
                        }
                    }
                    PlaybackCommandType.PLAY_PAUSE -> {
                        exoPlayer?.let { player ->
                            if (player.isPlaying) {
                                player.pause()
                                reportProgressNow(isPaused = true)
                            } else {
                                player.play()
                                reportProgressNow(isPaused = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun playMedia(itemId: UUID, startPositionTicks: Long?) {
        try {
            val streamUrl = jellyfinManager.getStreamUrl(itemId)
                ?: jellyfinManager.getAudioStreamUrl(itemId)

            if (streamUrl != null) {
                Log.i(TAG, "Playing: $streamUrl")

                // Stop previous playback reporting
                stopProgressReporting()

                currentItemId = itemId

                runOnUiThread {
                    exoPlayer?.let { player ->
                        player.stop()
                        player.clearMediaItems()
                        val mediaItem = MediaItem.fromUri(streamUrl)
                        player.setMediaItem(mediaItem)
                        startPositionTicks?.let { ticks ->
                            player.seekTo(ticks / 10_000)
                        }
                        player.prepare()
                        player.play()
                    }
                }

                // Report playback start to server
                jellyfinManager.reportPlaybackStart(itemId)

                // Start periodic progress reporting (every 5 seconds)
                startProgressReporting()

            } else {
                Log.e(TAG, "Failed to get stream URL for item $itemId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing media", e)
        }
    }

    private fun startProgressReporting() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                reportProgressNow(isPaused = exoPlayer?.isPlaying != true)
            }
        }
    }

    private fun stopProgressReporting() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun reportProgressNow(isPaused: Boolean) {
        val itemId = currentItemId ?: return
        val positionMs = exoPlayer?.currentPosition ?: 0L
        val positionTicks = positionMs * 10_000  // Convert ms to Jellyfin ticks
        jellyfinManager.reportPlaybackProgress(itemId, positionTicks, isPaused)
    }

    private fun stopPlaybackAndReport() {
        val itemId = currentItemId
        val positionMs = exoPlayer?.currentPosition ?: 0L
        val positionTicks = positionMs * 10_000

        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        stopProgressReporting()

        if (itemId != null) {
            jellyfinManager.reportPlaybackStopped(itemId, positionTicks)
            currentItemId = null
        }
    }

    private fun startRemoteService() {
        val serviceIntent = Intent(this, RemoteControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Track for long press detection
                if (isTvDevice && event?.repeatCount == 0) {
                    dpadCenterTracking = true
                    event.startTracking()
                    return true
                }
                // Non-TV: just toggle play/pause
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exoPlayer?.play()
                reportProgressNow(isPaused = false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exoPlayer?.pause()
                reportProgressNow(isPaused = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlaybackAndReport()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                exoPlayer?.let { it.seekTo(it.currentPosition + 30_000) }
                reportProgressNow(isPaused = exoPlayer?.isPlaying != true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                exoPlayer?.let { it.seekTo(maxOf(0, it.currentPosition - 10_000)) }
                reportProgressNow(isPaused = exoPlayer?.isPlaying != true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && isTvDevice) {
            dpadCenterTracking = false  // Consumed by long press
            // Only show QR if no media is currently playing
            val isPlaying = exoPlayer?.isPlaying == true || currentItemId != null
            if (!isPlaying) {
                Log.i(TAG, "Long press DPAD_CENTER: toggling QR code")
                val current = screenState.value
                if (current is Screen.QrCode) {
                    screenState.value = Screen.Player(isConfigured = true)
                } else {
                    screenState.value = Screen.QrCode
                }
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && isTvDevice && dpadCenterTracking) {
            dpadCenterTracking = false
            // Short press: toggle play/pause
            togglePlayPause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                reportProgressNow(isPaused = true)
            } else {
                player.play()
                reportProgressNow(isPaused = false)
            }
        }
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            val flags = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags

            // Re-hide if system bars briefly appear
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        window.decorView.systemUiVisibility = flags
                    }, 100)
                }
            }
        }
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        configServer?.stop()
        configServer = null
        exoPlayer?.release()
        exoPlayer = null
        jellyfinManager.destroy()
        stopService(Intent(this, RemoteControlService::class.java))
    }
}

sealed class Screen {
    data object Loading : Screen()
    data object Config : Screen()
    data object QrCode : Screen()
    data object QrScanner : Screen()
    data object RemoteConfig : Screen()
    data class Player(val isConfigured: Boolean) : Screen()
}
