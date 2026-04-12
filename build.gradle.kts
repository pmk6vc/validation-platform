plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.ktlint)
    jacoco
}

group = "com.platform"
version = "0.0.1"

repositories {
    mavenCentral()
}

val ktlintVersion = versionCatalogs.named("libs").findVersion("ktlint").get().requiredVersion

// Apply ktlint and JaCoCo to all subprojects (except test-services which have their own conventions)
subprojects {
    if (!path.startsWith(":test-services")) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "jacoco")

        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(ktlintVersion)
        }

        tasks.withType<JacocoReport> {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.withType<Test> {
            finalizedBy(tasks.withType<JacocoReport>())
            reports.junitXml.includeSystemOutLog.set(false)
            reports.junitXml.includeSystemErrLog.set(false)
        }
    }
}

// Aggregated JaCoCo coverage report across all platform modules
val platformModules = listOf(":shared", ":app", ":collector", ":agent")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Generates an aggregated JaCoCo coverage report for all platform modules"

    dependsOn(platformModules.map { "$it:jacocoTestReport" })

    executionData.setFrom(
        platformModules.map { modulePath ->
            fileTree(project(modulePath).layout.buildDirectory) {
                include("jacoco/test.exec")
            }
        },
    )

    sourceDirectories.setFrom(
        platformModules.map { modulePath ->
            files("${project(modulePath).projectDir}/src/main/kotlin")
        },
    )

    classDirectories.setFrom(
        platformModules.map { modulePath ->
            fileTree(project(modulePath).layout.buildDirectory) {
                include("classes/kotlin/main/**")
                exclude("**/generated/**")
            }
        },
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/jacocoAggregatedReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated/html"))
    }
}

// Project-level tasks (docker, test services deployment)
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

// Test services deployment to local Kubernetes cluster
tasks.register("testServicesBuild") {
    group = "test-services"
    description = "Build test service Docker images"
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")
}

tasks.register<Exec>("testServicesUp") {
    group = "test-services"
    description = "Deploy test services to local Kubernetes cluster"
    dependsOn("testServicesBuild")
    commandLine("kubectl", "apply", "-k", "k8s/test-services/base/")
    doLast {
        println(
            """
            |
            |Test services deployed! To check status:
            |  ./gradlew testServicesStatus
            |
            |To access the API Gateway:
            |  kubectl port-forward -n production svc/api-gateway 8080:8080
            |  curl http://localhost:8080/api/health
            |
            |To view logs:
            |  kubectl logs -n production -l app=api-gateway -f
            |  kubectl logs -n production -l app=traffic-generator -f
            """.trimMargin(),
        )
    }
}

tasks.register<Exec>("testServicesDown") {
    group = "test-services"
    description = "Remove test services from local Kubernetes cluster"
    commandLine("kubectl", "delete", "-k", "k8s/test-services/base/", "--ignore-not-found")
}

tasks.register<Exec>("testServicesStatus") {
    group = "test-services"
    description = "Show status of test services in local Kubernetes cluster"
    commandLine(
        "sh",
        "-c",
        """
        echo "=== Pods ===" &&
        kubectl get pods -n infrastructure -n production 2>/dev/null || echo "No pods found" &&
        echo "" &&
        echo "=== Services ===" &&
        kubectl get svc -n infrastructure -n production 2>/dev/null || echo "No services found"
        """.trimIndent(),
    )
}
