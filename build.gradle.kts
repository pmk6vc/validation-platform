val kotlin_version: String by project
val logback_version: String by project
val ktor_version: String by project
val exposed_version = "0.57.0"

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
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
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml:$ktor_version")

    // Database (Exposed + PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.4")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}

tasks.test {
    useJUnitPlatform()

    // Run tests sequentially to avoid conflicts with shared database
    // All tests use the same PostgreSQL instance, so parallel execution
    // could cause race conditions even with @BeforeEach cleanup
    maxParallelForks = 1
}

tasks.register<Exec>("dockerUp") {
    group = "docker"
    description = "Build and start all containers"
    commandLine("sh", "-c", "docker compose -f deploy/docker-compose.yaml --env-file .env up --build -d")
}

tasks.register<Exec>("dockerDown") {
    group = "docker"
    description = "Stop and remove all containers"
    commandLine("sh", "-c", "docker compose -f deploy/docker-compose.yaml --env-file .env down")
}

ktlint {
    version.set("1.5.0")
}

ktlint {
    version.set("1.5.0")
}
