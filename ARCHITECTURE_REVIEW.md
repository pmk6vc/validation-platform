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

### CRITICAL-1: The Agent Has No Endpoint to POST To — The Capture Pipeline Is Non-Functional End-to-End

- **Location**: `collector/src/main/kotlin/com/platform/collector/api/Routes.kt`; `agent/src/main/kotlin/com/platform/agent/CollectorClient.kt`, line 113
- **Issue**: `CollectorClient.sendBatch` posts to `$baseUrl/api/captured-inputs`, but the collector's `Routes.kt` defines only `GET /api/captured-inputs`, `GET /api/captured-inputs/{id}`, and `DELETE /api/captured-inputs`. There is no `POST` route. Every batch the agent successfully transforms will receive a 405 Method Not Allowed response, which falls into the `400..499` range, be classified as `PermanentFailure`, and be silently dropped.
- **Impact**: The entire traffic capture pipeline is a dead end. Data is captured, transformed, and then discarded. The end-to-end minikube verification in CLAUDE.md claiming "100 entries/batch every ~2s via kubeshark-front" almost certainly means the agent is running but silently dropping every batch.
- **Fix**: Implement `POST /api/captured-inputs` in the collector. The collector already has `createBatch` in `CapturedInputRepository`. The route needs to accept a `BatchCapturedInputRequest`-equivalent body, validate `serviceId` referential integrity via the FK constraint, generate IDs, and call `createBatch`. This is the highest-priority gap in the platform.

---

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

### ARCH-4: `TrafficTransformer` Uses `Math.random()` — Not Testable

- **Location**: `agent/src/main/kotlin/com/platform/agent/TrafficTransformer.kt`, line 54
- **Issue**: `Math.random()` is a static call that cannot be injected or seeded. Tests completely avoid testing sampling behavior (all use `samplingRate = 1.0`). The statistical correctness of sampling is untested.
- **Impact**: A bug that always accepts or always rejects entries would not be caught.
- **Fix**: Inject a `Random` instance. Replace `Math.random() < samplingRate` with `random.nextDouble() < samplingRate`. Tests can then inject a seeded `Random`.

---

### ARCH-5: `ServiceRepository.upsert` Has a Non-Atomic Read-After-Write Gotcha

- **Location**: `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, lines 117-152
- **Issue**: The `upsert` method generates a fresh UUID for the input service, but on conflict the original row's ID is preserved (`onUpdateExclude` includes `Services.id`). The caller's `service.id` is silently discarded. Any caller that uses `service.id` instead of the returned value has a stale ID — a subtle correctness trap that will cause FK violations when the agent registers services.
- **Fix**: Document that callers must use the returned value and never the input `service.id`, or restructure to query first by natural key.

---

### ARCH-6: `DatabaseFactory` Has No Connection Pool Configuration — Uses Exposed Defaults

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

### QUALITY-3: `OrganizationRoutesTest` Uses String Containment Instead of Structured Assertions

- **Location**: `app/src/test/kotlin/com/platform/api/OrganizationRoutesTest.kt`, lines 41-45, 53-63
- **Issue**: Tests like `assertTrue(body.contains("\"items\""))` are brittle and can pass with wrong JSON structure. `ServiceRoutesTest` correctly deserializes responses.
- **Fix**: Deserialize all route responses to typed models and assert on fields.

---

### QUALITY-6: `DynamicConfig` Fields Are Not Validated After Deserialization

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt`; `agent/src/main/kotlin/com/platform/agent/ConfigClient.kt`, line 40
- **Issue**: No validation after deserializing `DynamicConfig`. A `samplingRate = -0.5`, `batchSize = 0`, or `captureInterval = 0ms` would cause severe issues — e.g., zero captureInterval turns the capture loop into a tight-spin CPU loop.
- **Fix**: Add a `validate()` method and call it after deserialization, returning `null` on invalid config.

---

### QUALITY-7: `ServiceRepository.create` Does Not Handle Invalid UUID organizationId

- **Location**: `app/src/main/kotlin/com/platform/database/ServiceRepository.kt`, line 32; `app/src/main/kotlin/com/platform/api/Routes.kt`, line 87
- **Issue**: `UUID.fromString(service.organizationId)` throws `IllegalArgumentException` with a generic "Bad request" message rather than "organizationId must be a valid UUID".
- **Fix**: Add explicit UUID validation in route handlers with descriptive error messages.

---

### QUALITY-8: `app` and `collector` Application Modules Are Nearly Identical — Copy-Paste Risk

- **Location**: `app/src/main/kotlin/com/platform/Application.kt`; `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt`
- **Issue**: Same `StatusPages` configuration, same `ContentNegotiation` configuration, same SQL state handling. Changes must be duplicated.
- **Fix**: Extract a `fun Application.configurePlatformPlugins()` into `shared/` that both call.

---

### QUALITY-9: `ignoreUnknownKeys = true` on Server-Side JSON Parser

- **Location**: `app/src/main/kotlin/com/platform/Application.kt`, line 41; same in `CollectorApplication.kt`
- **Issue**: Server silently accepts request bodies with unrecognized fields. Typos in field names are invisible. A client sending `{"organizationid": "..."}` instead of `{"organizationId": "..."}` gets a confusing error for a missing required field rather than an unknown field warning.
- **Fix**: Use `ignoreUnknownKeys = true` only for client-side deserialization (agent). On the server side, omit it for request body parsing.

---

## Test Coverage Gaps

### GAP-1: No Test for Sampling Logic at Non-Trivial Rates (HIGH)

- All tests use `samplingRate = 1.0`. No test verifies that `samplingRate = 0.5` accepts ~50% of entries.
- **Fix**: After injecting `Random` (ARCH-4), add a test with 1000 entries, `samplingRate = 0.5`, and a seeded `Random`.

### GAP-3: No Test for `POST /api/captured-inputs` — Which Doesn't Exist Yet (CRITICAL)

- The collector batch endpoint is the core integration point. When implemented, needs tests for: valid batch, FK violation, empty batch, malformed JSON, oversized batch, idempotency.

### GAP-4: `ConfigClient` Swallows `CancellationException` (MEDIUM)

- **Location**: `agent/src/main/kotlin/com/platform/agent/ConfigClient.kt`, lines 41-44
- **Issue**: `fetchConfig` catches all `Exception` subtypes including `CancellationException`. Coroutine cancellation should never be swallowed.
- **Fix**: Add `} catch (e: CancellationException) { throw e }` before the general `Exception` catch.

### GAP-5: No Test for `ServiceRepository.upsert` Returning Existing Row's ID (MEDIUM)

- `upsert` on an existing service returns the original row's ID, not the input's. No test verifies this.
- **Fix**: Test that calls `upsert` twice with same natural key but different UUIDs, asserts returned ID matches first call.

### GAP-6: No Test for `KubernetesAdapter.close()` Resource Cleanup (LOW)

- Nothing verifies `close()` propagates to the underlying Kubernetes client. `KubernetesAdapter` should implement `Closeable`.

### GAP-7: No Test for Negative/Zero `limit` Parameter (LOW)

- `limit=0`, `limit=-1`, and `limit=200` (over max) edge cases are untested.

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