package com.platform.collector.api

import com.platform.auth.JWT_AUTH
import com.platform.collector.database.CapturedInputRepository
import com.platform.collector.models.BatchCreateCapturedInputRequest
import com.platform.collector.models.BatchCreateCapturedInputResponse
import com.platform.collector.models.CapturedInput
import com.platform.collector.models.CapturedInputId
import com.platform.collector.models.DeleteResponse
import com.platform.collector.models.InputType
import com.platform.collector.models.ServiceId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
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

                        val page =
                            CapturedInputRepository.find(
                                serviceId = serviceId,
                                inputType = inputType,
                                limit = limit,
                                cursor = cursor,
                            )
                        call.respond(page)
                    }

                    get("/{id}") {
                        val rawId =
                            call.parameters["id"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                        val id =
                            try {
                                CapturedInputId(rawId)
                            } catch (_: IllegalArgumentException) {
                                return@get call.respond(HttpStatusCode.BadRequest, "Invalid id (must be UUID): $rawId")
                            }

                        val input = CapturedInputRepository.findById(id)
                        if (input != null) {
                            call.respond(input)
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Captured input not found")
                        }
                    }

                    delete {
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

                        val deleted = CapturedInputRepository.deleteByService(serviceId)
                        call.respond(DeleteResponse(deleted = deleted))
                    }
                }
            }
        }
    }
}
