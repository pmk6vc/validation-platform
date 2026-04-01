package com.platform.testservices

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple HTTP server that simulates an external third-party webhook endpoint.
 *
 * Accepts POST requests to /webhook and returns 200 OK.
 * Tracks request counts for verification in integration tests.
 */

private val logger = LoggerFactory.getLogger("WebhookStub")
private val requestCount = AtomicLong(0)

@Serializable
data class WebhookResponse(val status: String, val received: Long)

@Serializable
data class StubHealthResponse(val status: String, val requestsReceived: Long)

fun main() {
    logger.info("Starting Webhook Stub...")

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }

        routing {
            get("/api/health") {
                call.respond(
                    StubHealthResponse(
                        status = "healthy",
                        requestsReceived = requestCount.get()
                    )
                )
            }

            post("/webhook") {
                val body = call.receiveText()
                val count = requestCount.incrementAndGet()
                logger.info("Webhook received (#$count): ${body.take(200)}")
                call.respond(HttpStatusCode.OK, WebhookResponse("accepted", count))
            }
        }
    }.start(wait = true)
}