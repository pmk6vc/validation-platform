pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
        id("io.ktor.plugin") version "3.3.3"
        id("com.google.cloud.tools.jib") version "3.4.4"
        id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    }
}

rootProject.name = "validation-platform"

// Test services - standalone microservices for k3s integration testing
include("test-services:api-gateway")
include("test-services:traffic-generator")
