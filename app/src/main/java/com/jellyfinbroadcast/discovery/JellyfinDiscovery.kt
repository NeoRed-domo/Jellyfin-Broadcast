package com.jellyfinbroadcast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Holds the resolved host and port of a discovered Jellyfin server. */
data class JellyfinServerInfo(val host: String, val port: Int)

/** Discovers a Jellyfin server on the local network via mDNS (NSD). */
class JellyfinDiscovery(private val context: Context) {

    companion object {
        internal const val SERVICE_TYPE = "_http._tcp."
        internal const val JELLYFIN_SERVICE_NAME = "Jellyfin"

        /** Default Jellyfin HTTP port used when NSD reports port 0. */
        const val DEFAULT_PORT = 8096

        /** How long to wait for discovery before giving up, in milliseconds. */
        const val DISCOVERY_TIMEOUT_MS = 5000L

        /**
         * Builds a [JellyfinServerInfo] from raw NSD values.
         * Normalises [port] = 0 to [DEFAULT_PORT].
         */
        fun parseServiceInfo(host: String, port: Int): JellyfinServerInfo =
            JellyfinServerInfo(
                host = host,
                port = if (port > 0) port else DEFAULT_PORT
            )
    }

    /**
     * Discovers the first Jellyfin server on the local network.
     * Returns `null` if none is found within [DISCOVERY_TIMEOUT_MS] milliseconds.
     */
    suspend fun discover(): JellyfinServerInfo? = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val resolving = AtomicBoolean(false)
            var listener: NsdManager.DiscoveryListener? = null

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: run {
                        if (continuation.isActive) continuation.resume(null)
                        return
                    }
                    val result = parseServiceInfo(host, info.port)
                    listener?.let {
                        try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
                    }
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
                    // Guard: only resolve the first matching service found
                    if (info.serviceName.contains(JELLYFIN_SERVICE_NAME, ignoreCase = true) &&
                        resolving.compareAndSet(false, true)) {
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
