package com.platform.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryLogicTest {
    private fun configWith(namespaceFilters: List<String> = emptyList()): MutableStateFlow<DynamicConfig> =
        MutableStateFlow(DynamicConfig.default().copy(namespaceFilters = namespaceFilters))

    @Test
    fun `registers all discovered services on first run`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(
                    DiscoveredService("production", "api-gateway"),
                    DiscoveredService("production", "order-service"),
                )
            coEvery { platformClient.registerService(any(), any()) } returns RegistrationOutcome.Success

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
            coVerify(exactly = 1) { platformClient.registerService("production", "order-service") }
            assertEquals(
                setOf("production" to "api-gateway", "production" to "order-service"),
                registered,
            )
            assertTrue(permanentlyFailed.isEmpty())
        }

    @Test
    fun `does not re-register services on subsequent runs`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(DiscoveredService("production", "api-gateway"))
            coEvery { platformClient.registerService(any(), any()) } returns RegistrationOutcome.Success

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
        }

    @Test
    fun `registers newly-appearing services across ticks`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returnsMany
                listOf(
                    listOf(DiscoveredService("production", "api-gateway")),
                    listOf(
                        DiscoveredService("production", "api-gateway"),
                        DiscoveredService("production", "order-service"),
                    ),
                )
            coEvery { platformClient.registerService(any(), any()) } returns RegistrationOutcome.Success

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
            coVerify(exactly = 1) { platformClient.registerService("production", "order-service") }
        }

    @Test
    fun `transient failure is retried on next tick`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(DiscoveredService("production", "api-gateway"))
            // First call: 503, second call: success.
            coEvery { platformClient.registerService("production", "api-gateway") } returnsMany
                listOf(RegistrationOutcome.TransientFailure, RegistrationOutcome.Success)

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            assertTrue(registered.isEmpty(), "transient failure must NOT be tracked as registered")
            assertTrue(permanentlyFailed.isEmpty(), "transient failure must NOT be tracked as permanent")

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            assertEquals(setOf("production" to "api-gateway"), registered)
            assertTrue(permanentlyFailed.isEmpty())

            coVerify(exactly = 2) { platformClient.registerService("production", "api-gateway") }
        }

    @Test
    fun `permanent rejection moves service to permanentlyFailed and never re-attempts`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(DiscoveredService("production", "bad-name"))
            coEvery { platformClient.registerService("production", "bad-name") } returns
                RegistrationOutcome.PermanentRejection

            // Three ticks — only the first should hit the platform.
            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)
            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            coVerify(exactly = 1) { platformClient.registerService("production", "bad-name") }
            assertTrue(registered.isEmpty())
            assertEquals(setOf("production" to "bad-name"), permanentlyFailed)
        }

    @Test
    fun `mixed outcomes route services into the correct sets`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(
                    DiscoveredService("production", "good"),
                    DiscoveredService("production", "bad-name"),
                    DiscoveredService("production", "transient-trouble"),
                )
            coEvery { platformClient.registerService("production", "good") } returns RegistrationOutcome.Success
            coEvery { platformClient.registerService("production", "bad-name") } returns
                RegistrationOutcome.PermanentRejection
            coEvery { platformClient.registerService("production", "transient-trouble") } returns
                RegistrationOutcome.TransientFailure

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            assertEquals(setOf("production" to "good"), registered)
            assertEquals(setOf("production" to "bad-name"), permanentlyFailed)
            // transient-trouble is in NEITHER set — next tick will retry.
        }

    @Test
    fun `forwards namespaceFilters from DynamicConfig to discovery`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns emptyList()

            discoverServices(
                discovery,
                platformClient,
                configWith(namespaceFilters = listOf("production", "external")),
                registered,
                permanentlyFailed,
            )

            coVerify(exactly = 1) { discovery.discover(listOf("production", "external")) }
        }

    @Test
    fun `discovery returning empty list is a no-op`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()
            val permanentlyFailed = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns emptyList()

            discoverServices(discovery, platformClient, configWith(), registered, permanentlyFailed)

            coVerify(exactly = 0) { platformClient.registerService(any(), any()) }
            assertTrue(registered.isEmpty())
            assertTrue(permanentlyFailed.isEmpty())
        }
}
