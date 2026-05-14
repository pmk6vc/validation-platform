<!-- refreshed: 2026-05-14 -->
# Testing

**Analysis Date:** 2026-05-14

## Frameworks

- **JUnit 5** (`junit-jupiter` 5.10.x) as the test runner. Configured via `tasks.test { useJUnitPlatform() }` in every module's `build.gradle.kts`.
- **kotlin.test** (`kotlin-test-junit5`) for assertions — `assertEquals`, `assertTrue`, `assertNull`, etc.
- **TestContainers** — `postgresql` and `k3s` modules, plus `junit-jupiter` integration.
- **Ktor MockEngine** (`ktor-client-mock-jvm`) — request/response mocking for agent HTTP client tests.
- **Ktor `testApplication`** — in-process Ktor app for route tests (wired via the shared `authedTestApplication` helper).

No external mocking framework (MockK, Mockito) is wired in. Tests rely on real TestContainers + MockEngine.

## Run Commands

```bash
./gradlew test               # All tests across all modules
./gradlew :platform:test     # Platform module only
./gradlew :collector:test
./gradlew :agent:test
./gradlew :e2e-tests:test    # End-to-end (requires Docker images built via Jib)
./gradlew ktlintCheck        # Lint
```

On macOS, TestContainers expects Colima's socket. The root `build.gradle.kts` auto-detects:

```kotlin
val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
if (colimaSocket.exists()) {
    environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
}
```

## Layout and Naming

- Tests live in `<module>/src/test/kotlin/com/platform/...`.
- No `*IT.kt` vs `*Test.kt` split — everything is `*Test.kt`. E2E tests under `e2e-tests/` end in `E2ETest.kt`.
- One test class per source class is the default. Test class name mirrors source name (`ServiceRepository` → `ServiceRepositoryTest`).
- Test method names use backtick strings: `` `GET services should return empty page when no services`() ``.

## Shared Test Fixtures (`shared/src/testFixtures/`)

Exposed to other modules via the `java-test-fixtures` Gradle plugin. Other modules consume them with `testImplementation(testFixtures(project(":shared")))`.

- `DatabaseTestBase` (`shared/src/testFixtures/kotlin/com/platform/shared/database/DatabaseTestBase.kt`)
  - Spins up a `PostgreSQLContainer("postgres:16-alpine")` as a JVM-static singleton.
  - Runs Flyway migrations once; subclasses clean only their own tables before each test.
  - Ryuk handles container cleanup at JVM exit.
- `KubernetesWorkloadTestBase` (`shared/src/testFixtures/kotlin/com/platform/kubernetes/KubernetesWorkloadTestBase.kt`)
  - Starts a k3s container.
  - Deploys 7 services across 3 namespaces (`infrastructure`, `production`, `external`) — `orders-db`, `redis`, `kafka`, `api-gateway`, `order-service`, `notification-service`, `webhook-stub`.
- `TestJwtKeys` (`shared/src/testFixtures/kotlin/com/platform/shared/testing/TestJwtKeys.kt`)
  - Single consolidated RSA test keypair used by every module's tests — no per-module key boilerplate.
  - Exposes `privateKeyPem` (lazy), `publicKey`, `DEFAULT_ORG_ID`, and `generateTestJwt(organizationId, cluster, role?, expiresAt?)` helper.
- `AuthedTestApplication` (`shared/src/testFixtures/kotlin/com/platform/shared/testing/AuthedTestApplication.kt`)
  - `authedTestApplication(token, setupApplication, block)` wraps Ktor's `testApplication`, installs `installJwtAuth(TestJwtKeys.privateKeyPem)`, configures JSON, and wires the bearer token onto the client.

## Per-Module Test Bases

- `PlatformDatabaseTestBase` (`platform/src/test/kotlin/com/platform/database/PlatformDatabaseTestBase.kt`)
  - Extends `DatabaseTestBase`; truncates `Organizations` and `Services` before each test.
- `CollectorDatabaseTestBase` (analogous; truncates `CapturedInputs`).
- Platform tests also wrap `authedTestApplication` in a `platformTestApplication { client -> ... }` helper that pre-installs the platform module.
- Collector tests have an equivalent `collectorTestApplication`.
- `PlatformStackTestBase` (`e2e-tests/`) spins up the full stack:
  - PostgreSQL container.
  - `validation-platform:test` and `validation-collector:test` images via `GenericContainer` (built by Jib as a test dependency).
  - Pre-built HTTP clients (`platformClient`, `collectorClient`) using the agent's client factories — so e2e exercises the same plugin stack agent uses in production.

## Test Patterns

**Repository test (uses real Postgres)**
```kotlin
class ServiceRepositoryTest : PlatformDatabaseTestBase() {
    @BeforeEach
    fun setupOrg() = runBlocking {
        OrganizationRepository.create(Organization(callerOrgId, "Test Org", Instant.now()))
    }

    @Test
    fun `find returns empty page when no services`() = runBlocking {
        val page = ServiceRepository.find(callerOrgId, cursor = null, limit = 10)
        assertEquals(emptyList(), page.items)
        assertNull(page.nextCursor)
    }
}
```

**Route test (Ktor `testApplication` via helper)**
```kotlin
class ServiceRoutesTest : PlatformDatabaseTestBase() {
    @Test
    fun `GET services scopes to caller org`() = platformTestApplication { client ->
        ServiceRepository.create(makeService("order-service"))
        val response = client.get("/api/services")
        assertEquals(HttpStatusCode.OK, response.status)
        val page = Json.decodeFromString(Page.serializer(Service.serializer()), response.bodyAsText())
        assertEquals(1, page.items.size)
    }
}
```

**Agent HTTP client test (MockEngine)**
```kotlin
private fun mockClient(status: HttpStatusCode = HttpStatusCode.Created): PlatformClient {
    val engine = MockEngine { _ -> respond("", status, headersOf(HttpHeaders.ContentType, "application/json")) }
    return PlatformClient(buildAgentPlatformHttpClient(engine), "http://platform:8080", "test-token")
}

@Test
fun `409 conflict is treated as Success`() = runBlocking {
    val outcome = mockClient(HttpStatusCode.Conflict).registerService("production", "api-gateway")
    assertEquals(RegistrationOutcome.Success, outcome)
}
```

**E2E test (full stack)**
```kotlin
class AgentDiscoveryE2ETest : PlatformStackTestBase() {
    @Test
    fun `agent registers k3s services through platform`() = runBlocking {
        startK3sWithServices()
        runAgentForOneDiscoveryTick()
        val services = platformClient.listServices().items
        assertTrue(services.any { it.name == "api-gateway" })
    }
}
```

## Concurrency in Tests

- Suspend tests use `runBlocking { ... }` at the top.
- Module-level parallelism is constrained where it matters (e.g., `shared/build.gradle.kts` sets `maxParallelForks = 1`) to avoid TestContainers thrash on shared singletons.
- TestContainers fixtures are class-static (`@BeforeAll`-installed), not per-test.

## Test Data and Fixtures

- Helper factories like `createService(name, cluster, ...)` keep test setup terse.
- Tokens always come from `TestJwtKeys.generateTestJwt(organizationId, cluster, role, expiresAt)` — never hand-rolled.
- Test orgs use `TestJwtKeys.DEFAULT_ORG_ID` so JWT claims match seeded rows out of the box.

## Coverage and Reporting

- No coverage tool is wired (no Kover or JaCoCo in `libs.versions.toml`).
- JUnit XML reports are produced by Gradle's default test task.

## E2E Image Builds

`e2e-tests/build.gradle.kts` declares Jib-built images as test dependencies:
- `validation-platform:test` and `validation-collector:test` are built into the local Docker daemon before tests run.
- Containers are started via TestContainers `GenericContainer` and wired together with the Postgres container.

## Local k3s + Test Workloads (manual)

```bash
./gradlew testServicesUp     # Deploy test microservices to local k3s
./gradlew testServicesStatus
./gradlew testServicesDown
```

These tasks are independent of automated tests — they exist for manual exploration and Kubeshark validation.

---

*Testing analysis: 2026-05-14*
