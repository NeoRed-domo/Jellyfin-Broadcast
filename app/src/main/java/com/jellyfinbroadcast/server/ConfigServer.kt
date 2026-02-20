package com.jellyfinbroadcast.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.ServerSocket

class ConfigServer(
    private val onConfigReceived: suspend (ConfigPayload) -> Boolean
) {
    private var server: ApplicationEngine? = null
    var port: Int = 8765
        private set

    companion object {
        fun findAvailablePort(startPort: Int = 8765, maxPort: Int = 8775): Int {
            for (p in startPort..maxPort) {
                try {
                    ServerSocket(p).use { return p }
                } catch (_: Exception) { continue }
            }
            return startPort
        }
    }

    fun start() {
        port = findAvailablePort()
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { json() }
            routing {
                post("/configure") {
                    val payload = runCatching { call.receive<ConfigPayload>() }.getOrNull()
                    if (payload == null || !payload.isValid()) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid config")
                        return@post
                    }
                    val success = onConfigReceived(payload)
                    if (success) {
                        call.respond(HttpStatusCode.OK, "Configuration applied")
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, "Invalid Jellyfin credentials")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }
}
