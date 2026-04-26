package com.platform.api

import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
import com.platform.shared.auth.AgentIdentity
import com.platform.shared.auth.JWT_AUTH
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.ServiceId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Instant

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Validation Platform API")
        }

        get("/health") {
            call.respondText("OK")
        }

        // All /api/* routes require a valid JWT. Previously enforced by Envoy;
        // now in-app via the shared installJwtAuth() plugin.
        authenticate(JWT_AUTH) {
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

                    post {
                        val request = call.receive<CreateOrganizationRequest>()
                        if (request.name.isBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Organization name must not be blank")
                        }
                        val organization =
                            Organization(
                                id = OrganizationId.generate(),
                                name = request.name,
                                createdAt = Instant.now(),
                            )
                        val created = OrganizationRepository.create(organization)
                        call.respond(HttpStatusCode.Created, created)
                    }

                    get("/{id}") {
                        val rawId =
                            call.parameters["id"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                        val id =
                            try {
                                OrganizationId(rawId)
                            } catch (_: IllegalArgumentException) {
                                return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    "Invalid organization ID (must be UUID): $rawId",
                                )
                            }

                        val organization = OrganizationRepository.findById(id)
                        if (organization != null) {
                            call.respond(organization)
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Organization not found")
                        }
                    }
                }

                route("/agent") {
                    get("/config") {
                        val identity = call.principal<AgentIdentity>()!!
                        val services =
                            ServiceRepository.find(
                                organizationId = identity.organizationId,
                                cluster = identity.cluster,
                                limit = ServiceRepository.MAX_PAGE_SIZE,
                            )
                        val targetServices =
                            services.items.associate { it.name to it.id.value }
                        call.respond(AgentConfigResponse(targetServices = targetServices))
                    }
                }

                route("/services") {
                    get {
                        val limit =
                            call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: ServiceRepository.DEFAULT_PAGE_SIZE
                        val cursor = call.request.queryParameters["cursor"]

                        val rawOrgId = call.request.queryParameters["organizationId"]
                        val organizationId =
                            rawOrgId?.let {
                                try {
                                    OrganizationId(it)
                                } catch (_: IllegalArgumentException) {
                                    return@get call.respond(
                                        HttpStatusCode.BadRequest,
                                        "Invalid organizationId (must be UUID): $it",
                                    )
                                }
                            }

                        val page =
                            ServiceRepository.find(
                                organizationId = organizationId,
                                cluster = call.request.queryParameters["cluster"],
                                namespace = call.request.queryParameters["namespace"],
                                limit = limit,
                                cursor = cursor,
                            )
                        call.respond(page)
                    }

                    post {
                        val request = call.receive<CreateServiceRequest>()
                        if (request.name.isBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Service name must not be blank")
                        }
                        val now = Instant.now()
                        val service =
                            Service(
                                id = ServiceId.generate(),
                                organizationId = request.organizationId,
                                cluster = request.cluster,
                                namespace = request.namespace,
                                name = request.name,
                                provider = request.provider,
                                discoveredAt = now,
                                lastSeenAt = now,
                                metadata = request.metadata,
                            )
                        val created = ServiceRepository.create(service)
                        call.respond(HttpStatusCode.Created, created)
                    }

                    get("/{id}") {
                        val rawId =
                            call.parameters["id"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                        val id =
                            try {
                                ServiceId(rawId)
                            } catch (_: IllegalArgumentException) {
                                return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    "Invalid service ID (must be UUID): $rawId",
                                )
                            }

                        val service = ServiceRepository.findById(id)
                        if (service != null) {
                            call.respond(service)
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Service not found")
                        }
                    }
                }
            }
        }
    }
}
