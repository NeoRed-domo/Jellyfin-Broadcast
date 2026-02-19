package com.jellyfinbroadcast.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket

private const val TAG = "ConfigServer"

class ConfigServer(
    private val context: Context,
    private val onConfigReceived: suspend (host: String, port: String, username: String, password: String) -> Result<String>
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var serverPort: Int = 0
        private set

    fun start(): String {
        val socket = ServerSocket(0) // Random available port
        serverSocket = socket
        serverPort = socket.localPort

        val localIp = getLocalIpAddress()
        val url = "http://$localIp:$serverPort"

        Log.i(TAG, "Config server started at $url")

        serverJob = scope.launch {
            while (isActive) {
                try {
                    val client = socket.accept()
                    launch {
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Server error", e)
                }
            }
        }

        return url
    }

    private suspend fun handleClient(clientSocket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)

            // Read HTTP request line
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Request: $requestLine")

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].lowercase()] = parts[1]
                    if (parts[0].equals("Content-Length", ignoreCase = true)) {
                        contentLength = parts[1].trim().toIntOrNull() ?: 0
                    }
                }
                line = reader.readLine()
            }

            when {
                requestLine.startsWith("GET /") -> {
                    sendResponse(writer, 200, """{"status":"ready","app":"Jellyfin Broadcast"}""")
                }
                requestLine.startsWith("POST /configure") -> {
                    // Read body
                    val body = if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        reader.read(chars, 0, contentLength)
                        String(chars)
                    } else ""

                    Log.d(TAG, "Config body: $body")

                    try {
                        val json = JSONObject(body)
                        val host = json.getString("host")
                        val port = json.getString("port")
                        val username = json.getString("username")
                        val password = json.getString("password")

                        val result = onConfigReceived(host, port, username, password)
                        if (result.isSuccess) {
                            sendResponse(writer, 200, """{"success":true,"message":"Configuration réussie !"}""")
                        } else {
                            sendResponse(writer, 400, """{"success":false,"message":"${result.exceptionOrNull()?.message ?: "Erreur inconnue"}"}""")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Config parse error", e)
                        sendResponse(writer, 400, """{"success":false,"message":"${e.message}"}""")
                    }
                }
                requestLine.startsWith("OPTIONS") -> {
                    sendResponse(writer, 200, "")
                }
                else -> {
                    sendResponse(writer, 404, """{"error":"Not found"}""")
                }
            }

            clientSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error", e)
        }
    }

    private fun sendResponse(writer: PrintWriter, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
        writer.print("Content-Length: ${body.toByteArray().size}\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    fun stop() {
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Config server stopped")
    }

    fun getLocalIpAddress(): String {
        try {
            // Try WifiManager first (most reliable on Android)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }

            // Fallback: enumerate network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains('.') == true) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return "0.0.0.0"
    }
}
