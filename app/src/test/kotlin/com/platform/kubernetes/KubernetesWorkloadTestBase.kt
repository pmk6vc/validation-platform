package com.platform.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.BeforeAll
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.nio.file.Paths
import java.time.Duration

/**
 * Base class for Kubernetes integration tests with actual running workloads.
 *
 * ## Deployed Workloads
 *
 * ### Infrastructure Namespace
 * - **orders-db**: PostgreSQL database for order-service
 * - **Redis**: Cache server for API Gateway (LRU eviction, 2MB max)
 * - **Kafka**: Message broker (KRaft mode, single node)
 *
 * ### Production Namespace
 * - **order-service**: HTTP API + Kafka producer (owns orders-db)
 * - **notification-service**: Kafka consumer + webhook caller
 * - **api-gateway**: Routes to order-service with Redis caching (NodePort 30080)
 * - **traffic-generator**: Concurrent HTTP traffic (no Service resource)
 *
 * ### External Namespace
 * - **webhook-stub**: Simulates a third-party webhook endpoint
 *
 * ## Traffic Flow
 * ```
 * traffic-generator → api-gateway → order-service → orders-db (PostgreSQL)
 *                                 → Redis (cache)    → Kafka (produce: order-events)
 *
 * Kafka (consume: order-events) → notification-service → webhook-stub (external)
 * ```
 *
 * ## Service Discovery
 * The KubernetesAdapter discovers services with K8s Service resources:
 * - orders-db, redis, kafka (infrastructure)
 * - order-service, notification-service, api-gateway (production)
 * - webhook-stub (external)
 *
 * Traffic generator has NO Service resource (client-only deployment).
 */
abstract class KubernetesWorkloadTestBase {
    companion object {
        private val logger = LoggerFactory.getLogger(KubernetesWorkloadTestBase::class.java)

        private var k3s: K3sContainer? = null
        private var initialized = false

        // Image names for test services (must match Jib config in each build.gradle.kts)
        private const val API_GATEWAY_IMAGE = "test-api-gateway:latest"
        private const val ORDER_SERVICE_IMAGE = "test-order-service:latest"
        private const val NOTIFICATION_SERVICE_IMAGE = "test-notification-service:latest"
        private const val WEBHOOK_STUB_IMAGE = "test-webhook-stub:latest"
        private const val TRAFFIC_GENERATOR_IMAGE = "test-traffic-generator:latest"

        // NodePort for API Gateway (must match k8s manifest)
        private const val API_GATEWAY_NODE_PORT = 30080

        // Path to K8s manifests (relative to project root)
        private const val MANIFESTS_PATH = "k8s/test-services/base"

        @BeforeAll
        @JvmStatic
        fun setupClusterWithWorkloads() {
            if (initialized) return
            initialized = true

            logger.info("Starting k3s cluster with workloads...")

            k3s =
                K3sContainer(DockerImageName.parse("rancher/k3s:v1.27.9-k3s1"))
                    .withLogConsumer(Slf4jLogConsumer(logger).withPrefix("k3s"))
                    .withExposedPorts(6443, API_GATEWAY_NODE_PORT)
                    .withCommand(
                        "server",
                        "--disable=traefik",
                        "--disable=metrics-server",
                    )
            k3s!!.start()

            // Load all test service images into k3s
            loadDockerImage(API_GATEWAY_IMAGE, "api-gateway")
            loadDockerImage(ORDER_SERVICE_IMAGE, "order-service")
            loadDockerImage(NOTIFICATION_SERVICE_IMAGE, "notification-service")
            loadDockerImage(WEBHOOK_STUB_IMAGE, "webhook-stub")
            loadDockerImage(TRAFFIC_GENERATOR_IMAGE, "traffic-generator")

            // Apply manifests
            applyManifests()

            // Wait for services to be ready. Each service retries connecting to its
            // own infrastructure dependencies (DB, Kafka, Redis), so we only need to
            // wait for the application-level deployments. Their readiness probes pass
            // only once all dependencies are reachable.
            val client = createKubernetesClient()
            try {
                waitForDeployment(client, "external", "webhook-stub", Duration.ofMinutes(1))
                waitForDeployment(client, "production", "order-service", Duration.ofMinutes(3))
                waitForDeployment(client, "production", "notification-service", Duration.ofMinutes(3))
                waitForDeployment(client, "production", "api-gateway", Duration.ofMinutes(3))
                waitForDeployment(client, "production", "traffic-generator", Duration.ofMinutes(1))

                logger.info("All workloads deployed and running")
            } finally {
                client.close()
            }
        }

        fun createKubernetesClient(): KubernetesClient {
            ensureClusterInitialized()
            val config =
                io.fabric8.kubernetes.client.Config
                    .fromKubeconfig(k3s!!.kubeConfigYaml)
            return io.fabric8.kubernetes.client
                .KubernetesClientBuilder()
                .withConfig(config)
                .build()
        }

        fun ensureClusterInitialized() {
            if (initialized) return
            setupClusterWithWorkloads()
        }

        fun getK3sContainer(): K3sContainer {
            ensureClusterInitialized()
            return k3s!!
        }

        fun getApiGatewayBaseUrl(): String {
            ensureClusterInitialized()
            val host = k3s!!.host
            val port = k3s!!.getMappedPort(API_GATEWAY_NODE_PORT)
            return "http://$host:$port"
        }

        private fun applyManifests() {
            logger.info("Applying Kubernetes manifests from $MANIFESTS_PATH...")

            val manifestsDir = findManifestsDirectory()
            if (!manifestsDir.exists()) {
                throw RuntimeException(
                    "Manifests directory not found at ${manifestsDir.absolutePath}. " +
                        "Make sure k8s/test-services/base/ exists in the project root.",
                )
            }

            k3s!!.copyFileToContainer(
                MountableFile.forHostPath(manifestsDir.toPath()),
                "/manifests",
            )

            // Remove kustomization.yaml — kubectl apply -R doesn't understand it,
            // and it's only needed for kustomize overlays (e.g. GKE deploy)
            k3s!!.execInContainer("rm", "-f", "/manifests/kustomization.yaml")

            val result =
                k3s!!.execInContainer(
                    "kubectl",
                    "apply",
                    "-R",
                    "-f",
                    "/manifests",
                )
            if (result.exitCode != 0) {
                logger.error("Failed to apply manifests: ${result.stderr}")
                throw RuntimeException("Failed to apply manifests: ${result.stderr}")
            }
            logger.info("Applied all manifests")
        }

        private fun findManifestsDirectory(): File {
            val direct = File(MANIFESTS_PATH)
            if (direct.exists()) return direct

            val projectRoot = System.getProperty("user.dir")
            val fromRoot = Paths.get(projectRoot, MANIFESTS_PATH).toFile()
            if (fromRoot.exists()) return fromRoot

            // When running from a submodule (e.g., app/), check parent directory
            val fromParent = Paths.get(projectRoot, "..", MANIFESTS_PATH).normalize().toFile()
            if (fromParent.exists()) return fromParent

            return direct
        }

        private fun loadDockerImage(
            imageName: String,
            label: String,
        ) {
            logger.info("Loading $label image into k3s...")

            val tarFile = File.createTempFile(label, ".tar")
            try {
                val processBuilder =
                    ProcessBuilder("docker", "save", imageName, "-o", tarFile.absolutePath)
                        .redirectErrorStream(true)
                processBuilder.environment().remove("DOCKER_HOST")

                val saveProcess = processBuilder.start()
                val output = saveProcess.inputStream.bufferedReader().readText()
                val saveExitCode = saveProcess.waitFor()
                if (saveExitCode != 0) {
                    logger.error("docker save failed: $output")
                    throw RuntimeException(
                        "Failed to save Docker image $imageName. " +
                            "Make sure to run: ./gradlew :test-services:$label:jibDockerBuild\nError: $output",
                    )
                }

                k3s!!.copyFileToContainer(MountableFile.forHostPath(tarFile.toPath()), "/tmp/$label.tar")

                val importResult =
                    k3s!!.execInContainer(
                        "ctr",
                        "images",
                        "import",
                        "/tmp/$label.tar",
                    )
                if (importResult.exitCode != 0) {
                    logger.error("Failed to import image: ${importResult.stderr}")
                    throw RuntimeException("Failed to import $label image into k3s: ${importResult.stderr}")
                }

                logger.info("Successfully loaded $label image into k3s")
            } finally {
                tarFile.delete()
            }
        }

        private fun waitForDeployment(
            client: KubernetesClient,
            namespace: String,
            name: String,
            timeout: Duration,
        ) {
            logger.info("Waiting for deployment $namespace/$name to be ready...")

            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val deployment =
                    client
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(name)
                        .get()

                val status = deployment?.status
                val ready = status?.readyReplicas ?: 0
                val desired = deployment?.spec?.replicas ?: 1

                if (ready >= desired) {
                    logger.info("Deployment $namespace/$name is ready ($ready/$desired replicas)")
                    return
                }

                Thread.sleep(2000)
            }

            // Debug output on timeout
            try {
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withLabel("app", name)
                        .list()
                        .items
                for (pod in pods) {
                    logger.error("Pod ${pod.metadata.name} status: ${pod.status.phase}")
                    pod.status.containerStatuses?.forEach { cs ->
                        logger.error("  Container ${cs.name}: ready=${cs.ready}, restartCount=${cs.restartCount}")
                        cs.state?.waiting?.let { w ->
                            logger.error("    Waiting: ${w.reason} - ${w.message}")
                        }
                        cs.state?.terminated?.let { t ->
                            logger.error("    Terminated: ${t.reason} - ${t.message}")
                        }
                    }
                    try {
                        val logs =
                            client
                                .pods()
                                .inNamespace(namespace)
                                .withName(pod.metadata.name)
                                .getLog()
                        logger.error("  Logs:\n$logs")
                    } catch (e: Exception) {
                        logger.error("  Could not get logs: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.error("Could not get pod details: ${e.message}")
            }

            throw RuntimeException("Timeout waiting for deployment $namespace/$name")
        }
    }
}
