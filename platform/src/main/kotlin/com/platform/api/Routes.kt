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
                        val principal = call.principal<AgentIdentity>()!!
                        val limit =
                            call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: OrganizationRepository.DEFAULT_PAGE_SIZE
                        val cursor = call.request.queryParameters["cursor"]

                        // Always scope to the caller's organization. A JWT identifies exactly one
                        // org, so this list returns 0 or 1 items. The paginated wrapper is kept
                        // for API consistency with future multi-org admin tokens.
                        val page =
                            OrganizationRepository.find(
                                organizationId = principal.organizationId,
                                limit = limit,
                                cursor = cursor,
                            )
                        call.respond(page)
                    }

                    post {
                        // TODO: Organization creation is an admin-only operation that doesn't fit
                        // the agent JWT model (an agent JWT is always scoped to an existing org).
                        // Deferring per-tenant enforcement here until an admin auth scheme is
                        // introduced. For now, org creation is open to any valid JWT bearer and
                        // the caller's organizationId claim is not enforced on this endpoint.
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
                        val principal = call.principal<AgentIdentity>()!!
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
                        // Return 404 whether the org doesn't exist OR belongs to a different tenant
                        // so callers cannot probe for other orgs' existence.
                        if (organization != null && organization.id == principal.organizationId) {
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
                        val principal = call.principal<AgentIdentity>()!!
                        val limit =
                            call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: ServiceRepository.DEFAULT_PAGE_SIZE
                        val cursor = call.request.queryParameters["cursor"]

                        // organizationId is always from the JWT — any organizationId query param
                        // is ignored so callers cannot read another tenant's services.
                        val page =
                            ServiceRepository.find(
                                organizationId = principal.organizationId,
                                cluster = call.request.queryParameters["cluster"],
                                namespace = call.request.queryParameters["namespace"],
                                limit = limit,
                                cursor = cursor,
                            )
                        call.respond(page)
                    }

                    post {
                        val principal = call.principal<AgentIdentity>()!!
                        val request = call.receive<CreateServiceRequest>()
                        if (request.name.isBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Service name must not be blank")
                        }
                        val now = Instant.now()
                        val service =
                            Service(
                                id = ServiceId.generate(),
                                organizationId = principal.organizationId,
                                cluster = principal.cluster,
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
                        val principal = call.principal<AgentIdentity>()!!
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
                        // Return 404 whether the service doesn't exist OR belongs to a different tenant
                        // so callers cannot probe for other orgs' services.
                        if (service != null && service.organizationId == principal.organizationId) {
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
