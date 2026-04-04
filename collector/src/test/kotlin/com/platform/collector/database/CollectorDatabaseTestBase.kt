package com.platform.collector.database

import com.platform.database.DatabaseTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach

abstract class CollectorDatabaseTestBase : DatabaseTestBase() {
    @BeforeEach
    fun cleanTables() {
        runBlocking {
            newSuspendedTransaction {
                CapturedInputs.deleteAll()
            }
        }
    }
}
