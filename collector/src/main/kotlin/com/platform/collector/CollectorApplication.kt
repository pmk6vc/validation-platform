package com.platform.collector

import com.platform.auth.installJwtAuth
import com.platform.collector.api.configureRouting
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

fun Application.module(
    initDatabase: Boolean = true,
    privateKeyPem: String? = null,
) {
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

    val resolvedKey =
        privateKeyPem
            ?: System.getenv("JWT_PRIVATE_KEY")
            ?: error("JWT_PRIVATE_KEY environment variable is required")
    installJwtAuth(resolvedKey)
    configureRouting()
}
