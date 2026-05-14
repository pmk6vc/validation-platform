<!-- refreshed: 2026-05-14 -->
# Codebase Structure

**Analysis Date:** 2026-05-14

## Top-Level Layout

```
validation-platform/
├── shared/                  # Shared infrastructure (JWT, DB, value classes, test fixtures)
├── platform/                # Organizations + Services + JWKS + agent config (port 8080)
├── collector/               # CapturedInputs ingestion (port 8081)
├── agent/                   # Standalone validation agent (deployed to customer clusters)
├── e2e-tests/               # Full-stack integration tests (platform + collector + k3s)
├── test-services/           # Standalone microservices for k3s integration testing
├── tap/                     # Experimental Go eBPF traffic-attribution tap
├── deploy/                  # Dockerfiles for platform/collector/agent
├── k8s/                     # Kubernetes manifests (platform, agent, test-services, tap)
├── infra/                   # Terraform (Cloud Run, Cloud SQL, GKE, Artifact Registry)
├── scripts/                 # Bring-up scripts (platform-up, sandbox-up, bootstrap-db, ...)
├── docs/                    # Implementation plans
├── build.gradle.kts         # Root Gradle config
├── settings.gradle.kts      # Subproject includes
├── CLAUDE.md                # Project instructions
└── .planning/codebase/      # Generated codebase maps
```

## Module-Level Layout

### `shared/`

```
shared/
├── src/main/kotlin/com/platform/shared/
│   ├── auth/                # JwtAuth.kt (installJwtAuth, derivePublicKey), AgentIdentity.kt
│   ├── database/            # DatabaseFactory.kt, MigrationMode.kt
│   ├── models/              # Page.kt, InstantSerializer.kt, Ids.kt (OrganizationId, ServiceId)
│   └── secrets/             # SecretsProvider.kt, GCP provider
├── src/main/resources/db/migration/
│   ├── V0001__create_organizations_table.sql
│   ├── V0002__create_services_table.sql
│   ├── V0003__alter_services_provider_to_enum.sql
│   ├── V0004__create_captured_inputs.sql
│   ├── V0005__use_timestamptz.sql
│   ├── V0006__drop_captured_inputs_service_fk.sql
│   └── V0007__add_organization_id_to_captured_inputs.sql
├── src/testFixtures/kotlin/com/platform/shared/
│   ├── database/DatabaseTestBase.kt           # TestContainers PostgreSQL singleton
│   ├── kubernetes/KubernetesWorkloadTestBase.kt  # k3s TestContainer + 7 services
│   └── testing/
│       ├── TestJwtKeys.kt                     # Shared RSA test keypair
│       └── AuthedTestApplication.kt           # Ktor testApplication with JWT auth wired
└── build.gradle.kts
```

### `platform/` (port 8080)

```
platform/
├── src/main/kotlin/com/platform/
│   ├── Application.kt                          # Ktor entry: installJwtAuth, DatabaseFactory, routing
│   ├── api/
│   │   ├── Routes.kt                           # /api/organizations, /api/services, /api/agent/config
│   │   ├── JwksRoute.kt                        # /.well-known/jwks.json
│   │   └── Requests.kt                         # CreateOrganizationRequest, CreateServiceRequest, AgentConfigResponse
│   ├── database/
│   │   ├── Tables.kt                           # Exposed Organizations, Services tables
│   │   ├── OrganizationRepository.kt
│   │   └── ServiceRepository.kt
│   ├── models/                                 # Organization.kt, Service.kt, Provider.kt
│   └── auth/JwtTokenGenerator.kt               # CLI: ./gradlew :platform:generateToken
├── src/main/resources/application.yaml         # Ktor 3 YAML config
├── src/test/kotlin/                            # Routes + Repository tests (uses testFixtures)
└── build.gradle.kts
```

### `collector/` (port 8081)

```
collector/
├── src/main/kotlin/com/platform/collector/
│   ├── CollectorApplication.kt                 # Ktor entry: installJwtAuth, Compression, routing
│   ├── api/Routes.kt                           # /api/captured-inputs (POST/GET/DELETE)
│   ├── database/
│   │   ├── CapturedInputs.kt                   # Exposed table
│   │   └── CapturedInputRepository.kt          # create, createBatch, findById, find, countByService, deleteByService
│   └── models/
│       ├── CapturedInput.kt
│       ├── CreateCapturedInputRequest.kt
│       ├── BatchCreateCapturedInputRequest.kt
│       ├── BatchCreateCapturedInputResponse.kt
│       ├── InputType.kt                        # HTTP, UNKNOWN
│       ├── Ids.kt                              # CapturedInputId, ServiceId
│       └── DeleteResponse.kt
├── src/main/resources/application.yaml
├── src/test/kotlin/                            # Routes + Repository tests
└── build.gradle.kts
```

### `agent/` (standalone process)

```
agent/
├── src/main/kotlin/com/platform/agent/
│   ├── AgentApplication.kt                     # main(); spawns 3 coroutine loops
│   ├── AgentConfig.kt                          # StaticConfig (env vars), DynamicConfig (polled)
│   ├── K8sServiceDiscovery.kt                  # Fabric8 KubernetesClient wrapper; implements Closeable
│   ├── PlatformClient.kt                       # POST /api/services; returns RegistrationOutcome
│   ├── ConfigClient.kt                         # GET /api/agent/config
│   ├── KubesharkClient.kt                      # Persistent WebSocket; bounded Channel<KubesharkEntry>(1000)
│   ├── CollectorClient.kt                      # POST /api/captured-inputs (gzip + retry)
│   ├── TrafficTransformer.kt                   # Filter + base64 decode + sample
│   └── models/
│       ├── KubesharkEntry.kt                   # HAR-ish wire DTOs
│       └── CapturedInputRequest.kt             # Collector POST payload DTOs
├── src/test/kotlin/                            # Unit + integration tests (MockEngine, no DB)
└── build.gradle.kts                            # Jib plugin; Fabric8 dep; Ktor client + WebSockets
```

### `e2e-tests/`

```
e2e-tests/
├── src/main/kotlin/com/platform/e2e/
│   ├── PlatformStackTestBase.kt                # Postgres + platform + collector via GenericContainer
│   ├── AgentDiscoveryE2ETest.kt                # End-to-end K8s → agent → platform → collector
│   └── *.E2ETest.kt
└── build.gradle.kts                            # TestContainers, Jib image builds as test deps
```

### `test-services/`

```
test-services/
├── api-gateway/
├── order-service/
├── notification-service/
├── webhook-stub/
└── traffic-generator/
```

Each service has its own `build.gradle.kts`, Dockerfile, and Kotlin source. Deployed to k3s via `./gradlew testServicesUp`.

### `tap/` (Go — experimental)

```
tap/
├── cmd/vp-tap/main.go                          # Entry point
├── go.mod / go.sum
└── pkg/...                                     # eBPF traffic-attribution logic
```

Separate ecosystem from the Kotlin modules. Not built by Gradle; standalone Go toolchain.

## Deployment Layout

```
deploy/
├── Dockerfile.platform          # multi-stage; gradle → eclipse-temurin:21-jre
├── Dockerfile.collector         # multi-stage; gradle → eclipse-temurin:21-jre
├── Dockerfile.agent             # multi-stage; non-root USER agent
└── docker-compose.yaml          # local dev: postgres + platform + collector

k8s/
├── platform/                    # platform + collector Cloud Run/K8s manifests
│   ├── platform.yaml
│   ├── collector.yaml
│   ├── postgres.yaml
│   ├── secret.yaml
│   ├── namespace.yaml
│   └── kustomization.yaml
├── agent/
│   ├── base/
│   │   ├── agent.yaml           # Deployment (1 replica, file-based liveness probe)
│   │   └── kustomization.yaml
│   └── overlays/sandbox/        # GKE-specific: image registry, serviceAccount, URL placeholders
├── test-services/
│   ├── base/                    # 01-infrastructure, 02-production, 03-external
│   └── overlays/gke/
└── tap/
    ├── daemonset.yaml
    └── rbac.yaml

infra/
├── platform/                    # Terraform: Cloud Run, Cloud SQL, VPC, IAM
├── sandbox/                     # Terraform: GKE cluster, Artifact Registry
└── bootstrap/                   # One-time setup

scripts/
├── platform-up.sh               # Provision Cloud Run + Cloud SQL
├── platform-down.sh
├── bootstrap-db.sh              # Grant platform SA ownership of public schema
├── sandbox-up.sh                # GKE + test services + seed-org + Kubeshark (Helm) + agent
├── sandbox-down.sh
├── seed-org.sh                  # Create test organization + token
└── common.sh
```

## Key File Locations

**Entry points**
- `platform/src/main/kotlin/com/platform/Application.kt`
- `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt`
- `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`
- `tap/cmd/vp-tap/main.go` (Go side)

**Configuration**
- `platform/src/main/resources/application.yaml` — port 8080, Ktor module reference
- `collector/src/main/resources/application.yaml` — port 8081, Ktor module reference
- `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt` — StaticConfig + DynamicConfig types

**Core logic**
- `platform/src/main/kotlin/com/platform/api/Routes.kt` — all platform endpoints
- `collector/src/main/kotlin/com/platform/collector/api/Routes.kt` — all collector endpoints
- `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt` — persistent WS session, KFL query, dedup
- `agent/src/main/kotlin/com/platform/agent/K8sServiceDiscovery.kt` — Loop 1 discovery
- `agent/src/main/kotlin/com/platform/agent/PlatformClient.kt` — `RegistrationOutcome` mapping
- `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt` — `installJwtAuth`, `derivePublicKey`

**Persistence**
- `platform/src/main/kotlin/com/platform/database/Tables.kt`, `ServiceRepository.kt`, `OrganizationRepository.kt`
- `collector/src/main/kotlin/com/platform/collector/database/CapturedInputs.kt`, `CapturedInputRepository.kt`
- `shared/src/main/kotlin/com/platform/shared/database/DatabaseFactory.kt`
- `shared/src/main/resources/db/migration/V0001..V0007.sql`

**Testing**
- `shared/src/testFixtures/kotlin/com/platform/shared/database/DatabaseTestBase.kt`
- `shared/src/testFixtures/kotlin/com/platform/kubernetes/KubernetesWorkloadTestBase.kt`
- `shared/src/testFixtures/kotlin/com/platform/shared/testing/TestJwtKeys.kt`
- `shared/src/testFixtures/kotlin/com/platform/shared/testing/AuthedTestApplication.kt`

## Naming Conventions

**Files**
- `*Routes.kt` — Ktor route handler groups (one per module API surface).
- `*Repository.kt` — data access singletons (Exposed-based).
- `*Table.kt` or `Tables.kt` — Exposed `object : Table(...)` definitions.
- `*Client.kt` — agent HTTP/WebSocket client facades (`PlatformClient`, `CollectorClient`, `ConfigClient`, `KubesharkClient`).
- `*Discovery.kt` — Kubernetes resource discovery (agent only).
- `*Transformer.kt` — data transformation pipeline (`TrafficTransformer`).
- `*Application.kt` — Ktor module entry (`Application.kt`, `CollectorApplication.kt`); agent uses `AgentApplication.kt`.
- Tests: `*Test.kt` only — no `*IT.kt` distinction. E2E tests live under `e2e-tests/` and end in `E2ETest.kt`.

**Directories (per module)**
- `api/` — route handlers + DTOs.
- `database/` — repositories + table definitions.
- `models/` — domain models, value classes.
- `auth/` — module-specific auth code (e.g., `JwtTokenGenerator` in platform).
- `secrets/` — secret providers (shared only).

**Identifiers**
- Packages: `com.platform.<module>[.subpackage]`.
- Classes: PascalCase. Tables use plural noun forms (`Organizations`, `Services`, `CapturedInputs`).
- Functions: camelCase. Test function names use backtick strings.
- Constants: UPPER_SNAKE_CASE (`DEFAULT_PAGE_SIZE = 20`, `MAX_PAGE_SIZE = 100`, `MAX_BATCH_SIZE = 1000`).
- Value class IDs: `OrganizationId`, `ServiceId`, `CapturedInputId` — UUID-validated `@JvmInline value class`.

## Where to Add New Code

**New feature module (e.g. replay engine)**
- `replay/src/main/kotlin/com/platform/replay/` with `api/`, `database/`, `models/` subpackages.
- Migration in `shared/src/main/resources/db/migration/V000X__create_replay_runs.sql`.
- Wire-up: add to `settings.gradle.kts`; depend on `:shared` only.
- Tests: `replay/src/test/kotlin/` extending `DatabaseTestBase`.

**New endpoint in an existing module**
- Add to that module's `api/Routes.kt`.
- DTOs in `api/Requests.kt` (or local file).
- Repository changes in `database/`.
- Migration in `shared/src/main/resources/db/migration/` (next V-number).

**New agent capability**
- New file in `agent/src/main/kotlin/com/platform/agent/`.
- Wire into `AgentApplication.kt` (new loop or modification to existing).
- Wire DTOs in `agent/src/main/kotlin/com/platform/agent/models/`.
- No imports from `:platform` or `:collector` — duplicate the wire types.

**New shared utility**
- `shared/src/main/kotlin/com/platform/shared/<area>/`. Only add to `testFixtures` if used in 2+ modules.

## Special Directories

- `shared/src/main/resources/db/migration/` — Flyway SQL migrations, hand-written, sequence `V0001..V000N`, committed to git, never edited after first apply.
- `k8s/agent/overlays/sandbox/` — Kustomize overlay with `__PLATFORM_URL__`, `__COLLECTOR_URL__`, `__KUBESHARK_URL__` placeholders that `scripts/sandbox-up.sh` substitutes at deploy time.
- `deploy/` — hand-written Dockerfiles; agent image is also buildable via Jib (`agent/build.gradle.kts`) for direct push to Artifact Registry.
- `.planning/codebase/` — output of `/gsd-map-codebase`. Consumed by GSD planning workflows.

---

*Structure analysis: 2026-05-14*
