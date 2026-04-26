package com.platform.collector.database

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.shared.database.DatabaseTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

abstract class CollectorDatabaseTestBase : DatabaseTestBase() {
    @BeforeEach
    fun cleanTables() {
        runBlocking {
            newSuspendedTransaction {
                CapturedInputs.deleteAll()
            }
        }
    }

    companion object {
        /**
         * RSA key pair generated once for the entire test suite.
         * Passed as [privateKeyPem] to [com.platform.collector.module] so tests don't need
         * the JWT_PRIVATE_KEY environment variable.
         */
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val testPrivateKey = keyPair.private as RSAPrivateKey
        val testPublicKey = keyPair.public as RSAPublicKey

        val testPrivateKeyPem: String by lazy {
            val encoded = Base64.getEncoder().encodeToString(testPrivateKey.encoded)
            "-----BEGIN PRIVATE KEY-----\n" +
                encoded.chunked(64).joinToString("\n") +
                "\n-----END PRIVATE KEY-----"
        }

        fun generateTestJwt(
            organizationId: String = "00000000-0000-0000-0000-000000000001",
            cluster: String = "test-cluster",
            expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
        ): String =
            JWT
                .create()
                .withClaim("organizationId", organizationId)
                .withClaim("cluster", cluster)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(testPublicKey, testPrivateKey))
    }
}
