<!-- refreshed: 2026-05-14 -->
# Architecture

**Analysis Date:** 2026-05-14

## System Overview

```text
CUSTOMER'S PRODUCTION CLUSTER
┌──────────────────────────────────────────────────────┐
│  Kubeshark (eBPF)      Validation Agent              │
│  captures HTTP ───────► 3 Coroutine Loops:           │
│  req/res pairs          - K8s Service Discovery      │
│                         - Config Polling             │
│  order-service          - Traffic Capture            │
│  api-gateway                                         │
│  notification-svc       MutableStateFlow<DynamicConfig>
└──────────────┬──────────────┬───────────────────────┘
               │ JWT (config) │ JWT (traffic)
               ▼              ▼
PLATFORM (Cloud Run / GKE)
┌───────────────────────────────────────────────────────┐
│  Platform (8080)           Collector (8081)           │
│  - RS256 JWT in-app        - RS256 JWT in-app         │
│  - Organizations table     - CapturedInputs table     │
│  - Services table          - POST batch ingest        │
│  - JWKS endpoint           - GET/DELETE               │
│  - Agent config endpoint   - Gzip decompression       │
└───────────────────────────────────────────────────────┘
              ▲
              │ JDBC (HikariCP pool)
              │
        Cloud SQL / PostgreSQL
        - Flyway migrations V0001-V0007
        - Shared schema; module-owned tables
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

**Overall:** Modular monolith with enforced module boundaries via HTTP APIs.

Key characteristics:
- Each module owns its database tables and repositories — no cross-module shared repositories.
- Cross-module data access flows exclusively through REST HTTP calls. No DB-level foreign keys across modules (V0006 dropped the last one).
- Shared infrastructure (`shared/`) provides JWT auth, DB connection pooling, Flyway migrations, and test fixtures via `java-test-fixtures`.
- Agent is a standalone Kotlin process (Jib-built container) deployed independently to customer clusters; it has no compile-time dependency on `platform/` or `collector/` — only the API contract.
- Platform and collector both run as Ktor 3 servers with in-app RS256 JWT validation (no Envoy / reverse proxy in the request path).
- Authentication via `AgentIdentity` principal populated directly from JWT claims (`organizationId`, `cluster`, `role`).

## Layers (per module)

**API Layer**
- Purpose: Ktor route handlers — validate input, apply pagination, enforce tenant isolation via JWT principal.
- Location: `platform/src/main/kotlin/com/platform/api/Routes.kt`, `collector/src/main/kotlin/com/platform/collector/api/Routes.kt`.
- Contains: route definitions, request/response DTOs, HTTP status mapping.
- Depends on: database layer, shared auth (`installJwtAuth`, `AgentIdentity`).

**Database Layer**
- Purpose: Data access via Exposed ORM, query builders, cursor pagination, tenant scoping.
- Location: `platform/src/main/kotlin/com/platform/database/`, `collector/src/main/kotlin/com/platform/collector/database/`.
- Contains: Repository singletons (`ServiceRepository`, `OrganizationRepository`, `CapturedInputRepository`), Exposed `Table` definitions, ResultRow → domain mappers.
- Depends on: Exposed ORM, PostgreSQL JDBC, shared `Page<T>`.

**Model Layer**
- Purpose: Domain models, value classes, serialization adapters.
- Location: `platform/src/main/kotlin/com/platform/models/`, `collector/src/main/kotlin/com/platform/collector/models/`.
- Contains: `Organization`, `Service`, `CapturedInput`, `InputType`, `Provider`, value class IDs.
- Depends on: kotlinx.serialization, shared value classes.

**Shared Infrastructure (`shared/`)**
- Purpose: cross-module utilities — auth, DB pool, migrations, test fixtures.
- Location: `shared/src/main/kotlin/com/platform/shared/`.
- Contains: `DatabaseFactory`, `installJwtAuth()`, `derivePublicKey()`, `Page<T>`, `InstantSerializer`, `OrganizationId`, `ServiceId`.
- Depends on: Ktor, Exposed, Flyway, HikariCP, `com.auth0:java-jwt`, kotlinx.serialization.

**Agent Coroutine Loops**
- Purpose: three independent coroutine loops coordinating via a shared `MutableStateFlow<DynamicConfig>`.
- Location: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`.
- Loop 1 (Service Discovery): `K8sServiceDiscovery` (Fabric8) → `PlatformClient.registerService()` → `RegistrationOutcome`.
- Loop 2 (Config Polling): `ConfigClient.fetchConfig()` → updates `MutableStateFlow<DynamicConfig>`.
- Loop 3 (Traffic Capture): `KubesharkClient` (persistent WebSocket, observes config) → `TrafficTransformer` (filter + decode + sample) → `CollectorClient.sendBatch()`.

## Data Flow

### Primary Path: Agent Captures and Forwards Traffic

1. **Kubeshark eBPF** in the customer's production cluster captures HTTP request/response pairs at L7.
2. **`KubesharkClient`** (`agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt`) maintains a persistent WebSocket session to `${KUBESHARK_URL}/api/wsFull`:
   - Sends a KFL query as the first text frame: `"http"` or `"http and (dst.name == svc1 or dst.name == svc2 ...)"`.
   - Receives HAR-ish JSON frames.
   - Buffers entries into a bounded `Channel<KubesharkEntry>` (capacity 1000) for TCP backpressure.
   - Observes `StateFlow<DynamicConfig>` via a `configWatcherJob`; reconnects with a fresh KFL query when `targetServices` changes.
   - Drops entries older than `lastSeenTimestamp - 5s` on reconnect (dedup window for replay noise).
3. **`TrafficTransformer`** (`agent/src/main/kotlin/com/platform/agent/TrafficTransformer.kt`) observes the same `StateFlow<DynamicConfig>` and applies:
   - Client-side service filter (safety net for reconnect-replay leakage).
   - Base64 decode of `response.content.text` when `content.encoding == "base64"`.
   - Sampling at the configured rate.
4. **`CollectorClient`** (`agent/src/main/kotlin/com/platform/agent/CollectorClient.kt`) batches up to `batchSize` entries, gzip-compresses, and POSTs to `${COLLECTOR_URL}/api/captured-inputs` with `Authorization: Bearer <JWT>`.
5. **Collector route** (`collector/src/main/kotlin/com/platform/collector/api/Routes.kt`) validates JWT, stamps `organizationId` from the principal, and inserts the batch via `CapturedInputRepository.createBatch()`.

### Loop 1: K8s Service Discovery → Registration

1. `K8sServiceDiscovery.discover()` lists K8s `Service` resources, filters out system namespaces, and validates required pod-selector labels.
2. `serviceDiscoveryLoop()` diffs against `registeredServices` and `permanentlyFailed` sets. For each new service:
   - `PlatformClient.registerService()` → POST `/api/services` with bearer JWT.
   - Result classified by `RegistrationOutcome`:
     - `Success` → added to in-memory `name → serviceId` map.
     - `PermanentRejection` (400/422) → added to `permanentlyFailed`, never retried.
     - `TransientFailure` (anything else) → retried on next discovery tick.
3. Platform stamps `organizationId` and `cluster` from the JWT — never from the request body.

### Loop 2: Config Polling

1. `ConfigClient.fetchConfig()` GETs `/api/agent/config` with bearer JWT.
2. Platform returns `AgentConfigResponse(targetServices, samplingRate, batchSize, captureInterval, configPollInterval, discoveryInterval, namespaceFilters)`.
3. `configPollLoop()` writes the result into `MutableStateFlow<DynamicConfig>`.
4. `KubesharkClient` reacts: cancel current WS session and reconnect with the new KFL query.
5. `TrafficTransformer` reacts: updated sampling rate applied to new entries.

### State Management

- **DynamicConfig**: `MutableStateFlow<DynamicConfig>` shared across all three loops via parameter injection.
- **Static Configuration**: env vars read once at startup (`PLATFORM_URL`, `COLLECTOR_URL`, `KUBESHARK_URL`, `API_KEY`).
- **Registered Services / Permanently Failed**: in-memory sets local to `serviceDiscoveryLoop()`.
- **No global mutable state** in platform/collector — routes are stateless; tenancy comes from the JWT principal on each call.

## Key Abstractions

**`AgentIdentity`** — JWT principal containing `organizationId`, `cluster`, `role?`. Resolved by `installJwtAuth()`, available via `call.principal<AgentIdentity>()`.

**Value classes** — `OrganizationId`, `ServiceId` (`shared/`), and collector-local `CapturedInputId`. `@JvmInline value class`, UUID validated at construction, zero runtime overhead.

**`Page<T>`** — cursor-based pagination model in `shared/`. Cursor is base64(`timestamp|id`); repositories fetch `pageLimit + 1` rows to detect `nextCursor`.

**`RegistrationOutcome`** — sealed class in `agent/`: `Success` / `PermanentRejection(service, status, error)` / `TransientFailure(service, error)`. Drives Loop 1 retry policy.

**`KubesharkEntry`** — HAR-ish wire DTO matching Kubeshark's `/api/wsFull` frame format. `request.postData.text` is plaintext; `response.content.text` is base64 when `content.encoding == "base64"`.

**`BatchCreateCapturedInputRequest` / `Response`** — agent↔collector ingest contract. Agent batches up to `batchSize`, collector replies with `created` count.

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

**Cross-module DB access.** No module should import another module's repository. Replay engine will fetch captured inputs via `GET /api/captured-inputs`, not by importing `CapturedInputRepository`. Enforced socially (no compile-time guard); reviewers should flag any such import.

**Adding DB-level FKs across modules.** V0006 explicitly dropped the FK between `captured_inputs.service_id` and `services.id`. Module decoupling depends on this. New cross-module FKs should be rejected — referential integrity is application-level only.

**Trusting body fields for tenancy.** `organizationId` and `cluster` on `POST /api/services` and `POST /api/captured-inputs` are stamped from the JWT, never from the request body. Adding tenant fields to request DTOs would break the security model.

**Retrying permanent registration failures.** `serviceDiscoveryLoop()` treats 400/422 as `PermanentRejection` — unrecoverable. Don't widen this to other codes (auth errors must keep retrying because they're caller-level, not per-service).

**Unbounded WebSocket channels.** `KubesharkClient` uses a bounded `Channel(1000)` to propagate TCP backpressure. Switching to an unbounded channel would OOM the agent under load.

**Sharing compile-time types between agent and platform/collector.** Agent ships and versions independently. Wire DTOs are duplicated by design with `Json { ignoreUnknownKeys = true }` for additive evolution.

## Error Handling Strategy

Distinguish transient vs permanent; log and continue vs fail fast.

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

---

*Architecture analysis: 2026-05-14*
