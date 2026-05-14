# Technology Stack

**Analysis Date:** 2026-05-14

## Languages

**Primary:**
- Kotlin 2.2.21 - All application code (platform, collector, agent, test services, integration tests)

**Secondary:**
- Bash - Deployment and bootstrap scripts (`scripts/*.sh`)
- SQL - Database migrations and schema definitions (`shared/src/main/resources/db/migration/V*.sql`)
- HCL/Terraform - Infrastructure as Code (`infra/platform/*.tf`)
- YAML - Kubernetes manifests (`k8s/**/*.yaml`) and Ktor application config (`application.yaml`)

## Runtime

**Environment:**
- JVM - OpenJDK 21 (Eclipse Temurin)
- Gradle 8.11 - Build system

**Package Manager:**
- Gradle 8.11 with Kotlin DSL (`build.gradle.kts`)
- Lockfile: `gradle/wrapper/gradle-wrapper.jar` (Gradle wrapper for reproducible builds)
- Version catalog: `gradle/libs.versions.toml` (centralized dependency management)

## Frameworks

**Core Application:**
- Ktor 3.3.3 - Kotlin-native web framework (async/coroutines-first)
  - `ktor-server-core`, `ktor-server-netty` (HTTP server engines)
  - `ktor-server-auth`, `ktor-server-auth-jwt` (JWT authentication)
  - `ktor-server-content-negotiation`, `ktor-serialization-json` (JSON serialization)
  - `ktor-server-config-yaml` (YAML configuration parser)
  - `ktor-server-compression` (gzip on responses/requests)
  - `ktor-client-cio`, `ktor-client-websockets` (HTTP/WebSocket client for agent)
  - `ktor-server-test-host` (testing framework)

**Database:**
- Exposed 0.57.0 - Kotlin ORM and query DSL
  - `exposed-core` (core ORM)
  - `exposed-jdbc` (JDBC adapter)
  - `exposed-java-time` (Instant/temporal support)
  - `exposed-json` (JSONB column support)
- PostgreSQL 42.7.7 - JDBC driver
- Flyway 9.22.3 - Database schema migrations (V0001–V0007 in `shared/src/main/resources/db/migration/`)
- HikariCP 5.1.0 - Connection pooling (max pool size configurable via `DATABASE_POOL_SIZE` env var, default 10)

**Kubernetes & Distributed Systems:**
- Fabric8 Kubernetes Client 6.10.0 - K8s API access for agent service discovery (Loop 1)

**Authentication & JWT:**
- java-jwt (Auth0) 4.4.0 - RS256 JWT generation and validation
- BouncyCastle 1.79 - Cryptographic operations for RSA key handling and K3s EC key support

**Serialization:**
- kotlinx-serialization-json 1.7.3 - JSON serialization/deserialization (ignoreUnknownKeys = true for schema evolution)

**GCP Integration:**
- google-cloud-secretmanager 2.54.0 - Cloud Secret Manager SDK (runtime secret resolution)
- cloud-sql-postgres-socket-factory 1.21.0 - Cloud SQL JDBC socket factory for IAM authentication (Cloud Run only)

**Logging:**
- Logback 1.5.26 - Logging framework
- logstash-logback-encoder 8.1 - JSON logging for structured log aggregation

**Testing:**
- JUnit 5 (Jupiter) 5.10.0 - Test runner
- Kotlin test 2.2.21 - Kotlin testing utilities with JUnit 5 integration
- TestContainers 2.0.3 - Docker-based integration testing
  - `testcontainers-postgresql` (PostgreSQL in Docker)
  - `testcontainers-k3s` (K3s Kubernetes cluster in Docker)
  - `testcontainers-junit-jupiter` (JUnit 5 integration)
- MockK 1.13.9 - Kotlin mocking framework
- Ktor client mock - Mock HTTP responses for testing

**Build Tools:**
- Jib 3.4.4 - Containerized JAR builds (multi-architecture amd64/arm64 support)
  - Agent uses: `jibDockerBuild` for local development, images pushed to Artifact Registry in CI/CD
- ktlint 1.5.0 (plugin 12.1.2) - Kotlin code formatter and linter (applied to all modules except `test-services`)

## Key Dependencies

**Critical:**
- Ktor 3.3.3 - Foundation of all HTTP communication (platform, collector, agent, tests)
- PostgreSQL 16 - Transactional data store (organizations, services, captured inputs)
- Exposed 0.57.0 - Type-safe ORM queries (critical for data model integrity)
- Flyway 9.22.3 - Schema versioning and migrations
- java-jwt 4.4.0 - RS256 JWT validation in both platform and collector servers
- Fabric8 Kubernetes Client 6.10.0 - Enables agent Loop 1 (K8s service discovery)
- kotlinx-serialization-json 1.7.3 - Shared serialization for all API contracts

**Infrastructure:**
- HikariCP 5.1.0 - Connection pool management (tunable for Cloud Run concurrency)
- google-cloud-secretmanager 2.54.0 - Secure secret delivery in GCP production
- cloud-sql-postgres-socket-factory 1.21.0 - IAM-authenticated DB connections (Cloud Run)
- logback + logstash-logback-encoder - Structured logging for observability

**Development & Testing:**
- TestContainers 2.0.3 - Isolated integration tests without external services
- MockK 1.13.9 - Unit test mocking
- Gradle 8.11 - Reproducible builds via wrapper

## Configuration

**Environment Variables:**

**Database (both platform and collector):**
- `DATABASE_URL` - JDBC URL (default: `jdbc:postgresql://localhost:5432/platform`)
- `DATABASE_USER` - Postgres username (default: `postgres`)
- `DATABASE_PASSWORD` - Postgres password (read via SecretsProvider)
- `DATABASE_AUTH_MODE` - `password` (default) or `iam` (Cloud Run with Workload Identity)
- `DATABASE_POOL_SIZE` - HikariCP max pool size (default: 10)
- `DATABASE_CONNECTION_TIMEOUT_MS` - Connection timeout (default: 30,000 ms)

**Secrets & JWT:**
- `JWT_PRIVATE_KEY` - PEM-encoded RSA private key (pipes used for newlines in env vars)
- `SECRETS_PROVIDER` - `literal` (default, env vars) or `gcp` (Cloud Secret Manager)

**Agent-Specific:**
- `PLATFORM_URL` - Platform server URL for config polling (default: `http://platform.validation.svc.cluster.local:8080`)
- `COLLECTOR_URL` - Collector server URL for traffic ingestion (default: falls back to `PLATFORM_URL`)
- `API_KEY` - JWT bearer token for authentication (sourced from Kubernetes Secret `platform-api-key/jwt-token`)
- `KUBESHARK_URL` - Kubeshark WebSocket endpoint (default: `http://kubeshark-front.default:80`)

**Build:**
- Ktor application modules configured in `application.yaml` per module:
  - Platform: `ktor.deployment.port: 8080`, module `com.platform.ApplicationKt.module`
  - Collector: `ktor.deployment.port: 8081`, module `com.platform.collector.CollectorApplicationKt.module`

## Platform Requirements

**Development:**
- Java 21 (Eclipse Temurin JRE)
- Gradle 8.11 (via wrapper)
- Docker + Docker Compose (for `dockerUp` / `dockerDown`)
- Colima or Docker Desktop (macOS; TestContainers auto-detects Colima socket at `~/.colima/docker.sock`)
- kubectl (for Kubernetes test deployments and TAP/agent management)
- Terraform (for GCP infrastructure via `scripts/platform-up.sh`)
- gcloud CLI (for GCP authentication and Secret Manager access)

**Production (Cloud Run + Cloud SQL):**
- GCP Project with:
  - Cloud Run enabled (services: `validation-platform`, `validation-collector`)
  - Cloud SQL enabled (PostgreSQL 16 instance)
  - Cloud Secret Manager (stores `JWT_PRIVATE_KEY`)
  - Artifact Registry (stores container images)
  - Workload Identity (service account authentication to Cloud SQL via IAM)
- GKE cluster for agent + vp-tap DaemonSet deployment (Kubeshark integration)

**Local/Staging (Docker Compose + k3s):**
- Docker Compose (spins up PostgreSQL + platform + collector containers)
- k3s (TestContainers k3s cluster for integration tests)
- Local Kubernetes via minikube or Colima cluster

## Container Images

**Build Artifacts:**
- `validation-platform:test` - Built locally for e2e tests via `deploy/Dockerfile.platform`
  - Base: `eclipse-temurin:21-jre` (glibc, not alpine due to netty-tcnative)
  - Entry: `java -jar validation-platform.jar`
  - Runs as non-root user `platform`

- `validation-collector:test` - Built locally for e2e tests via `deploy/Dockerfile.collector`
  - Base: `eclipse-temurin:21-jre`
  - Entry: `java -jar collector.jar`
  - Runs as non-root user `collector`

- `validation-agent:latest` - Built via Jib from `agent/build.gradle.kts`
  - Base: `eclipse-temurin:21-jre-alpine`
  - Multi-arch: amd64 (default) + arm64 (auto-detected)
  - JVM flags: `-Xms32m -Xmx128m` (lightweight for sidecar deployment)
  - Entry: `com.platform.agent.AgentApplicationKt`
  - Runs as non-root user `agent`

- `vp-tap:prototype` - Privileged DaemonSet pod for eBPF traffic capture
  - Image: `us-central1-docker.pkg.dev/zugzwang-381922/validation/vp-tap:prototype`
  - Architecture: Go-based BPF program + Pod Informer for cgroup_id → pod metadata mapping

**Registry:**
- Artifact Registry: `us-central1-docker.pkg.dev/[PROJECT]/validation/`
  - Platform and collector images pushed by CI/CD with SHA tags
  - Agent image pushed by Jib or local build

---

*Stack analysis: 2026-05-14*
