# Architecture Review — Validation Platform

**Last updated:** 2026-04-18
**Reviewer:** Claude (architecture-reviewer agent)
**Scope:** Full-system audit of all modules, database layer, API layer, agent, test suite, deployment, and security.

---

## Prioritized Fix List

| Priority | Issue | Severity | Effort | Why Now |
|----------|-------|----------|--------|---------|
| 1 | [CRITICAL-1](#critical-1-api-key-hardcoded-in-kubernetes-manifest) | Critical | Small | Checked-in plaintext secret + no server-side auth validation. |
| 2 | [ARCH-1](#arch-1-databasefactory-has-no-connection-pool) | Architectural | Medium | No connection pool, validation, or leak detection in production. |
| 3 | [QUALITY-1](#quality-1-dynamicconfig-not-validated-after-deserialization) | Quality | Small | Zero captureInterval = tight-spin CPU loop. |
| 4 | [ARCH-2](#arch-2-repositories-are-object-singletons) | Architectural | Medium | Every route test needs TestContainers. Pattern should not spread. |
| 5 | [ARCH-3](#arch-3-cross-module-fk-coupling-on-service_id) | Architectural | Small | Error message is generic; FK blocks future DB separation. |
| 6 | [QUALITY-2](#quality-2-decodecursor-returns-uuid-but-models-use-string-ids) | Quality | Medium | Type mismatch; proper fix touches models across all modules. |
| 7 | [QUALITY-3](#quality-3-encodecursor-accepts-string-id-without-uuid-enforcement) | Quality | Trivial | Companion to QUALITY-2; enforce UUID at compile time. |
| 8 | [QUALITY-4](#quality-4-collectordatabasetestbase-incomplete-table-cleanup) | Quality | Trivial | Test isolation gap; fixtures accumulate across test methods. |
| 9 | [QUALITY-5](#quality-5-servicerepository-uuid-validation-error-message) | Quality | Trivial | Generic "Bad request" instead of descriptive message. |
| 10 | [QUALITY-6](#quality-6-ignoreunknownkeys-on-server-side-json) | Quality | Small | Typos in request fields are silently ignored. |
| 11 | [QUALITY-7](#quality-7-orderservice-no-connection-pool) | Quality | Small | Test service only; makes test workloads less realistic. |

---

## Critical Issues

### CRITICAL-1: API Key Hardcoded in Kubernetes Manifest

- **Location**: `k8s/agent/agent.yaml`, line 56
- **Issue**: `API_KEY: "test-api-key"` is a literal string in a checked-in manifest. No server-side auth middleware validates this token anyway.
- **Impact**: Any client can write arbitrary captured traffic. Multi-tenant data integrity risk.
- **Fix**: Use a Kubernetes `Secret` with `secretKeyRef`. Add bearer token validation middleware to collector routes.

---

## Architectural Issues

### ARCH-1: `DatabaseFactory` Has No Connection Pool

- **Location**: `shared/src/main/kotlin/com/platform/database/DatabaseFactory.kt`
- **Issue**: `Database.connect(...)` without a `DataSource` uses Exposed's default pool (not HikariCP). CLAUDE.md claims HikariCP but the code doesn't use it. No pool sizing, timeout config, or connection validation.
- **Impact**: Production reliability risk.
- **Fix**: Pass a `HikariDataSource` to `Database.connect` with proper pool configuration from env vars.

### ARCH-2: Repositories Are `object` Singletons

- **Location**: `app/.../OrganizationRepository.kt`, `app/.../ServiceRepository.kt`, `collector/.../CapturedInputRepository.kt`
- **Issue**: All repositories are Kotlin `object` singletons called as global state. Cannot inject mocks for route-level unit tests.
- **Impact**: Every route test requires TestContainers + Docker. Pattern should not spread to new modules.
- **Fix**: Convert to classes, inject via Ktor `Application` extension function.

### ARCH-3: Cross-Module FK Coupling on `service_id`

- **Location**: `collector/.../api/Routes.kt`, lines 31–55; migration V0004
- **Issue**: `captured_inputs.service_id` has an FK to `services.id` (app module's table). FK violations surface as generic `"Referenced resource not found"`. The coupling prevents separating module databases.
- **Impact**: Error messages are unhelpful. Database-level coupling blocks future service separation.
- **Fix**: Improve error message to name the invalid field. Document the FK as intentional coupling that must be removed before DB separation.

---

## Code Quality Issues

### QUALITY-1: `DynamicConfig` Not Validated After Deserialization

- **Location**: `agent/.../AgentConfig.kt`; `agent/.../ConfigClient.kt`, line 40
- **Issue**: No validation on deserialized `DynamicConfig`. `samplingRate = -0.5`, `batchSize = 0`, or `captureInterval = 0ms` cause severe issues (tight-spin CPU loop, divide-by-zero, etc.).
- **Fix**: Add `validate()` method, return null on invalid config.

### QUALITY-2: `decodeCursor` Returns UUID But Models Use String IDs

- **Location**: `shared/src/main/kotlin/com/platform/models/Page.kt`, line 24
- **Issue**: `decodeCursor` returns `Pair<Instant, UUID>`, but all model `id` fields are `String`.
- **Quick fix**: Return `Pair<Instant, String>` (keep UUID validation internally).
- **Proper fix**: Introduce `value class` ID wrappers per entity for compile-time safety.

### QUALITY-3: `encodeCursor` Accepts String ID Without UUID Enforcement

- **Location**: `shared/src/main/kotlin/com/platform/models/Page.kt`
- **Issue**: `encodeCursor` takes a plain `String` for the ID. A non-UUID string would produce a cursor that `decodeCursor` rejects later. Types don't enforce this invariant.
- **Fix**: Change signature to `fun encodeCursor(timestamp: Instant, id: UUID): String`.

### QUALITY-4: `CollectorDatabaseTestBase` Incomplete Table Cleanup

- **Location**: `collector/.../CollectorDatabaseTestBase.kt`
- **Issue**: `cleanTables()` only deletes `CapturedInputs`, but `AppApiTestHelper` inserts orgs and services in `@BeforeEach`. Fixtures accumulate.
- **Fix**: Clean `CapturedInputs`, then `Services`, then `Organizations` (FK order).

### QUALITY-5: `ServiceRepository` UUID Validation Error Message

- **Location**: `app/.../ServiceRepository.kt`, line 32; `app/.../api/Routes.kt`, line 87
- **Issue**: `UUID.fromString(service.organizationId)` throws `IllegalArgumentException` with generic "Bad request" instead of `"organizationId must be a valid UUID"`.
- **Fix**: Explicit UUID validation with descriptive error message in route handler.

### QUALITY-6: `ignoreUnknownKeys` on Server-Side JSON

- **Location**: `app/.../Application.kt`, line 41; `collector/.../CollectorApplication.kt`
- **Issue**: Server silently accepts unrecognized fields. `{"organizationid": "..."}` (typo) gives a confusing missing-field error.
- **Fix**: Only use `ignoreUnknownKeys = true` on the agent (client-side). Remove it from server JSON config.

### QUALITY-7: `OrderService` No Connection Pool

- **Location**: `test-services/order-service/.../OrderService.kt`, lines 122–124
- **Issue**: `DriverManager.getConnection(...)` per request — no connection pool. Under traffic-generator load, creates/destroys a connection per request.
- **Scope**: Test service only, but makes workloads less realistic.
- **Fix**: Replace with `HikariDataSource` (10-line change).

---

## Positive Patterns Worth Preserving

1. **Structured concurrency in the agent is done correctly.** `AgentApplication` passes `coroutineScope` to `KubesharkClient`, which launches its streamer job within that scope. Cancellation propagates cleanly. `CancellationException` is properly re-thrown in all loops (`serviceDiscoveryLoop`, `configPollLoop`, `KubesharkClient.streamerLoop`, `CollectorClient.tryPost`).

2. **The `CollectorClient` retry model is sound.** Exponential backoff with configurable cap, 4xx as permanent failures, 5xx and network errors as transient, backpressure through the channel.

3. **Cursor-based pagination is consistent across all three repositories.** The `(timestamp, id)` compound cursor with the correct OR predicate avoids duplicates and gaps. The `limit + 1` probe for `hasMore` is idiomatic.

4. **Module boundary enforcement via `AppApiTestHelper` is principled.** Collector tests create fixtures through app's HTTP API, not by importing repositories directly.

5. **`KubesharkClient` dedup window design is well-reasoned.** The timestamp sliding-window vs LRU ID cache trade-off is correctly analyzed and documented.

6. **Flyway migration chain is safe.** V0001–V0005 are additive only, no destructive DDL. Zero-padding prevents ordering issues.

7. **`DurationAsMillisSerializer` in `AgentConfig` is clean.** Bridges Kotlin `Duration` to wire-format `Long` without polluting the in-memory type. Test verifies wire format correctness.

8. **Agent's config concurrency model is appropriate.** `StateFlow<DynamicConfig>` snapshot at transform call start ensures consistency. No locks needed.

9. **`KubesharkClient.configWatcherJob` uses `distinctUntilChanged()` to avoid spurious reconnects.** Config updates that don't change `targetServices` (e.g., only `samplingRate` changes) do not trigger a WebSocket reconnect.

10. **`CapturePipelineIntegrationTest` tests real transport, not just mocks.** Real TCP WebSocket to an embedded Ktor server, mock HTTP for the collector. Good balance of real-transport and mock coverage.

11. **`CapturedInputs` table correctly avoids cross-module FK in Exposed while preserving it in SQL.** The comment explains why `.references()` is omitted (module boundary). SQL-level FK preserved in migration for data integrity. Principled, documented tradeoff.

12. **JaCoCo aggregated coverage at root project level.** Spans all modules, wired to run after each module's own report. Right level for cross-cutting coverage tracking.

13. **Collector enforces batch size limits.** `MAX_BATCH_SIZE = 1000` prevents OOM from oversized POST requests. Agent-side `batchSize` defaults to 100, well within limits.

14. **Agent populates `latencyMs` from Kubeshark's `elapsedTime`.** Ensures the Mann-Whitney U latency comparison has data from day one.

15. **App and collector containers run as non-root users.** `Dockerfile.app` and `Dockerfile.collector` create dedicated service users, matching the agent's existing security posture.

16. **`KubernetesAdapter` no longer excludes `default` namespace.** Services in the `default` namespace are now discovered correctly. Only true system namespaces (`kube-system`, `kube-public`, `kube-node-lease`) are excluded.

17. **`ServiceRepository.upsert` post-select is documented.** The post-select after `upsert()` is necessary because on conflict the caller's ID is ignored. Comment explains the dependency on Exposed 1.x `upsertReturning()` for elimination.

18. **`DatabaseTestBase` deliberately does not stop the container.** The PostgreSQL container is a static singleton shared across test classes — `@AfterAll` runs per class, so stopping it would kill the connection mid-suite. Ryuk handles cleanup after JVM exit.

19. **`AppApiTestHelper.createOrganizationAndService` consolidates Ktor startups.** A single `testApplication` session creates both org and service fixtures, saving one Ktor boot cycle per test (~50-100ms × 42 tests).
