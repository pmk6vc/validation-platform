val kotlin_version: String by project
val logback_version: String by project
val ktor_version: String by project
val exposed_version = "0.57.0"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

group = "com.platform"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

ktor {
    fatJar {
        archiveFileName.set("validation-platform.jar")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml:$ktor_version")

    // Database (Exposed + PostgreSQL) - needed for app-owned tables and repositories
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.7")

    // Kubernetes
    implementation("io.fabric8:kubernetes-client:6.10.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Testing
    testImplementation(testFixtures(project(":shared")))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.3")
    testImplementation("io.mockk:mockk:1.13.9")
    // BouncyCastle is required for K3s EC keys in fabric8 kubernetes-client
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // Ktor client for integration tests
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")
}

tasks.test {
    useJUnitPlatform()

    // Run tests from root project directory so paths to k8s/ manifests resolve correctly
    workingDir = rootProject.projectDir

    // Run tests sequentially to avoid conflicts with shared database
    maxParallelForks = 1

    // Testcontainers configuration for Colima on macOS
    val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
    if (colimaSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }

    // Build test service Docker images before running integration tests
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")
}
