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
- **Provider tracking** (UNKNOWN, MANUAL_SEED, KUBERNETES) — enum values in data model; adapter classes removed after agent Loop 1 took over in-cluster discovery
- **Modular monolith** with enforced module boundaries: cross-module data access goes through REST APIs, not shared repositories; no DB-level FK between modules
- **Validation agent** — three-loop Kotlin process deployed to customer cluster; streams traffic from Kubeshark WebSocket with server-side KFL filtering, samples, and pushes directly to platform (8080) and collector (8081) with a JWT bearer token; file-based liveness probe; non-root container; API key stored in Kubernetes Secret
- **E2E tests** — `e2e-tests/` module tests the full platform stack (platform + collector) using TestContainers; includes `AgentDiscoveryE2ETest` for the full K8s → agent → platform discovery path
- **Agent Loop 1 (K8s service discovery)** — `K8sServiceDiscovery` + `PlatformClient` in the agent; discovers K8s Services and registers them with the platform via `POST /api/services`

### Module Ownership

Each module owns its tables, models, and repositories. Cross-module communication is via HTTP API calls, with no DB-level foreign keys across modules.

| Module | Owns | Port |
|--------|------|------|
| `shared/` | DatabaseFactory, Flyway migrations, shared models (Page, InstantSerializer), `AgentIdentity`, `installJwtAuth()`, `OrganizationId`/`ServiceId` value classes; test fixtures: `DatabaseTestBase`, `KubernetesWorkloadTestBase`, `TestJwtKeys`, `authedTestApplication` | — |
| `platform/` | Organizations, Services tables; OrganizationRepository, ServiceRepository; Ktor server; JWKS endpoint; JWT token generator (note: `platform/adapters/` was removed after agent Loop 1 took over K8s discovery) | 8080 |
| `collector/` | CapturedInputs table; CapturedInputRepository; Ktor server | 8081 |
| `agent/` | Kubeshark polling, K8s service discovery, traffic capture and forwarding directly to platform (8080) and collector (8081) | — (standalone process) |
| `e2e-tests/` | End-to-end tests for the full platform stack (platform + collector) | — |
| `test-services/` | Standalone Kotlin microservices for k3s integration testing | — |

### Platform Module API Endpoints (port 8080)

```
GET    /health                             # Health check (no auth)
GET    /.well-known/jwks.json              # RSA public key (no auth)
GET    /api/organizations                  # List organizations — scoped to caller's org (0 or 1 result)
POST   /api/organizations                  # Create organization — requires JWT (admin-only; role not enforced yet, see TODO in Routes.kt)
GET    /api/organizations/{id}             # Get organization — 404 if id != JWT organizationId
GET    /api/services                       # List services — scoped to caller's org
POST   /api/services                       # Create service — organizationId + cluster taken from JWT (not in body)
GET    /api/services/{id}                  # Get service — 404 if service.organizationId != JWT organizationId
GET    /api/agent/config                   # Agent dynamic config (target services for the JWT's org+cluster) — requires JWT
```

### Collector Module API Endpoints (port 8081)

```
GET    /health                                    # Health check (no auth)
POST   /api/captured-inputs                       # Ingest batch — organizationId auto-stamped from JWT; scoped to caller's org
GET    /api/captured-inputs                       # List captured inputs — scoped to caller's org
GET    /api/captured-inputs/{id}                  # Get captured input — 404 if input.organizationId != JWT organizationId
DELETE /api/captured-inputs?serviceId={id}        # Delete captured inputs for service — scoped to caller's org only
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
// organizationId and cluster are NOT body fields — both come from the JWT
// principal, so an agent token cannot register services into a different
// org or cluster than its own.
data class CreateServiceRequest(
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
- `platform/` — Ktor API server on port 8080; owns Organizations + Services tables, repositories, routes; JWKS endpoint; depends on `:shared` (the `adapters/` package was removed — K8s discovery now lives in the agent)
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

### Cloud SQL public schema bootstrap (one-time per DB lifetime)

Cloud Run authenticates to Postgres as the platform SA via IAM auth — no
static DB password. But the SA needs to own the `public` schema before
Flyway can create tables (PG15+ default doesn't grant `CREATE` on `public`
to ordinary roles). That ownership transfer requires one privileged SQL
session, bootstrapped by:

```bash
# After a brand-new platform-up (or after the validation DB is recreated):
./scripts/platform-up.sh
./scripts/bootstrap-db.sh
```

`bootstrap-db.sh` briefly sets a random password on `postgres`, runs
`GRANT ALL ON SCHEMA public TO <platform-sa>` via `cloud-sql-proxy`, then
rotates the password to another random value nobody knows. Net result:
the SA can create tables in `public` (Flyway succeeds) and no static
credential exists anywhere. Idempotent — safe to re-run.

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
7. **Single-cluster scope**: Capture from one cluster, replay against one staging cluster. Multi-cluster federation is out of scope until a customer demands it. The coordination cost isn't justified by current demand.
8. **Replay fidelity stops at LOAD mode**: REPLAY-11 (token-bucket prod-rate replay) is the ceiling for MVP. Adaptive concurrency, request-timing reconstruction beyond inter-request deltas, and latency simulation are deferred — they add complexity without unblocking a verdict.
9. **Integration sequencing**: GitHub Action (MVP-9) is the first external integration. A CLI wrapping the API is useful but only after the Action establishes the integration pattern — don't build both in parallel.

### Staging-Based Validation (Architectural Pivot)

The original design used PCAP-based record-replay proxies to mock all dependencies in an isolated namespace. A **TLS blocker** was discovered: production databases (RDS, CloudSQL) use TLS, and Kubeshark's eBPF hooks cannot capture the Postgres wire protocol through TLS. Building protocol-specific proxies for every database flavor adds months of complexity.

**The pivot**: require customers to provide staging environments with real dependencies already wired up. The platform focuses on what it does uniquely well — capture real traffic, replay it, compare behavior.

### Validation Agent (Push Model)

The agent runs in the customer's K8s cluster as a standalone Kotlin process. It pushes data to the platform — the platform never reaches into the customer's cluster.

**Three independent coroutine loops:**

| Loop | Interval | Responsibility |
|------|----------|----------------|
| Service discovery | ~60s | `K8sServiceDiscovery` queries K8s API → diff against in-memory map → register new services with platform via `POST /api/services` (`PlatformClient`) → update in-memory `name → serviceId` map; `RegistrationOutcome` distinguishes per-service payload errors (PermanentRejection: 400/422) from environment failures (TransientFailure: all other errors) |
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
    AgentApplication.kt        # main, three coroutine loops; shared MutableStateFlow<DynamicConfig>; HTTP client factories (gzip on collector client)
    AgentConfig.kt             # StaticConfig (env vars), DynamicConfig (polled), DurationAsMillisSerializer
    K8sServiceDiscovery.kt     # Lists K8s Service resources (Loop 1); wraps Fabric8 KubernetesClient; implements Closeable
    PlatformClient.kt          # HTTP client for POST /api/services (Loop 1); returns RegistrationOutcome sealed class
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
- `agent/build.gradle.kts` — Jib plugin config for building `validation-agent:latest`; includes Fabric8 Kubernetes Client dependency for Loop 1
- `k8s/agent/base/agent.yaml` — Kubernetes Deployment base manifest (namespace `validation`, single replica); `API_KEY` from `secretKeyRef: platform-api-key/jwt-token`; file-based liveness probe on `/tmp/agent-alive`; `KUBESHARK_URL` set here as default
- `k8s/agent/base/kustomization.yaml` — Kustomize base for the agent
- `k8s/agent/overlays/sandbox/` — Sandbox GKE overlay: sets Artifact Registry image, `imagePullPolicy: Always`, serviceAccountName, and patches `__PLATFORM_URL__` / `__COLLECTOR_URL__` / `__KUBESHARK_URL__` placeholders that `scripts/sandbox-up.sh` substitutes at deploy time

**Bring-up flow (sandbox):**
```
scripts/platform-up.sh       # provision Cloud Run services + Cloud SQL
scripts/bootstrap-db.sh      # grant SA ownership of public schema (one-time)
scripts/sandbox-up.sh        # GKE cluster (Terraform) + test services + seed-org.sh + Kubeshark (Helm) + agent overlay
```
`sandbox-up.sh` installs Kubeshark idempotently via `helm upgrade --install` and applies `k8s/agent/overlays/sandbox/` with sed-substituted Cloud Run URLs. Pass `--build-local` to Jib-build and push the agent image from local source before deploying.

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

### Test Infrastructure

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
- **Key Libraries**: Ktor, Exposed + PostgreSQL, Fabric8 Kubernetes Client (agent only), TestContainers
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

## Delivery Plan

### Phase 1: Foundation — COMPLETE

Project setup, Gradle, Organization + Service models, Exposed tables, repositories, pagination, Docker, Flyway, ktlint. k3s TestContainers infrastructure, Colima config. (ServiceAdapter / KubernetesAdapter / ManualSeedAdapter were originally built here but later removed when agent Loop 1 took over K8s discovery.)

**Milestone:** Foundation complete, test infrastructure in place.

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
- [x] Agent 79+ unit/integration tests; e2e-tests module with platform + collector stack tests (including `AgentDiscoveryE2ETest`)
- [x] Platform: `GET /api/agent/config` endpoint — returns the agent's target services (`name → serviceId`) for the JWT's organization + cluster
- [x] Agent Loop 1: `K8sServiceDiscovery` + `PlatformClient` — discovers K8s Services, registers with platform, maintains in-memory ID map; `RegistrationOutcome` sealed class (Success / PermanentRejection / TransientFailure)
- [x] HTTP gzip on agent→collector POST (via Ktor `ContentEncoding` plugin in `buildAgentCollectorHttpClient`)

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
| Registration outcome classification | Sealed `RegistrationOutcome` (Success / PermanentRejection / TransientFailure); PermanentRejection scoped to 400/422 only | Without this, a 401 would silently poison every service into `permanentlyFailed` even though the rejection is caller-level (auth, rate-limit, platform down). Narrow PermanentRejection to per-service payload validation; everything else retries on the next discovery tick. |
| K8sServiceDiscovery lifecycle | Implements `Closeable` (wraps Fabric8 `KubernetesClient`) | Connection pool released when discovery goes out of scope. Usable with Kotlin `use`. |

---

## Reference Documents

- **Linear** — active tracking for the MVP roadmap, customer-onboarding catalog, and tech-debt items. Three projects under the `Validation-platform` team: [Replay Engine](https://linear.app/validation-platform/project/replay-engine-a9b7d282ff76), [Customer Onboarding](https://linear.app/validation-platform/project/customer-onboarding-1d3a825ed8d6), [Tech Debt](https://linear.app/validation-platform/project/tech-debt-ff5e67ba9787). Issue titles preserve the original doc IDs (e.g. `[REPLAY-3]`, `[ARCH-4]`) for cross-reference.

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
- `DatabaseFactory` — HikariCP `DataSource` passed to both Flyway and Exposed `Database.connect`. Pool size configurable via `DATABASE_POOL_SIZE` (default 10). See Linear `[ARCH-4]` for pool-size calibration vs. Cloud Run instance count.
- `Page<T>` — cursor-based pagination model
- `InstantSerializer` — kotlinx.serialization adapter for `java.time.Instant`
- `DatabaseTestBase` (test fixtures) — starts a TestContainers PostgreSQL instance
- `KubernetesWorkloadTestBase` (test fixtures) — starts a k3s cluster with test workloads
- `TestJwtKeys` (test fixtures) — single consolidated RSA test keypair + signed-token helpers used by all module tests
- `authedTestApplication` (test fixtures) — Ktor test app helper that installs `installJwtAuth()` with the test private key, removing per-module JWT setup boilerplate

<!-- GSD:project-start source:PROJECT.md -->
## Project

**Validation Platform — Native eBPF Traffic Capture Cutover**

The validation platform captures real production traffic with eBPF and replays it against staging to catch regressions before they ship — memory leaks, latency drift, behavioral changes, the things unit tests miss. This milestone replaces the existing Kubeshark + Kotlin agent capture stack with our own Go eBPF tap and a Go agent, so capture works under sustained production load and the team owns every layer of the data path.

**Core Value:** **The tap and Go agent capture production traffic stably on the sandbox cluster that previously broke Kubeshark — and then Kubeshark + the Kotlin agent are gone from the repo.** If everything else slips, this must land.

### Constraints

- **Tech stack** — Go ≥ 1.22 (per `tap/go.mod`), `cilium/ebpf` + `bpf2go`, `client-go`, standard library `net/http`. Compile with CO-RE so binaries portable across kernels ≥ 5.10 (target: GKE COS, kernel 6.12). Kotlin platform/collector stay on JDK 21 + Ktor 3.
- **Performance** — must hold steady at the load Kubeshark broke on (exact target lives in TAP-6 benchmarks; sandbox cluster is the bench rig). Memory + CPU footprint per node must fit comfortably inside whatever node-pool sizing the sandbox already runs with — retuning Terraform is allowed but should be a last resort.
- **Compatibility** — Go agent must speak the existing platform/collector wire contracts byte-for-byte (the platform side is not changing). `BatchCreateCapturedInputRequest` shape, `AgentConfigResponse` shape, JWT claim contract (`organizationId`, `cluster`, `role?`), RS256 signature, gzip request bodies on collector POSTs — all preserved.
- **Auth** — JWT bearer tokens minted by the existing platform `JwtTokenGenerator` work unchanged. No new key material, no new endpoints. Go-side libraries (e.g. `github.com/golang-jwt/jwt`) only need to *attach* the token; the platform/collector do all validation.
- **Test coverage** — integration / e2e coverage for any behavior the Go module owns cannot regress when the Kotlin agent is deleted. Bilingual e2e (Kotlin `e2e-tests/` driving the Go agent binary/container) is the chosen mechanism.
- **PR shape** — every cutover/tear-out PR must be reviewable in one sitting. No "delete the world" mega-PR. Tag the PR series so reviewers can follow the order.
- **Schedule** — "validate ASAP." No hard deadline named, but stretch scope (TAP-9 / TAP-10) is out, and "good enough" on the sandbox load test is the trigger to start tearing out.
- **Reversibility** — TAP-7 (cutover) must be reversible until TAP-8 lands. If the sandbox swap reveals a regression, we should be able to flip back to Kubeshark while we fix forward. After TAP-8, reversal becomes a git revert exercise — acceptable.
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Kotlin 2.2.21 - All application code (platform, collector, agent, test services, integration tests)
- Bash - Deployment and bootstrap scripts (`scripts/*.sh`)
- SQL - Database migrations and schema definitions (`shared/src/main/resources/db/migration/V*.sql`)
- HCL/Terraform - Infrastructure as Code (`infra/platform/*.tf`)
- YAML - Kubernetes manifests (`k8s/**/*.yaml`) and Ktor application config (`application.yaml`)
## Runtime
- JVM - OpenJDK 21 (Eclipse Temurin)
- Gradle 8.11 - Build system
- Gradle 8.11 with Kotlin DSL (`build.gradle.kts`)
- Lockfile: `gradle/wrapper/gradle-wrapper.jar` (Gradle wrapper for reproducible builds)
- Version catalog: `gradle/libs.versions.toml` (centralized dependency management)
## Frameworks
- Ktor 3.3.3 - Kotlin-native web framework (async/coroutines-first)
- Exposed 0.57.0 - Kotlin ORM and query DSL
- PostgreSQL 42.7.7 - JDBC driver
- Flyway 9.22.3 - Database schema migrations (V0001–V0007 in `shared/src/main/resources/db/migration/`)
- HikariCP 5.1.0 - Connection pooling (max pool size configurable via `DATABASE_POOL_SIZE` env var, default 10)
- Fabric8 Kubernetes Client 6.10.0 - K8s API access for agent service discovery (Loop 1)
- java-jwt (Auth0) 4.4.0 - RS256 JWT generation and validation
- BouncyCastle 1.79 - Cryptographic operations for RSA key handling and K3s EC key support
- kotlinx-serialization-json 1.7.3 - JSON serialization/deserialization (ignoreUnknownKeys = true for schema evolution)
- google-cloud-secretmanager 2.54.0 - Cloud Secret Manager SDK (runtime secret resolution)
- cloud-sql-postgres-socket-factory 1.21.0 - Cloud SQL JDBC socket factory for IAM authentication (Cloud Run only)
- Logback 1.5.26 - Logging framework
- logstash-logback-encoder 8.1 - JSON logging for structured log aggregation
- JUnit 5 (Jupiter) 5.10.0 - Test runner
- Kotlin test 2.2.21 - Kotlin testing utilities with JUnit 5 integration
- TestContainers 2.0.3 - Docker-based integration testing
- MockK 1.13.9 - Kotlin mocking framework
- Ktor client mock - Mock HTTP responses for testing
- Jib 3.4.4 - Containerized JAR builds (multi-architecture amd64/arm64 support)
- ktlint 1.5.0 (plugin 12.1.2) - Kotlin code formatter and linter (applied to all modules except `test-services`)
## Key Dependencies
- Ktor 3.3.3 - Foundation of all HTTP communication (platform, collector, agent, tests)
- PostgreSQL 16 - Transactional data store (organizations, services, captured inputs)
- Exposed 0.57.0 - Type-safe ORM queries (critical for data model integrity)
- Flyway 9.22.3 - Schema versioning and migrations
- java-jwt 4.4.0 - RS256 JWT validation in both platform and collector servers
- Fabric8 Kubernetes Client 6.10.0 - Enables agent Loop 1 (K8s service discovery)
- kotlinx-serialization-json 1.7.3 - Shared serialization for all API contracts
- HikariCP 5.1.0 - Connection pool management (tunable for Cloud Run concurrency)
- google-cloud-secretmanager 2.54.0 - Secure secret delivery in GCP production
- cloud-sql-postgres-socket-factory 1.21.0 - IAM-authenticated DB connections (Cloud Run)
- logback + logstash-logback-encoder - Structured logging for observability
- TestContainers 2.0.3 - Isolated integration tests without external services
- MockK 1.13.9 - Unit test mocking
- Gradle 8.11 - Reproducible builds via wrapper
## Configuration
- `DATABASE_URL` - JDBC URL (default: `jdbc:postgresql://localhost:5432/platform`)
- `DATABASE_USER` - Postgres username (default: `postgres`)
- `DATABASE_PASSWORD` - Postgres password (read via SecretsProvider)
- `DATABASE_AUTH_MODE` - `password` (default) or `iam` (Cloud Run with Workload Identity)
- `DATABASE_POOL_SIZE` - HikariCP max pool size (default: 10)
- `DATABASE_CONNECTION_TIMEOUT_MS` - Connection timeout (default: 30,000 ms)
- `JWT_PRIVATE_KEY` - PEM-encoded RSA private key (pipes used for newlines in env vars)
- `SECRETS_PROVIDER` - `literal` (default, env vars) or `gcp` (Cloud Secret Manager)
- `PLATFORM_URL` - Platform server URL for config polling (default: `http://platform.validation.svc.cluster.local:8080`)
- `COLLECTOR_URL` - Collector server URL for traffic ingestion (default: falls back to `PLATFORM_URL`)
- `API_KEY` - JWT bearer token for authentication (sourced from Kubernetes Secret `platform-api-key/jwt-token`)
- `KUBESHARK_URL` - Kubeshark WebSocket endpoint (default: `http://kubeshark-front.default:80`)
- Ktor application modules configured in `application.yaml` per module:
## Platform Requirements
- Java 21 (Eclipse Temurin JRE)
- Gradle 8.11 (via wrapper)
- Docker + Docker Compose (for `dockerUp` / `dockerDown`)
- Colima or Docker Desktop (macOS; TestContainers auto-detects Colima socket at `~/.colima/docker.sock`)
- kubectl (for Kubernetes test deployments and TAP/agent management)
- Terraform (for GCP infrastructure via `scripts/platform-up.sh`)
- gcloud CLI (for GCP authentication and Secret Manager access)
- GCP Project with:
- GKE cluster for agent + vp-tap DaemonSet deployment (Kubeshark integration)
- Docker Compose (spins up PostgreSQL + platform + collector containers)
- k3s (TestContainers k3s cluster for integration tests)
- Local Kubernetes via minikube or Colima cluster
## Container Images
- `validation-platform:test` - Built locally for e2e tests via `deploy/Dockerfile.platform`
- `validation-collector:test` - Built locally for e2e tests via `deploy/Dockerfile.collector`
- `validation-agent:latest` - Built via Jib from `agent/build.gradle.kts`
- `vp-tap:prototype` - Privileged DaemonSet pod for eBPF traffic capture
- Artifact Registry: `us-central1-docker.pkg.dev/[PROJECT]/validation/`
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Language and Linting
- **Kotlin** on the JVM (Java 21 toolchain), Gradle Kotlin DSL build (`build.gradle.kts`).
- **ktlint** enforces style across all Kotlin modules. Configured at root `build.gradle.kts`. Run via `./gradlew ktlintCheck`.
- Conventional `.editorconfig` (when present) sets 4-space indent and ~120-char line length consistent with `ktlint_official` style.
- The Go `tap/` module is a separate ecosystem with its own conventions and tooling (see `tap/go.mod`). It is not subject to the Kotlin rules here.
## Package Structure
- `com.platform.shared.*` — `auth`, `database`, `models`, `secrets` (and `testing` fixtures).
- `com.platform.*` (platform) — `api`, `auth`, `database`, `models`.
- `com.platform.collector.*` — `api`, `database`, `models`.
- `com.platform.agent.*` — flat package with a nested `models/` subpackage for wire DTOs.
- `com.platform.e2e.*` — full-stack test cases.
## Naming Conventions
| Element | Convention | Examples |
|---------|------------|----------|
| Files / classes | PascalCase | `Routes.kt`, `ServiceRepository.kt`, `KubesharkClient.kt` |
| Functions | camelCase | `registerService`, `fetchConfig`, `drainBatch` |
| Local vars / params | camelCase | `organizationId`, `targetServices` |
| Constants (`const val`) | UPPER_SNAKE_CASE | `DEFAULT_PAGE_SIZE = 20`, `MAX_PAGE_SIZE = 100`, `MAX_BATCH_SIZE = 1000` |
| Test method names | backtick strings | `` `GET services should return empty page when no services`() `` |
| Exposed tables | plural `object` | `object Organizations : Table("organizations")` |
| Value classes | suffix with `Id` | `OrganizationId`, `ServiceId`, `CapturedInputId` |
| Sealed outcomes | `*Outcome` | `RegistrationOutcome` |
| HTTP client facades | `*Client` | `PlatformClient`, `CollectorClient`, `ConfigClient`, `KubesharkClient` |
| Discovery / pipeline | role-suffixed | `K8sServiceDiscovery`, `TrafficTransformer`, `JwtTokenGenerator` |
## Type Patterns
- All domain models and DTOs are `data class`.
- Wire DTOs are `@Serializable` (kotlinx.serialization).
- Optional fields default to `null` or sensible defaults — additive evolution friendly.
- Example: `data class Service(val id: ServiceId, val organizationId: OrganizationId, ... , val metadata: Map<String, String>? = null)`.
- `@JvmInline value class OrganizationId(val value: String)` (similarly `ServiceId`, `CapturedInputId`).
- `init` block validates UUID format — throws at construction, not at use.
- Companion `generate()` factory provides random UUID v4 values.
- `@Serializable` so they round-trip through JSON as plain strings.
- `sealed class RegistrationOutcome` with `data object Success`, `data class PermanentRejection(...)`, `data class TransientFailure(...)`.
- Used for branching retry/abort logic without exceptions on the happy path.
- `enum class InputType { HTTP, UNKNOWN }`, `enum class Provider { UNKNOWN, MANUAL_SEED, KUBERNETES }`.
- Always include an `UNKNOWN` variant on enums that cross the wire — forward compatibility for additive deploys.
## Serialization
- **kotlinx.serialization** for all JSON.
- All HTTP clients (agent and test code) configure `Json { ignoreUnknownKeys = true; encodeDefaults = true }` to make the agent↔platform contract additive.
- Custom serializers:
- Apply via `@Serializable(with = InstantSerializer::class)` at the property site, not globally.
## HTTP Clients (Agent)
- `buildAgentPlatformHttpClient()` — base Ktor client for `/api/services`, `/api/agent/config`.
- `buildAgentCollectorHttpClient()` — adds `ContentEncoding` plugin (gzip request bodies).
- `buildAgentKubesharkHttpClient()` — adds WebSockets plugin; no auth.
- Bearer auth applied per call via `bearerAuth(apiKey)` (not a default plugin) so the same client can talk to multiple targets.
- All clients accept an optional `HttpClientEngine` parameter to enable `MockEngine` in tests.
- `Json { ignoreUnknownKeys = true }` consistently.
## Repository Pattern
- Repositories are Kotlin `object` (singletons), not classes.
- Every public function is `suspend` and wraps work in `newSuspendedTransaction { ... }` (Exposed coroutine bridge).
- Private extension `ResultRow.toService()` (or equivalent) maps DB rows to domain models — one mapper per repository.
- Pagination uses cursor-based `Page<T>` from `shared/`: `find(...)` accepts `cursor: String?`, `limit: Int`, clamps limit to `[1, MAX_PAGE_SIZE]`, fetches `limit + 1` rows, encodes `nextCursor` from the last row.
- All queries are scoped to `organizationId` from the JWT principal — tenant isolation lives at this layer, not the route.
## Exposed ORM
- Tables defined as `object Foo : Table("foos") { val id = ...; init { uniqueIndex(...) } }`.
- Always use `newSuspendedTransaction { ... }`; never raw `transaction { }` in production code.
- Cursor helpers `encodeCursor(timestamp, id)` / `decodeCursor(cursor)` shared in repositories.
- Unique constraints (`uniqueIndex`) enforce business invariants at the DB layer — e.g., `(organizationId, cluster, namespace, name)` on `services`.
## JWT and Auth
- Single shared installer: `installJwtAuth(privateKeyPem)` from `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt`.
- Both platform and collector call it once in their `Application.module()`.
- Required claims: `organizationId` (UUID string), `cluster` (string). Optional: `role`.
- Principal: `AgentIdentity(organizationId, cluster, role?)`. Routes obtain it via `call.principal<AgentIdentity>()`.
- Unauthenticated routes: `/health`, `/.well-known/*`. Everything under `/api/*` requires a valid bearer token.
- Body fields for tenancy (`organizationId`, `cluster`) are never trusted — they come from the principal.
## Coroutine Patterns
- All I/O is suspending.
- Agent uses structured concurrency: `runBlocking { coroutineScope { launch(...) launch(...) launch(...) } }` in `AgentApplication.main`.
- Shared mutable state in the agent flows through a `MutableStateFlow<DynamicConfig>` — observers `.collect()` or read `.value`.
- `CancellationException` is always re-thrown when caught generically; otherwise structured concurrency breaks.
- Backpressure is propagated via bounded `Channel`s (`Channel<KubesharkEntry>(capacity = 1000)` in `KubesharkClient`).
## Error Handling
- API routes: validation errors → 400; uniqueness violations → 409; not-found → 404 (also for tenant-mismatch — never 403, to avoid leaking existence).
- Ktor `StatusPages` plugin centralizes unhandled exception → 500 mapping.
- Repositories let Exposed exceptions bubble; routes translate.
- Agent uses outcomes (`RegistrationOutcome`) instead of exceptions for predictable failure modes; exceptions reserved for truly exceptional cases.
- Loops catch broadly, log, sleep, retry — but always re-throw `CancellationException`.
## Logging
- SLF4J + Logback. Per-class instance:
- Object loggers use a named string: `LoggerFactory.getLogger("ServiceDiscoveryLoop")`.
- Parameterized messages: `logger.info("Registered service {} → {}", name, id)` — no string interpolation.
- Levels: INFO for state transitions, WARN for transient failures, ERROR for unrecoverable errors.
## Comments
- KDoc on public types and non-obvious functions, focused on the *why* (constraints, invariants, design rationale).
- Inline `//` comments reserved for subtle invariants or callouts (e.g., "Drop entries older than `lastSeen - 5s` — covers reconnect-replay noise").
- Trailing-summary or change-log comments are avoided; CLAUDE.md and git history are authoritative.
## File-Level Conventions
- One public class per file; helpers can live alongside if they're tightly coupled.
- Route files (`Routes.kt`) group endpoints by resource: `route("/api/services") { get { ... } post { ... } }`.
- Wire DTOs for an HTTP API live next to their consuming routes (`api/Requests.kt`) — not in `models/`. `models/` is for domain types.
## Cross-Module Contracts
- No compile-time imports across modules except `:shared` ← anyone, and tests.
- Agent does not depend on `:platform` or `:collector` — wire DTOs are duplicated and kept in sync via `ignoreUnknownKeys`.
- New endpoints introduced on platform/collector must be additive: optional fields with defaults; never remove or rename fields without a versioning plan.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## System Overview
```text
```
## Component Responsibilities
| Component | Responsibility | Location |
|-----------|----------------|----------|
| **platform** | Organizations + Services + JWKS + agent config | `platform/src/main/kotlin/com/platform/` |
| **collector** | CapturedInputs ingestion + list/delete | `collector/src/main/kotlin/com/platform/collector/` |
| **agent** | K8s discovery + Kubeshark polling + traffic forwarding | `agent/src/main/kotlin/com/platform/agent/` |
| **shared** | JWT auth + DatabaseFactory + value classes + test fixtures | `shared/src/main/kotlin/com/platform/shared/` |
| **e2e-tests** | Full-stack integration tests | `e2e-tests/` |
| **test-services** | Standalone microservices for k3s integration testing | `test-services/*/` |
| **tap** (Go) | Experimental eBPF traffic-attribution tap (separate ecosystem) | `tap/` |
## Pattern Overview
- Each module owns its database tables and repositories — no cross-module shared repositories.
- Cross-module data access flows exclusively through REST HTTP calls. No DB-level foreign keys across modules (V0006 dropped the last one).
- Shared infrastructure (`shared/`) provides JWT auth, DB connection pooling, Flyway migrations, and test fixtures via `java-test-fixtures`.
- Agent is a standalone Kotlin process (Jib-built container) deployed independently to customer clusters; it has no compile-time dependency on `platform/` or `collector/` — only the API contract.
- Platform and collector both run as Ktor 3 servers with in-app RS256 JWT validation (no Envoy / reverse proxy in the request path).
- Authentication via `AgentIdentity` principal populated directly from JWT claims (`organizationId`, `cluster`, `role`).
## Layers (per module)
- Purpose: Ktor route handlers — validate input, apply pagination, enforce tenant isolation via JWT principal.
- Location: `platform/src/main/kotlin/com/platform/api/Routes.kt`, `collector/src/main/kotlin/com/platform/collector/api/Routes.kt`.
- Contains: route definitions, request/response DTOs, HTTP status mapping.
- Depends on: database layer, shared auth (`installJwtAuth`, `AgentIdentity`).
- Purpose: Data access via Exposed ORM, query builders, cursor pagination, tenant scoping.
- Location: `platform/src/main/kotlin/com/platform/database/`, `collector/src/main/kotlin/com/platform/collector/database/`.
- Contains: Repository singletons (`ServiceRepository`, `OrganizationRepository`, `CapturedInputRepository`), Exposed `Table` definitions, ResultRow → domain mappers.
- Depends on: Exposed ORM, PostgreSQL JDBC, shared `Page<T>`.
- Purpose: Domain models, value classes, serialization adapters.
- Location: `platform/src/main/kotlin/com/platform/models/`, `collector/src/main/kotlin/com/platform/collector/models/`.
- Contains: `Organization`, `Service`, `CapturedInput`, `InputType`, `Provider`, value class IDs.
- Depends on: kotlinx.serialization, shared value classes.
- Purpose: cross-module utilities — auth, DB pool, migrations, test fixtures.
- Location: `shared/src/main/kotlin/com/platform/shared/`.
- Contains: `DatabaseFactory`, `installJwtAuth()`, `derivePublicKey()`, `Page<T>`, `InstantSerializer`, `OrganizationId`, `ServiceId`.
- Depends on: Ktor, Exposed, Flyway, HikariCP, `com.auth0:java-jwt`, kotlinx.serialization.
- Purpose: three independent coroutine loops coordinating via a shared `MutableStateFlow<DynamicConfig>`.
- Location: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`.
- Loop 1 (Service Discovery): `K8sServiceDiscovery` (Fabric8) → `PlatformClient.registerService()` → `RegistrationOutcome`.
- Loop 2 (Config Polling): `ConfigClient.fetchConfig()` → updates `MutableStateFlow<DynamicConfig>`.
- Loop 3 (Traffic Capture): `KubesharkClient` (persistent WebSocket, observes config) → `TrafficTransformer` (filter + decode + sample) → `CollectorClient.sendBatch()`.
## Data Flow
### Primary Path: Agent Captures and Forwards Traffic
### Loop 1: K8s Service Discovery → Registration
### Loop 2: Config Polling
### State Management
- **DynamicConfig**: `MutableStateFlow<DynamicConfig>` shared across all three loops via parameter injection.
- **Static Configuration**: env vars read once at startup (`PLATFORM_URL`, `COLLECTOR_URL`, `KUBESHARK_URL`, `API_KEY`).
- **Registered Services / Permanently Failed**: in-memory sets local to `serviceDiscoveryLoop()`.
- **No global mutable state** in platform/collector — routes are stateless; tenancy comes from the JWT principal on each call.
## Key Abstractions
## Entry Points
- **Platform server** — `platform/src/main/kotlin/com/platform/Application.kt`. Ktor Netty engine, reads `platform/src/main/resources/application.yaml`, calls `Application.module()`: JWT auth, DatabaseFactory init, routing, exception handling, JSON content negotiation.
- **Collector server** — `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt`. Reads `collector/src/main/resources/application.yaml`. Same shape as platform, plus the `Compression` plugin for gzip-decoded ingest.
- **Validation agent** — `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt::main`. `runBlocking { coroutineScope { launch(...) } }` spawns three loops, touches `/tmp/agent-alive` for liveness, runs until cancelled.
- **JWT token generator** — `platform/src/main/kotlin/com/platform/auth/JwtTokenGenerator.kt`. CLI invoked via `./gradlew :platform:generateToken --args="--org <uuid> --cluster <name>"`.
## Architectural Constraints
- **Threading model**: coroutines-first. Agent uses structured concurrency (`coroutineScope { launch { ... } }`). Platform/collector use Ktor's Netty thread pool plus Exposed `newSuspendedTransaction` for DB I/O.
- **DB connection pool**: singleton `HikariCP` initialized once at `DatabaseFactory.init()`. Pool size set by `DATABASE_POOL_SIZE` (default 10). Agent has no DB access.
- **JWT key material**: shared `JWT_PRIVATE_KEY` env var, PEM-encoded RSA private key (newlines replaced with `|` for env compatibility). Public key derived in-process by `derivePublicKey()`.
- **Tenant isolation**: every repository scopes queries to `organizationId` from the JWT principal. Routes return 404 (not 403) when a resource belongs to a different tenant, to avoid leaking existence.
- **Module lifecycle**: `K8sServiceDiscovery` implements `Closeable` (wraps Fabric8 `KubernetesClient`). HTTP clients are `Closeable` and released on shutdown.
## Anti-Patterns to Avoid
## Error Handling Strategy
- **API routes**: validation errors map to 400; unique-constraint violations to 409; not-found to 404 (also used for tenant-mismatch). Unexpected exceptions surface to Ktor's `StatusPages` for centralized formatting.
- **Repositories**: Exposed exceptions bubble up; routes convert to status codes.
- **Agent loops**: catch, log, sleep, retry. `CancellationException` is always re-thrown to respect structured concurrency.
- **WebSocket sessions**: `KubesharkClient` catches connection/protocol errors, closes the channel cleanly, waits `reconnectDelay`, then opens a fresh session.
## Cross-Cutting Concerns
- **Logging**: SLF4J + Logback. INFO for normal operation, WARN for transient errors, ERROR for unrecoverable failures. Parameterized messages with `{}` placeholders.
- **Validation**: routes validate input (RFC1123 labels, UUID format, non-blank strings); DB uniqueness via Exposed `uniqueIndex`; JWT required claims checked in `installJwtAuth`.
- **Authentication**: shared `installJwtAuth(privateKeyPem)` in both servers; agent attaches `Authorization: Bearer <JWT>` on every `/api/*` call; JWKS at `/.well-known/jwks.json` unauthenticated.
- **Secrets**: `JWT_PRIVATE_KEY` and DB creds via env vars in Docker/Compose; via Secret Manager + IAM in GCP; via Kubernetes `Secret` (`platform-api-key/jwt-token`) for the agent.
- **Deployment topology**: platform + collector → Cloud Run (Cloud SQL via private IP); agent + Kubeshark → GKE; test workloads → GKE namespaces (infrastructure / production / external).
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
