package com.platform.api

import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
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
import java.util.UUID

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

                post {
                    val request = call.receive<CreateOrganizationRequest>()
                    if (request.name.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Organization name must not be blank")
                    }
                    val organization =
                        Organization(
                            id = UUID.randomUUID().toString(),
                            name = request.name,
                            createdAt = Instant.now(),
                        )
                    val created = OrganizationRepository.create(organization)
                    call.respond(HttpStatusCode.Created, created)
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

            // Agent config requires identity (org + cluster from Envoy-forwarded JWT claims)
            authenticate(ENVOY_IDENTITY_AUTH) {
                route("/agent") {
                    get("/config") {
                        val identity = call.principal<AgentIdentity>()!!
                        // TODO: Replace this graceful UUID handling with dedicated value-class
                        //  typing (OrganizationId, ServiceId) so invalid IDs are caught at
                        //  compile time rather than runtime. See ARCHITECTURE_REVIEW.md QUALITY-5.
                        val orgId =
                            try {
                                UUID.fromString(identity.organizationId)
                                identity.organizationId
                            } catch (_: IllegalArgumentException) {
                                // Non-UUID org ID in JWT claim — no services will match
                                null
                            }
                        val services =
                            ServiceRepository.find(
                                organizationId = orgId,
                                cluster = identity.cluster,
                                limit = ServiceRepository.MAX_PAGE_SIZE,
                            )
                        val targetServices =
                            services.items.associate { it.name to it.id }
                        call.respond(AgentConfigResponse(targetServices = targetServices))
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

                post {
                    val request = call.receive<CreateServiceRequest>()
                    if (request.name.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Service name must not be blank")
                    }
                    val now = Instant.now()
                    val service =
                        Service(
                            id = UUID.randomUUID().toString(),
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
        }
    }
}
