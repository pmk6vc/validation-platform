val exposed_version = "0.57.0"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
}
