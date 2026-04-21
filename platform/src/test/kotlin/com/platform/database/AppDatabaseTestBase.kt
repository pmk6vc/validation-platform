package com.platform.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach

abstract class AppDatabaseTestBase : DatabaseTestBase() {
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
