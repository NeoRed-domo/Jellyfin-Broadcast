package com.jellyfinbroadcast.core

import android.content.Context
import android.util.Log
import com.jellyfinbroadcast.server.ConfigPayload
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import java.util.UUID

class JellyfinSession(private val context: Context) {

    companion object {
        private const val TAG = "JellyfinSession"
        const val CLIENT_NAME = "Jellyfin Broadcast"
        const val CLIENT_VERSION = "1.0.0"
        const val DEFAULT_PORT = 8096

        fun buildServerUrl(host: String, port: Int): String {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val trimmed = host.trimEnd('/')
            return try {
                val uri = java.net.URI(
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
                    else "http://$trimmed"
                )
                val scheme = uri.scheme ?: "http"
                val uriHost = uri.host ?: trimmed
                val uriPort = if (uri.port > 0) uri.port else effectivePort
                val path = uri.path?.trimEnd('/') ?: ""
                java.net.URI(scheme, null, uriHost, uriPort, path.ifEmpty { null }, null, null).toString()
            } catch (_: Exception) {
                "http://$trimmed:$effectivePort"
            }
        }
    }

    private var api: ApiClient? = null
    private var userId: UUID? = null
    private val sessionStore = SessionStore(context)

    // Pre-create Jellyfin instance once (avoids re-initialization on each call)
    private val jellyfin: Jellyfin by lazy {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        Jellyfin(JellyfinOptions.Builder().apply {
            this.context = this@JellyfinSession.context
            clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
            deviceInfo = DeviceInfo(
                id = deviceId,
                name = "Jellyfin Broadcast - ${android.os.Build.MODEL}"
            )
        })
    }

    suspend fun authenticate(config: ConfigPayload): String? {
        return try {
            val serverUrl = buildServerUrl(config.host, config.port)
            Log.d(TAG, "Authenticating to $serverUrl as '${config.username}'")
            val client = jellyfin.createApi(baseUrl = serverUrl)
            val authApi = UserApi(client)
            val response = authApi.authenticateUserByName(
                AuthenticateUserByName(
                    username = config.username,
                    pw = config.password
                )
            )
            val token = response.content.accessToken
            if (token == null) {
                val msg = "Serveur connecté mais token d'accès absent"
                Log.w(TAG, msg)
                return msg
            }
            client.update(accessToken = token)
            api = client
            val uid = response.content.user?.id
            userId = uid
            sessionStore.save(serverUrl, token, config.host, config.port, uid?.toString())
            Log.i(TAG, "Authenticated successfully, session saved (userId=$uid)")
            null
        } catch (e: ApiClientException) {
            val msg = "Erreur API Jellyfin: ${e.message}"
            Log.w(TAG, msg)
            msg
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Jellyfin auth failed: $msg")
            msg
        }
    }

    suspend fun restoreSession(): Boolean {
        if (!sessionStore.hasSavedSession()) return false
        val serverUrl = sessionStore.getServerUrl()!!
        val token = sessionStore.getAccessToken()!!
        return try {
            val client = jellyfin.createApi(baseUrl = serverUrl, accessToken = token)
            val userApi = UserApi(client)
            val currentUser = userApi.getCurrentUser()
            api = client
            userId = currentUser.content.id
            Log.i(TAG, "Session restored from saved state (userId=$userId)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Saved session invalid: ${e.message}")
            sessionStore.clear()
            false
        }
    }

    fun getApi(): ApiClient? = api

    fun getUserId(): UUID? = userId

    fun getServerUrl(): String? = sessionStore.getServerUrl()

    fun disconnect() {
        api = null
        sessionStore.clear()
    }
}
