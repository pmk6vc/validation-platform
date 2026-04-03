package com.platform.api

import com.platform.database.CapturedInputRepository
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.capture.InputType
import com.platform.models.capture.TrafficClassification
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Validation Platform API")
        }

        get("/health") {
            call.respondText("OK")
        }

        route("/api") {
            route("/organizations") {
                get {
                    val limit =
                        call.request.queryParameters["limit"]?.toIntOrNull()
                            ?: OrganizationRepository.DEFAULT_PAGE_SIZE
                    val cursor = call.request.queryParameters["cursor"]

                    val page = OrganizationRepository.find(limit = limit, cursor = cursor)
                    call.respond(page)
                }

                get("/{id}") {
                    val id =
                        call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")

                    val organization = OrganizationRepository.findById(id)
                    if (organization != null) {
                        call.respond(organization)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Organization not found")
                    }
                }
            }

            route("/services") {
                get {
                    val limit =
                        call.request.queryParameters["limit"]?.toIntOrNull()
                            ?: ServiceRepository.DEFAULT_PAGE_SIZE
                    val cursor = call.request.queryParameters["cursor"]

                    val page =
                        ServiceRepository.find(
                            organizationId = call.request.queryParameters["organizationId"],
                            cluster = call.request.queryParameters["cluster"],
                            namespace = call.request.queryParameters["namespace"],
                            limit = limit,
                            cursor = cursor,
                        )
                    call.respond(page)
                }

                get("/{id}") {
                    val id =
                        call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")

                    val service = ServiceRepository.findById(id)
                    if (service != null) {
                        call.respond(service)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Service not found")
                    }
                }
            }

            route("/captured-inputs") {
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
                    val classification =
                        call.request.queryParameters["classification"]?.let { raw ->
                            runCatching { TrafficClassification.valueOf(raw) }.getOrElse {
                                return@get call.respond(HttpStatusCode.BadRequest, "Invalid classification: $raw")
                            }
                        }

                    val page =
                        CapturedInputRepository.find(
                            serviceId = call.request.queryParameters["serviceId"],
                            inputType = inputType,
                            classification = classification,
                            limit = limit,
                            cursor = cursor,
                        )
                    call.respond(page)
                }

                get("/{id}") {
                    val id =
                        call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")

                    val input = CapturedInputRepository.findById(id)
                    if (input != null) {
                        call.respond(input)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Captured input not found")
                    }
                }

                delete {
                    val serviceId = call.request.queryParameters["serviceId"]
                    if (serviceId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing required query parameter: serviceId")
                        return@delete
                    }

                    val deleted = CapturedInputRepository.deleteByService(serviceId)
                    call.respond(mapOf("deleted" to deleted))
                }
            }
        }
    }
}
