package com.platform.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Talks to the platform's `/api/services` registration endpoint.
 *
 * The platform stamps `organizationId` and `cluster` from the JWT, so the
 * request body is just `{namespace, name, provider}`. A 409 means the service
 * is already registered (the platform's unique constraint is
 * org+cluster+namespace+name) — that's idempotent success from our point of
 * view.
 *
 * Unlike [CollectorClient] this does NOT retry transient failures. Discovery
 * runs on a periodic loop (default 60s), and a transient failure simply means
 * the service shows up registered on the next tick. Wedging the loop on a
 * suspended retry would also block subsequent discoveries.
 */
class PlatformClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val logger = LoggerFactory.getLogger(PlatformClient::class.java)

    suspend fun registerService(
        namespace: String,
        name: String,
    ): Boolean =
        try {
            val response =
                httpClient.post("$baseUrl/api/services") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(apiKey)
                    setBody(RegisterServiceRequest(namespace = namespace, name = name))
                }
            when (response.status) {
                HttpStatusCode.OK,
                HttpStatusCode.Created,
                HttpStatusCode.Conflict,
                -> true
                else -> {
                    logger.warn(
                        "Platform rejected service registration {}/{}: {} {}",
                        namespace,
                        name,
                        response.status,
                        response.bodyAsText(),
                    )
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to register service {}/{} with platform: {}",
                namespace,
                name,
                e.message,
            )
            false
        }
}

/**
 * Wire format for `POST /api/services`. Mirrors `CreateServiceRequest` in
 * `platform/src/main/kotlin/com/platform/api/Requests.kt`. `organizationId`
 * and `cluster` are NOT body fields — both come from the JWT principal.
 */
@Serializable
private data class RegisterServiceRequest(
    val namespace: String,
    val name: String,
    val provider: String = "KUBERNETES",
)
