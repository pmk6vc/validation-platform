package com.platform.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlin.test.assertEquals

class JwksRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun generateKeyPair(): Pair<RSAPrivateKey, String> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = keyPair.private as RSAPrivateKey
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" +
                encoded.chunked(64).joinToString("\n") +
                "\n-----END PRIVATE KEY-----"
        return Pair(privateKey, pem)
    }

    @Test
    fun `JWKS endpoint serves public key that can verify JWTs signed with the private key`() =
        testApplication {
            val (privateKey, pem) = generateKeyPair()

            application {
                configureJwks(privateKeyPem = pem)
                module(initDatabase = false)
            }

            // Fetch the JWKS
            val response = client.get("/.well-known/jwks.json")
            assertEquals(HttpStatusCode.OK, response.status)

            val jwks = json.decodeFromString<JsonObject>(response.bodyAsText())
            val keys = jwks["keys"]!!.jsonArray
            assertEquals(1, keys.size)

            val key = keys[0].jsonObject
            assertEquals("RSA", key["kty"]!!.jsonPrimitive.content)
            assertEquals("RS256", key["alg"]!!.jsonPrimitive.content)
            assertEquals("sig", key["use"]!!.jsonPrimitive.content)

            // Reconstruct the public key from JWKS n and e values
            val nBytes = Base64.getUrlDecoder().decode(key["n"]!!.jsonPrimitive.content)
            val eBytes = Base64.getUrlDecoder().decode(key["e"]!!.jsonPrimitive.content)
            val publicKey =
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(BigInteger(1, nBytes), BigInteger(1, eBytes)),
                ) as RSAPublicKey

            // Sign a JWT with the private key
            val token =
                JWT
                    .create()
                    .withClaim("organizationId", "org-123")
                    .withClaim("cluster", "prod")
                    .sign(Algorithm.RSA256(publicKey, privateKey))

            // Verify with the public key from JWKS — this is what Envoy does
            val verifier = JWT.require(Algorithm.RSA256(publicKey, null)).build()
            val decoded = verifier.verify(token)

            assertEquals("org-123", decoded.getClaim("organizationId").asString())
            assertEquals("prod", decoded.getClaim("cluster").asString())
        }

    @Test
    fun `JWKS endpoint returns consistent key across multiple requests`() =
        testApplication {
            val (_, pem) = generateKeyPair()

            application {
                configureJwks(privateKeyPem = pem)
                module(initDatabase = false)
            }

            val response1 = client.get("/.well-known/jwks.json").bodyAsText()
            val response2 = client.get("/.well-known/jwks.json").bodyAsText()

            assertEquals(response1, response2, "JWKS should be deterministic across requests")
        }
}
