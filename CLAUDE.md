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

- **Two Ktor servers**: `app` on port 8080 (organizations, services) and `collector` on port 8081 (captured inputs)
- **PostgreSQL database** with Flyway migrations (V0001–V0005), all migrations in `shared/`
- **Multi-tenant data model** with Organizations and Services (owned by `app`)
- **CapturedInput model** (owned by `collector`) — HTTP-first, non-nullable method/url/responseStatus
- **Pagination and filtering** on all list endpoints (cursor-based)
- **Docker deployment** — app, collector, and db all start by default; health checks on both services
- **Test infrastructure** with TestContainers (PostgreSQL + k3s Kubernetes)
- **Code quality** with ktlint
- **Adapter pattern** with ServiceAdapter interface
- **Service discovery** via ManualSeedAdapter and KubernetesAdapter
- **Provider tracking** (UNKNOWN, MANUAL_SEED, KUBERNETES)
- **Modular monolith** with enforced module boundaries: cross-module data access goes through REST APIs, not shared repositories

### Module Ownership

Each module owns its tables, models, and repositories. Cross-module communication is via HTTP API calls.

| Module | Owns | Port |
|--------|------|------|
| `shared/` | DatabaseFactory, Flyway migrations, shared models (Page, InstantSerializer) | — |
| `app/` | Organizations, Services tables; OrganizationRepository, ServiceRepository; Ktor server | 8080 |
| `collector/` | CapturedInputs table; CapturedInputRepository; Ktor server | 8081 |
| `test-services/` | Standalone Kotlin microservices for k3s integration testing | — |

### App Module API Endpoints (port 8080)

```
GET    /health                             # Health check
GET    /api/organizations                  # List organizations (paginated)
POST   /api/organizations                  # Create organization
GET    /api/organizations/{id}             # Get organization by ID
GET    /api/services                       # List services (paginated, filterable by organizationId/cluster/namespace)
POST   /api/services                       # Create service
GET    /api/services/{id}                  # Get service by ID
```

### Collector Module API Endpoints (port 8081)

```
GET    /health                                    # Health check
GET    /api/captured-inputs                       # List captured inputs (paginated, filterable by serviceId/inputType)
GET    /api/captured-inputs/{id}                  # Get captured input by ID
DELETE /api/captured-inputs?serviceId={id}        # Delete all captured inputs for a service
```

### Current Data Models

```kotlin
// --- app module ---

// Organization - a tenant/team in the platform
// app/src/main/kotlin/com/platform/models/Organization.kt
data class Organization(
    val id: String,
    val name: String,
    val createdAt: Instant,
    // Note: no updatedAt — organizations are immutable after creation
)

// Service - a deployable unit discovered from various providers
// Uniquely identified by: organizationId + cluster + namespace + name
// app/src/main/kotlin/com/platform/models/Service.kt
data class Service(
    val id: String,
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val discoveredAt: Instant,
    val lastSeenAt: Instant,
    val metadata: Map<String, String>? = null
)

// Provider - tracks where a service was discovered
// app/src/main/kotlin/com/platform/models/Provider.kt
enum class Provider {
    UNKNOWN,        // Provider unknown or not specified
    MANUAL_SEED,    // Manually seeded test data
    KUBERNETES,     // Discovered via Kubernetes API
    // KUBESHARK - Reserved for future Kubeshark adapter
}

// --- collector module ---

// CapturedInput - an HTTP req/res pair captured from production traffic
// HTTP-only for now; method, url, responseStatus are non-nullable
// collector/src/main/kotlin/com/platform/collector/models/CapturedInput.kt
data class CapturedInput(
    val id: String,
    val serviceId: String,
    val inputType: InputType,
    val method: String,           // non-nullable: HTTP-only for now
    val url: String,              // non-nullable: HTTP-only for now
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,      // non-nullable: HTTP-only for now
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: Instant,
)

// InputType - HTTP-first; UNKNOWN for unrecognized protocols
// KAFKA and PUBSUB removed (YAGNI — will add when replay engine needs them)
// collector/src/main/kotlin/com/platform/collector/models/InputType.kt
enum class InputType {
    HTTP,
    UNKNOWN,
}
```

### Request DTOs (app module)

```kotlin
// app/src/main/kotlin/com/platform/api/Requests.kt
data class CreateOrganizationRequest(val name: String)

data class CreateServiceRequest(
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val metadata: Map<String, String>? = null,
)
```

### Development Setup

```bash
# Prerequisites (macOS): Install Colima for TestContainers
# Why Colima? Docker Desktop has socket compatibility issues with TestContainers.
# Colima provides a lightweight Docker runtime that works reliably with both
# TestContainers and Jib. build.gradle.kts auto-detects Colima's socket.
brew install colima docker && colima start

# Start all services (app + collector + db; all start by default)
./gradlew dockerUp

# Run application servers individually
./gradlew :app:run          # app server on port 8080
./gradlew :collector:run    # collector server on port 8081

# Run tests (includes k3s Kubernetes integration tests)
./gradlew test

# Lint code
./gradlew ktlintCheck
```

**Module structure:**
- `shared/` — DatabaseFactory, Flyway migrations (`V0001–V0005`), shared models (Page, InstantSerializer); exposes `java-test-fixtures` with `DatabaseTestBase` and `KubernetesWorkloadTestBase`
- `app/` — Ktor API server on port 8080; owns Organizations + Services tables, repositories, adapters, routes; depends on `:shared`
- `collector/` — Ktor API server on port 8081; owns CapturedInputs table, repository, routes; depends on `:shared`; uses `application.yaml` (Ktor 3 YAML config)
- `test-services/` — Standalone Kotlin microservices for k3s integration testing

**Note on collector config:** The collector uses `application.yaml` (not HOCON `.conf`). This is required by Ktor 3, which uses the YAML config parser.

**Optional:** Deploy test workloads to local Kubernetes (kind/minikube) for manual testing:
```bash
./gradlew testServicesUp              # Deploy test services
./gradlew testServicesStatus          # Check status
./gradlew testServicesDown            # Remove test services
```

---

## Architecture Vision

### High-Level Design

```
PRODUCTION CLUSTER                         STAGING CLUSTER
┌──────────────────────┐                  ┌──────────────────────────────────┐
│  Kubeshark (eBPF)    │                  │  Kubeshark (eBPF)               │
│  captures HTTP       │                  │  observes replay traffic         │
│  req/res pairs       │   captured       │                                  │
│                      │   traffic        │  ┌────────────┐                 │
│  order-service ─────►│ ─────────────►   │  │ order-svc  │ (baseline or   │
│  api-gateway ───────►│                  │  │ (target)   │  candidate)    │
│  notification-svc ──►│                  │  └─────┬──────┘                 │
└──────────────────────┘                  │        │ real connections        │
                                          │        ▼                         │
                                          │  staging-db, staging-kafka, etc  │
                                          └──────────────────────────────────┘

PLATFORM
┌────────────────────────────────────────────────────────────────────┐
│  1. Capture: pull HTTP req/res from prod Kubeshark                │
│  2. Classify: safe (read) vs mutating (write)                     │
│  3. Replay: send captured requests to staging (read-only default) │
│  4. Observe: Kubeshark in staging + K8s metrics API               │
│  5. Compare: baseline run vs candidate run → verdict              │
└────────────────────────────────────────────────────────────────────┘
```

### Validation Flow

```
1. CAPTURE (production, continuous)
   Kubeshark eBPF captures HTTP request/response pairs at L7
   Platform pulls and stores correlated req/res with read/write classification

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
3. **HTTP-first, protocol-extensible model**: `CapturedInput` uses an `InputType` enum (`HTTP`, `UNKNOWN`). KAFKA and PUBSUB variants are intentionally deferred (YAGNI) until the replay engine needs them. The data model can extend without breaking changes.
4. **Module ownership via APIs**: Each module owns its tables and repositories. Cross-module data access (e.g., the collector needing a service fixture in tests) goes through REST API calls, not direct repository imports.
5. **eBPF for capture and observation**: Kubeshark in production for traffic capture, Kubeshark in staging for observability during replay.
6. **Statistical rigor**: Use proper statistical tests (Mann-Whitney U, linear regression), not arbitrary thresholds.

### Staging-Based Validation (Architectural Pivot)

**Why staging instead of isolated namespaces with dependency mocking?**

The original design used PCAP-based record-replay proxies to mock all dependencies (databases, APIs, queues) in an isolated validation namespace. Testing on minikube (2026-04-02) revealed a **TLS blocker**: almost all production databases (RDS, CloudSQL) use TLS encryption. Kubeshark's eBPF hooks intercept plaintext via `SSL_read`/`SSL_write`, but a serialization bug drops binary protocol data (Postgres wire protocol). Building protocol-specific recording proxies for every database flavor adds months of complexity.

**The pivot**: require customers to provide staging environments with real dependencies already wired up. The platform focuses on what it does uniquely well — capture real traffic, replay it, compare behavior — without needing to mock every protocol.

**What was preserved from the original PCAP validation (2026-04-02):**

| Finding | Status |
|---------|--------|
| Kubeshark captures HTTP req/res pairs at L7 (even over TLS) | Works, used for traffic capture |
| PCAP contains full Postgres queries for non-TLS connections | Validated, not needed with staging approach |
| PCAP contains full Kafka messages for non-TLS connections | Validated, not needed with staging approach |
| No PCAP truncation (11,324 frames, zero data loss) | Validated |
| TLS-encrypted DB traffic invisible in PCAPs | **Blocker** that motivated the pivot |

### Read/Write Traffic Classification (Planned — Not Yet Implemented)

To avoid mutating staging state between sequential baseline/candidate runs, the platform will classify traffic and default to read-only replay. The `TrafficClassifier` was previously stubbed in the app module but removed as premature — it belongs in the replay engine, which will own this logic.

| Protocol | Read | Write | Reliability |
|---|---|---|---|
| HTTP REST | `GET`, `HEAD` | `POST`, `PUT`, `PATCH`, `DELETE` | ~95% |
| gRPC | Method name: `Get*`, `List*`, `Search*`, `Find*`, `Query*` | Everything else | ~80% |
| GraphQL | `query` in body | `mutation` in body | ~99% |

Conservative default: ambiguous = write = skip. User can override specific endpoints (e.g., mark `POST /api/search` as safe).

### Message Queue Support (Future, De-Risked)

Message queues use built-in fan-out for safe capture:
- **Kafka**: Separate consumer group, zero impact on production consumers
- **GCP Pub/Sub**: Mirror subscription on same topic
- **AWS SNS**: Add capture SQS queue as subscriber
- **RabbitMQ**: Bind capture queue to same exchange

**Key insight**: If the message *producer* is a service we're already replaying HTTP to, Kafka messages flow naturally as a side effect in staging. Separate message capture is only needed for "entry point" messages from external systems.

**Limitations**: Consumer offset resets, idempotency guards, cross-partition ordering, timing sensitivity.

### What Customers Provide

| Requirement | Required? | Details |
|---|---|---|
| Staging cluster | Yes | With real dependencies (DB, queues, caches) wired up |
| Kubeshark access | Yes | Platform deploys/manages in both clusters |
| Deployment mechanism | Yes | How to deploy candidate to staging (image tag, Helm, kustomize) |
| Endpoint classification | Optional | Mark ambiguous endpoints as safe/mutating |
| DB reset hook | Optional | Enables full replay including writes |

### Adapter Implementation Status

**ServiceAdapter Interface** (`app/src/main/kotlin/com/platform/adapters/ServiceAdapter.kt`):
```kotlin
interface ServiceAdapter {
    suspend fun discoverServices(organizationId: String): List<Service>
}
```

**Implemented Adapters:**

1. **ManualSeedAdapter** - Provides 8 hardcoded services (frontend, backend, messaging, data layers) for testing and development without external dependencies.

2. **KubernetesAdapter** - Discovers services from Kubernetes clusters via the Kubernetes API. Supports in-cluster config, KUBECONFIG, and ~/.kube/config. Filters system namespaces by default. Extracts metadata from labels and annotations.

3. **KubesharkAdapter** - Planned: pull captured HTTP traffic from Kubeshark API for the collector module.

**Test Infrastructure:**

- `KubernetesWorkloadTestBase` - Spins up k3s cluster with test workloads using TestContainers
- Manifests in `k8s/test-services/` used for both automated tests and local development
- Handles Colima socket complexities for image loading
- 3 namespaces: `infrastructure`, `production`, `external`
- 7 discoverable K8s Services (traffic-generator has no Service resource)

**Deployed test services (exercises every dependency type):**
```
traffic-generator → api-gateway → order-service → orders-db (PostgreSQL)
                                → Redis (cache)   → Kafka (produce: order-events)

Kafka (consume: order-events) → notification-service → webhook-stub (external)
```

| Namespace | Services |
|-----------|----------|
| infrastructure | orders-db (PostgreSQL 16), redis (7-alpine, 2MB maxmemory + allkeys-lru), kafka (apache/kafka:3.7.0, KRaft mode) |
| production | api-gateway (HTTP proxy + Redis cache), order-service (HTTP API + PostgreSQL + Kafka producer), notification-service (Kafka consumer + webhook caller), traffic-generator (5 reader + 1 writer coroutines, no Service resource) |
| external | webhook-stub (simulates third-party API endpoint) |

| Dependency type | Exercised by |
|----------------|-------------|
| APPLICATION | api-gateway → order-service |
| DATASTORE | order-service → orders-db |
| MESSAGE_QUEUE | order-service → Kafka → notification-service |
| CACHE | api-gateway → Redis |
| EXTERNAL | notification-service → webhook-stub |

Per-service PostgreSQL is colocated with service manifests (not shared). The Kafka path creates a real async coupling between order-service and notification-service.

---

## Tech Stack

### Language: Kotlin

- Strong typing catches errors early, aids refactoring
- Coroutines provide clean async handling for I/O-heavy workload
- Data classes reduce boilerplate
- JetBrains support ensures good tooling

### Framework: Ktor

- Kotlin-native, built by JetBrains
- Coroutines are first-class
- Lightweight, only include what you need
- Simple mental model, no magic annotations

### Database: PostgreSQL

- Production-grade relational database
- Exposed ORM for type-safe queries
- Flyway for schema migrations

### Key Libraries

- **Ktor**: Kotlin-native web framework
- **Exposed + PostgreSQL**: Type-safe database access
- **Fabric8 Kubernetes Client**: Service discovery from K8s clusters
- **TestContainers**: Integration testing with PostgreSQL and k3s
- See `build.gradle.kts` for complete dependency list

---

## Data Models

| Model | Purpose | Module | Status |
|-------|---------|--------|--------|
| Organization | Tenant/team in the platform | `app` | Implemented |
| Service | Deployable unit discovered from various providers | `app` | Implemented |
| CapturedInput | HTTP req/res pair captured from production traffic | `collector` | Implemented |
| ReplayRun | A replay run against staging (config, status, collected responses) | TBD (likely its own module) | Planned |
| ReplayResponse | Per-request response collected during replay (status, body, latency) | TBD | Planned |
| ObservationData | Kubeshark + K8s metrics collected during a replay run | TBD | Planned |
| ValidationResult | Comparison of baseline vs candidate runs with verdict | TBD | Planned |
| ResourceSample | Point-in-time CPU/memory usage during replay | TBD | Planned |

---

## Planned Features

### Feature 1: Traffic Capture (via Kubeshark/eBPF)

Pull HTTP request/response pairs from Kubeshark in production. Classify as read/write. Store for replay. Protocol-agnostic model supports future Kafka/gRPC capture.

### Feature 2: Replay Engine

Send captured traffic to a target service in the customer's staging cluster. Configurable concurrency (QUICK/STANDARD/LOAD). Read-only by default, full replay with optional DB reset hook.

### Feature 3: Staging Observation

During replay, collect metrics via Kubeshark in staging (outbound connections, call patterns) and K8s Metrics API (pod CPU/memory). Detect behavioral changes like increased DB connection counts.

### Feature 4: Comparison & Verdicts

Compare baseline run (current version) vs candidate run (PR branch). Response diffs, latency (Mann-Whitney U), error rates, outbound connection delta, memory trends (linear regression). Generate PASS/FAIL/INCONCLUSIVE verdict with evidence.

### Feature 5: Orchestration API

Single `POST /api/validations` endpoint that orchestrates: capture traffic → baseline replay → (optional reset) → candidate replay → compare → verdict.

### Feature 6: Message Queue Capture (Future)

Capture Kafka/PubSub messages via separate consumer groups. Replay by producing to staging topics. Only needed for "entry point" messages from external systems.

---

## Adapter Implementation Matrix

Adapters normalize data from different sources into the unified model.

| Adapter | Status | Services | Traffic Capture | Staging Observation |
|---------|--------|----------|----------------|---------------------|
| **Manual Seed** | Implemented | Yes | No | No |
| **Kubernetes** | Implemented | Yes | No | No |
| **Kubeshark (eBPF)** | In Progress | Yes (via observed traffic) | Yes (HTTP req/res at L7) | Yes (outbound connections, call patterns) |

---

## Delivery Plan

### Phase 1: Foundation

**Week 1: Project Setup + Data Model** - COMPLETE
- [x] Initialize Gradle project with dependencies
- [x] Create package structure
- [x] Define Organization and Service models
- [x] Create Exposed table definitions
- [x] Implement database repositories with CRUD and pagination
- [x] Write database tests with TestContainers
- [x] Set up Docker deployment
- [x] Configure Flyway migrations
- [x] Set up ktlint for code quality

**Week 2: Kubernetes Integration + Manual Seed** - COMPLETE
- [x] Implement ServiceAdapter interface
- [x] Implement KubernetesAdapter with service discovery
- [x] Create ManualSeedAdapter with hardcoded test data
- [x] Set up KubernetesWorkloadTestBase with k3s integration tests
- [x] Create test workloads (PostgreSQL, Redis, API Gateway, traffic generator)
- [x] Configure Colima for TestContainers on macOS
- [ ] Create API endpoints: `POST /api/seed`, `GET /api/topology` (deferred)

**Milestone:** Adapter pattern implemented, services discoverable from Kubernetes and manual seed data

---

### Phase 2: Test Services + Kubeshark Validation

**Week 3: Expand Test Microservices** - COMPLETE
- [x] Implement order-service (HTTP API, PostgreSQL for orders-db, Kafka producer)
- [x] Implement notification-service (Kafka consumer, external HTTP call to webhook-stub)
- [x] Add Kafka (apache/kafka:3.7.0, KRaft mode) to k8s/test-services infrastructure
- [x] Add per-service PostgreSQL (orders-db colocated with service manifest)
- [x] Add webhook-stub in external namespace for EXTERNAL dep testing
- [x] Update api-gateway to proxy to order-service with Redis LRU cache
- [x] Update traffic-generator with concurrent coroutines (5 readers + 1 writer)
- [x] Update KubernetesWorkloadTestBase and integration tests (7-service topology)

**Week 4: Kubeshark/eBPF Validation** - COMPLETE
- [x] Deploy Kubeshark to minikube with test services
- [x] Validate HTTP traffic capture at L7 (req/res pairs with bodies)
- [x] Validate PCAP extraction for Postgres (non-TLS) and Kafka
- [x] De-risk: TLS-encrypted connections → **BLOCKER** for PCAP-only approach
- [x] De-risk: PCAP size limits → NOT A RISK (11,324 frames, zero truncation)
- [x] Architecture decision: pivot from record-replay proxy to staging-based validation

**Milestone:** Kubeshark validated for HTTP capture. Staging-based approach chosen over PCAP record-replay.

---

### Phase 3: Traffic Capture + Replay - IN PROGRESS

**Traffic Capture (Feature 1)**
- [x] CapturedInput model (HTTP-first, non-nullable method/url/responseStatus) — `collector/src/main/kotlin/com/platform/collector/models/`
- [x] InputType enum simplified to HTTP + UNKNOWN (YAGNI: KAFKA, PUBSUB deferred)
- [x] TrafficClassification enum and TrafficClassifier removed — not needed until replay engine
- [x] CapturedInputs table definition — `collector/src/main/kotlin/com/platform/collector/database/CapturedInputs.kt`
- [x] Database migration V0004 (method/url/responseStatus non-nullable) — `shared/src/main/resources/db/migration/`
- [x] CapturedInputRepository (create, createBatch, findById, find, countByService, deleteByService) — `collector/src/main/kotlin/com/platform/collector/database/`
- [x] Collector API: `GET /api/captured-inputs`, `GET /api/captured-inputs/{id}`, `DELETE /api/captured-inputs?serviceId=` (port 8081)
- [x] POST endpoints in app module: `POST /api/organizations`, `POST /api/services`
- [x] Collector module: full Ktor server on port 8081 (no longer a skeleton)
- [x] Test infrastructure: 18 CapturedInputRepository tests, 12 CapturedInputRoutesTest, 1 HealthRoutesTest, 6 new POST endpoint tests in app
- [x] AppApiTestHelper: collector tests create org/service fixtures via POST API calls to app module (enforces module boundary)
- [ ] collector: Kubeshark polling → store captured inputs (Kubeshark adapter not yet implemented)

**Replay Engine (Feature 2)**
- [ ] ReplayRun model + database migration (likely in its own module)
- [ ] ReplayEngine: send captured HTTP requests to staging target (fetches inputs via collector API)
- [ ] Configurable fidelity: QUICK (sequential), STANDARD (10-50 concurrent), LOAD (prod-rate)
- [ ] Read-only flag (skip write-classified requests)
- [ ] Optional DB reset hook between runs
- [ ] API: `POST /api/replay-runs`, `GET /api/replay-runs/{id}`

**Milestone:** Captured traffic replayable against staging services via API.

---

### Phase 4: Observation + Verdicts

**Staging Observation (Feature 3)**
- [ ] StagingObserver: poll Kubeshark in staging during replay
- [ ] ResourceMonitor: poll K8s Metrics API for pod CPU/memory
- [ ] Collect outbound connection destinations, call counts

**Comparison & Verdicts (Feature 4)**
- [ ] ComparisonEngine: diff baseline vs candidate replay runs
- [ ] StatisticalTests: Mann-Whitney U (latency), linear regression (memory trends)
- [ ] VerdictGenerator: PASS/FAIL/INCONCLUSIVE with evidence
- [ ] API: `GET /api/validations/{id}`

**Milestone:** Full comparison with statistical verdict.

---

### Phase 5: Orchestration + Hardening

**Orchestration API (Feature 5)**
- [ ] ValidationOrchestrator: capture → baseline → (reset) → candidate → compare → verdict
- [ ] API: `POST /api/validations` (single endpoint)
- [ ] Candidate deployment to staging (image tag swap)

**Hardening**
- [ ] Error handling and retry logic
- [ ] Logging and observability
- [ ] End-to-end tests
- [ ] Message queue capture support (Feature 6)

**Milestone:** Production-ready V1 API

---

## Future Features (V2+)

- **CLI**: Optional command-line interface wrapping the API for terminal workflows
- **Web UI**: Dashboard for visualizing topology, validation results, and anomalies
- **PR Integration**: GitHub/GitLab webhook integration, automatic validation on PR
- **Deployment Correlation**: Correlate anomalies with recent deploys
- **Automatic Rollback**: Integration with Argo/Flux, anomaly-triggered rollback
- **Multi-Cluster Support**: Federated topology across clusters

---

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Validation approach | Staging-based (customer provides staging env) | TLS blocks PCAP-based dependency mocking for production databases. Staging-based avoids protocol-specific proxies entirely. Simpler, shippable sooner. |
| Traffic capture | Kubeshark (eBPF) for HTTP at L7 | Zero instrumentation in production. HTTP req/res pairs captured with bodies. Validated on minikube 2026-04-02. |
| Module boundaries | Cross-module access via HTTP API, not shared repositories | Enforces clean ownership. Collector tests create org/service fixtures via POST to app routes (AppApiTestHelper), not by importing app repositories directly. |
| InputType enum | HTTP + UNKNOWN only (KAFKA, PUBSUB removed) | YAGNI: message queue capture deferred until replay engine needs it. Enum can extend without breaking changes. |
| CapturedInput fields | method, url, responseStatus non-nullable | HTTP-only for now; nullable fields were premature abstraction before non-HTTP capture exists. |
| TrafficClassifier | Removed from app module | Premature: classification is only needed during replay, which may be its own module. Re-add there when the time comes. |
| Collector config format | `application.yaml` (not HOCON) | Ktor 3 requires the YAML config parser; HOCON is legacy Ktor 2 behavior. |
| Test base hierarchy | `DatabaseTestBase` (shared fixtures) → `AppDatabaseTestBase` / `CollectorDatabaseTestBase` | Each module's test base cleans only its own tables, preserving module ownership in tests. |
| Replay model | `CapturedInput` with InputType field | HTTP-first, but `type: HTTP | UNKNOWN` allows extension to KAFKA/PUBSUB without model changes. |
| Replay safety | Read-only by default, full with reset hook | Avoids DB mutation between sequential baseline/candidate runs. Conservative classification (ambiguous = write = skip). |
| Run model | Sequential (baseline then candidate) | Simpler than parallel — one set of staging infra. For 5-15 min runs, environmental drift is negligible. |
| Staging observation | Kubeshark in staging + K8s Metrics API | Kubeshark gives outbound connection counts and patterns. K8s Metrics gives CPU/memory for leak detection. |
| Statistical tests | Mann-Whitney U | Non-parametric, handles skewed latency distributions |
| Leak detection | Linear regression | Detect memory growth trend over time |
| Interface | API-first (CLI deferred) | Enables UI/webhook integration without binary distribution; CLI can wrap API later if needed |

## Known Issues & Technical Debt

Architecture review performed 2026-04-03. Items ordered by priority.

### Fix Before Replay Phase

| # | Issue | Location | Fix |
|---|-------|----------|-----|
| 3 | Blocking Kubernetes API call on coroutine dispatcher — `discoverServices` calls synchronous Fabric8 client without `Dispatchers.IO`, can starve Ktor worker threads | `KubernetesAdapter.kt:82` | Wrap in `withContext(Dispatchers.IO)` |

### Address When Adding Third Module

| # | Issue | Location | Fix |
|---|-------|----------|-----|
| 4 | Application startup code duplicated across app and collector — identical DB config resolution + `ContentNegotiation` setup | `Application.kt:17-43`, `CollectorApplication.kt:17-43` | Extract `configureDatabase()` and `configureContentNegotiation()` into `shared/` |
| 5 | Collector test dependency on app internals — `testImplementation(project(":app"))` imports `CreateOrganizationRequest`, `configureRouting` directly | `collector/build.gradle.kts:38`, `AppApiTestHelper.kt:3-5` | Long-term: extract shared test DTOs into `shared` testFixtures, or use synthetic UUIDs that bypass FK |

### Minor Cleanup

| # | Issue | Fix |
|---|-------|-----|
| 6 | `captured_inputs.service_id` FK in SQL migration but not in Exposed table definition — inconsistent | Either add `.references()` to Exposed column or document as intentional |
| 7 | Stale Pixie references in adapter KDoc | `ServiceAdapter.kt:8`, `ManualSeedAdapter.kt:15` — replace "Pixie" with "Kubeshark" |
| 8 | `DELETE /api/captured-inputs` response uses untyped `mapOf("deleted" to deleted)` | Add a `DeleteResponse(val deleted: Long)` data class |
| 9 | `prettyPrint = true` in production JSON config — wastes bandwidth | Consider removing for non-dev environments |

---

## Implementation Guidelines

### Module Assignment

Before implementing a feature, decide which module owns it:
- **`app`**: Organizations, Services, topology/discovery concerns
- **`collector`**: CapturedInputs, Kubeshark polling, traffic ingestion
- **Future replay module**: ReplayRuns, ReplayResponses, ReplayEngine

### Implementation Order (per module)

1. Data model in the owning module's `models/` package
2. Database migration in `shared/src/main/resources/db/migration/` (V-numbered, sequential)
3. Exposed table definition in the owning module's `database/` package
4. Repository in the owning module's `database/` package
5. API endpoint in the owning module's `api/Routes.kt`
6. Tests: use the module's `*DatabaseTestBase` (which extends `DatabaseTestBase` from shared fixtures)

### Cross-Module Access

Cross-module data access uses HTTP REST calls, not direct repository imports.

Example: collector tests need org/service fixtures. They call `AppApiTestHelper`, which starts a `testApplication` with the app module's routing and issues `POST /api/organizations` and `POST /api/services`. The collector's `build.gradle.kts` declares `testImplementation(project(":app"))` only for this purpose.

This pattern will extend to the replay engine: it fetches captured inputs via `GET /api/captured-inputs` from the collector, not by importing `CapturedInputRepository` directly.

### Shared Infrastructure

`shared/` provides:
- `DatabaseFactory` — HikariCP connection pool + Flyway migrations
- `Page<T>` — cursor-based pagination model
- `InstantSerializer` — kotlinx.serialization adapter for `java.time.Instant`
- `DatabaseTestBase` (test fixtures) — starts a TestContainers PostgreSQL instance
- `KubernetesWorkloadTestBase` (test fixtures) — starts a k3s cluster with test workloads
