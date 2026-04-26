package com.platform

import com.platform.api.configureJwks
import com.platform.api.configureRouting
import com.platform.shared.auth.installJwtAuth
import com.platform.shared.database.DatabaseFactory
import com.platform.shared.secrets.secretsProviderFromEnvironment
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
    val secretsProvider = secretsProviderFromEnvironment()

    if (initDatabase) {
        DatabaseFactory.initFromEnvironment(secretsProvider)
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

    val resolvedKey =
        privateKeyPem
            ?: secretsProvider.get("JWT_PRIVATE_KEY")
    installJwtAuth(resolvedKey)
    configureJwks(resolvedKey)
    configureRouting()
}
