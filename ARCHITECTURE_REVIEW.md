# Architecture Review — Validation Platform

**Last updated:** 2026-04-26
**Reviewer:** Claude (architecture-reviewer agent)
**Scope:** Full-system audit of all modules, database layer, API layer, agent, test suite, deployment, and security.

---

## Prioritized Fix List

| Priority | Issue | Severity | Effort | Why Now |
|----------|-------|----------|--------|---------|
| 1 | [SECURITY-1](#security-1-authorization-is-authentication--cross-tenant-data-exposure-on-every-list-endpoint) | Security | Medium | Any valid JWT reads all orgs' data. Multi-tenant breach. |
| 2 | [SECURITY-2](#security-2-post-apiservices-does-not-verify-jwt-organization-matches-request-body) | Security | Small | Compromised agent can plant services under another org. |
| 3 | [ARCH-5](#arch-5-trafficcaptureloop-swallows-cancellationexception--agent-cannot-shut-down-cleanly) | Architectural | Small | Agent cannot shut down cleanly; misleading ERROR logs in tests. |
| 4 | [SECURITY-3](#security-3-kfl-query-injection-via-service-names) | Security | Small | Service names interpolated into KFL queries without escaping. |
| 5 | [ARCH-6](#arch-6-cursor-pagination-on-captured_inputs-uses-agent-supplied-capturedat--clock-skew-causes-gaps-and-duplicates) | Architectural | Medium | Replay engine will silently skip or double-replay requests. |
| 6 | [OPS-1](#ops-1-collector-outage-triggers-agent-pod-restart-after-45-seconds) | Operational | Small | 75s collector outage kills agent pod, causing data loss. |
| 7 | [SECURITY-4](#security-4-jwt-has-no-iss-or-aud-claims--tokens-are-cross-service) | Security | Small | Tokens lack issuer/audience; no service binding. |
| 8 | [QUALITY-8](#quality-8-captureonebatch-heartbeat-is-touched-even-when-kubeshark-is-disconnected) | Quality | Small | Broken Kubeshark session is silent; liveness probe says alive. |
| 9 | [ARCH-7](#arch-7-both-platform-and-collector-run-flyway-against-the-same-schema-on-cold-start) | Architectural | Medium | Concurrent Flyway runs on cold start; implicit ordering dependency. |
| 10 | [OPS-2](#ops-2-jwt-tokens-have-365-day-default-expiry-with-no-rotation-mechanism) | Operational | Small | Leaked token is valid 1 year; no revocation path. |
| 11 | [QUALITY-1](#quality-1-dynamicconfig-not-validated-after-deserialization) | Quality | Small | Zero captureInterval = tight-spin CPU loop. |
| 12 | [ARCH-2](#arch-2-repositories-are-object-singletons) | Architectural | Medium | Every route test needs TestContainers. Pattern should not spread. |
| 13 | [QUALITY-6](#quality-6-ignoreunknownkeys-on-server-side-json) | Quality | Small | Typos in request fields are silently ignored. |
| 14 | [QUALITY-7](#quality-7-orderservice-no-connection-pool) | Quality | Small | Test service only; makes test workloads less realistic. |

---

## Security Issues

### SECURITY-1: Authorization Is Authentication — Cross-Tenant Data Exposure on Every List Endpoint

- **Location**: `platform/src/main/kotlin/com/platform/api/Routes.kt` lines 40–48, 65–85, 103–131; `collector/src/main/kotlin/com/platform/collector/api/Routes.kt` lines 70–104, 106–123, 125–143
- **Issue**: Every list and get endpoint ignores the resolved `AgentIdentity` principal. `GET /api/organizations` returns all organizations in the database to any valid JWT. `GET /api/services` accepts an optional `?organizationId=` query parameter but does not enforce that it matches the caller's JWT `organizationId` — omitting the filter returns all services across all tenants. `GET /api/captured-inputs` on the collector has no org-scoping at all; any authenticated caller can enumerate all captured production traffic for all customers by paginating without filters. `GET /api/organizations/{id}` and `GET /api/services/{id}` return data for any org when given its ID.

  The only route that correctly enforces tenant scoping is `GET /api/agent/config` (which extracts `identity.organizationId` and `identity.cluster`). Every other authenticated route treats "authenticated" as "authorized for everything."

- **Impact**: Tenant A's agent (or any caller with a valid JWT) can enumerate Tenant B's entire service registry and all of Tenant B's captured production traffic — including request/response bodies, URLs, headers, and source IPs. This is a multi-tenant data breach at the API layer. The collector's lack of scoping is particularly severe because captured traffic may include PII, session tokens, and business-sensitive payloads.
- **Fix**: Enforce `call.principal<AgentIdentity>()!!.organizationId` as a mandatory filter on every authenticated list/get endpoint. For the collector, add an `organization_id UUID NOT NULL` column to `captured_inputs` (populated from the JWT principal at ingest time via a V0007 migration) and filter on it. The pattern is already correct in `GET /api/agent/config` — replicate it everywhere.

---

### SECURITY-2: `POST /api/services` Does Not Verify JWT Organization Matches Request Body

- **Location**: `platform/src/main/kotlin/com/platform/api/Routes.kt` lines 134–154
- **Issue**: `POST /api/services` accepts a `CreateServiceRequest` body containing an `organizationId` field. The route never verifies that `request.organizationId == call.principal<AgentIdentity>()!!.organizationId`. A valid JWT for Org A can create services under Org B by supplying Org B's UUID in the request body. The only enforcement is the FK from `services.organization_id` to `organizations.id`, which prevents creating services for nonexistent orgs but not for legitimate orgs the caller doesn't own.

  `POST /api/organizations` has no principal check at all — any authenticated caller can create organizations. This may be an intentional provisioning path, but it is undocumented.

- **Impact**: A compromised or malicious agent can pollute another tenant's service registry, affecting their `GET /api/agent/config` response and their KFL queries, redirecting their traffic capture.
- **Fix**: In `POST /api/services`, enforce `request.organizationId == identity.organizationId`; return 403 if they differ. For `POST /api/organizations`, decide whether it requires an admin role and enforce `identity.role == "admin"` if so.

---

### SECURITY-3: KFL Query Injection via Service Names

- **Location**: `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt` lines 205–221
- **Issue**: `buildKflQuery` interpolates Kubernetes service names directly into a KFL query string without escaping:

  ```kotlin
  { name -> """dst.name == "$name"""" }
  ```

  A service name containing `"` or KFL operators produces a semantically different query. For example, a service named `svc" or true or dst.name == "` would produce `http and dst.name == "svc" or true or dst.name == ""`, which Kubeshark may evaluate as "all HTTP traffic." K8s service names are RFC 1123 (lowercase alphanumeric and hyphens), so well-behaved clusters cannot create such names. The risk materializes if `POST /api/services` accepts names without format validation — currently the only check is `isBlank()`.

- **Impact**: If exploited, Kubeshark streams all traffic to the agent instead of only targeted services, causing unintended cross-tenant data capture.
- **Fix**: Add RFC 1123 name validation (`^[a-z0-9][a-z0-9\-]*[a-z0-9]$`, max 63 chars) to `POST /api/services`. Additionally, sanitize names in `buildKflQuery` defensively — if a name fails the pattern, skip it with a warning. Defense-in-depth: do not trust that API validation alone is sufficient.

---

### SECURITY-4: JWT Has No `iss` or `aud` Claims — Tokens Are Cross-Service

- **Location**: `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt` lines 43–44; `platform/src/main/kotlin/com/platform/auth/JwtTokenGenerator.kt` lines 49–55
- **Issue**: Generated JWTs have no `issuer` or `audience` claims. `installJwtAuth` constructs the verifier with `JWT.require(algorithm).build()` — no `.withIssuer()` or `.withAudience()` constraint. Any token signed with the platform's private key is accepted by both the platform and the collector with no service binding. As future services are added that share the same key, tokens issued for one service will be accepted by all others.
- **Impact**: Currently low given the shared-key design is intentional. Becomes high as the platform scales to more services with different privilege levels.
- **Fix**: Add `withIssuer("validation-platform")` and `withAudience(expectedAudience)` to the verifier, passing `expectedAudience` as a parameter to `installJwtAuth`. Update `JwtTokenGenerator` to include these claims. Small change that establishes the claim structure before it becomes a breaking migration.

---

## Architectural Issues

### ARCH-2: Repositories Are `object` Singletons

- **Location**: `platform/.../OrganizationRepository.kt`, `platform/.../ServiceRepository.kt`, `collector/.../CapturedInputRepository.kt`
- **Issue**: All repositories are Kotlin `object` singletons called as global state. Cannot inject mocks for route-level unit tests.
- **Impact**: Every route test requires TestContainers + Docker. Pattern should not spread to new modules.
- **Fix**: Convert to classes, inject via Ktor `Application` extension function.

---

### ARCH-5: `trafficCaptureLoop` Swallows `CancellationException` — Agent Cannot Shut Down Cleanly

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` lines 122–149
- **Issue**: `trafficCaptureLoop` catches `Exception` but does not re-throw `CancellationException`. Compare with `serviceDiscoveryLoop` (line 82) and `configPollLoop` (line 98), both of which explicitly `throw e` on `CancellationException`. When the enclosing `coroutineScope` is cancelled (graceful shutdown, test teardown, uncaught exception in a sibling loop), `trafficCaptureLoop` catches the `CancellationException`, logs "Traffic capture failed" at ERROR level, delays for `captureInterval`, and then loops. The delay is a suspend point that will eventually re-propagate cancellation, but not before firing a misleading error log and potentially running one more iteration.
- **Impact**: Spurious ERROR logs on every agent shutdown and test teardown. In automated test suites, error logs from normal shutdown paths mask real failures.
- **Fix**: Add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` block, matching the pattern in the other two loops.

---

### ARCH-6: Cursor Pagination on `captured_inputs` Uses Agent-Supplied `capturedAt` — Clock Skew Causes Gaps and Duplicates

- **Location**: `collector/src/main/kotlin/com/platform/collector/database/CapturedInputRepository.kt` lines 95–121; `shared/src/main/resources/db/migration/V0004__create_captured_inputs.sql` line 15
- **Issue**: The pagination cursor for `GET /api/captured-inputs` is computed from `capturedAt`, the timestamp at which traffic was observed on the agent host. This timestamp comes directly from Kubeshark's wire format (`entry.timestamp`), which is the agent's wall clock at capture time.

  Cursor-based pagination requires the sort key to be monotonically assigned at insert time or immune to external manipulation. `capturedAt` is neither: NTP jitter between the agent and the collector creates overlapping timestamp ranges across batches; Kubeshark's 5-second out-of-order delivery window means a batch may contain entries with `capturedAt` values earlier than the previous batch's cursor; agent retries of failed batches re-insert the same `capturedAt` values.

  Concretely: a cursor derived from batch N's last item will skip items in batch N+1 that have earlier `capturedAt` values but were inserted later (because the agent was retrying or Kubeshark delivered them out of order). The pagination index (`idx_captured_inputs_captured_at`) is on the agent-supplied column, which is correct for current ordering but does not fix the stability problem.

- **Impact**: The replay engine (planned) will fetch captured inputs via `GET /api/captured-inputs`. Pagination gaps mean captured requests are silently never replayed. Duplicates (from retries) mean the same request is replayed twice, potentially mutating staging state. Both failure modes are silent — the API returns 200 with what appears to be a valid page.
- **Fix**: Add `collected_at TIMESTAMPTZ NOT NULL DEFAULT now()` to `captured_inputs` in a V0007 migration. Sort and build cursors on `collected_at` (DB-assigned, monotonic). Retain `capturedAt` as a queryable data field for latency analysis. Add an index on `collected_at`.

---

### ARCH-7: Both Platform and Collector Run Flyway Against the Same Schema on Cold Start

- **Location**: `shared/src/main/kotlin/com/platform/shared/database/DatabaseFactory.kt` lines 48–55; `platform/src/main/kotlin/com/platform/Application.kt` line 27; `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt` line 26
- **Issue**: `DatabaseFactory.init()` runs Flyway migrations from `classpath:db/migration`. Both the `platform` and `collector` JARs include the `shared` module's migration resources on their classpath. When both services start simultaneously (as they do in Docker Compose and Kubernetes cold starts), both processes call `Flyway.migrate()` against the same PostgreSQL database concurrently.

  Flyway uses a distributed lock on the `flyway_schema_history` table so data integrity is preserved. However: (1) the second process to acquire the lock blocks until the first completes, potentially causing readiness probe failures if migrations are slow; (2) the Kubernetes manifests have no `initContainer` or explicit startup ordering enforcing that `platform` runs migrations before `collector` starts; (3) if Flyway's lock timeout is shorter than migration duration (possible with large future migrations), the second process fails its startup entirely — a silent dependency that becomes a production incident.

- **Impact**: Low risk today with six short migrations. Grows as the migration chain expands for replay engine, observation data, and verdict storage. The first large migration (multi-second) on a populated database will expose this race.
- **Fix**: Designate `platform` as the sole migration runner. Add a `runMigrations: Boolean = true` parameter to `DatabaseFactory.init()` and pass `false` from the collector. Alternatively, add a Kubernetes `initContainer` on the collector that polls `platform`'s `/health` endpoint (which only returns 200 after migrations succeed) before starting the collector process.

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

### QUALITY-8: `captureOneBatch` Heartbeat Is Touched Even When Kubeshark Is Disconnected

- **Location**: `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` lines 133–134; `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt` lines 95–113
- **Issue**: `drainBatch` returns an empty list both when Kubeshark delivers no traffic (legitimate idle) and when the Kubeshark WebSocket is disconnected (the `streamerJob` is in its reconnect delay loop and the channel is empty). In both cases `captureOneBatch` returns `CaptureResult(entriesProcessed=0, lag=null)`, and `touchHeartbeat()` is called — keeping the liveness probe satisfied.

  An agent whose Kubeshark WebSocket has been failing to reconnect for hours will report as alive while capturing zero traffic.

- **Impact**: Silent data loss. No alertable signal that capture has stopped until operators notice missing data in the collector — which could be hours or days after the Kubeshark connection broke.
- **Fix**: Track whether the `streamerJob` is actively connected (expose `isStreaming: Boolean` from `KubesharkClient`, set to `true` after a successful frame is received, reset during reconnect delay). Only touch the heartbeat when `isStreaming` is true or the idle condition is legitimately quiet. Alternatively, use a separate status file that the liveness probe checks for Kubeshark connectivity health, failing the probe only after a configurable disconnection duration.

---

## Operational Issues

### OPS-1: Collector Outage Triggers Agent Pod Restart After ~45 Seconds

- **Location**: `k8s/agent/agent.yaml` lines 62–66; `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` lines 133–134; `agent/src/main/kotlin/com/platform/agent/CollectorClient.kt` lines 81–98
- **Issue**: `touchHeartbeat()` is called inside the `try` block of `trafficCaptureLoop` after `captureOneBatch` returns. `captureOneBatch` calls `collectorClient.sendBatch()`, which retries transient failures indefinitely — suspending in the retry loop. While `sendBatch` is suspended retrying, `touchHeartbeat()` is never reached.

  The liveness probe checks that `/tmp/agent-alive` was modified within the last minute. With `initialDelaySeconds: 30`, `periodSeconds: 15`, and `failureThreshold: 3`, a collector outage of ~75 seconds causes K8s to restart the agent pod.

  Restarting the agent tears down the Kubeshark WebSocket, causing Kubeshark to replay ~4–10 seconds of history on reconnect and dropping entries that were buffered in the channel at pod termination — exactly the data loss path the bounded channel was designed to prevent. A collector rolling deployment (typically 30–60 seconds) crosses this threshold.

- **Impact**: Data loss during collector deployments. Increased Kubeshark reconnect load. Potential restart loop if the collector is persistently degraded.
- **Fix**: Touch the heartbeat after a successful `drainBatch` rather than after a successful `sendBatch`. The heartbeat should signal "agent is reachable and processing" — Kubeshark connectivity, not collector delivery:

  ```kotlin
  val entries = kubesharkClient.drainBatch(limit = batchSize, maxWait = maxWait)
  if (entries.isNotEmpty()) touchHeartbeat()  // alive = pipeline is running
  ```

  The collector retry loop remains the correct mechanism for handling collector downtime.

---

### OPS-2: JWT Tokens Have 365-Day Default Expiry with No Rotation Mechanism

- **Location**: `platform/src/main/kotlin/com/platform/auth/JwtTokenGenerator.kt` line 28; `k8s/agent/agent.yaml` lines 58–61; `k8s/platform/secret.yaml`
- **Issue**: `JwtTokenGenerator` defaults to 365-day expiry. The generated JWT is stored in a Kubernetes Secret (`platform-api-key/jwt-token`) and injected as an environment variable at pod startup. There is no rotation procedure, no revocation mechanism, and no `jti` claim that would allow the platform to track individual tokens.

  Rotating a compromised token requires rotating the RSA private key itself, which immediately invalidates all other agents' tokens and requires coordinated redeployment of all agents and both servers. There is no documented runbook for this.

- **Impact**: A leaked agent JWT is valid for up to a year with no revocation path. Acceptable for early-stage development; a known gap before the platform handles sensitive production data at scale.
- **Fix** (near-term): Reduce default expiry to 30 days. Document a rotation runbook: generate new token → update Kubernetes Secret → `kubectl rollout restart deployment/validation-agent`. Add a TODO in `JwtTokenGenerator` for a future `GET /api/auth/token` refresh endpoint. (Long-term: implement short-lived tokens with a refresh flow, enabling non-disruptive rotation without private key rotation.)

---

## Positive Patterns Worth Preserving

1. **Structured concurrency in the agent is done correctly.** `AgentApplication` passes `coroutineScope` to `KubesharkClient`, which launches its streamer job within that scope. Cancellation propagates cleanly. `CancellationException` is properly re-thrown in all loops (`serviceDiscoveryLoop`, `configPollLoop`, `KubesharkClient.streamerLoop`, `CollectorClient.tryPost`). (Note: `trafficCaptureLoop` is the one exception — see ARCH-5.)

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

22. **`GET /api/agent/config` correctly enforces multi-tenant scoping.** The route extracts both `organizationId` and `cluster` from the JWT principal and passes them as mandatory filters to `ServiceRepository.find()`. This is the correct authorization pattern for the platform — it is the only endpoint that currently implements it, and it demonstrates the team knows how to do this correctly. (See SECURITY-1 for why all other endpoints need the same treatment.)
