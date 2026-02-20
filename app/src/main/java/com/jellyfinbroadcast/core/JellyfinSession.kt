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

class JellyfinSession(private val context: Context) {

    companion object {
        private const val TAG = "JellyfinSession"
        const val CLIENT_NAME = "Jellyfin Broadcast"
        const val CLIENT_VERSION = "1.0.0"
        const val DEFAULT_PORT = 8096

        /**
         * Builds a full server URL from [host] and [port].
         * Preserves existing http:// or https:// scheme.
         * Uses [DEFAULT_PORT] if [port] is 0.
         * Does not append port if the host already contains one after the scheme.
         */
        fun buildServerUrl(host: String, port: Int): String {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            return when {
                host.startsWith("http://") || host.startsWith("https://") -> {
                    // Check if host already has a port (e.g. "http://server:9000")
                    val afterScheme = host.substringAfter("://")
                    if (afterScheme.contains(':')) host  // already has port
                    else "$host:$effectivePort"
                }
                else -> "http://$host:$effectivePort"
            }
        }
    }

    private var api: ApiClient? = null

    /**
     * Authenticates with the Jellyfin server using [config] credentials.
     * @return `true` on success, `false` on failure (logs reason with Log.w)
     */
    suspend fun authenticate(config: ConfigPayload): Boolean {
        return try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            val jellyfin = Jellyfin(JellyfinOptions.Builder().apply {
                clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
                deviceInfo = DeviceInfo(
                    id = deviceId,
                    name = "Jellyfin Broadcast - ${android.os.Build.MODEL}"
                )
            })

            val serverUrl = buildServerUrl(config.host, config.port)
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
                Log.w(TAG, "Authentication succeeded but server returned null accessToken")
                return false
            }
            client.update(accessToken = token)
            api = client
            true
        } catch (e: ApiClientException) {
            Log.w(TAG, "Jellyfin auth failed (API error ${e.message})")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Jellyfin auth failed (${e.javaClass.simpleName}: ${e.message})")
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
