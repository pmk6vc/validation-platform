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

            coEvery { discovery.discover(any()) } returns
                listOf(
                    DiscoveredService("production", "api-gateway"),
                    DiscoveredService("production", "order-service"),
                )
            coEvery { platformClient.registerService(any(), any()) } returns true

            discoverServices(discovery, platformClient, configWith(), registered)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
            coVerify(exactly = 1) { platformClient.registerService("production", "order-service") }
            assertEquals(
                setOf("production" to "api-gateway", "production" to "order-service"),
                registered,
            )
        }

    @Test
    fun `does not re-register services on subsequent runs`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(DiscoveredService("production", "api-gateway"))
            coEvery { platformClient.registerService(any(), any()) } returns true

            // First tick registers it.
            discoverServices(discovery, platformClient, configWith(), registered)
            // Second tick should be a no-op for that service.
            discoverServices(discovery, platformClient, configWith(), registered)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
        }

    @Test
    fun `registers newly-appearing services across ticks`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returnsMany
                listOf(
                    listOf(DiscoveredService("production", "api-gateway")),
                    listOf(
                        DiscoveredService("production", "api-gateway"),
                        DiscoveredService("production", "order-service"),
                    ),
                )
            coEvery { platformClient.registerService(any(), any()) } returns true

            discoverServices(discovery, platformClient, configWith(), registered)
            discoverServices(discovery, platformClient, configWith(), registered)

            coVerify(exactly = 1) { platformClient.registerService("production", "api-gateway") }
            coVerify(exactly = 1) { platformClient.registerService("production", "order-service") }
        }

    @Test
    fun `failed registration is retried on next tick`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns
                listOf(DiscoveredService("production", "api-gateway"))
            // First call fails, second succeeds.
            coEvery { platformClient.registerService("production", "api-gateway") } returnsMany
                listOf(false, true)

            discoverServices(discovery, platformClient, configWith(), registered)
            assertTrue(registered.isEmpty(), "failed registration must NOT be tracked as registered")

            discoverServices(discovery, platformClient, configWith(), registered)
            assertEquals(setOf("production" to "api-gateway"), registered)

            coVerify(exactly = 2) { platformClient.registerService("production", "api-gateway") }
        }

    @Test
    fun `forwards namespaceFilters from DynamicConfig to discovery`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns emptyList()

            discoverServices(
                discovery,
                platformClient,
                configWith(namespaceFilters = listOf("production", "external")),
                registered,
            )

            coVerify(exactly = 1) { discovery.discover(listOf("production", "external")) }
        }

    @Test
    fun `discovery returning empty list is a no-op`() =
        runBlocking {
            val discovery = mockk<K8sServiceDiscovery>()
            val platformClient = mockk<PlatformClient>()
            val registered = mutableSetOf<Pair<String, String>>()

            coEvery { discovery.discover(any()) } returns emptyList()

            discoverServices(discovery, platformClient, configWith(), registered)

            coVerify(exactly = 0) { platformClient.registerService(any(), any()) }
            assertTrue(registered.isEmpty())
        }
}
