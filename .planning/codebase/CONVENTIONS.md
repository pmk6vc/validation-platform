<!-- refreshed: 2026-05-14 -->
# Code Conventions

**Analysis Date:** 2026-05-14

## Language and Linting

- **Kotlin** on the JVM (Java 21 toolchain), Gradle Kotlin DSL build (`build.gradle.kts`).
- **ktlint** enforces style across all Kotlin modules. Configured at root `build.gradle.kts`. Run via `./gradlew ktlintCheck`.
- Conventional `.editorconfig` (when present) sets 4-space indent and ~120-char line length consistent with `ktlint_official` style.
- The Go `tap/` module is a separate ecosystem with its own conventions and tooling (see `tap/go.mod`). It is not subject to the Kotlin rules here.

## Package Structure

All Kotlin code lives under `com.platform.*`, with module-scoped subpackages:

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

**Data classes for models**
- All domain models and DTOs are `data class`.
- Wire DTOs are `@Serializable` (kotlinx.serialization).
- Optional fields default to `null` or sensible defaults — additive evolution friendly.
- Example: `data class Service(val id: ServiceId, val organizationId: OrganizationId, ... , val metadata: Map<String, String>? = null)`.

**Value classes for typed IDs**
- `@JvmInline value class OrganizationId(val value: String)` (similarly `ServiceId`, `CapturedInputId`).
- `init` block validates UUID format — throws at construction, not at use.
- Companion `generate()` factory provides random UUID v4 values.
- `@Serializable` so they round-trip through JSON as plain strings.

**Sealed classes for outcomes**
- `sealed class RegistrationOutcome` with `data object Success`, `data class PermanentRejection(...)`, `data class TransientFailure(...)`.
- Used for branching retry/abort logic without exceptions on the happy path.

**Enums for finite, stable variants**
- `enum class InputType { HTTP, UNKNOWN }`, `enum class Provider { UNKNOWN, MANUAL_SEED, KUBERNETES }`.
- Always include an `UNKNOWN` variant on enums that cross the wire — forward compatibility for additive deploys.

## Serialization

- **kotlinx.serialization** for all JSON.
- All HTTP clients (agent and test code) configure `Json { ignoreUnknownKeys = true; encodeDefaults = true }` to make the agent↔platform contract additive.
- Custom serializers:
  - `InstantSerializer` (`shared/`) — `java.time.Instant` as ISO-8601 string.
  - `DurationAsMillisSerializer` (`agent/AgentConfig.kt`) — `Duration` as `Long` milliseconds.
- Apply via `@Serializable(with = InstantSerializer::class)` at the property site, not globally.

## HTTP Clients (Agent)

One factory per target server, all in `agent/src/main/kotlin/com/platform/agent/`:

- `buildAgentPlatformHttpClient()` — base Ktor client for `/api/services`, `/api/agent/config`.
- `buildAgentCollectorHttpClient()` — adds `ContentEncoding` plugin (gzip request bodies).
- `buildAgentKubesharkHttpClient()` — adds WebSockets plugin; no auth.

Patterns:
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
  ```kotlin
  private val logger = LoggerFactory.getLogger(KubesharkClient::class.java)
  ```
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

---

*Conventions analysis: 2026-05-14*
