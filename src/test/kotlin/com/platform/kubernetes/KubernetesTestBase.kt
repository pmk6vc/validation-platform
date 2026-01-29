package com.platform.kubernetes

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base class for Kubernetes integration tests.
 *
 * ## How it works
 * This class sets up a lightweight k3s cluster via Testcontainers that is shared across
 * all tests. The cluster is populated with sample services representing a typical
 * microservices environment.
 *
 * ## Cluster Configuration
 * - **Distribution**: k3s (lightweight Kubernetes)
 * - **Version**: v1.27.9
 * - **Lifecycle**: Shared across all tests (started once, stopped after all tests)
 * - **Namespaces**: production, infrastructure
 *
 * ## Pre-created Services
 * The cluster comes pre-populated with:
 * - `production/frontend-service` - LoadBalancer service with HTTP port
 * - `production/api-gateway` - ClusterIP service with HTTP and gRPC ports
 * - `infrastructure/redis` - ClusterIP service for Redis
 *
 * ## Usage
 * Extend this class to get access to:
 * - `k3s`: The Testcontainers k3s instance
 * - `createKubernetesClient()`: Helper to create a client connected to the test cluster
 *
 * ## Test Isolation
 * Unlike database tests, Kubernetes resources are NOT cleaned between tests.
 * If you need isolation, create resources in unique namespaces per test.
 *
 * ## CI vs Local Development
 * Works the same in both environments - Testcontainers will handle Docker connectivity.
 * The k3s image is lightweight (~100MB) and starts quickly.
 *
 * Example usage:
 * ```kotlin
 * class MyK8sTest : KubernetesTestBase() {
 *     @Test
 *     fun `test something with k8s`() {
 *         val client = createKubernetesClient()
 *         try {
 *             // Your test code here
 *         } finally {
 *             client.close()
 *         }
 *     }
 * }
 * ```
 */
abstract class KubernetesTestBase {
    companion object {
        protected lateinit var k3s: K3sContainer

        @BeforeAll
        @JvmStatic
        fun setupK3sCluster() {
            // Start k3s cluster (lightweight Kubernetes distribution)
            k3s =
                K3sContainer(DockerImageName.parse("rancher/k3s:v1.27.9-k3s1")).apply {
                    start()
                }

            // Populate cluster with sample services
            val client = createKubernetesClient()
            try {
                createNamespaces(client)
                createSampleServices(client)
            } finally {
                client.close()
            }
        }

        /**
         * Creates a Kubernetes client connected to the test k3s cluster.
         * Caller is responsible for closing the client after use.
         */
        fun createKubernetesClient(): KubernetesClient {
            val config = Config.fromKubeconfig(k3s.kubeConfigYaml)
            return KubernetesClientBuilder().withConfig(config).build()
        }

        /**
         * Creates the namespaces used in tests.
         */
        private fun createNamespaces(client: KubernetesClient) {
            // Create production namespace
            client
                .namespaces()
                .resource(
                    io.fabric8.kubernetes.api.model
                        .NamespaceBuilder()
                        .withNewMetadata()
                        .withName("production")
                        .endMetadata()
                        .build(),
                ).create()

            // Create infrastructure namespace
            client
                .namespaces()
                .resource(
                    io.fabric8.kubernetes.api.model
                        .NamespaceBuilder()
                        .withNewMetadata()
                        .withName("infrastructure")
                        .endMetadata()
                        .build(),
                ).create()
        }

        /**
         * Creates sample Kubernetes Service resources for testing.
         * These services represent a typical microservices architecture.
         */
        private fun createSampleServices(client: KubernetesClient) {
            // Frontend service - LoadBalancer type
            client
                .services()
                .inNamespace("production")
                .resource(
                    ServiceBuilder()
                        .withMetadata(
                            ObjectMetaBuilder()
                                .withName("frontend-service")
                                .withNamespace("production")
                                .addToLabels("app", "frontend")
                                .addToLabels("app.kubernetes.io/name", "frontend")
                                .addToLabels("app.kubernetes.io/version", "2.1.0")
                                .addToLabels("app.kubernetes.io/component", "web")
                                .addToLabels("team", "ui")
                                .addToAnnotations("description", "Frontend web service")
                                .addToAnnotations("owner", "ui-team@company.com")
                                .build(),
                        ).withNewSpec()
                        .withType("LoadBalancer")
                        .withPorts(
                            ServicePortBuilder()
                                .withName("http")
                                .withPort(80)
                                .withProtocol("TCP")
                                .build(),
                        ).addToSelector("app", "frontend")
                        .endSpec()
                        .build(),
                ).create()

            // API Gateway - ClusterIP with multiple ports
            client
                .services()
                .inNamespace("production")
                .resource(
                    ServiceBuilder()
                        .withMetadata(
                            ObjectMetaBuilder()
                                .withName("api-gateway")
                                .withNamespace("production")
                                .addToLabels("app", "api-gateway")
                                .addToLabels("app.kubernetes.io/name", "api-gateway")
                                .addToLabels("app.kubernetes.io/version", "1.5.0")
                                .addToLabels("app.kubernetes.io/component", "gateway")
                                .addToLabels("team", "backend")
                                .addToAnnotations("description", "API Gateway service")
                                .addToAnnotations("owner", "backend-team@company.com")
                                .build(),
                        ).withNewSpec()
                        .withType("ClusterIP")
                        .withPorts(
                            ServicePortBuilder()
                                .withName("http")
                                .withPort(8080)
                                .withProtocol("TCP")
                                .build(),
                            ServicePortBuilder()
                                .withName("grpc")
                                .withPort(9090)
                                .withProtocol("TCP")
                                .build(),
                        ).addToSelector("app", "api-gateway")
                        .addToSelector("version", "v1")
                        .endSpec()
                        .build(),
                ).create()

            // Redis - Infrastructure service
            client
                .services()
                .inNamespace("infrastructure")
                .resource(
                    ServiceBuilder()
                        .withMetadata(
                            ObjectMetaBuilder()
                                .withName("redis")
                                .withNamespace("infrastructure")
                                .addToLabels("app", "redis")
                                .addToLabels("app.kubernetes.io/name", "redis")
                                .addToLabels("app.kubernetes.io/version", "7.2")
                                .addToLabels("app.kubernetes.io/component", "cache")
                                .build(),
                        ).withNewSpec()
                        .withType("ClusterIP")
                        .withPorts(
                            ServicePortBuilder()
                                .withName("redis")
                                .withPort(6379)
                                .withProtocol("TCP")
                                .build(),
                        ).addToSelector("app", "redis")
                        .endSpec()
                        .build(),
                ).create()

            // PostgreSQL - Infrastructure database service
            client
                .services()
                .inNamespace("infrastructure")
                .resource(
                    ServiceBuilder()
                        .withMetadata(
                            ObjectMetaBuilder()
                                .withName("postgresql")
                                .withNamespace("infrastructure")
                                .addToLabels("app", "postgresql")
                                .addToLabels("app.kubernetes.io/name", "postgresql")
                                .addToLabels("app.kubernetes.io/version", "16.1")
                                .addToLabels("app.kubernetes.io/component", "database")
                                .build(),
                        ).withNewSpec()
                        .withType("ClusterIP")
                        .withPorts(
                            ServicePortBuilder()
                                .withName("postgresql")
                                .withPort(5432)
                                .withProtocol("TCP")
                                .build(),
                        ).addToSelector("app", "postgresql")
                        .endSpec()
                        .build(),
                ).create()
        }
    }
}
