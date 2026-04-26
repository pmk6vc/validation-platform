package com.platform.e2e

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import org.testcontainers.utility.MountableFile
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Shared test infrastructure for e2e tests that need the full platform stack:
 * Postgres + App + Collector + Envoy.
 *
 * Subclasses get [envoyUrl], [httpClient], and [generateJwt] for free.
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
        lateinit var envoy: GenericContainer<*>
        lateinit var httpClient: HttpClient
        lateinit var envoyUrl: String

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

            envoy =
                GenericContainer("envoyproxy/envoy:v1.31-latest")
                    .withNetwork(network)
                    .withNetworkAliases("envoy")
                    .withExposedPorts(8082)
                    .withCopyFileToContainer(
                        MountableFile.forHostPath("deploy/envoy/envoy.yaml"),
                        "/etc/envoy/envoy.yaml",
                    ).waitingFor(Wait.forHttp("/health").forPort(8082).forStatusCode(200))
            envoy.start()

            envoyUrl = "http://${envoy.host}:${envoy.getMappedPort(8082)}"

            httpClient =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
        }

        @AfterAll
        @JvmStatic
        fun stopStack() {
            httpClient.close()
            envoy.stop()
            collector.stop()
            app.stop()
            postgres.stop()
            network.close()
        }
    }
}
