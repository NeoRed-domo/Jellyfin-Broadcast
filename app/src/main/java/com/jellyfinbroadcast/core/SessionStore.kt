package com.jellyfinbroadcast.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "jellyfin_session"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER_ID = "user_id"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(serverUrl: String, accessToken: String, host: String, port: Int, userId: String? = null) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply {
                if (userId != null) putString(KEY_USER_ID, userId)
            }
            .apply()
    }

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)
    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun getHost(): String? = prefs.getString(KEY_HOST, null)
    fun getPort(): Int = prefs.getInt(KEY_PORT, 8096)
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun hasSavedSession(): Boolean =
        getServerUrl() != null && getAccessToken() != null

    fun clear() {
        prefs.edit().clear().apply()
    }
}
