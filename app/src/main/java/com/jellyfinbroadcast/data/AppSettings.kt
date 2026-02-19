package com.jellyfinbroadcast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ServerConfig(
    val serverHost: String = "",
    val serverPort: String = "8096",
    val username: String = "",
    val accessToken: String = "",
    val userId: String = "",
    val deviceId: String = ""
) {
    val isConfigured: Boolean
        get() = serverHost.isNotBlank() && accessToken.isNotBlank()

    val baseUrl: String
        get() {
            val h = serverHost.trim()
            return when {
                h.startsWith("http://") || h.startsWith("https://") ->
                    if (serverPort.isNotBlank() && serverPort != "80" && serverPort != "443") "$h:$serverPort" else h
                else -> "http://$h:$serverPort"
            }
        }
}

class AppSettings(private val context: Context) {

    companion object {
        private val KEY_SERVER_HOST = stringPreferencesKey("server_host")
        private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            serverHost = prefs[KEY_SERVER_HOST] ?: "",
            serverPort = prefs[KEY_SERVER_PORT] ?: "8096",
            username = prefs[KEY_USERNAME] ?: "",
            accessToken = prefs[KEY_ACCESS_TOKEN] ?: "",
            userId = prefs[KEY_USER_ID] ?: "",
            deviceId = prefs[KEY_DEVICE_ID] ?: ""
        )
    }

    suspend fun saveConfig(
        serverHost: String,
        serverPort: String,
        username: String,
        accessToken: String,
        userId: String,
        deviceId: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_HOST] = serverHost
            prefs[KEY_SERVER_PORT] = serverPort
            prefs[KEY_USERNAME] = username
            prefs[KEY_ACCESS_TOKEN] = accessToken
            prefs[KEY_USER_ID] = userId
            prefs[KEY_DEVICE_ID] = deviceId
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
    }
}
