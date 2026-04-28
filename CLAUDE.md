# CLAUDE.md - Validation & Release Platform

## Project Overview

This is a **validation and release platform** that helps engineering teams validate code changes against real production traffic before deployment. The platform captures live traffic, replays it against candidate versions, and statistically compares behavior to detect regressions—including performance degradation, memory leaks, and behavioral changes.

### Core Value Proposition

> **"Validate every change against real production traffic before it hits production."**

1. **"What services do I have and how do they connect?"** - Automatic topology discovery
2. **"If I change service X, what's affected?"** - Blast radius analysis
3. **"Does this change break anything?"** - Traffic replay with statistical comparison
4. **"Is something leaking or degrading?"** - Resource monitoring during validation
5. **"What does normal look like?"** - Baseline learning
6. **"Is something wrong right now?"** - Anomaly detection

### Key Differentiators

| What We Do | Why It's Different |
|------------|-------------------|
| **Replay real production traffic** | Synthetic load tests miss edge cases; we use actual requests |
| **Statistical comparison (control vs candidate)** | Not threshold-based; compare against current version simultaneously |
| **Resource trend detection** | Detect memory leaks and CPU growth under realistic load |
| **Blast radius at PR time** | CI tools don't know your service topology |
| **Zero instrumentation for capture** | eBPF-based capture (Kubeshark) requires no code changes in production |

### What This Catches That Unit Tests Don't

| Issue Type | Unit Tests | Synthetic Load | Our Approach |
|------------|------------|----------------|--------------|
| Logic bugs | Yes | Yes | Yes |
| Memory leaks | No | Sometimes | Yes |
| N+1 queries with real data | No | No | Yes |
| Connection pool exhaustion | No | Sometimes | Yes |
| Cache miss storms | No | No | Yes |
| Hot key/partition issues | No | No | Yes |
| Payload-specific edge cases | No | No | Yes |

---

## Current Implementation Status

### What's Working Now

- **Two Ktor servers**: `platform` on port 8080 (organizations, services) and `collector` on port 8081 (captured inputs)
- **RS256 JWT auth in-app**: both `platform` and `collector` validate JWT tokens directly via the shared `installJwtAuth()` extension; no reverse proxy in the request path
- **PostgreSQL database** with Flyway migrations (V0001–V0006), all migrations in `shared/`
- **Multi-tenant data model** with Organizations and Services (owned by `platform`); `OrganizationId` and `ServiceId` value classes for type safety (both defined in `shared/`)
- **CapturedInput model** (owned by `collector`) — HTTP-first, non-nullable method/url/responseStatus; no DB-level FK to services (decoupled at DB layer since V0006)
- **Collector batch ingest** — `POST /api/captured-inputs` accepts `BatchCreateCapturedInputRequest` from the agent
- **JWT auth**: platform generates tokens via `JwtTokenGenerator` (`./gradlew :platform:generateToken`); platform serves public key at `/.well-known/jwks.json`; both servers validate RS256 JWT from the `Authorization: Bearer` header on all `/api/*` routes
- **Pagination and filtering** on all list endpoints (cursor-based); limit clamping tested (0, -1 → 1; >100 → 100)
- **Docker deployment** — platform, collector, and db start by default; health checks on all services
- **Test infrastructure** with TestContainers (PostgreSQL + k3s Kubernetes)
- **Code quality** with ktlint
- **Adapter pattern** with ServiceAdapter interface
- **Service discovery** via ManualSeedAdapter and KubernetesAdapter (implements `Closeable`)
- **Provider tracking** (UNKNOWN, MANUAL_SEED, KUBERNETES)
- **Modular monolith** with enforced module boundaries: cross-module data access goes through REST APIs, not shared repositories; no DB-level FK between modules
- **Validation agent** — three-loop Kotlin process deployed to customer cluster; streams traffic from Kubeshark WebSocket with server-side KFL filtering, samples, and pushes directly to platform (8080) and collector (8081) with a JWT bearer token; file-based liveness probe; non-root container; API key stored in Kubernetes Secret
- **E2E tests** — `e2e-tests/` module tests the full platform stack (platform + collector) using TestContainers

### Module Ownership

Each module owns its tables, models, and repositories. Cross-module communication is via HTTP API calls, with no DB-level foreign keys across modules.

| Module | Owns | Port |
|--------|------|------|
| `shared/` | DatabaseFactory, Flyway migrations, shared models (Page, InstantSerializer), `AgentIdentity`, `installJwtAuth()`, `OrganizationId`/`ServiceId` value classes; test fixtures: `DatabaseTestBase`, `KubernetesWorkloadTestBase`, `TestJwtKeys`, `authedTestApplication` | — |
| `platform/` | Organizations, Services tables; OrganizationRepository, ServiceRepository; Ktor server; JWKS endpoint; JWT token generator | 8080 |
| `collector/` | CapturedInputs table; CapturedInputRepository; Ktor server | 8081 |
| `agent/` | Kubeshark polling, K8s service discovery, traffic capture and forwarding directly to platform (8080) and collector (8081) | — (standalone process) |
| `e2e-tests/` | End-to-end tests for the full platform stack (platform + collector) | — |
| `test-services/` | Standalone Kotlin microservices for k3s integration testing | — |

### Platform Module API Endpoints (port 8080)

```
GET    /health                             # Health check (no auth)
GET    /.well-known/jwks.json              # RSA public key (no auth)
GET    /api/organizations                  # List organizations (paginated) — requires JWT
POST   /api/organizations                  # Create organization — requires JWT
GET    /api/organizations/{id}             # Get organization by ID — requires JWT
GET    /api/services                       # List services (paginated, filterable) — requires JWT
POST   /api/services                       # Create service — requires JWT
GET    /api/services/{id}                  # Get service by ID — requires JWT
GET    /api/agent/config                   # Agent dynamic config (target services for the JWT's org+cluster) — requires JWT
```

### Collector Module API Endpoints (port 8081)

```
GET    /health                                    # Health check (no auth)
POST   /api/captured-inputs                       # Ingest a batch of captured inputs — requires JWT
GET    /api/captured-inputs                       # List captured inputs (paginated) — requires JWT
GET    /api/captured-inputs/{id}                  # Get captured input by ID — requires JWT
DELETE /api/captured-inputs?serviceId={id}        # Delete all captured inputs for a service — requires JWT
```

### JWT Authentication

Both servers validate RS256 JWT tokens directly using `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt`.

- `installJwtAuth(privateKeyPem)` — shared Ktor extension; installs the JWT plugin, derives the RSA public key from the provided PEM, validates `organizationId` (UUID) and `cluster` claims, and populates `call.principal<AgentIdentity>()`
- `AgentIdentity(organizationId, cluster, role?)` — the resolved principal; defined in `shared/`, available in both servers
- Required claims: `organizationId` (UUID string), `cluster` (string)
- Optional claims: `role` (string)
- Both servers read `JWT_PRIVATE_KEY` from the environment (PEM-encoded RSA private key, `|` used in place of newlines in env vars)
- `/health` and `/.well-known/*` are unauthenticated; all `/api/*` routes require a valid bearer token

The platform still serves `/.well-known/jwks.json` (RSA public key in JWK format) for any external clients that need it.

### Current Data Models

```kotlin
// --- platform module ---

// OrganizationId / ServiceId — inline value classes, UUID-validated at construction
// shared/src/main/kotlin/com/platform/shared/models/Ids.kt
@JvmInline value class OrganizationId(val value: String)
@JvmInline value class ServiceId(val value: String)

// Organization - a tenant/team in the platform
data class Organization(
    val id: OrganizationId,
    val name: String,
    val createdAt: Instant,
)

// Service - a deployable unit discovered from various providers
// Uniquely identified by: organizationId + cluster + namespace + name
data class Service(
    val id: ServiceId,
    val organizationId: OrganizationId,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val discoveredAt: Instant,
    val lastSeenAt: Instant,
    val metadata: Map<String, String>? = null,
)

enum class Provider { UNKNOWN, MANUAL_SEED, KUBERNETES }

// --- collector module ---

// CapturedInput - an HTTP req/res pair captured from production traffic
// HTTP-only for now; method, url, responseStatus are non-nullable
// serviceId is a plain String — no DB-level FK to services table (decoupled at DB layer)
data class CapturedInput(
    val id: String,
    val serviceId: String,
    val inputType: InputType,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: Instant,
)

// InputType - HTTP-first; KAFKA and PUBSUB deferred (YAGNI)
enum class InputType { HTTP, UNKNOWN }
```

### Request/Response DTOs

```kotlin
// platform module — platform/src/main/kotlin/com/platform/api/Requests.kt
data class CreateOrganizationRequest(val name: String)
data class CreateServiceRequest(
    val organizationId: OrganizationId,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val metadata: Map<String, String>? = null,
)

// Response for GET /api/agent/config — matches the agent's DynamicConfig wire format
data class AgentConfigResponse(
    val targetServices: Map<String, String> = emptyMap(), // service name → service ID
    val samplingRate: Double = 1.0,
    val batchSize: Int = 100,
    val captureInterval: Long = 5000,    // millis
    val configPollInterval: Long = 30000, // millis
    val discoveryInterval: Long = 60000,  // millis
    val namespaceFilters: List<String> = emptyList(),
)

// collector module — collector/src/main/kotlin/com/platform/collector/models/
data class CreateCapturedInputRequest(
    val serviceId: String,
    val inputType: InputType = InputType.HTTP,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: Instant,
)
data class BatchCreateCapturedInputRequest(val items: List<CreateCapturedInputRequest>)
data class BatchCreateCapturedInputResponse(val created: Int)
```

### Development Setup

```bash
# Prerequisites (macOS): Install Colima for TestContainers
# Colima provides a Docker runtime compatible with both TestContainers and Jib.
# build.gradle.kts auto-detects Colima's socket.
brew install colima docker && colima start

# Start all services (platform + collector + db)
./gradlew dockerUp

# Run application servers individually
./gradlew :platform:run    # platform server on port 8080
./gradlew :collector:run   # collector server on port 8081

# Generate a JWT for testing (reads JWT_PRIVATE_KEY env var)
./gradlew :platform:generateToken --args="--org <uuid> --cluster <name>"

# Run tests
./gradlew test

# Lint code
./gradlew ktlintCheck
```

**Module structure:**
- `shared/` — DatabaseFactory, Flyway migrations (`V0001–V0006`), shared models (Page, InstantSerializer, `OrganizationId`, `ServiceId`), JWT auth library (`AgentIdentity`, `installJwtAuth()`, `derivePublicKey()`); exposes `java-test-fixtures` with `DatabaseTestBase`, `KubernetesWorkloadTestBase`, `TestJwtKeys` (consolidated test JWT keypair), and `authedTestApplication` (Ktor test app helper that wires JWT auth)
- `platform/` — Ktor API server on port 8080; owns Organizations + Services tables, repositories, adapters, routes; JWKS endpoint; depends on `:shared`
- `collector/` — Ktor API server on port 8081; owns CapturedInputs table, repository, routes; depends on `:shared`; uses `application.yaml` (Ktor 3 YAML config)
- `agent/` — Standalone Kotlin process deployed to customer K8s clusters; polls Kubeshark + K8s API, pushes to platform (config) and collector (traffic) directly via JWT; no dependency on `shared/`, `platform/`, or `collector/` (API contract only)
- `e2e-tests/` — Integration tests for the full stack (platform + collector + DB) using TestContainers
- `test-services/` — Standalone Kotlin microservices for k3s integration testing

**Note on collector config:** The collector uses `application.yaml` (not HOCON `.conf`). This is required by Ktor 3's YAML config parser.

**Optional:** Deploy test workloads to local Kubernetes for manual testing:
```bash
./gradlew testServicesUp              # Deploy test services
./gradlew testServicesStatus          # Check status
./gradlew testServicesDown            # Remove test services
```

### Cloud SQL admin bootstrap (one-time per DB lifetime)

Cloud Run uses IAM auth as the platform SA — no static DB password. Granting
the SA ownership of the `public` schema (so Flyway can create tables) and
granting engineers `cloudsqlsuperuser` for break-glass access requires one
privileged SQL session. That session is bootstrapped by:

```bash
# After a brand-new platform-up (or after the validation DB is recreated):
cp infra/platform/terraform.tfvars.example infra/platform/terraform.tfvars
# edit terraform.tfvars to add your email under db_admin_users
./scripts/platform-up.sh                       # creates IAM users + bindings
./scripts/bootstrap-db.sh                      # one-time in-DB grants
```

`bootstrap-db.sh` briefly sets a random password on `postgres`, runs the
grants via `cloud-sql-proxy`, then rotates the password to another random
value nobody knows. After it finishes, all admin DB access uses IAM auth via
`cloud-sql-proxy --auto-iam-authn` — no static credential exists anywhere.

To add another engineer admin later: add their email to `terraform.tfvars`,
re-run `platform-up.sh`, then re-run `bootstrap-db.sh` (idempotent).

---

## Architecture Vision

### High-Level Design

```
CUSTOMER'S PRODUCTION CLUSTER
┌─────────────────────────────────────────────────┐
│  Kubeshark (eBPF)      Validation Agent         │
│  captures HTTP ───────► (3 loops):              │
│  req/res pairs          1. K8s API → discover   │
│                            services → register  │
│  order-service          2. Poll platform for    │
│  api-gateway               config (sampling,    │
│  notification-svc          namespace filters)   │
│                         3. Poll Kubeshark →     │
│                            filter + sample →    │
│                            POST to collector    │
└──────────────┬────────────────┬─────────────────┘
               │ JWT (config)   │ JWT (captured inputs)
               ▼                ▼
PLATFORM
┌────────────────────────────────────────────────────────────────────┐
│  Platform (8080)             Collector (8081)                      │
│  RS256 JWT in-app            RS256 JWT in-app                      │
│  Organizations               CapturedInputs                        │
│  Services                    POST (agent ingest)                   │
│  JWKS endpoint               GET/DELETE                            │
│  Agent config                                                       │
│                                                                    │
│  Replay Engine (planned):                                          │
│  1. Fetch captured inputs from collector                           │
│  2. Replay against staging (read-only default)                     │
│  3. Observe via Kubeshark in staging + K8s metrics                 │
│  4. Compare baseline vs candidate → verdict                        │
└────────────────────────────────────────────────────────────────────┘

CUSTOMER'S STAGING CLUSTER
┌──────────────────────────────────────────┐
│  Kubeshark (eBPF)    target service      │
│  observes replay     (baseline or        │
│  traffic             candidate)          │
│                      ↓ real connections   │
│                      staging-db, kafka    │
└──────────────────────────────────────────┘
```

### Validation Flow

```
1. CAPTURE (production, continuous, push model)
   Kubeshark eBPF captures HTTP request/response pairs at L7
   Validation agent polls Kubeshark, filters by registered services, samples, and pushes directly to collector (8081)

2. BASELINE RUN (staging, current version)
   Replay captured read traffic against current version in staging
   Kubeshark in staging observes outbound connections, call patterns
   K8s Metrics API collects pod CPU/memory

3. CANDIDATE RUN (staging, PR branch version)
   Deploy candidate to staging, replay same captured traffic
   Same observation via Kubeshark + K8s Metrics

4. COMPARE
   Response diffs, latency (Mann-Whitney U), error rates,
   outbound connection counts, memory trends (linear regression)

5. VERDICT
   PASS / FAIL / INCONCLUSIVE with evidence
```

### Design Principles

1. **Staging-based validation**: Customer provides staging environments with real dependencies. Platform captures production traffic and replays it against staging — no dependency mocking needed.
2. **Read-only replay by default**: Only replay safe (read) requests to avoid mutating staging DB state between sequential runs. Full replay available when customer provides a DB reset hook.
3. **HTTP-first, protocol-extensible model**: `CapturedInput` uses an `InputType` enum (`HTTP`, `UNKNOWN`). KAFKA and PUBSUB variants are intentionally deferred (YAGNI).
4. **Module ownership via APIs**: Each module owns its tables and repositories. No DB-level FKs across module boundaries. Cross-module access goes through REST API calls.
5. **eBPF for capture and observation**: Kubeshark in production for traffic capture, Kubeshark in staging for observability during replay.
6. **Statistical rigor**: Use proper statistical tests (Mann-Whitney U, linear regression), not arbitrary thresholds.

### Staging-Based Validation (Architectural Pivot)

The original design used PCAP-based record-replay proxies to mock all dependencies in an isolated namespace. A **TLS blocker** was discovered: production databases (RDS, CloudSQL) use TLS, and Kubeshark's eBPF hooks cannot capture the Postgres wire protocol through TLS. Building protocol-specific proxies for every database flavor adds months of complexity.

**The pivot**: require customers to provide staging environments with real dependencies already wired up. The platform focuses on what it does uniquely well — capture real traffic, replay it, compare behavior.

### Validation Agent (Push Model)

The agent runs in the customer's K8s cluster as a standalone Kotlin process. It pushes data to the platform — the platform never reaches into the customer's cluster.

**Three independent coroutine loops:**

| Loop | Interval | Responsibility |
|------|----------|----------------|
| Service discovery | ~60s | Query K8s API for services → diff against in-memory map → register new services with platform via `POST /api/services` → receive service ID map |
| Config polling | ~60s | `GET /api/agent/config` → update sampling rate, namespace filters, batch size, poll interval |
| Traffic capture | continuous | Drain up to batchSize entries from `KubesharkClient`'s persistent WebSocket channel → filter by target services → sample → `POST /api/captured-inputs` to collector |

**Concurrency model:** Config is stored in a `MutableStateFlow<DynamicConfig>`. `KubesharkClient` and `TrafficTransformer` observe this flow directly. No locks, no coordination.

**Static config (env vars, set at deploy time):**
- `PLATFORM_URL` — platform server URL (e.g., `http://platform.validation.svc.cluster.local:8080`)
- `COLLECTOR_URL` — collector server URL (e.g., `http://collector.validation.svc.cluster.local:8081`); falls back to `PLATFORM_URL` if unset
- `API_KEY` — JWT bearer token (stored in Kubernetes Secret `platform-api-key`, key `jwt-token`)
- `KUBESHARK_URL` — in-cluster Kubeshark front URL (default: `http://kubeshark-front.default:80`)

**Dynamic config (polled from platform):**
- Sampling rate per service, namespace filters, batch size, poll intervals

**Kubeshark WebSocket transport:**
- Kubeshark v53+ serves traffic exclusively over WebSocket at `/api/wsFull`. The REST `/api/entries` endpoint was removed.
- The server accepts a KFL (Kubeshark Filter Language) query as the first text frame. `KubesharkClient.buildKflQuery()` sends `http` or `http and (dst.name == X or ...)`. When no target services are configured, the query is `"http"`.
- Entries arrive as HAR-ish JSON frames: request body at `request.postData.text` (plaintext); response body at `response.content.text` is base64-encoded when `content.encoding == "base64"` — the agent decodes before forwarding.
- **Persistent session**: `KubesharkClient` maintains a single long-lived WebSocket. A bounded `Channel<KubesharkEntry>` (capacity 1000) buffers entries with backpressure — `Channel.send` suspends when full, which propagates TCP backpressure to Kubeshark. The agent never OOMs under load.
- **Reactive KFL updates**: `KubesharkClient` observes the `StateFlow<DynamicConfig>` via a `configWatcherJob`. When `targetServices` changes it immediately cancels and reconnects with the updated KFL query.
- **Reconnect dedup**: tracks `lastSeenTimestamp` with a 5s lookback window. Entries older than `lastSeen - 5s` are dropped as reconnect-replay noise, covering observed in-session out-of-order jitter.

**Agent module structure:**
```
agent/
  src/main/kotlin/com/platform/agent/
    AgentApplication.kt        # main, three coroutine loops; shared MutableStateFlow<DynamicConfig>
    AgentConfig.kt             # StaticConfig (env vars), DynamicConfig (polled), DurationAsMillisSerializer
    KubesharkClient.kt         # Persistent WebSocket client; observes StateFlow for KFL query updates
    CollectorClient.kt         # HTTP client for collector POST with exponential-backoff retry
    ConfigClient.kt            # HTTP client for platform GET /api/agent/config
    TrafficTransformer.kt      # Kubeshark → CapturedInputRequest (filters + base64 decode); observes StateFlow
    models/
      KubesharkEntry.kt        # Kubeshark WebSocket wire-format DTOs (HAR-ish)
      CapturedInputRequest.kt  # Collector POST payload DTOs (BatchCapturedInputRequest)
```

**Deployment artifacts:**
- `deploy/Dockerfile.agent` — multi-stage Dockerfile (non-root user via `USER agent`)
- `agent/build.gradle.kts` — Jib plugin config for building `validation-agent:latest`
- `k8s/agent/agent.yaml` — Kubernetes Deployment (namespace `validation`, single replica); `API_KEY` from `secretKeyRef: platform-api-key/jwt-token`; file-based liveness probe on `/tmp/agent-alive`

### Read/Write Traffic Classification (Planned — Not Yet Implemented)

The `TrafficClassifier` was removed as premature — it belongs in the replay engine.

| Protocol | Read | Write | Reliability |
|---|---|---|---|
| HTTP REST | `GET`, `HEAD` | `POST`, `PUT`, `PATCH`, `DELETE` | ~95% |
| gRPC | Method name: `Get*`, `List*`, `Search*`, `Find*`, `Query*` | Everything else | ~80% |
| GraphQL | `query` in body | `mutation` in body | ~99% |

Conservative default: ambiguous = write = skip. User can override specific endpoints.

### Message Queue Support (Future, De-Risked)

Message queues use built-in fan-out for safe capture: Kafka (separate consumer group), Pub/Sub (mirror subscription), SNS (capture SQS subscriber), RabbitMQ (bind to same exchange). If the message producer is a service already replaying HTTP to, Kafka messages flow naturally as a side effect in staging.

### What Customers Provide

| Requirement | Required? | Details |
|---|---|---|
| Staging cluster | Yes | With real dependencies (DB, queues, caches) wired up |
| Kubeshark access | Yes | Platform deploys/manages in both clusters |
| Deployment mechanism | Yes | How to deploy candidate to staging (image tag, Helm, kustomize) |
| Endpoint classification | Optional | Mark ambiguous endpoints as safe/mutating |
| DB reset hook | Optional | Enables full replay including writes |

### Adapter Implementation Status

**ServiceAdapter Interface** (`platform/src/main/kotlin/com/platform/adapters/ServiceAdapter.kt`):
```kotlin
interface ServiceAdapter {
    suspend fun discoverServices(organizationId: String): List<Service>
}
```

**Implemented Adapters:**
1. **ManualSeedAdapter** — 8 hardcoded services for testing without external dependencies
2. **KubernetesAdapter** — Discovers services from Kubernetes via the API. Supports in-cluster, KUBECONFIG, and `~/.kube/config`. Implements `Closeable`.
3. **KubesharkAdapter** — Planned

**Test Infrastructure:**

- `KubernetesWorkloadTestBase` — spins up k3s cluster with test workloads using TestContainers
- 3 namespaces: `infrastructure`, `production`, `external`; 7 discoverable K8s Services

```
traffic-generator → api-gateway → order-service → orders-db (PostgreSQL)
                                → Redis (cache)   → Kafka (produce: order-events)
Kafka (consume: order-events) → notification-service → webhook-stub (external)
```

| Namespace | Services |
|-----------|----------|
| infrastructure | orders-db (PostgreSQL 16), redis (7-alpine), kafka (apache/kafka:3.7.0, KRaft mode) |
| production | api-gateway, order-service, notification-service, traffic-generator (no Service resource) |
| external | webhook-stub |

---

## Tech Stack

- **Language**: Kotlin — coroutines, data classes, strong typing
- **Framework**: Ktor — Kotlin-native, coroutines-first, lightweight
- **Database**: PostgreSQL with Exposed ORM + Flyway migrations
- **Auth**: RS256 JWT — platform generates tokens, serves JWKS; both app servers validate directly via shared `installJwtAuth()` library
- **Key Libraries**: Ktor, Exposed + PostgreSQL, Fabric8 Kubernetes Client, TestContainers
- See `build.gradle.kts` for the complete dependency list

---

## Data Models

| Model | Purpose | Module | Status |
|-------|---------|--------|--------|
| Organization | Tenant/team in the platform | `platform` | Implemented |
| Service | Deployable unit discovered from various providers | `platform` | Implemented |
| CapturedInput | HTTP req/res pair captured from production traffic | `collector` | Implemented |
| ReplayRun | A replay run against staging (config, status, collected responses) | TBD (likely its own module) | Planned |
| ReplayResponse | Per-request response collected during replay (status, body, latency) | TBD | Planned |
| ObservationData | Kubeshark + K8s metrics collected during a replay run | TBD | Planned |
| ValidationResult | Comparison of baseline vs candidate runs with verdict | TBD | Planned |
| ResourceSample | Point-in-time CPU/memory usage during replay | TBD | Planned |

---

## Planned Features

### Feature 1: Traffic Capture (via Kubeshark/eBPF) — Implemented
HTTP req/res pairs captured via agent, stored in collector. See Phase 3 below.

### Feature 2: Replay Engine
Send captured traffic to a target service in the customer's staging cluster. Configurable concurrency (QUICK/STANDARD/LOAD). Read-only by default, full replay with optional DB reset hook.

### Feature 3: Staging Observation
During replay, collect metrics via Kubeshark in staging (outbound connections, call patterns) and K8s Metrics API (pod CPU/memory).

### Feature 4: Comparison & Verdicts
Compare baseline vs candidate replay runs. Response diffs, latency (Mann-Whitney U), error rates, outbound connection delta, memory trends (linear regression). Generate PASS/FAIL/INCONCLUSIVE verdict with evidence.

### Feature 5: Orchestration API
Single `POST /api/validations` endpoint: capture → baseline replay → (optional reset) → candidate replay → compare → verdict.

### Feature 6: Message Queue Capture (Future)
Capture Kafka/PubSub messages via separate consumer groups. Only needed for "entry point" messages from external systems.

---

## Adapter Implementation Matrix

| Adapter | Status | Services | Traffic Capture | Staging Observation |
|---------|--------|----------|----------------|---------------------|
| **Manual Seed** | Implemented | Yes | No | No |
| **Kubernetes** | Implemented | Yes | No | No |
| **Kubeshark (eBPF)** | In Progress | Yes (via observed traffic) | Yes (HTTP req/res at L7) | Yes (outbound connections, call patterns) |

---

## Delivery Plan

### Phase 1: Foundation — COMPLETE

Project setup, Gradle, Organization + Service models, Exposed tables, repositories, pagination, Docker, Flyway, ktlint. ServiceAdapter interface, KubernetesAdapter, ManualSeedAdapter, k3s TestContainers infrastructure, Colima config.

**Milestone:** Adapter pattern implemented, services discoverable from Kubernetes and manual seed data.

---

### Phase 2: Test Services + Kubeshark Validation — COMPLETE

Expanded test microservices (order-service, notification-service, Kafka KRaft, Redis, webhook-stub). Kubeshark validated for HTTP capture at L7. TLS blocker confirmed for PCAP-based DB capture → architecture pivot to staging-based validation.

**Milestone:** Kubeshark validated for HTTP capture. Staging-based approach chosen over PCAP record-replay.

---

### Phase 3: Traffic Capture + Replay — IN PROGRESS

**Traffic Capture (Feature 1) — Largely Complete**
- [x] CapturedInput model + InputType enum (HTTP + UNKNOWN) + CapturedInputs table
- [x] Flyway migrations V0001–V0006 (V0006 drops FK between `captured_inputs.service_id` and `services.id`)
- [x] CapturedInputRepository (create, createBatch, findById, find, countByService, deleteByService)
- [x] Collector API: POST/GET/DELETE `/api/captured-inputs` (port 8081)
- [x] Platform module (renamed from `app`): POST/GET `/api/organizations`, `/api/services`
- [x] `OrganizationId` and `ServiceId` value classes (UUID-validated inline value classes)
- [x] RS256 JWT validated in-app via shared `installJwtAuth()` library; no Envoy in the path
- [x] `AgentIdentity` principal in `shared/`; populated directly from JWT claims (`organizationId`, `cluster`, `role`)
- [x] JWKS endpoint (`/.well-known/jwks.json`): platform derives RSA public key from `JWT_PRIVATE_KEY` env var
- [x] `JwtTokenGenerator`: CLI tool to generate signed JWTs (`./gradlew :platform:generateToken`)
- [x] Agent: `KubesharkClient` (WebSocket), `CollectorClient`, `ConfigClient`, `TrafficTransformer`, `AgentConfig`, `AgentApplication`; uses `PLATFORM_URL` for config and `COLLECTOR_URL` for traffic ingestion
- [x] Agent deployment: `API_KEY` sourced from Kubernetes Secret (`secretKeyRef: platform-api-key/jwt-token`)
- [x] Agent 79+ unit/integration tests; e2e-tests module with platform + collector stack tests
- [x] KubernetesAdapter implements `Closeable`
- [x] Platform: `GET /api/agent/config` endpoint — returns the agent's target services (`name → serviceId`) for the JWT's organization + cluster
- [ ] Agent Loop 1: K8s service discovery → register with platform → receive ID map (stubbed as no-op)
- [ ] HTTP gzip on agent→collector POST (wire bandwidth optimization)

**Replay Engine (Feature 2)**
- [ ] ReplayRun model + database migration (likely in its own module)
- [ ] ReplayEngine: send captured HTTP requests to staging target (fetches inputs via collector API)
- [ ] Configurable fidelity: QUICK (sequential), STANDARD (10-50 concurrent), LOAD (prod-rate)
- [ ] Read-only flag; optional DB reset hook between runs
- [ ] API: `POST /api/replay-runs`, `GET /api/replay-runs/{id}`

**Milestone:** Captured traffic replayable against staging services via API.

---

### Phase 4: Observation + Verdicts

- [ ] StagingObserver: poll Kubeshark in staging during replay; ResourceMonitor: poll K8s Metrics API
- [ ] ComparisonEngine: response diffs, latency (Mann-Whitney U), error rates, memory trends (linear regression)
- [ ] VerdictGenerator: PASS/FAIL/INCONCLUSIVE with evidence
- [ ] API: `GET /api/validations/{id}`

**Milestone:** Full comparison with statistical verdict.

---

### Phase 5: Orchestration + Hardening

- [ ] ValidationOrchestrator: `POST /api/validations` (capture → baseline → reset → candidate → compare → verdict)
- [ ] Candidate deployment to staging (image tag swap)
- [ ] Error handling, logging, observability, message queue capture support

**Milestone:** Production-ready V1 API

---

## Future Features (V2+)

- **CLI**: Optional command-line interface wrapping the API
- **Web UI**: Dashboard for visualizing topology, validation results, and anomalies
- **PR Integration**: GitHub/GitLab webhook integration, automatic validation on PR
- **Deployment Correlation**: Correlate anomalies with recent deploys
- **Automatic Rollback**: Integration with Argo/Flux, anomaly-triggered rollback
- **Multi-Cluster Support**: Federated topology across clusters

---

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Validation approach | Staging-based | TLS blocks PCAP-based dependency mocking for production DBs. Staging avoids protocol-specific proxies entirely. |
| Traffic capture | Kubeshark (eBPF) for HTTP at L7 | Zero instrumentation in production. HTTP req/res pairs with bodies. |
| Auth model | RS256 JWT validated in-app via shared library | Both `platform` and `collector` call `installJwtAuth()` from `shared/`. No reverse proxy in the request path. `AgentIdentity` principal populated directly from JWT claims. |
| Module DB boundaries | No FK across modules (V0006 drops `captured_inputs → services` FK) | Modules are fully decoupled at DB level. Referential integrity enforced at application layer: agent registers services, receives IDs, uses those IDs when posting to collector. |
| Module boundaries | Cross-module access via HTTP API, not shared repositories | Enforces clean ownership. Replay engine will fetch captured inputs via `GET /api/captured-inputs`, not by importing `CapturedInputRepository`. |
| Value classes | `OrganizationId`, `ServiceId` as `@JvmInline value class` | UUID validated at construction, type-safe at compile time, zero runtime overhead. |
| InputType enum | HTTP + UNKNOWN only | YAGNI: message queue capture deferred until replay engine needs it. Enum can extend without breaking changes. |
| CapturedInput fields | method, url, responseStatus non-nullable | HTTP-only for now; nullable fields were premature abstraction. |
| Collector config format | `application.yaml` (not HOCON) | Ktor 3 requires YAML config parser; HOCON is legacy Ktor 2. |
| Test base hierarchy | `DatabaseTestBase` → `PlatformDatabaseTestBase` / `CollectorDatabaseTestBase` | Each module's test base cleans only its own tables. |
| Replay safety | Read-only by default, full with reset hook | Avoids DB mutation between sequential baseline/candidate runs. |
| Run model | Sequential (baseline then candidate) | Simpler than parallel — one set of staging infra. |
| Statistical tests | Mann-Whitney U (latency), linear regression (memory) | Non-parametric; handles skewed latency distributions. Detects growth trends. |
| Traffic capture model | Push (agent → platform/collector directly) not pull | Only requires outbound network access. Platform never reaches into customer clusters. |
| Agent language | Kotlin | Same build toolchain, CI, team knowledge. Swappable — agent communicates via HTTP only. |
| Agent config | Static env vars (URLs, auth) + dynamic polling | Mutable config polled from platform avoids redeploying agent for config changes. |
| Agent ↔ platform contract | Separate DTOs, API contract only, no shared compile-time types | Agent ships as container, versions independently. `ignoreUnknownKeys = true` enables additive API evolution. |
| Kubeshark transport | WebSocket `/api/wsFull` + KFL server-side filtering | v53+ removed REST endpoint. KFL pushes filtering to Kubeshark, reducing agent CPU. `TrafficTransformer` keeps client-side checks as reconnect safety net. |
| Response body encoding | Kubeshark base64-encodes `response.content.text`; agent decodes | Binary-safe for non-UTF-8 payloads. Request bodies at `request.postData.text` are NOT encoded. |
| Agent session model | Persistent WebSocket + bounded channel (1000) | Persistent session avoids replaying ~4-10s of Kubeshark history on every reconnect. Bounded channel provides backpressure without OOM risk. |
| Agent reconnect dedup | `lastSeenTimestamp` with 5s lookback | Covers in-session out-of-order jitter without dropping live entries. Dupes per reconnect are acceptable vs complexity of ID-based LRU. |
| Config propagation | `MutableStateFlow<DynamicConfig>` | `KubesharkClient` observes via `configWatcherJob` and triggers reconnect on `targetServices` changes. Decouples config propagation from imperative calls. |
| KubernetesAdapter lifecycle | Implements `Closeable` | Connection pool released when adapter goes out of scope. Usable with Kotlin `use`. |

---

## Implementation Guidelines

### Module Assignment

Before implementing a feature, decide which module owns it:
- **`platform`**: Organizations, Services, topology/discovery, agent config endpoint, JWKS, token generation
- **`collector`**: CapturedInputs, traffic ingestion (POST endpoint for agent)
- **`agent`**: Kubeshark polling, K8s service discovery, traffic capture and forwarding (standalone process, no platform dependencies)
- **`e2e-tests`**: Full-stack integration tests (platform + collector + DB)
- **Future replay module**: ReplayRuns, ReplayResponses, ReplayEngine

### Implementation Order (per module)

1. Data model in the owning module's `models/` package
2. Database migration in `shared/src/main/resources/db/migration/` (V-numbered, sequential, currently at V0006)
3. Exposed table definition in the owning module's `database/` package
4. Repository in the owning module's `database/` package
5. API endpoint in the owning module's `api/Routes.kt`
6. Tests: use the module's `*DatabaseTestBase` (which extends `DatabaseTestBase` from shared fixtures)

### Cross-Module Access

Cross-module data access uses HTTP REST calls, not direct repository imports. There are no DB-level foreign keys between modules (V0006 removed the last one).

The replay engine will fetch captured inputs via `GET /api/captured-inputs` from the collector, not by importing `CapturedInputRepository` directly.

### Shared Infrastructure

`shared/` provides:
- `DatabaseFactory` — Exposed `Database.connect` (direct, no explicit connection pool) + Flyway migrations. Note: uses Exposed's internal connection management, not HikariCP. See ARCHITECTURE_REVIEW.md ARCH-4 for the known gap.
- `Page<T>` — cursor-based pagination model
- `InstantSerializer` — kotlinx.serialization adapter for `java.time.Instant`
- `DatabaseTestBase` (test fixtures) — starts a TestContainers PostgreSQL instance
- `KubernetesWorkloadTestBase` (test fixtures) — starts a k3s cluster with test workloads
- `TestJwtKeys` (test fixtures) — single consolidated RSA test keypair + signed-token helpers used by all module tests
- `authedTestApplication` (test fixtures) — Ktor test app helper that installs `installJwtAuth()` with the test private key, removing per-module JWT setup boilerplate
