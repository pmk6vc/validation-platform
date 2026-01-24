package com.platform

import com.platform.api.configureRouting
import com.platform.database.DatabaseFactory
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

fun Application.module() {
    val dbUrl =
        environment.config.propertyOrNull("database.url")?.getString()
            ?: System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/platform"

    val dbUser =
        environment.config.propertyOrNull("database.user")?.getString()
            ?: System.getenv("DATABASE_USER")
            ?: "postgres"

    val dbPassword =
        environment.config.propertyOrNull("database.password")?.getString()
            ?: System.getenv("DATABASE_PASSWORD")
            ?: "postgres"

    DatabaseFactory.init(dbUrl, dbUser, dbPassword)

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            },
        )
    }

    configureRouting()
}
