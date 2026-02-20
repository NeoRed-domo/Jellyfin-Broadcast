package com.jellyfinbroadcast.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr is Inet4Address
                }?.hostAddress ?: "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }
}
