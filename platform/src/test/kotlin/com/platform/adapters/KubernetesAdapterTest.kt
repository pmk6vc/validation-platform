package com.platform.adapters

import com.platform.models.OrganizationId
import com.platform.models.Provider
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceList
import io.fabric8.kubernetes.api.model.ServicePort
import io.fabric8.kubernetes.api.model.ServiceSpec
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.ServiceResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KubernetesAdapterTest {
    @Test
    fun `discoverServices should return empty list when no services found`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns emptyList()

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.isEmpty())
        }

    @Test
    fun `discoverServices should discover services from all namespaces by default`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService = createMockK8sService("test-service", "test-namespace")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals("test-service", services[0].name)
            assertEquals("test-namespace", services[0].namespace)
        }

    @Test
    fun `discoverServices should filter out system namespaces by default`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val userService = createMockK8sService("user-service", "production")
            val systemService = createMockK8sService("kube-dns", "kube-system")
            val publicService = createMockK8sService("public-service", "kube-public")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(userService, systemService, publicService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals("user-service", services[0].name)
            assertEquals("production", services[0].namespace)
        }

    @Test
    fun `discoverServices should include services in default namespace`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val defaultService = createMockK8sService("my-service", "default")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(defaultService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals("my-service", services[0].name)
            assertEquals("default", services[0].namespace)
        }

    @Test
    fun `discoverServices should include system namespaces when excludeSystemNamespaces is false`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val userService = createMockK8sService("user-service", "production")
            val systemService = createMockK8sService("kube-dns", "kube-system")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(userService, systemService)

            val adapter =
                KubernetesAdapter(
                    client = client,
                    excludeSystemNamespaces = false,
                )
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(2, services.size)
            assertTrue(services.any { it.name == "user-service" })
            assertTrue(services.any { it.name == "kube-dns" })
        }

    @Test
    fun `discoverServices should only discover from specified namespaces`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList1 = mockk<ServiceList>()
            val serviceList2 = mockk<ServiceList>()

            val service1 = createMockK8sService("service-1", "namespace-1")
            val service2 = createMockK8sService("service-2", "namespace-2")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inNamespace("namespace-1") } returns servicesOperation
            every { servicesOperation.inNamespace("namespace-2") } returns servicesOperation
            every { servicesOperation.list() } returnsMany listOf(serviceList1, serviceList2)
            every { serviceList1.items } returns listOf(service1)
            every { serviceList2.items } returns listOf(service2)

            val adapter =
                KubernetesAdapter(
                    client = client,
                    namespaces = listOf("namespace-1", "namespace-2"),
                )
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(2, services.size)
            assertTrue(services.any { it.name == "service-1" && it.namespace == "namespace-1" })
            assertTrue(services.any { it.name == "service-2" && it.namespace == "namespace-2" })

            verify(exactly = 1) { servicesOperation.inNamespace("namespace-1") }
            verify(exactly = 1) { servicesOperation.inNamespace("namespace-2") }
            verify(exactly = 0) { servicesOperation.inAnyNamespace() }
        }

    @Test
    fun `discoverServices should set provider to KUBERNETES`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService = createMockK8sService("test-service", "test-namespace")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals(Provider.KUBERNETES, services[0].provider)
        }

    @Test
    fun `discoverServices should set organization ID for all services`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val service1 = createMockK8sService("service-1", "namespace-1")
            val service2 = createMockK8sService("service-2", "namespace-2")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(service1, service2)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(2, services.size)
            assertTrue(services.all { it.organizationId == organizationId })
        }

    @Test
    fun `discoverServices should use provided cluster name`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService = createMockK8sService("test-service", "test-namespace")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter =
                KubernetesAdapter(
                    client = client,
                    clusterName = "prod-us-west",
                )
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals("prod-us-west", services[0].cluster)
        }

    @Test
    fun `discoverServices should extract service type metadata`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService =
                createMockK8sService(
                    name = "test-service",
                    namespace = "test-namespace",
                    serviceType = "LoadBalancer",
                )

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].metadata)
            assertEquals("LoadBalancer", services[0].metadata!!["k8s.service.type"])
        }

    @Test
    fun `discoverServices should extract cluster IP metadata`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService =
                createMockK8sService(
                    name = "test-service",
                    namespace = "test-namespace",
                    clusterIP = "10.96.0.1",
                )

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].metadata)
            assertEquals("10.96.0.1", services[0].metadata!!["k8s.cluster.ip"])
        }

    @Test
    fun `discoverServices should extract port information`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val ports =
                listOf(
                    ServicePort().apply {
                        name = "http"
                        port = 80
                        protocol = "TCP"
                    },
                    ServicePort().apply {
                        name = "https"
                        port = 443
                        protocol = "TCP"
                    },
                )

            val k8sService =
                createMockK8sService(
                    name = "test-service",
                    namespace = "test-namespace",
                    ports = ports,
                )

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].metadata)
            val portsMetadata = services[0].metadata!!["k8s.ports"]
            assertNotNull(portsMetadata)
            assertTrue(portsMetadata.contains("http:80/TCP"))
            assertTrue(portsMetadata.contains("https:443/TCP"))
        }

    @Test
    fun `discoverServices should extract labels`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val labels =
                mapOf(
                    "app" to "my-app",
                    "app.kubernetes.io/version" to "1.2.3",
                    "team" to "platform",
                )

            val k8sService =
                createMockK8sService(
                    name = "test-service",
                    namespace = "test-namespace",
                    labels = labels,
                )

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].metadata)
            assertEquals("my-app", services[0].metadata!!["app"])
            assertEquals("1.2.3", services[0].metadata!!["version"])
            assertEquals("platform", services[0].metadata!!["team"])
        }

    @Test
    fun `discoverServices should extract selector information`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val selector = mapOf("app" to "my-app", "env" to "prod")

            val k8sService =
                createMockK8sService(
                    name = "test-service",
                    namespace = "test-namespace",
                    selector = selector,
                )

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].metadata)
            val selectorMetadata = services[0].metadata!!["k8s.selector"]
            assertNotNull(selectorMetadata)
            assertTrue(selectorMetadata.contains("app=my-app"))
            assertTrue(selectorMetadata.contains("env=prod"))
        }

    @Test
    fun `discoverServices should handle services with missing optional fields`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService =
                Service().apply {
                    metadata =
                        ObjectMeta().apply {
                            name = "minimal-service"
                            namespace = "test-namespace"
                        }
                    spec = ServiceSpec() // Empty spec
                }

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertEquals("minimal-service", services[0].name)
            assertEquals("test-namespace", services[0].namespace)
        }

    @Test
    fun `discoverServices should skip services with invalid metadata`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val validService = createMockK8sService("valid-service", "test-namespace")
            val invalidService = Service() // Missing metadata

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(validService, invalidService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            // Only valid service should be included
            assertEquals(1, services.size)
            assertEquals("valid-service", services[0].name)
        }

    @Test
    fun `discoverServices should return empty list on client exception`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } throws RuntimeException("Connection failed")

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertTrue(services.isEmpty())
        }

    @Test
    fun `discoverServices should set discoveredAt and lastSeenAt timestamps`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val k8sService = createMockK8sService("test-service", "test-namespace")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(k8sService)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(1, services.size)
            assertNotNull(services[0].discoveredAt)
            assertNotNull(services[0].lastSeenAt)
        }

    @Test
    fun `discoverServices should generate unique IDs for each service`() =
        runBlocking {
            val client = mockk<KubernetesClient>()
            val servicesOperation = mockk<MixedOperation<Service, ServiceList, ServiceResource<Service>>>()
            val serviceList = mockk<ServiceList>()

            val service1 = createMockK8sService("service-1", "namespace-1")
            val service2 = createMockK8sService("service-2", "namespace-2")
            val service3 = createMockK8sService("service-3", "namespace-3")

            every { client.services() } returns servicesOperation
            every { servicesOperation.inAnyNamespace() } returns servicesOperation
            every { servicesOperation.list() } returns serviceList
            every { serviceList.items } returns listOf(service1, service2, service3)

            val adapter = KubernetesAdapter(client = client)
            val organizationId = OrganizationId.generate()

            val services = adapter.discoverServices(organizationId)

            assertEquals(3, services.size)
            val uniqueIds = services.map { it.id }.toSet()
            assertEquals(3, uniqueIds.size)
        }

    @Test
    fun `close should propagate to the underlying Kubernetes client`() {
        // Given
        val client = mockk<KubernetesClient>(relaxed = true)
        val adapter = KubernetesAdapter(client = client)

        // When
        adapter.close()

        // Then - the underlying client must be closed exactly once so its
        // connection pool and watches are properly released
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `KubernetesAdapter implements Closeable so it can be used with use`() {
        // Given
        val client = mockk<KubernetesClient>(relaxed = true)

        // When - Kotlin's use extension calls close() at the end of the block
        KubernetesAdapter(client = client).use { }

        // Then
        verify(exactly = 1) { client.close() }
    }

    // Helper function to create mock Kubernetes services
    private fun createMockK8sService(
        name: String,
        namespace: String,
        serviceType: String? = null,
        clusterIP: String? = null,
        ports: List<ServicePort>? = null,
        labels: Map<String, String>? = null,
        selector: Map<String, String>? = null,
    ): Service =
        Service().apply {
            metadata =
                ObjectMeta().apply {
                    this.name = name
                    this.namespace = namespace
                    labels?.let { this.labels = it }
                }
            spec =
                ServiceSpec().apply {
                    serviceType?.let { this.type = it }
                    clusterIP?.let { this.clusterIP = it }
                    ports?.let { this.ports = it }
                    selector?.let { this.selector = it }
                }
        }
}
