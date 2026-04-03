plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

group = "com.platform"
version = "0.0.1"

application {
    mainClass.set("com.platform.collector.CollectorApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("collector.jar")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Logging
    implementation(libs.logback)
}
