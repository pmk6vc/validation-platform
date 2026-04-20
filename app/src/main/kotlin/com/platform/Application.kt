package com.platform

import com.platform.api.configureJwks
import com.platform.api.configureRouting
import com.platform.api.installAuth
import com.platform.database.DatabaseFactory
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module(initDatabase: Boolean = true) {
    if (initDatabase) {
        DatabaseFactory.initFromEnvironment()
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<ExposedSQLException> { call, cause ->
            when (cause.sqlState) {
                "23505" -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Resource already exists"))
                "23503" -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Referenced resource not found"))
                else -> throw cause
            }
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }

    installAuth()
    configureJwks()
    configureRouting()
}
