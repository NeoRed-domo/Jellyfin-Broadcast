package com.jellyfinbroadcast.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.jellyfinbroadcast.server.ConfigPayload
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName

class JellyfinSession(private val context: Context) {

    companion object {
        const val CLIENT_NAME = "Jellyfin Broadcast"
        const val CLIENT_VERSION = "1.0.0"
        const val DEFAULT_PORT = 8096

        /**
         * Builds a full server URL from [host] and [port].
         * If [host] already starts with http:// or https://, the scheme is preserved.
         * If [port] is 0, [DEFAULT_PORT] is used.
         */
        fun buildServerUrl(host: String, port: Int): String {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            return when {
                host.startsWith("http://") || host.startsWith("https://") ->
                    "$host:$effectivePort"
                else -> "http://$host:$effectivePort"
            }
        }
    }

    private var api: ApiClient? = null

    /**
     * Authenticates with the Jellyfin server using [config] credentials.
     * Returns `true` on success, `false` on authentication failure or network error.
     */
    suspend fun authenticate(config: ConfigPayload): Boolean {
        return try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            val jellyfinOptions = JellyfinOptions.Builder().apply {
                clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
                deviceInfo = DeviceInfo(
                    id = deviceId,
                    name = "Jellyfin Broadcast - ${android.os.Build.MODEL}"
                )
            }

            val jellyfin = Jellyfin(jellyfinOptions)
            val serverUrl = buildServerUrl(config.host, config.port)
            val client = jellyfin.createApi(baseUrl = serverUrl)
            val authApi = UserApi(client)
            val response = authApi.authenticateUserByName(
                AuthenticateUserByName(
                    username = config.username,
                    pw = config.password
                )
            )
            api = client.apply {
                accessToken = response.content.accessToken
            }
            true
        } catch (e: ApiClientException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the authenticated [ApiClient], or null if not authenticated. */
    fun getApi(): ApiClient? = api

    /** Clears the current session. */
    fun disconnect() {
        api = null
    }
}
