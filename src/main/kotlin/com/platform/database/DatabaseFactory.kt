package com.platform.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Database configuration and initialization.
 */
object DatabaseFactory {
    fun init(
        jdbcUrl: String,
        username: String,
        password: String
    ) {
        Database.connect(
            url = jdbcUrl,
            driver = "org.postgresql.Driver",
            user = username,
            password = password
        )

        transaction {
            SchemaUtils.create(Organizations, Services)
        }
    }
}
