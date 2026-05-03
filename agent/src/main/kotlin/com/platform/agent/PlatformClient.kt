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
 * Outcome of one [PlatformClient.registerService] call.
 *
 * Distinguishes "platform / caller broken, retry next tick" from "platform
 * rejected THIS service's payload, stop trying just for this service" —
 * without that distinction the discovery loop either hammers the platform
 * with the same bad payload forever or quietly poisons services on what's
 * really an environmental issue (auth, rate limit) so they never re-register
 * after the environment recovers.
 */
sealed class RegistrationOutcome {
    /** 200 / 201 / 409 — service is registered (or was already registered). */
    data object Success : RegistrationOutcome()

    /**
     * 400 / 422 — the platform rejected this specific service's payload
     * (e.g. a name that fails the platform's validation rules). Retrying
     * with the same `{namespace, name}` won't change the answer; stop
     * trying for this service. Other services should keep being attempted.
     */
    data object PermanentRejection : RegistrationOutcome()

    /**
     * 401 / 403 / 404 / 429 / 5xx / network failure / timeout — every
     * service is going to see the same outcome (auth, rate limit, platform
     * down). Per-service exclusion is the wrong remediation; retry on the
     * next discovery tick when the environment may have recovered.
     */
    data object TransientFailure : RegistrationOutcome()
}

/**
 * Talks to the platform's `/api/services` registration endpoint.
 *
 * The platform stamps `organizationId` and `cluster` from the JWT, so the
 * request body is just `{namespace, name, provider}`. A 409 means the service
 * is already registered (the platform's unique constraint is
 * org+cluster+namespace+name) — that's idempotent success from our point of
 * view.
 *
 * Unlike [CollectorClient] this does NOT retry transient failures inline.
 * Discovery runs on a periodic loop (default 60s), so a [TransientFailure] is
 * already going to be re-attempted on the next tick. Wedging the loop on a
 * suspended retry would block ALL services on the back of one bad service.
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
    ): RegistrationOutcome =
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
                -> RegistrationOutcome.Success

                // Only payload-validation failures are per-service permanent.
                // Other 4xx (401 auth, 403 forbidden, 404 wrong endpoint,
                // 429 rate limit) are caller-/environment-level and would
                // hit every service equally — those go to TransientFailure
                // so a recovered environment re-registers everything.
                HttpStatusCode.BadRequest,
                HttpStatusCode.UnprocessableEntity,
                -> {
                    logger.warn(
                        "Platform rejected service registration {}/{}: {} {} (permanent, dropping)",
                        namespace,
                        name,
                        response.status,
                        response.bodyAsText(),
                    )
                    RegistrationOutcome.PermanentRejection
                }

                else -> {
                    logger.warn(
                        "Platform returned {} for service {}/{}, will retry next tick: {}",
                        response.status,
                        namespace,
                        name,
                        response.bodyAsText(),
                    )
                    RegistrationOutcome.TransientFailure
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to register service {}/{} with platform (will retry next tick): {}",
                namespace,
                name,
                e.message,
            )
            RegistrationOutcome.TransientFailure
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
