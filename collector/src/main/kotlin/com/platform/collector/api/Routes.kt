package com.platform.collector.api

import com.platform.collector.database.CapturedInputRepository
import com.platform.collector.models.BatchCreateCapturedInputRequest
import com.platform.collector.models.BatchCreateCapturedInputResponse
import com.platform.collector.models.CapturedInput
import com.platform.collector.models.CapturedInputId
import com.platform.collector.models.DeleteResponse
import com.platform.collector.models.InputType
import com.platform.collector.models.ServiceId
import com.platform.shared.auth.AgentIdentity
import com.platform.shared.auth.JWT_AUTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

const val MAX_BATCH_SIZE = 1000

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respondText("OK")
        }

        authenticate(JWT_AUTH) {
            route("/api") {
                route("/captured-inputs") {
                    post {
                        // organizationId is always taken from the JWT — the request body has no
                        // organizationId field, so there's nothing to reject or auto-fill over.
                        val principal = call.principal<AgentIdentity>()!!

                        val request = call.receive<BatchCreateCapturedInputRequest>()
                        if (request.items.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Batch must not be empty")
                        }
                        if (request.items.size > MAX_BATCH_SIZE) {
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                "Batch size ${request.items.size} exceeds maximum of $MAX_BATCH_SIZE",
                            )
                        }
                        val inputs =
                            request.items.map { item ->
                                CapturedInput(
                                    id = CapturedInputId.generate(),
                                    serviceId = item.serviceId,
                                    // Stamp organizationId from JWT — not from caller-supplied data.
                                    organizationId = principal.organizationId,
                                    inputType = item.inputType,
                                    method = item.method,
                                    url = item.url,
                                    requestHeaders = item.requestHeaders,
                                    requestBody = item.requestBody,
                                    responseStatus = item.responseStatus,
                                    responseHeaders = item.responseHeaders,
                                    responseBody = item.responseBody,
                                    latencyMs = item.latencyMs,
                                    sourceIp = item.sourceIp,
                                    destinationIp = item.destinationIp,
                                    capturedAt = item.capturedAt,
                                )
                            }
                        CapturedInputRepository.createBatch(inputs)
                        call.respond(HttpStatusCode.Created, BatchCreateCapturedInputResponse(created = inputs.size))
                    }

                    get {
                        val principal = call.principal<AgentIdentity>()!!

                        val limit =
                            call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: CapturedInputRepository.DEFAULT_PAGE_SIZE
                        val cursor = call.request.queryParameters["cursor"]

                        val inputType =
                            call.request.queryParameters["inputType"]?.let { raw ->
                                runCatching { InputType.valueOf(raw) }.getOrElse {
                                    return@get call.respond(HttpStatusCode.BadRequest, "Invalid inputType: $raw")
                                }
                            }

                        val rawServiceId = call.request.queryParameters["serviceId"]
                        val serviceId =
                            rawServiceId?.let {
                                try {
                                    ServiceId(it)
                                } catch (_: IllegalArgumentException) {
                                    return@get call.respond(
                                        HttpStatusCode.BadRequest,
                                        "Invalid serviceId (must be UUID): $it",
                                    )
                                }
                            }

                        // organizationId is always from the JWT — any organizationId query param
                        // is ignored so callers cannot read another tenant's data.
                        val page =
                            CapturedInputRepository.find(
                                organizationId = principal.organizationId,
                                serviceId = serviceId,
                                inputType = inputType,
                                limit = limit,
                                cursor = cursor,
                            )
                        call.respond(page)
                    }

                    get("/{id}") {
                        val principal = call.principal<AgentIdentity>()!!

                        val rawId =
                            call.parameters["id"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                        val id =
                            try {
                                CapturedInputId(rawId)
                            } catch (_: IllegalArgumentException) {
                                return@get call.respond(HttpStatusCode.BadRequest, "Invalid id (must be UUID): $rawId")
                            }

                        // findById scopes to organizationId from JWT — returns null for both
                        // "not found" and "exists but belongs to a different org" so callers
                        // cannot distinguish the two (404 in both cases, no info leak).
                        val input = CapturedInputRepository.findById(id = id, organizationId = principal.organizationId)
                        if (input != null) {
                            call.respond(input)
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Captured input not found")
                        }
                    }

                    delete {
                        val principal = call.principal<AgentIdentity>()!!

                        val rawServiceId = call.request.queryParameters["serviceId"]
                        if (rawServiceId == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing required query parameter: serviceId")
                            return@delete
                        }

                        val serviceId =
                            try {
                                ServiceId(rawServiceId)
                            } catch (_: IllegalArgumentException) {
                                return@delete call.respond(
                                    HttpStatusCode.BadRequest,
                                    "Invalid serviceId (must be UUID): $rawServiceId",
                                )
                            }

                        // Scoped to the caller's org — silently ignores rows that belong to other orgs.
                        val deleted =
                            CapturedInputRepository.deleteByService(
                                serviceId = serviceId,
                                organizationId = principal.organizationId,
                            )
                        call.respond(DeleteResponse(deleted = deleted))
                    }
                }
            }
        }
    }
}
