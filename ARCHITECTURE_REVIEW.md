# Architecture Review — Validation Platform

**Date:** 2026-04-12
**Reviewer:** Claude Opus 4.6 (architecture-reviewer agent)
**Scope:** Full-system audit of all modules, database layer, API layer, agent, test suite, deployment, and security.

---

## Overall Assessment

**Verdict: NEEDS REVISION**

The codebase demonstrates strong architectural discipline. Module boundaries are consistently enforced, the adapter pattern is properly implemented, coroutine usage is mostly correct, and the test suite is substantive. However, there are meaningful gaps — particularly around the missing `POST /api/captured-inputs` endpoint (the critical path between agent and collector doesn't exist yet), several security issues in the Kubernetes manifest, and a number of correctness edge cases that will bite in production.

---

## Critical Issues (Bugs or Security Vulnerabilities)

### CRITICAL-2: API Key Hardcoded in Plain Text in Kubernetes Manifest

- **Location**: `k8s/agent/agent.yaml`, line 56
- **Issue**: `API_KEY: "test-api-key"` is a literal string in a checked-in Kubernetes manifest. Even if this is acknowledged as a dev reference, manifests frequently get copy-pasted into production environments. There is no RBAC on the collector that would validate this token server-side anyway (no auth middleware in `Routes.kt`), making the token theatrical security at best.
- **Impact**: Any code that reaches the collector endpoint can write arbitrary captured traffic. In a multi-tenant future this is a data integrity and privacy problem.
- **Fix**: Use a Kubernetes `Secret` with `secretKeyRef` for `API_KEY`. Add bearer token validation middleware to the collector routes.

---

## Architectural Issues (Design Problems)

### ARCH-1: `ConfigClient` URL Points to Collector, But the Config Endpoint Belongs to App

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`, lines 41-43
- **Issue**: `AgentApplication.kt` creates the `ConfigClient` with `staticConfig.collectorUrl`. The `GET /api/agent/config` endpoint is documented as belonging to the `app` module (port 8080), but the agent only has one base URL for the "platform". The current code points the config client at the collector (8081), and the config endpoint does not exist on either service. When the endpoint is eventually implemented in `app`, the agent will need a separate `APP_URL` env var or the collector will need to proxy it.
- **Impact**: The config polling loop will indefinitely log warnings against the wrong service when the endpoint is implemented. The static config model has a coherence problem.
- **Fix**: Either add an `APP_URL` env var to `StaticConfig` for the config endpoint, or implement the config endpoint in the collector and have it delegate to app. Document the decision explicitly.

---

### ARCH-2: Repositories Are `object` Singletons — Untestable Without a Live Database

- **Location**: `app/src/main/kotlin/com/platform/database/OrganizationRepository.kt`, `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, `collector/src/main/kotlin/com/platform/collector/database/CapturedInputRepository.kt`
- **Issue**: All three repositories are Kotlin `object` singletons. Routes call them as global state. It's impossible to inject mock repositories for unit testing routes without a real database. Every route test requires a PostgreSQL container.
- **Impact**: Every route test requires TestContainers and Docker. The architecture cannot evolve to support repository interfaces without a breaking refactor.
- **Fix**: Convert repositories to classes and inject them through the Ktor `Application` extension function. Low-urgency but the pattern should not spread to new modules.

---

### ARCH-4: `DatabaseFactory` Has No Connection Pool Configuration — Uses Exposed Defaults

- **Location**: `shared/src/main/kotlin/com/platform/database/DatabaseFactory.kt`
- **Issue**: `Database.connect(...)` called without a `DataSource` means Exposed manages connections internally using its default pool, which is not HikariCP. The CLAUDE.md mentions "HikariCP connection pool" but the code does not use it. No configuration of pool size, connection timeout, idle timeout, or health validation.
- **Impact**: Production reliability risk. No connection validation, no min/max pool sizing, no connection leak detection, no metrics.
- **Fix**: Pass a `HikariDataSource` to `Database.connect`. Add `maximumPoolSize`, `connectionTimeout`, `idleTimeout`, and `validationTimeout` configuration from environment variables.

---

## Code Quality Issues (Smells, Inconsistencies)

### QUALITY-1: `decodeCursor` Returns UUID But Models Use String IDs

- **Location**: `shared/src/main/kotlin/com/platform/models/Page.kt`, line 24
- **Issue**: `decodeCursor` returns `Pair<Instant, UUID>`, but the `id` field in all models is a `String`. Inconsistent types.
- **Quick fix**: Change the return type to `Pair<Instant, String>` (keep `UUID.fromString` for validation, return `parts[1]` as a String). Surgical, 1-file change.
- **Proper fix**: Introduce Kotlin `value class` newtype wrappers per entity (e.g., `value class OrganizationId(val value: UUID)`, `value class ServiceId(val value: UUID)`). This gives compile-time safety against mixing up different entity IDs — plain `UUID` everywhere doesn't prevent passing an `organizationId` where a `serviceId` is expected. Requires a custom kotlinx `UUIDSerializer`, touches models, repositories, route handlers, and tests across all modules. Should be a dedicated refactor PR, not mixed with bug fixes.

---

### QUALITY-2: `CollectorDatabaseTestBase` Does Not Delete Service/Organization Rows

- **Location**: `collector/src/test/kotlin/com/platform/collector/database/CollectorDatabaseTestBase.kt`
- **Issue**: `cleanTables()` only deletes `CapturedInputs`, but `AppApiTestHelper` inserts into `organizations` and `services` in `@BeforeEach`. These accumulate across all test methods — tests are not fully isolated.
- **Fix**: Clean `CapturedInputs`, then `Services`, then `Organizations` (FK order) in `cleanTables()`.

---

### QUALITY-3: `DynamicConfig` Fields Are Not Validated After Deserialization

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt`; `agent/src/main/kotlin/com/platform/agent/ConfigClient.kt`, line 40
- **Issue**: No validation after deserializing `DynamicConfig`. A `samplingRate = -0.5`, `batchSize = 0`, or `captureInterval = 0ms` would cause severe issues — e.g., zero captureInterval turns the capture loop into a tight-spin CPU loop.
- **Fix**: Add a `validate()` method and call it after deserialization, returning `null` on invalid config.

---

### QUALITY-7: `ServiceRepository.create` Does Not Handle Invalid UUID organizationId

- **Location**: `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, line 32; `app/src/main/kotlin/com/platform/api/Routes.kt`, line 87
- **Issue**: `UUID.fromString(service.organizationId)` throws `IllegalArgumentException` with a generic "Bad request" message rather than "organizationId must be a valid UUID".
- **Fix**: Add explicit UUID validation in route handlers with descriptive error messages.

---

### QUALITY-8: `ignoreUnknownKeys = true` on Server-Side JSON Parser

- **Location**: `app/src/main/kotlin/com/platform/Application.kt`, line 41; same in `CollectorApplication.kt`
- **Issue**: Server silently accepts request bodies with unrecognized fields. Typos in field names are invisible. A client sending `{"organizationid": "..."}` instead of `{"organizationId": "..."}` gets a confusing error for a missing required field rather than an unknown field warning.
- **Fix**: Use `ignoreUnknownKeys = true` only for client-side deserialization (agent). On the server side, omit it for request body parsing.

---

---

## Positive Patterns Worth Preserving

1. **Structured concurrency in the agent is done correctly.** `AgentApplication` passes `coroutineScope` to `KubesharkClient`, which launches its streamer job within that scope. Cancellation propagates cleanly. `CancellationException` is properly re-thrown in `KubesharkClient.streamerLoop` and `CollectorClient.tryPost`.

2. **The `CollectorClient` retry model is sound.** Exponential backoff with configurable cap, 4xx as permanent failures, 5xx and network errors as transient, backpressure through the channel.

3. **Cursor-based pagination is consistent across all three repositories.** The `(timestamp, id)` compound cursor with the correct OR predicate avoids duplicates and gaps. The `limit + 1` probe for `hasMore` is idiomatic.

4. **Module boundary enforcement via `AppApiTestHelper` is principled.** Collector tests create fixtures through app's HTTP API, not by importing repositories directly.

5. **`KubesharkClient` dedup window design is well-reasoned.** The timestamp sliding-window vs LRU ID cache trade-off is correctly analyzed and documented.

6. **Flyway migration chain is safe.** V0001-V0005 are additive only, no destructive DDL. Zero-padding prevents ordering issues.

7. **`DurationAsMillisSerializer` in `AgentConfig` is clean.** Bridges Kotlin `Duration` to wire-format `Long` without polluting the in-memory type. Test verifies wire format correctness.

8. **Agent's `AtomicReference<DynamicConfig>` concurrency model is appropriate.** Config snapshot at transform call start ensures consistency. No locks needed.

---

# Architecture Review — Validation Platform (Continued)

**Date:** 2026-04-18
**Reviewer:** Claude Sonnet 4.6 (architecture-reviewer agent)
**Scope:** Follow-up audit covering gaps not addressed in the 2026-04-12 review. All findings below are new. Nothing here duplicates the prior review.

---

## New Critical Issues

### CRITICAL-3: `serviceDiscoveryLoop` Swallows `CancellationException`

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`, lines 76–85
- **Issue**: The loop's `catch (e: Exception)` block catches all exceptions, including `CancellationException`. `CancellationException` extends `Exception` in Kotlin's coroutine library (specifically via `IllegalStateException` inheritance chain). Because `CancellationException` is caught and logged rather than re-thrown, cancelling the parent `coroutineScope` does not cleanly terminate this loop. The loop will continue executing after the parent scope is cancelled, which means the agent cannot shut down cleanly.
- **Comparison**: `KubesharkClient.streamerLoop` (line 126) and `CollectorClient.tryPost` (line 152) both correctly catch `CancellationException` and re-throw it. The service discovery loop is inconsistent with those patterns.
- **Impact**: A cancelled agent (e.g., `SIGTERM` triggering `coroutineScope` cancellation) will hang instead of exiting. The JVM process stays alive indefinitely, blocking pod replacement in Kubernetes rolling deploys.
- **Fix**: Add an explicit `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` handler, the same pattern used in the rest of the codebase.

---

### CRITICAL-4: `POST /api/captured-inputs` Has No Batch Size Limit — Memory Exhaustion Risk

- **Location**: `collector/src/main/kotlin/com/platform/collector/api/Routes.kt`, lines 31–55
- **Issue**: The only guard on the batch endpoint is `request.items.isEmpty()`. There is no upper bound on `request.items.size`. An agent misconfigured with an enormous batch size, or a malicious client, can POST a single request with hundreds of thousands of items. The route immediately allocates a full in-memory `List<CapturedInput>` (one per item) before calling `createBatch`. With large response bodies stored as `String`, this can easily exhaust the collector's heap.
- **Impact**: Collector OOM kill under sustained high traffic or a single oversized request. The collector is shared across all tenants in the current design, so one agent can affect others.
- **Fix**: Add a `MAX_BATCH_SIZE` constant (e.g., 1000, matching the agent's channel capacity) and return `400 Bad Request` if `request.items.size > MAX_BATCH_SIZE`. Document the limit in `BatchCreateCapturedInputRequest` KDoc.

---

## New Architectural Issues

### ARCH-5: `KubernetesAdapter` Hardcodes `"default"` Namespace in System Namespace Exclusion List

- **Location**: `app/src/main/kotlin/com/platform/adapters/KubernetesAdapter.kt`, lines 54–60
- **Issue**: The `SYSTEM_NAMESPACES` set includes `"default"`. The `default` Kubernetes namespace is a valid namespace for customer workloads in many clusters — it is not a system namespace. `kube-system`, `kube-public`, and `kube-node-lease` are genuinely system namespaces. Including `"default"` silently hides services deployed there, with no log message or warning to tell the operator why those services are absent.
- **Impact**: Any customer that deploys to the `default` namespace (common in smaller clusters and dev environments) will see zero services discovered by the `KubernetesAdapter`. This is an invisible false negative — the adapter returns successfully with a reduced list, so callers cannot distinguish it from "no services in that namespace."
- **Fix**: Remove `"default"` from `SYSTEM_NAMESPACES`. If opinionated filtering is desired, document it explicitly and make it configurable alongside `excludeSystemNamespaces`. Alternatively, rename the constant to `EXCLUDED_NAMESPACES` to signal that this is a policy decision, not a technical constraint.

---

### ARCH-6: `DatabaseTestBase` Uses a Shared Mutable Singleton — Parallel Test Suites Will Interfere

- **Location**: `shared/src/testFixtures/kotlin/com/platform/database/DatabaseTestBase.kt`, lines 9–16
- **Issue**: `DatabaseTestBase.Companion` uses a static `initialized: Boolean` and a static `postgres: PostgreSQLContainer?`. These are `companion object` fields — one instance per classloader. When the `app` test suite and the `collector` test suite both extend `DatabaseTestBase`, they share the same `initialized` flag. In a multi-project Gradle build with `maxParallelForks = 1` per module, the flag prevents each module from starting its own container, so both suites hit the same database instance. This is intentional when running sequentially. However, if anyone ever runs `./gradlew test` with parallel project execution enabled (`--parallel`), both modules' `@BeforeAll` hooks will race to initialize the same static, and one module will silently skip database setup (the `if (initialized) return` guard).
- **Secondary issue**: Because the static `postgres` reference is kept alive indefinitely (never stopped in `@AfterAll`), the TestContainers container leaks until the JVM exits. Ryuk (TestContainers' cleanup daemon) handles this eventually, but it is not explicit.
- **Fix**: Use `@TestcontainersExtension` or a JUnit 5 `@ExtendWith` extension that manages container lifecycle per-class or per-suite, rather than manual `initialized` flags. If the intent is a single container per JVM invocation, use `Testcontainers.LifecycleMode.PER_CLASS` with the declarative `@Container` annotation, which is thread-safe.

---

### ARCH-7: Agent `latencyMs` Field Is Never Populated — Data Is Always `null` in the Database

- **Location**: `agent/src/main/kotlin/com/platform/agent/models/CapturedInputRequest.kt` (no `latencyMs` field); `agent/src/main/kotlin/com/platform/agent/TrafficTransformer.kt`, lines 60–79
- **Issue**: `CapturedInputRequest` (the agent's collector POST DTO) has no `latencyMs` field, even though `KubesharkEntry` carries `elapsedTime: Long?` which is exactly latency in milliseconds. `TrafficTransformer.transform` maps `KubesharkEntry` to `CapturedInputRequest` but never reads `elapsedTime`. The collector's `CreateCapturedInputRequest` has a `latencyMs: Long? = null` field, so the schema supports it — but every row written by the agent will have `NULL` latency.
- **Impact**: The `latency_ms` column exists precisely to support the Mann-Whitney U statistical test that is core to the platform's verdict generation. An always-null column means latency comparison is impossible until this is fixed, at which point all historical data will still be null. This is a silent data quality defect — no errors, no warnings, wrong data.
- **Fix**: Add `val latencyMs: Long? = null` to `CapturedInputRequest`. In `TrafficTransformer.transform`, populate it from `entry.elapsedTime`. Add a test asserting that non-null `elapsedTime` entries produce non-null `latencyMs` in the output.

---

### ARCH-8: `POST /api/captured-inputs` Does Not Validate That `serviceId` Exists — FK Violation Surfaces as Generic 400

- **Location**: `collector/src/main/kotlin/com/platform/collector/api/Routes.kt`, lines 31–55; `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt`, lines 29–32
- **Issue**: The POST route passes `item.serviceId` directly to the `CapturedInput` constructor and then to `createBatch`. The `captured_inputs.service_id` column has a foreign key constraint on `services.id`. When a non-existent `serviceId` is submitted, PostgreSQL throws a constraint violation (`SQLSTATE 23503`), which the `StatusPages` handler catches and returns as `400 Bad Request: "Referenced resource not found"`. This is the right HTTP status code, but the error message is generic. More importantly, the FK constraint creates a cross-module coupling in the database: the collector's table references the app module's table. This violates the module boundary rule stated in `CLAUDE.md` — "Each module owns its tables and repositories."
- **Impact**: The database-layer FK coupling means collector's schema cannot be migrated independently of app's schema. In a future where modules run as separate services with separate databases, this FK cannot exist. The current error message also gives the caller no indication of which field is invalid.
- **Fix for error message**: Catch the `23503` case and return `"serviceId '$id' does not exist"` instead of the generic message.
- **Fix for coupling**: The FK is intentional for now (single-database deployment) but should be documented as a known coupling that must be removed before module databases can be separated. Add a comment in the migration and in `CapturedInputs.kt` noting this.

---

## New Code Quality Issues

### QUALITY-4: `CreateOrganizationRequest.name` and `CreateServiceRequest.name` Have No Blank Validation

- **Location**: `app/src/main/kotlin/com/platform/api/Routes.kt`, line 44–51; `app/src/main/kotlin/com/platform/api/Requests.kt`
- **Issue**: `POST /api/organizations` and `POST /api/services` accept `name = ""` (empty string) or `name = "   "` (whitespace-only) without returning an error. The database will happily store these. An organization named `"   "` is a confusing sentinel that violates the implicit invariant that names are human-readable identifiers.
- **Fix**: In the route handlers, check `request.name.isBlank()` and return `400 Bad Request` with a descriptive message before constructing the entity. This pattern is consistent with how `serviceId` UUID validation is already expected to work.

---

### QUALITY-5: `decodeCursor` Returns `UUID` But `OrganizationRepository` and `ServiceRepository` Compare Against `Organizations.id` and `Services.id` as UUID Columns Directly

- **Location**: `app/src/main/kotlin/com/platform/database/OrganizationRepository.kt`, line 65; `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, line 86; `collector/src/main/kotlin/com/platform/collector/database/CapturedInputRepository.kt`, line 97
- **Issue**: This is distinct from QUALITY-1 (which noted the return type mismatch). The deeper issue is that `decodeCursor` validates the cursor ID via `UUID.fromString(parts[1])` and returns a `UUID`. But `encodeCursor` takes a plain `String` for the ID parameter and writes it verbatim. If any code path passes a non-UUID string to `encodeCursor`, the cursor will be malformed in a way that `decodeCursor` will reject later. Currently all IDs are `UUID.randomUUID().toString()` so this doesn't bite, but the types don't enforce this invariant. A future model with non-UUID IDs (e.g., sequential integers for an imported dataset) would silently produce invalid cursors.
- **Fix**: Change `encodeCursor` signature to `fun encodeCursor(timestamp: Instant, id: UUID): String` so the compiler enforces that only UUID IDs produce cursors. This is consistent with QUALITY-1's recommendation and is the natural companion fix.

---

### QUALITY-6: `Dockerfile.collector` and `Dockerfile.agent` Run as Root / Non-Root Inconsistently

- **Location**: `deploy/Dockerfile.collector` (lines 1–9); `deploy/Dockerfile.agent` (lines 1–14)
- **Issue**: `Dockerfile.agent` correctly adds a non-root `agent` user and switches to it with `USER agent`. `Dockerfile.collector` (and by extension `Dockerfile.app`, which uses the same pattern) has no `USER` instruction — the container process runs as root inside the container. This is a standard container security hardening gap.
- **Impact**: A vulnerability in the Ktor server or its dependencies gives an attacker root inside the collector container, making container escapes significantly easier. This contradicts the principle of least privilege.
- **Fix**: Mirror the agent's pattern in `Dockerfile.collector`:
  ```
  RUN addgroup -S collector && adduser -S collector -G collector
  USER collector
  ```
  Same for `Dockerfile.app`. The `eclipse-temurin:21-jre-alpine` base image supports this cleanly.

---

### QUALITY-9: `DatabaseTestBase` `postgres` Field Is Not Closed — Container Resource Leak

- **Location**: `shared/src/testFixtures/kotlin/com/platform/database/DatabaseTestBase.kt`, line 9
- **Issue**: `private var postgres: PostgreSQLContainer?` is initialized in `@BeforeAll` and never stopped. There is no `@AfterAll` hook calling `postgres?.stop()`. TestContainers' Ryuk sidecar will eventually clean up the container, but the reliance on Ryuk is implicit. In CI environments with Ryuk disabled (some restricted Docker setups), this leaks a running PostgreSQL container per test suite invocation until the JVM exits.
- **Fix**: Add a `@AfterAll @JvmStatic fun tearDownDatabase() { postgres?.stop() }` method to `DatabaseTestBase`. This is safe because `DatabaseTestBase` already manages its own lifecycle in the companion object.

---

### QUALITY-10: `AppApiTestHelper` Spins Up a New `testApplication` Per Call — Startup Cost in Every `@BeforeEach`

- **Location**: `collector/src/test/kotlin/com/platform/collector/database/AppApiTestHelper.kt`, lines 35–75
- **Issue**: `createOrganization` and `createService` each call `testApplication { ... }`, which starts and stops a full embedded Ktor server per call. `CapturedInputRoutesTest.setupServiceFixture()` calls both in sequence in `@BeforeEach`, meaning every test method pays two full Ktor server startup/teardown cycles. On a warm JVM this adds ~50-100ms overhead per test.
- **Impact**: With 15+ tests in `CapturedInputRoutesTest`, this is ~1-2 seconds of avoidable overhead per full run. More importantly, this pattern will scale poorly as more `@BeforeEach` fixtures are added. It also creates two separate transactional scopes (one for org creation, one for service creation) which makes debugging fixture failures harder.
- **Fix**: Either (a) consolidate into one `testApplication` call that creates both org and service in a single embedded server session, or (b) cache the test org and service as class-level fixtures with `@BeforeAll` rather than recreating them for every test method. Since `CollectorDatabaseTestBase.cleanTables` only deletes `CapturedInputs`, org and service rows survive between tests anyway — so recreating them in every `@BeforeEach` is already redundant after the first test method runs in the test class.

---

### QUALITY-11: `ServiceRepository.upsert` Does a Select After Upsert Inside the Same Transaction — Unnecessary Round-Trip

- **Location**: `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, lines 118–153
- **Issue**: After calling `Services.upsert(...)`, the method immediately executes a `selectAll().where { ... }` query to fetch the row back. For the common case (insert), this is a round-trip that could be avoided by returning the inserted values directly from `RETURNING *` (PostgreSQL supports this in `ON CONFLICT DO UPDATE` via `RETURNING`). For the conflict case (update), the concern is getting back the pre-existing `id` — which is valid. However, the comment on line 143 says "may have a different id if it already existed," which is only true on conflict; the select-after-insert path is purely wasteful.
- **Impact**: Double the number of database round-trips for every service upsert, which is on the hot path of agent service discovery (Loop 1 will call this for every discovered K8s service).
- **Fix**: Expose only the conflict case as needing a post-select. For the insert case, Exposed's `upsert` returns a `ResultRow` when using `returning` parameter. Alternatively, given that the upsert keys are `(organizationId, cluster, namespace, name)`, use `upsert` with a `returning` clause or accept the current two-statement approach with a comment explaining why a post-select is necessary.

---

### QUALITY-12: `OrderService` Uses `DriverManager.getConnection` Per Request — No Connection Pool

- **Location**: `test-services/order-service/src/main/kotlin/com/platform/testservices/OrderService.kt`, lines 122–124, and all call sites
- **Issue**: Every HTTP handler calls `getConnection()` which calls `DriverManager.getConnection(...)` — a new physical JDBC connection per request. There is no connection pool. Under the traffic generated by `traffic-generator` (5 reader + 1 writer coroutines), this creates and destroys a new PostgreSQL connection per request. PostgreSQL has a hard limit on connections (default 100), and connection setup has ~5-10ms overhead.
- **Scope**: This is a test service, not platform code, so it doesn't affect production correctness. However, it makes the test services less realistic as a proxy for production behavior, which undermines the purpose of using them to validate the platform.
- **Fix**: Replace `DriverManager.getConnection` with a `HikariDataSource` configured with a small pool (3-5 connections). This is a 10-line change. Since the test services' `build.gradle.kts` already pulls in `postgresql`, just add `hikari` as a dependency.

---

## New Positive Patterns Worth Preserving

9. **`KubesharkClient.configWatcherJob` uses `distinctUntilChanged()` to avoid spurious reconnects.** The `StateFlow.map { it.targetServices }.distinctUntilChanged()` chain ensures that config poll updates that don't change `targetServices` (e.g., only `samplingRate` changes) do not trigger a WebSocket reconnect. This is subtle and correct — tested explicitly in `KubesharkClientTest.same targetServices update does not trigger reconnect`.

10. **`CapturePipelineIntegrationTest` tests real transport, not just mocks.** The test harness wires a real `KubesharkClient` to an embedded Ktor Netty server via a real TCP WebSocket, while keeping the `CollectorClient` on a `MockEngine`. This gives meaningful coverage of the channel, backpressure, and reconnect behavior without needing a real Kubeshark instance. The balance between real-transport and mock-HTTP is well-chosen.

11. **`CapturedInputs` table correctly avoids the cross-module FK in Exposed while preserving it in SQL.** The comment in `CapturedInputs.kt` (line 13) explains exactly why `.references()` is omitted: importing the app module's `Services` table would break module boundaries. The SQL-level FK is still present in the migration for data integrity. This is a principled, well-documented tradeoff.

12. **JaCoCo aggregated coverage report is configured at the root project level.** `build.gradle.kts` registers a `jacocoAggregatedReport` task that spans all platform modules. This is the right level to track coverage — individual-module reports miss cross-cutting concerns. The aggregation is wired to run after each module's own `jacocoTestReport`.

---

## Prioritized Fix List (New Issues Only)

| Priority | Issue | Why Now |
|----------|-------|---------|
| 1 | **CRITICAL-3**: `serviceDiscoveryLoop` swallows `CancellationException` | Agent will not shut down cleanly; K8s rolling deploys will hang. One-line fix. |
| 2 | **CRITICAL-4**: No batch size limit on `POST /api/captured-inputs` | OOM risk on a shared service. Simple constant + validation. |
| 3 | **ARCH-7**: `latencyMs` never populated by agent | Silently corrupts the data that backs the core statistical verdict. The replay engine will assume latency data exists. |
| 4 | **ARCH-5**: `"default"` namespace in system exclusion list | Silent service discovery failure for a common cluster pattern. One-line fix. |
| 5 | **QUALITY-6**: Collector and app containers run as root | Security hardening; mirror what the agent already does. |
| 6 | **QUALITY-4**: Blank name accepted on POST | Data integrity; prevents garbage data from entering the system. |
| 7 | **ARCH-6 / QUALITY-9**: `DatabaseTestBase` static singleton + no cleanup | Test reliability and CI stability as the test suite grows. |
| 8 | **ARCH-8**: FK violation error message | Developer experience improvement; low urgency. |
| 9 | **QUALITY-10**: `AppApiTestHelper` per-call `testApplication` | Test suite performance; noticeable but not blocking. |
| 10 | **QUALITY-11**: `ServiceRepository.upsert` post-select round-trip | Performance on service discovery hot path; deferred until Loop 1 is implemented. |