package com.jellyfinbroadcast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class JellyfinServerInfo(val host: String, val port: Int)

class JellyfinDiscovery(private val context: Context) {

    companion object {
        private const val SERVICE_TYPE = "_http._tcp."
        private const val JELLYFIN_SERVICE_NAME = "Jellyfin"
        const val DEFAULT_PORT = 8096
        const val DISCOVERY_TIMEOUT_MS = 5000L

        fun parseServiceInfo(host: String, port: Int): JellyfinServerInfo {
            return JellyfinServerInfo(
                host = host,
                port = if (port > 0) port else DEFAULT_PORT
            )
        }
    }

    suspend fun discover(): JellyfinServerInfo? = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            var listener: NsdManager.DiscoveryListener? = null

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val result = parseServiceInfo(host, info.port)
                    listener?.let { nsdManager.stopServiceDiscovery(it) }
                    if (continuation.isActive) continuation.resume(result)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onStopDiscoveryFailed(type: String, error: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName.contains(JELLYFIN_SERVICE_NAME, ignoreCase = true)) {
                        nsdManager.resolveService(info, resolveListener)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {}
            }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

            continuation.invokeOnCancellation {
                try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
            }
        }
    }
}
