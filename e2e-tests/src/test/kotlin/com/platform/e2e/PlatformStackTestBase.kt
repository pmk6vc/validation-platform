package com.platform.e2e

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.agent.buildAgentHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Shared test infrastructure for e2e tests that need the full platform stack:
 * Postgres + Platform + Collector. Both apps validate JWTs directly.
 *
 * Subclasses get [platformUrl], [collectorUrl], [httpClient], [agentHttpClient],
 * and [generateJwt] for free. Use [agentHttpClient] for any call that
 * simulates the production agent (config poll, service registration,
 * captured-inputs POST) so the agent's plugin stack is exercised end-to-end.
 * The stack is started once per test class (companion @BeforeAll).
 */
abstract class PlatformStackTestBase {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
        private val network = Network.newNetwork()

        private val keyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val privateKey = keyPair.private as RSAPrivateKey
        private val publicKey = keyPair.public as RSAPublicKey
        private val privateKeyPem = buildPrivateKeyPem(privateKey)

        lateinit var postgres: PostgreSQLContainer<*>
        lateinit var app: GenericContainer<*>
        lateinit var collector: GenericContainer<*>
        lateinit var httpClient: HttpClient

        // Built via the agent's production factory so e2e calls that
        // simulate the agent inherit any plugin added there.
        lateinit var agentHttpClient: HttpClient
        lateinit var platformUrl: String
        lateinit var collectorUrl: String

        fun generateJwt(
            organizationId: String = "00000000-0000-0000-0000-000000000001",
            cluster: String = "test-cluster",
            role: String = "admin",
            expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
        ): String =
            JWT
                .create()
                .withClaim("organizationId", organizationId)
                .withClaim("cluster", cluster)
                .withClaim("role", role)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(publicKey, privateKey))

        private fun buildPrivateKeyPem(key: RSAPrivateKey): String {
            val encoded = Base64.getEncoder().encodeToString(key.encoded)
            return "-----BEGIN PRIVATE KEY-----\n" +
                encoded.chunked(64).joinToString("\n") +
                "\n-----END PRIVATE KEY-----"
        }

        @BeforeAll
        @JvmStatic
        fun startStack() {
            postgres =
                PostgreSQLContainer("postgres:16-alpine")
                    .withNetwork(network)
                    .withNetworkAliases("db")
                    .withDatabaseName("platform_test")
                    .withUsername("test")
                    .withPassword("test")
            postgres.start()

            val jdbcUrl = "jdbc:postgresql://db:5432/platform_test"

            app =
                GenericContainer("validation-platform:test")
                    .withNetwork(network)
                    .withNetworkAliases("platform")
                    .withExposedPorts(8080)
                    .withEnv("DATABASE_URL", jdbcUrl)
                    .withEnv("DATABASE_USER", "test")
                    .withEnv("DATABASE_PASSWORD", "test")
                    .withEnv("JWT_PRIVATE_KEY", privateKeyPem)
                    .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            app.start()

            collector =
                GenericContainer("validation-collector:test")
                    .withNetwork(network)
                    .withNetworkAliases("collector")
                    .withExposedPorts(8081)
                    .withEnv("DATABASE_URL", jdbcUrl)
                    .withEnv("DATABASE_USER", "test")
                    .withEnv("DATABASE_PASSWORD", "test")
                    .withEnv("JWT_PRIVATE_KEY", privateKeyPem)
                    .waitingFor(Wait.forHttp("/health").forPort(8081).forStatusCode(200))
            collector.start()

            platformUrl = "http://${app.host}:${app.getMappedPort(8080)}"
            collectorUrl = "http://${collector.host}:${collector.getMappedPort(8081)}"

            httpClient =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
            agentHttpClient = buildAgentHttpClient()
        }

        @AfterAll
        @JvmStatic
        fun stopStack() {
            agentHttpClient.close()
            httpClient.close()
            collector.stop()
            app.stop()
            postgres.stop()
            network.close()
        }
    }
}
