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
import kotlinx.serialization.Serializable
import java.net.ServerSocket

@Serializable
data class DiscoveredServer(val host: String, val port: Int)

class ConfigServer(
    private val onConfigReceived: suspend (ConfigPayload) -> String?
) {
    private var server: ApplicationEngine? = null
    var port: Int = 8765
        private set

    var discoveredHost: String? = null
    var discoveredPort: Int = 8096

    companion object {
        fun findAvailablePort(startPort: Int = 8765, maxPort: Int = 8775): Int {
            for (p in startPort..maxPort) {
                try {
                    ServerSocket(p).use { return p }
                } catch (_: Exception) { continue }
            }
            throw IllegalStateException("No available port found in range $startPort..$maxPort")
        }
    }

    fun start() {
        port = findAvailablePort()
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { json() }
            routing {
                get("/server-info") {
                    val host = discoveredHost
                    if (host != null) {
                        call.respond(DiscoveredServer(host, discoveredPort))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                post("/configure") {
                    val payload = runCatching { call.receive<ConfigPayload>() }.getOrNull()
                    if (payload == null || !payload.isValid()) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid config")
                        return@post
                    }
                    val error = onConfigReceived(payload)
                    if (error == null) {
                        call.respondText("Configuration applied", status = HttpStatusCode.OK)
                    } else {
                        call.respondText(error, status = HttpStatusCode.UnprocessableEntity)
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
