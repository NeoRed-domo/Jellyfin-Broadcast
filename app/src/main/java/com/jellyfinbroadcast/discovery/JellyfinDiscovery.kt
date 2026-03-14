package com.jellyfinbroadcast.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

data class JellyfinServerInfo(val host: String, val port: Int)

/**
 * Discovers Jellyfin servers using the official UDP broadcast protocol.
 * Sends "Who is JellyfinServer?" to broadcast on port 7359.
 * The server responds with JSON containing its address.
 */
class JellyfinDiscovery(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    companion object {
        private const val TAG = "JellyfinDiscovery"
        const val DISCOVERY_PORT = 7359
        const val DISCOVERY_MESSAGE = "Who is JellyfinServer?"
        const val DISCOVERY_TIMEOUT_MS = 5_000L
        const val DEFAULT_PORT = 8096

        fun parseServerResponse(json: String): JellyfinServerInfo? {
            return try {
                val obj = Json.parseToJsonElement(json).jsonObject
                val address = obj["Address"]?.jsonPrimitive?.content ?: return null
                val uri = URI(address)
                val host = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else DEFAULT_PORT
                JellyfinServerInfo(host, port)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse discovery response: ${e.message}")
                null
            }
        }
    }

    suspend fun discover(): JellyfinServerInfo? = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = (DISCOVERY_TIMEOUT_MS - 500).toInt()
                }
                socket.use {
                    // Send broadcast
                    val message = DISCOVERY_MESSAGE.toByteArray()
                    val broadcastAddress = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(message, message.size, broadcastAddress, DISCOVERY_PORT)
                    socket.send(packet)
                    Log.d(TAG, "Sent UDP discovery broadcast")

                    // Wait for response
                    val buffer = ByteArray(4096)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)

                    val response = String(responsePacket.data, 0, responsePacket.length)
                    Log.d(TAG, "Discovery response: $response")
                    parseServerResponse(response)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Discovery failed: ${e.message}")
                null
            }
        }
    }
}
