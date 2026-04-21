# Architecture Review — Validation Platform

**Last updated:** 2026-04-20
**Reviewer:** Claude (architecture-reviewer agent)
**Scope:** Full-system audit of all modules, database layer, API layer, agent, test suite, deployment, and security.

---

## Prioritized Fix List

| Priority | Issue | Severity | Effort | Why Now |
|----------|-------|----------|--------|---------|
| 1 | [QUALITY-1](#quality-1-dynamicconfig-not-validated-after-deserialization) | Quality | Small | Zero captureInterval = tight-spin CPU loop. |
| 2 | [ARCH-2](#arch-2-repositories-are-object-singletons) | Architectural | Medium | Every route test needs TestContainers. Pattern should not spread. |
| 3 | [QUALITY-6](#quality-6-ignoreunknownkeys-on-server-side-json) | Quality | Small | Typos in request fields are silently ignored. |
| 4 | [QUALITY-7](#quality-7-orderservice-no-connection-pool) | Quality | Small | Test service only; makes test workloads less realistic. |

---

## Architectural Issues

### ARCH-2: Repositories Are `object` Singletons

- **Location**: `platform/.../OrganizationRepository.kt`, `platform/.../ServiceRepository.kt`, `collector/.../CapturedInputRepository.kt`
- **Issue**: All repositories are Kotlin `object` singletons called as global state. Cannot inject mocks for route-level unit tests.
- **Impact**: Every route test requires TestContainers + Docker. Pattern should not spread to new modules.
- **Fix**: Convert to classes, inject via Ktor `Application` extension function.

---

## Code Quality Issues

### QUALITY-1: `DynamicConfig` Not Validated After Deserialization

- **Location**: `agent/.../AgentConfig.kt`; `agent/.../ConfigClient.kt`, line 40
- **Issue**: No validation on deserialized `DynamicConfig`. `samplingRate = -0.5`, `batchSize = 0`, or `captureInterval = 0ms` cause severe issues (tight-spin CPU loop, divide-by-zero, etc.).
- **Fix**: Add `validate()` method, return null on invalid config.

### QUALITY-6: `ignoreUnknownKeys` on Server-Side JSON

- **Location**: `platform/.../Application.kt`, line 41; `collector/.../CollectorApplication.kt`
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

11. **`CapturedInputs` table has no cross-module FK coupling.** The FK from `captured_inputs.service_id` to `services.id` was removed (V0006 migration), fully decoupling collector from app at the database level. Modules communicate via REST APIs only.

12. **JaCoCo aggregated coverage at root project level.** Spans all modules, wired to run after each module's own report. Right level for cross-cutting coverage tracking.

13. **Collector enforces batch size limits.** `MAX_BATCH_SIZE = 1000` prevents OOM from oversized POST requests. Agent-side `batchSize` defaults to 100, well within limits.

14. **Agent populates `latencyMs` from Kubeshark's `elapsedTime`.** Ensures the Mann-Whitney U latency comparison has data from day one.

15. **App and collector containers run as non-root users.** `Dockerfile.app` and `Dockerfile.collector` create dedicated service users, matching the agent's existing security posture.

16. **`KubernetesAdapter` no longer excludes `default` namespace.** Services in the `default` namespace are now discovered correctly. Only true system namespaces (`kube-system`, `kube-public`, `kube-node-lease`) are excluded.

17. **`ServiceRepository.upsert` post-select is documented.** The post-select after `upsert()` is necessary because on conflict the caller's ID is ignored. Comment explains the dependency on Exposed 1.x `upsertReturning()` for elimination.

18. **`DatabaseTestBase` deliberately does not stop the container.** The PostgreSQL container is a static singleton shared across test classes — `@AfterAll` runs per class, so stopping it would kill the connection mid-suite. Ryuk handles cleanup after JVM exit.

19. **`AppApiTestHelper.createOrganizationAndService` consolidates Ktor startups.** A single `testApplication` session creates both org and service fixtures, saving one Ktor boot cycle per test (~50-100ms × 42 tests).

20. **`value class` ID wrappers enforce UUID validity at compile time.** `OrganizationId`, `ServiceId` (app module), `CapturedInputId`, `ServiceId` (collector module) validate UUID format in `init` with descriptive error messages. Zero runtime overhead (inline classes). Each module owns its own ID types — collector has its own `ServiceId` rather than importing from app, preserving module boundaries.

21. **`DatabaseFactory` uses HikariCP connection pool.** Configurable pool size, connection timeout, and leak detection via environment variables. Proper connection lifecycle management for production workloads.
