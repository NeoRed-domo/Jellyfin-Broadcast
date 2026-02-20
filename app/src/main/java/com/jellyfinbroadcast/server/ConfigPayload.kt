package com.jellyfinbroadcast.server

import kotlinx.serialization.Serializable

@Serializable
data class ConfigPayload(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    fun isValid(): Boolean =
        host.isNotBlank() && username.isNotBlank() && port > 0
}
