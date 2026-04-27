plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-test-fixtures`
}

group = "com.platform"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // JWT auth (shared across platform and collector)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.java.jwt)

    // Database (Exposed + PostgreSQL)
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)

    // Database migrations
    implementation(libs.flyway.core)

    // Connection pool
    implementation(libs.hikari)

    // Cloud SQL JDBC socket factory — used at runtime via socketFactory= URL param
    // when DATABASE_URL points at a Cloud SQL instance (Cloud Run deploys).
    // Pulled in here because DatabaseFactory drives connection creation.
    runtimeOnly(libs.cloud.sql.postgres.socket.factory)

    // GCP Secret Manager SDK — used by GcpSecretsProvider at runtime in GCP environments
    implementation(libs.google.cloud.secretmanager)

    // Test fixtures: shared test infrastructure for integration tests
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.testcontainers)
    testFixturesImplementation(libs.testcontainers.k3s)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.fabric8.kubernetes.client)
    testFixturesImplementation(libs.logback)
    // For TestJwtKeys (java-jwt) and authedTestApplication (Ktor test host + client)
    testFixturesImplementation(libs.java.jwt)
    testFixturesImplementation(libs.ktor.server.test.host)
    testFixturesImplementation(libs.ktor.client.cio)
    testFixturesImplementation(libs.ktor.client.content.negotiation)
    testFixturesImplementation(libs.ktor.serialization.json)

    // Tests: WorkloadTrafficIntegrationTest (validates test cluster)
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.k3s)
    // BouncyCastle is required for K3s EC keys in fabric8 kubernetes-client
    testImplementation(libs.bouncycastle)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
}

tasks.test {
    useJUnitPlatform()

    // Run tests from root project directory so paths to k8s/ manifests resolve correctly
    workingDir = rootProject.projectDir

    maxParallelForks = 1

    // Testcontainers configuration for Colima on macOS
    val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
    if (colimaSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }

    // Build test service Docker images before running k3s integration tests
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")
}
