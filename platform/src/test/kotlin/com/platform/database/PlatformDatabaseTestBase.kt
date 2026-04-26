package com.platform.database

import com.platform.shared.database.DatabaseTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach

/**
 * Platform-specific test base: cleans the platform-owned tables (Organizations,
 * Services) before each test. Inherits TestContainers PostgreSQL setup from
 * [DatabaseTestBase] in `:shared`.
 *
 * JWT key fixtures and `generateTestJwt` live in
 * [com.platform.shared.testing.TestJwtKeys] — single source of truth across
 * platform and collector tests.
 */
abstract class PlatformDatabaseTestBase : DatabaseTestBase() {
    @BeforeEach
    fun cleanTables() {
        runBlocking {
            newSuspendedTransaction {
                Services.deleteAll()
                Organizations.deleteAll()
            }
        }
    }
}
