package com.platform.kubernetes

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.BeforeAll
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration

/**
 * Base class for Kubernetes integration tests with actual running workloads.
 *
 * ## Overview
 * Unlike [KubernetesTestBase] which only creates Service objects, this class deploys
 * actual pods that communicate with each other. This enables testing with realistic
 * traffic patterns suitable for Pixie integration.
 *
 * ## Deployed Workloads
 * - **PostgreSQL** (infrastructure namespace): Database with sample data
 * - **Redis** (infrastructure namespace): Cache server
 * - **API Gateway** (production namespace): Custom Ktor service that queries DB and cache
 * - **Traffic Generator** (production namespace): Continuous HTTP traffic to API Gateway
 *
 * ## Traffic Patterns
 * ```
 * traffic-generator --> api-gateway --> postgresql
 *                                   --> redis
 * ```
 *
 * ## Usage
 * ```kotlin
 * class MyPixieTest : KubernetesWorkloadTestBase() {
 *     @Test
 *     fun `test with real traffic`() {
 *         // Workloads are already running and generating traffic
 *         // Query Pixie for captured HTTP/TCP data
 *     }
 * }
 * ```
 *
 * ## Prerequisites
 * Before running tests, build the API Gateway image:
 * ```
 * ./gradlew :test-services:api-gateway:jibDockerBuild
 * ```
 *
 * ## Resource Requirements
 * - Docker with ~2GB available memory
 * - ~60 seconds for initial cluster + workload startup
 */
abstract class KubernetesWorkloadTestBase {
    companion object {
        private val logger = LoggerFactory.getLogger(KubernetesWorkloadTestBase::class.java)

        private var k3s: K3sContainer? = null
        private var initialized = false

        // Image name for the test API Gateway
        const val API_GATEWAY_IMAGE = "test-api-gateway:latest"

        @BeforeAll
        @JvmStatic
        fun setupClusterWithWorkloads() {
            if (initialized) return
            initialized = true

            logger.info("Starting k3s cluster with workloads...")

            // Start k3s cluster
            k3s =
                K3sContainer(DockerImageName.parse("rancher/k3s:v1.27.9-k3s1"))
                    .withLogConsumer(Slf4jLogConsumer(logger).withPrefix("k3s"))
                    .withCommand(
                        "server",
                        "--disable=traefik", // We don't need ingress for tests
                        "--disable=metrics-server", // Reduce resource usage
                    )
            k3s!!.start()

            val client = createKubernetesClient()
            try {
                // Create namespaces
                createNamespaces(client)

                // Deploy infrastructure
                deployPostgreSQL(client)
                deployRedis(client)

                // Wait for infrastructure to be ready
                waitForDeployment(client, "infrastructure", "postgresql", Duration.ofMinutes(2))
                waitForDeployment(client, "infrastructure", "redis", Duration.ofMinutes(1))

                // Load API Gateway image into k3s and deploy
                loadApiGatewayImage()
                deployApiGateway(client)

                // Deploy traffic generator
                deployTrafficGenerator(client)

                // Wait for application pods
                waitForDeployment(client, "production", "api-gateway", Duration.ofMinutes(2))
                waitForDeployment(client, "production", "traffic-generator", Duration.ofMinutes(1))

                logger.info("All workloads deployed and running")
            } finally {
                client.close()
            }
        }

        /**
         * Creates a Kubernetes client connected to the test k3s cluster.
         */
        fun createKubernetesClient(): KubernetesClient {
            if (k3s == null) {
                throw IllegalStateException("k3s container not initialized")
            }
            val config =
                io.fabric8.kubernetes.client.Config
                    .fromKubeconfig(k3s!!.kubeConfigYaml)
            return io.fabric8.kubernetes.client
                .KubernetesClientBuilder()
                .withConfig(config)
                .build()
        }

        /**
         * Get the k3s container for direct access if needed.
         */
        fun getK3sContainer(): K3sContainer = k3s ?: throw IllegalStateException("k3s container not initialized")

        private fun createNamespaces(client: KubernetesClient) {
            listOf("production", "infrastructure").forEach { ns ->
                client
                    .namespaces()
                    .resource(
                        io.fabric8.kubernetes.api.model
                            .NamespaceBuilder()
                            .withNewMetadata()
                            .withName(ns)
                            .endMetadata()
                            .build(),
                    ).serverSideApply()
                logger.info("Created namespace: $ns")
            }
        }

        /**
         * Deploy PostgreSQL with initial data.
         */
        private fun deployPostgreSQL(client: KubernetesClient) {
            val namespace = "infrastructure"

            // ConfigMap with init SQL
            client
                .configMaps()
                .inNamespace(namespace)
                .resource(
                    ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("postgres-init")
                        .withNamespace(namespace)
                        .endMetadata()
                        .addToData(
                            "init.sql",
                            """
                            -- Users table
                            CREATE TABLE IF NOT EXISTS users (
                                id SERIAL PRIMARY KEY,
                                name VARCHAR(100) NOT NULL,
                                email VARCHAR(100) NOT NULL
                            );

                            -- Orders table
                            CREATE TABLE IF NOT EXISTS orders (
                                id SERIAL PRIMARY KEY,
                                user_id INTEGER REFERENCES users(id),
                                total DECIMAL(10, 2) NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            );

                            -- Seed data
                            INSERT INTO users (name, email) VALUES
                                ('Alice Johnson', 'alice@example.com'),
                                ('Bob Smith', 'bob@example.com'),
                                ('Charlie Brown', 'charlie@example.com'),
                                ('Diana Prince', 'diana@example.com'),
                                ('Eve Wilson', 'eve@example.com');

                            INSERT INTO orders (user_id, total) VALUES
                                (1, 99.99),
                                (1, 149.50),
                                (2, 75.00),
                                (2, 200.00),
                                (3, 50.25),
                                (4, 300.00),
                                (5, 125.75);
                            """.trimIndent(),
                        ).build(),
                ).serverSideApply()

            // Deployment
            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .resource(
                    DeploymentBuilder()
                        .withNewMetadata()
                        .withName("postgresql")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .addToMatchLabels("app", "postgresql")
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .addToLabels("app", "postgresql")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("postgresql")
                        .withImage("postgres:16-alpine")
                        .addNewEnv()
                        .withName("POSTGRES_PASSWORD")
                        .withValue("testpass")
                        .endEnv()
                        .addNewEnv()
                        .withName("POSTGRES_DB")
                        .withValue("testdb")
                        .endEnv()
                        .addNewPort()
                        .withContainerPort(5432)
                        .endPort()
                        .addNewVolumeMount()
                        .withName("init-scripts")
                        .withMountPath("/docker-entrypoint-initdb.d")
                        .endVolumeMount()
                        .withNewResources()
                        .addToRequests(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("128Mi"),
                        ).addToRequests(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("100m"),
                        ).addToLimits(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("256Mi"),
                        ).addToLimits(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("500m"),
                        ).endResources()
                        .endContainer()
                        .addNewVolume()
                        .withName("init-scripts")
                        .withNewConfigMap()
                        .withName("postgres-init")
                        .endConfigMap()
                        .endVolume()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            // Service
            client
                .services()
                .inNamespace(namespace)
                .resource(
                    ServiceBuilder()
                        .withNewMetadata()
                        .withName("postgresql")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withType("ClusterIP")
                        .addToSelector("app", "postgresql")
                        .addNewPort()
                        .withName("postgresql")
                        .withPort(5432)
                        .withTargetPort(IntOrString(5432))
                        .endPort()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            logger.info("Deployed PostgreSQL")
        }

        /**
         * Deploy Redis cache.
         */
        private fun deployRedis(client: KubernetesClient) {
            val namespace = "infrastructure"

            // Deployment
            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .resource(
                    DeploymentBuilder()
                        .withNewMetadata()
                        .withName("redis")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .addToMatchLabels("app", "redis")
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .addToLabels("app", "redis")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("redis")
                        .withImage("redis:7-alpine")
                        .addNewPort()
                        .withContainerPort(6379)
                        .endPort()
                        .withNewResources()
                        .addToRequests(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("64Mi"),
                        ).addToRequests(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("50m"),
                        ).addToLimits(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("128Mi"),
                        ).addToLimits(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("200m"),
                        ).endResources()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            // Service
            client
                .services()
                .inNamespace(namespace)
                .resource(
                    ServiceBuilder()
                        .withNewMetadata()
                        .withName("redis")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withType("ClusterIP")
                        .addToSelector("app", "redis")
                        .addNewPort()
                        .withName("redis")
                        .withPort(6379)
                        .withTargetPort(IntOrString(6379))
                        .endPort()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            logger.info("Deployed Redis")
        }

        /**
         * Load the API Gateway Docker image into k3s.
         *
         * k3s runs in a container, so we need to transfer the image into its containerd.
         * We do this by saving the image to a tar file and importing it via ctr.
         */
        private fun loadApiGatewayImage() {
            logger.info("Loading API Gateway image into k3s...")

            // Save the Docker image to a tar file
            val tarFile = File.createTempFile("api-gateway", ".tar")
            try {
                // Don't use DOCKER_HOST from test environment - use default Docker context
                // The image was built with Jib which uses the default Docker daemon
                val processBuilder =
                    ProcessBuilder("docker", "save", API_GATEWAY_IMAGE, "-o", tarFile.absolutePath)
                        .redirectErrorStream(true)

                // Remove DOCKER_HOST to use default Docker context (where Jib built the image)
                processBuilder.environment().remove("DOCKER_HOST")

                val saveProcess = processBuilder.start()
                val output = saveProcess.inputStream.bufferedReader().readText()
                val saveExitCode = saveProcess.waitFor()
                if (saveExitCode != 0) {
                    logger.error("docker save failed: $output")
                    throw RuntimeException(
                        "Failed to save Docker image. Make sure to run: ./gradlew :test-services:api-gateway:jibDockerBuild\nError: $output",
                    )
                }

                // Copy the tar file into the k3s container
                k3s!!.copyFileToContainer(MountableFile.forHostPath(tarFile.toPath()), "/tmp/api-gateway.tar")

                // Import the image using ctr
                val importResult =
                    k3s!!.execInContainer(
                        "ctr",
                        "images",
                        "import",
                        "/tmp/api-gateway.tar",
                    )
                if (importResult.exitCode != 0) {
                    logger.error("Failed to import image: ${importResult.stderr}")
                    throw RuntimeException("Failed to import image into k3s: ${importResult.stderr}")
                }

                logger.info("Successfully loaded API Gateway image into k3s")
            } finally {
                tarFile.delete()
            }
        }

        /**
         * Deploy the API Gateway service.
         */
        private fun deployApiGateway(client: KubernetesClient) {
            val namespace = "production"

            // Deployment
            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .resource(
                    DeploymentBuilder()
                        .withNewMetadata()
                        .withName("api-gateway")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .addToMatchLabels("app", "api-gateway")
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .addToLabels("app", "api-gateway")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("api-gateway")
                        .withImage("docker.io/library/$API_GATEWAY_IMAGE")
                        .withImagePullPolicy("Never") // Use local image
                        .addNewEnv()
                        .withName(
                            "POSTGRES_HOST",
                        ).withValue("postgresql.infrastructure.svc.cluster.local")
                        .endEnv()
                        .addNewEnv()
                        .withName("POSTGRES_PORT")
                        .withValue("5432")
                        .endEnv()
                        .addNewEnv()
                        .withName("POSTGRES_DB")
                        .withValue("testdb")
                        .endEnv()
                        .addNewEnv()
                        .withName("POSTGRES_USER")
                        .withValue("postgres")
                        .endEnv()
                        .addNewEnv()
                        .withName("POSTGRES_PASSWORD")
                        .withValue("testpass")
                        .endEnv()
                        .addNewEnv()
                        .withName("REDIS_HOST")
                        .withValue("redis.infrastructure.svc.cluster.local")
                        .endEnv()
                        .addNewEnv()
                        .withName("REDIS_PORT")
                        .withValue("6379")
                        .endEnv()
                        .addNewPort()
                        .withContainerPort(8080)
                        .endPort()
                        .withNewResources()
                        .addToRequests(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("256Mi"),
                        ).addToRequests(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("100m"),
                        ).addToLimits(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("512Mi"),
                        ).addToLimits(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("500m"),
                        ).endResources()
                        .withNewReadinessProbe()
                        .withNewHttpGet()
                        .withPath("/api/health")
                        .withPort(IntOrString(8080))
                        .endHttpGet()
                        .withInitialDelaySeconds(10)
                        .withPeriodSeconds(5)
                        .endReadinessProbe()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            // Service
            client
                .services()
                .inNamespace(namespace)
                .resource(
                    ServiceBuilder()
                        .withNewMetadata()
                        .withName("api-gateway")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withType("ClusterIP")
                        .addToSelector("app", "api-gateway")
                        .addNewPort()
                        .withName("http")
                        .withPort(8080)
                        .withTargetPort(IntOrString(8080))
                        .endPort()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            logger.info("Deployed API Gateway")
        }

        /**
         * Deploy traffic generator that continuously hits the API Gateway.
         */
        private fun deployTrafficGenerator(client: KubernetesClient) {
            val namespace = "production"

            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .resource(
                    DeploymentBuilder()
                        .withNewMetadata()
                        .withName("traffic-generator")
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .addToMatchLabels("app", "traffic-generator")
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .addToLabels("app", "traffic-generator")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("traffic-gen")
                        .withImage("curlimages/curl:latest")
                        .withCommand("/bin/sh", "-c")
                        .withArgs(
                            """
                            echo "Waiting for API Gateway to be ready..."
                            sleep 30
                            echo "Starting traffic generation..."
                            while true; do
                              # Health check
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/health

                              # List all users (cache miss then hit)
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/users

                              # Get individual users
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/users/1
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/users/2
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/users/3

                              # List all orders (database join)
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/orders

                              # Get orders by user
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/orders/1
                              curl -s http://api-gateway.production.svc.cluster.local:8080/api/orders/2

                              # Sleep between rounds
                              sleep 2
                            done
                            """.trimIndent(),
                        ).withNewResources()
                        .addToRequests(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("32Mi"),
                        ).addToRequests(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("10m"),
                        ).addToLimits(
                            "memory",
                            io.fabric8.kubernetes.api.model
                                .Quantity("64Mi"),
                        ).addToLimits(
                            "cpu",
                            io.fabric8.kubernetes.api.model
                                .Quantity("100m"),
                        ).endResources()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build(),
                ).serverSideApply()

            logger.info("Deployed Traffic Generator")
        }

        /**
         * Wait for a deployment to be ready.
         */
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

            // Get pod logs for debugging
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
                    // Try to get logs
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
