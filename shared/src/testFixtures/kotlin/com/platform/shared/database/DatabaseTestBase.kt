package com.platform.shared.database

import org.junit.jupiter.api.BeforeAll
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

abstract class DatabaseTestBase {
    companion object {
        private var postgres: PostgreSQLContainer? = null
        private var initialized = false

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            if (initialized) return
            initialized = true

            postgres =
                PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                    withDatabaseName("platform_test")
                    withUsername("test")
                    withPassword("test")
                    start()
                }

            DatabaseFactory.init(
                jdbcUrl = postgres!!.getJdbcUrl(),
                username = postgres!!.getUsername(),
                password = postgres!!.getPassword(),
            )
        }

        // Container is intentionally not stopped here. It's a static singleton
        // shared across all test classes in the module — @AfterAll runs per class,
        // so stopping it would kill the container while other classes still need it.
        // TestContainers' Ryuk daemon handles cleanup after the JVM exits.
    }
}
