package com.jellyfinbroadcast.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String {
        return try {
            val addresses = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { !it.isLoopback }
                ?.flatMap { iface ->
                    iface.inetAddresses.asSequence()
                        .filter { !it.isLoopbackAddress && it is Inet4Address }
                        .map { iface to it.hostAddress }
                }
                ?.toList()
                ?: return "0.0.0.0"

            val wifiAddress = addresses.firstOrNull { it.first.name.startsWith("wlan") }?.second
            wifiAddress ?: addresses.firstOrNull()?.second ?: "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }
}
