package com.platform.adapters

import com.platform.models.Provider
import com.platform.models.Service
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import io.fabric8.kubernetes.api.model.Service as K8sService

/**
 * Adapter that discovers services from Kubernetes clusters.
 *
 * This adapter connects to a Kubernetes cluster and discovers services by querying
 * the Kubernetes API. It supports both in-cluster configuration (when running inside
 * a Kubernetes pod) and kubeconfig-based configuration (for local development).
 *
 * Discovery strategy:
 * - Queries Kubernetes Service resources from specified namespaces (or all namespaces)
 * - Filters out system services (kube-system, kube-public, etc.) by default
 * - Extracts metadata from labels and annotations
 * - Normalizes into the platform's unified Service model
 *
 * Implements [Closeable] so callers can release the underlying Kubernetes client
 * connection pool when the adapter is no longer needed (e.g. via try-with-resources
 * or Kotlin's `use` extension).
 *
 * @property client The Kubernetes client to use for API calls. If not provided, a default
 *                  client will be created using the standard Kubernetes configuration chain:
 *                  1. In-cluster config (when running in a pod)
 *                  2. KUBECONFIG environment variable
 *                  3. ~/.kube/config file
 * @property clusterName The name to use for the cluster field in discovered services.
 *                       Defaults to the cluster name from kubeconfig or "default"
 * @property namespaces List of namespaces to discover services from. If empty, discovers
 *                      from all namespaces (except system namespaces)
 * @property excludeSystemNamespaces Whether to filter out system namespaces like kube-system.
 *                                   Default: true
 */
class KubernetesAdapter(
    private val client: KubernetesClient = KubernetesClientBuilder().build(),
    private val clusterName: String = detectClusterName(client),
    private val namespaces: List<String> = emptyList(),
    private val excludeSystemNamespaces: Boolean = true,
) : ServiceAdapter, Closeable {
    private val logger = LoggerFactory.getLogger(KubernetesAdapter::class.java)

    companion object {
        private val SYSTEM_NAMESPACES =
            setOf(
                "kube-system",
                "kube-public",
                "kube-node-lease",
                "default",
            )

        /**
         * Attempts to detect the cluster name from the Kubernetes client configuration.
         * Falls back to "default" if detection fails.
         */
        private fun detectClusterName(client: KubernetesClient): String =
            runCatching {
                client.configuration.currentContext
                    ?.context
                    ?.cluster ?: "default"
            }.getOrElse {
                "default"
            }
    }

    /**
     * Discovers services from the Kubernetes cluster.
     *
     * This method queries the Kubernetes API for Service resources and normalizes them
     * into the platform's Service model. It handles errors gracefully and logs warnings
     * for services that cannot be processed.
     *
     * @param organizationId The organization context for discovered services
     * @return List of services discovered from Kubernetes
     */
    override suspend fun discoverServices(organizationId: String): List<Service> {
        logger.info("Starting service discovery for cluster: $clusterName")

        return try {
            val k8sServices = withContext(Dispatchers.IO) { fetchKubernetesServices() }
            logger.info("Found ${k8sServices.size} Kubernetes services")

            k8sServices
                .mapNotNull { k8sService ->
                    runCatching {
                        normalizeService(k8sService, organizationId)
                    }.onFailure { e ->
                        logger.warn(
                            "Failed to normalize service ${k8sService.metadata?.name} " +
                                "in namespace ${k8sService.metadata?.namespace}: ${e.message}",
                        )
                    }.getOrNull()
                }.also { services ->
                    logger.info("Successfully discovered ${services.size} services")
                }
        } catch (e: Exception) {
            logger.error("Failed to discover services from Kubernetes: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches Kubernetes Service resources from the cluster.
     * Respects namespace filtering and system namespace exclusion settings.
     */
    private fun fetchKubernetesServices(): List<K8sService> {
        val services =
            if (namespaces.isEmpty()) {
                // Discover from all namespaces
                client
                    .services()
                    .inAnyNamespace()
                    .list()
                    .items
            } else {
                // Discover from specified namespaces
                namespaces.flatMap { namespace ->
                    client
                        .services()
                        .inNamespace(namespace)
                        .list()
                        .items
                }
            }

        return if (excludeSystemNamespaces) {
            services.filter { service ->
                val namespace = service.metadata?.namespace
                namespace != null && namespace !in SYSTEM_NAMESPACES
            }
        } else {
            services
        }
    }

    /**
     * Normalizes a Kubernetes Service into the platform's unified Service model.
     *
     * Extracts useful information from:
     * - Service metadata (name, namespace, labels, annotations)
     * - Service spec (type, ports)
     * - Resource status
     */
    private fun normalizeService(
        k8sService: K8sService,
        organizationId: String,
    ): Service {
        val metadata = k8sService.metadata ?: error("Service missing metadata")
        val name = metadata.name ?: error("Service missing name")
        val namespace = metadata.namespace ?: error("Service missing namespace")
        val now = Instant.now()

        // Extract metadata from labels and annotations
        val serviceMetadata = buildServiceMetadata(k8sService)

        return Service(
            id = UUID.randomUUID().toString(),
            organizationId = organizationId,
            cluster = clusterName,
            namespace = namespace,
            name = name,
            provider = Provider.KUBERNETES,
            discoveredAt = now,
            lastSeenAt = now,
            metadata = serviceMetadata,
        )
    }

    /**
     * Builds the metadata map from Kubernetes Service labels, annotations, and spec.
     */
    private fun buildServiceMetadata(k8sService: K8sService): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        val k8sMetadata = k8sService.metadata
        val spec = k8sService.spec

        // Add service type
        spec?.type?.let { metadata["k8s.service.type"] = it }

        // Add cluster IP
        spec?.clusterIP?.let { metadata["k8s.cluster.ip"] = it }

        // Add ports information
        spec?.ports?.let { ports ->
            if (ports.isNotEmpty()) {
                metadata["k8s.ports"] =
                    ports.joinToString(",") { port ->
                        "${port.name ?: port.port}:${port.port}/${port.protocol ?: "TCP"}"
                    }
            }
        }

        // Add selector labels (these identify which pods the service routes to)
        spec?.selector?.let { selector ->
            if (selector.isNotEmpty()) {
                metadata["k8s.selector"] = selector.entries.joinToString(",") { "${it.key}=${it.value}" }
            }
        }

        // Extract common labels
        k8sMetadata?.labels?.let { labels ->
            labels["app"]?.let { metadata["app"] = it }
            labels["app.kubernetes.io/name"]?.let { metadata["app.name"] = it }
            labels["app.kubernetes.io/version"]?.let { metadata["version"] = it }
            labels["app.kubernetes.io/component"]?.let { metadata["component"] = it }
            labels["app.kubernetes.io/part-of"]?.let { metadata["part-of"] = it }
            labels["team"]?.let { metadata["team"] = it }
        }

        // Extract useful annotations
        k8sMetadata?.annotations?.let { annotations ->
            annotations["description"]?.let { metadata["description"] = it }
            annotations["owner"]?.let { metadata["owner"] = it }
        }

        // Add resource age
        k8sMetadata?.creationTimestamp?.let { timestamp ->
            metadata["k8s.created.at"] = timestamp
        }

        // Add UID for correlation
        k8sMetadata?.uid?.let { uid ->
            metadata["k8s.uid"] = uid
        }

        return metadata
    }

    /**
     * Closes the Kubernetes client and releases its connection pool.
     *
     * Implements [Closeable.close] so the adapter can be used with Kotlin's `use`
     * extension or Java's try-with-resources. Should be called when the adapter is
     * no longer needed to avoid resource leaks.
     */
    override fun close() {
        client.close()
    }
}
