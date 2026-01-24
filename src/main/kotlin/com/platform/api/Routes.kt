package com.platform.api

import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

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
                    val organizations = OrganizationRepository.findAll()
                    call.respond(organizations)
                }

                get("/{id}") {
                    val id = call.parameters["id"]
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
                    val services = ServiceRepository.find(
                        organizationId = call.request.queryParameters["organizationId"],
                        cluster = call.request.queryParameters["cluster"],
                        namespace = call.request.queryParameters["namespace"]
                    )
                    call.respond(services)
                }

                get("/{id}") {
                    val id = call.parameters["id"]
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
