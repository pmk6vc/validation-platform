val kotlin_version: String by project
val ktor_version: String by project
val exposed_version = "0.57.0"

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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Database (Exposed + PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.7")

    // Database migrations
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Test fixtures: shared test infrastructure for k3s integration tests
    testFixturesImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testFixturesImplementation("org.testcontainers:testcontainers:2.0.3")
    testFixturesImplementation("org.testcontainers:testcontainers-k3s:2.0.3")
    testFixturesImplementation("io.fabric8:kubernetes-client:6.10.0")
    testFixturesImplementation("ch.qos.logback:logback-classic:1.5.26")

    // Tests: WorkloadTrafficIntegrationTest (validates test cluster)
    testImplementation(testFixtures(project(":shared")))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-k3s:2.0.3")
    // BouncyCastle is required for K3s EC keys in fabric8 kubernetes-client
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
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
