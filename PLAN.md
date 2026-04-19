# Plan: Deploy Agent + Platform to GKE for End-to-End Validation

## Context

The agent captures traffic from test services via Kubeshark and pushes to the platform (collector + app). Currently only test services and the agent deploy to GKE — the platform itself (app, collector, postgres) has no K8s manifests and only runs locally via docker-compose. The agent also can't poll config because `GET /api/agent/config` doesn't exist, and the `ConfigClient` incorrectly uses `COLLECTOR_URL` instead of a separate app URL. Neither server validates auth tokens.

**Goal:** Deploy the full pipeline to GKE and verify: agent installation, service discovery, agent performance, platform performance on real traffic, and basic auth.

---

## Phase 1: Agent config endpoint + APP_URL fix

**Why:** Agent can't poll config — the endpoint doesn't exist, and `ConfigClient` points at the wrong URL.

### 1a. Add `GET /api/agent/config` to app module
- **File:** `app/src/main/kotlin/com/platform/api/Routes.kt`
- Add `route("/agent")` inside the existing `route("/api")` block
- `GET /config` returns a `DynamicConfig`-shaped JSON response
- For sandbox: query `ServiceRepository` to build the `targetServices` map (service name → service ID), return with defaults for other fields
- Duration fields must serialize as Long milliseconds (match `DurationAsMillisSerializer`)

### 1b. Add `appUrl` to `StaticConfig`
- **File:** `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt`
- Add `appUrl: String` field, read from `APP_URL` env var (required)
- Update `CLUSTER` to also be read from env (it's referenced in CLAUDE.md but not in StaticConfig)

### 1c. Wire `ConfigClient` to `appUrl`
- **File:** `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`
- Line 45: change `staticConfig.collectorUrl` → `staticConfig.appUrl`

### 1d. Add `APP_URL` to agent manifest
- **File:** `k8s/agent/agent.yaml`
- Add `APP_URL: "http://app.validation.svc.cluster.local:8080"`

### Tests
- Add route test for `GET /api/agent/config` in app module
- Update `AgentConfig` unit tests for new `appUrl` field

---

## Phase 2: JWT auth via Envoy reverse proxy

**Why:** No auth means any pod in the cluster can push arbitrary traffic or read captured data. A reverse proxy centralizes auth, routes to independently-scaled backends, and establishes the real production traffic path. Envoy is the right choice over a custom Ktor proxy because it handles request forwarding, load balancing, health checking, and JWT validation out of the box.

**Why separate scaling matters:** The collector handles the hot path (agents pushing batches every ~2s), while the app handles low-frequency operations (config polls, service registration). They need independent replica counts (collector: 3-10+, app: 1-2). A routing layer is required regardless — Envoy provides it for free.

**Why RS256 over HMAC-SHA256:** Asymmetric signing (RS256) lets us serve the public verification key at a JWKS endpoint without exposing the signing secret. Envoy fetches the public key via `remote_jwks` — no secrets in Envoy config, no custom Dockerfile, no entrypoint scripts. This is the same pattern used by Google (`googleapis.com/oauth2/v3/certs`), Auth0, and every OIDC provider.

### Architecture

```
Agent → Envoy (8082, all requests, Bearer <JWT>)
         │
         │  1. Validates JWT signature using public key
         │     fetched from app's JWKS endpoint
         │  2. Extracts claims → forwards as headers
         │
         ├── /api/captured-inputs/* → Collector (8081)
         ├── /api/*                 → App (8080)
         └── /health                → 200 OK (direct response)

App serves:
  GET /.well-known/jwks.json → RSA public key (JWKS format)
  (used by Envoy for JWT verification)
```

**Key flow:**
- RSA private key: stored in secrets manager / K8s Secret, read by app module only
- RSA public key: served by app at `/.well-known/jwks.json`, fetched by Envoy
- JWT tokens: signed by app (or a Gradle task for sandbox), contain `organizationId`, `cluster`, `role` claims
- Envoy validates JWT using the public key, forwards claims as `X-Organization-Id`, `X-Cluster`, `X-Role` headers
- Backend modules trust these headers — no JWT code in app or collector

### 2a. JWKS endpoint + Envoy configuration

**App module — JWKS endpoint:**
- **`app/src/main/kotlin/com/platform/api/JwksRoute.kt`** — new file:
  - `GET /.well-known/jwks.json` — serves the RSA public key in JWKS format
  - Reads RSA private key from `JWT_PRIVATE_KEY` env var (PEM format) or a file path
  - Derives the public key, formats as JWKS JSON (`kty: "RSA"`, `alg: "RS256"`, `n`, `e` fields)
  - This endpoint is unauthenticated (Envoy needs it before it can validate anything)

**Envoy config:**
- **`deploy/envoy/envoy.yaml`** — static Envoy config, **no templating, no custom image**:
  - **Listener** on port 8082
  - **JWT authn filter** (`envoy.filters.http.jwt_authn`):
    - `remote_jwks` pointing to `http://app:8080/.well-known/jwks.json`
    - Envoy fetches and caches the public key, refreshes periodically
    - Extracts `organizationId`, `cluster`, `role` claims
    - Forwards as `X-Organization-Id`, `X-Cluster`, `X-Role` headers
    - Bypasses auth for `/health`, `/`, and `/.well-known/jwks.json`
  - **Route config**:
    - `/api/captured-inputs/*` → `collector` cluster (port 8081)
    - `/api/*` → `app` cluster (port 8080)
    - `/.well-known/*` → `app` cluster (unauthenticated)
    - `/health` → direct 200 response
  - **Clusters**: `app` and `collector` with health checking
- **Stock `envoyproxy/envoy:v1.31-latest` image** — no custom Dockerfile, no entrypoint script

**Token generation — Gradle task:**
- **`app/src/main/kotlin/com/platform/auth/JwtTokenGenerator.kt`** — Kotlin `main()` function:
  - Reads RSA private key from env/file
  - Accepts `--organizationId`, `--cluster`, `--role`, `--expiryDays` args
  - Signs and prints a JWT using `com.auth0:java-jwt`
  - Invoked via `./gradlew :app:run --args="generate-token --organizationId org-123 --cluster prod"` or a dedicated Gradle task
- No shell scripts, no Python dependency

### 2b. Replace auth in backend modules
- **`app/src/main/kotlin/com/platform/api/Auth.kt`** — replace `BearerAuthPlugin` with `HeaderIdentityPlugin`:
  - Reads `X-Organization-Id`, `X-Cluster`, `X-Role` headers (set by Envoy)
  - Sets `AgentIdentity` as call attribute (same `AgentIdentityKey` used by Routes.kt)
  - No token validation, no 401s — Envoy already handled that
  - When headers absent, identity is null (backwards compatible for direct access in dev)
- **`app/src/main/kotlin/com/platform/Application.kt`** — remove `apiKey`/`apiKeyOrgId`/`apiKeyCluster` params from `module()`
- **`collector/src/main/kotlin/com/platform/collector/api/Auth.kt`** — delete entirely
- **`collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt`** — remove `apiKey` param and `installAuth()` call

### 2c. Simplify agent to single `PLATFORM_URL`
- **`agent/src/main/kotlin/com/platform/agent/AgentConfig.kt`** — replace `collectorUrl` + `appUrl` with single `platformUrl` (from `PLATFORM_URL` env var). Keep `apiKey` — agent doesn't know it's a JWT.
- **`agent/src/main/kotlin/com/platform/agent/AgentApplication.kt`** — pass `platformUrl` to both `CollectorClient` and `ConfigClient`

### 2d. Deployment artifacts
- **`deploy/docker-compose.yaml`** — add `envoy` service using stock `envoyproxy/envoy:v1.31-latest` image with volume-mounted config. Envoy is the only externally-exposed port (8082). App and collector internal only. App gets `JWT_PRIVATE_KEY` env var.
- **`k8s/platform/secret.yaml`** — RSA private key (for app to sign tokens and serve JWKS) + pre-generated agent JWT + DB password
- **`k8s/platform/envoy.yaml`** — Deployment + Service + ConfigMap (envoy config). No secrets needed — Envoy fetches public key from app's JWKS endpoint.
- **`k8s/agent/agent.yaml`** — `PLATFORM_URL` pointing to Envoy, `API_KEY` reads JWT from secret

### 2e. Integration tests (TestContainers, no docker-compose)
- **`integration-tests/` module** — dedicated Gradle module for cross-module tests
- Tests use TestContainers to spin up individual containers:
  - `PostgreSQLContainer` for the database
  - `GenericContainer` for app (built from `deploy/Dockerfile.app`)
  - `GenericContainer` for collector (built from `deploy/Dockerfile.collector`)
  - `GenericContainer` for Envoy (stock `envoyproxy/envoy:v1.31-latest`, config mounted)
  - All containers on a shared TestContainers `Network`
- No docker-compose file for tests — each container's lifecycle managed by test code
- Uses `com.auth0:java-jwt` to generate test JWTs in Kotlin
- Test coverage:
  - Health endpoint accessible without auth
  - Unauthenticated `/api/*` returns 401
  - Invalid/expired/wrong-key JWT returns 401
  - Valid JWT routes to correct upstream (app vs collector)
  - Claims forwarded as headers (verified by agent config scoping)
  - Multi-org isolation: JWT for org-A can't see org-B's services

---

## Phase 3: K8s manifests for platform

**Why:** App, collector, Envoy, and postgres have no K8s manifests — only docker-compose for local dev.

### 3a. Create platform manifests
- **New dir:** `k8s/platform/`
- **`namespace.yaml`**: `validation` namespace
- **`postgres.yaml`**: Deployment + Service + PVC (5Gi) + ConfigMap (db name/user) + Secret (password). Port 5432. Readiness probe: `pg_isready`.
- **`app.yaml`**: Deployment (image `validation-app:latest`) + Service (port 8080, ClusterIP — not externally exposed). Env vars: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`. Health: `GET /health`.
- **`collector.yaml`**: Same pattern, port 8081, ClusterIP. Independent replica count for scaling under ingest load.
- **`envoy.yaml`**: Deployment (`envoyproxy/envoy:v1.31-latest`) + Service (port 8082) + ConfigMap (envoy config). JWT secret from K8s Secret.
- **`secret.yaml`**: JWT signing secret + pre-generated agent JWT token + DB password
- **`kustomization.yaml`**: Aggregate all platform resources

### 3b. Deployment order
- Postgres (wait for ready) → app (runs Flyway, wait for healthy) → collector → Envoy → agent

---

## Phase 4: Update sandbox-up.sh

**Why:** Script needs to build/push platform images (including proxy) and deploy platform manifests.

### Changes to `scripts/sandbox-up.sh`:
1. Build app, collector, and proxy images: `docker build --platform linux/amd64 ...`
2. Add `validation-app`, `validation-collector`, `validation-proxy` to `IMAGES` array
3. Generate JWT signing secret and agent token: `scripts/generate-jwt.sh`
4. Create K8s secret with JWT secret + token + DB password
5. Deploy platform manifests in order: postgres → app → collector → proxy
6. Deploy agent (reads JWT token from secret, `PLATFORM_URL` points to proxy)
7. Seed data: `curl` through proxy with JWT to `POST /api/organizations` + `POST /api/services`
8. Update final output with proxy port-forward instructions

---

## Phase 5: End-to-end verification

After `sandbox-up.sh` completes:

1. **Platform health:** `curl http://proxy:8082/health` returns 200
2. **Auth works:** unauthenticated `curl http://proxy:8082/api/services` returns 401; with JWT returns 200
3. **Agent config:** `GET /api/agent/config` through proxy returns targetServices scoped to the agent's org+cluster
4. **Agent logs:** `kubectl logs -n validation deployment/validation-agent` shows "Captured N entries" batches
5. **Data flows:** `GET /api/captured-inputs` through proxy returns captured traffic
6. **Performance:** Agent CPU/memory within limits, no OOMs, no excessive restarts
7. **Multi-tenancy:** Create a second JWT with a different org — verify it only sees its own services

---

## Files to modify/create

| File | Action |
|------|--------|
| `deploy/envoy/envoy.yaml` | **New** — static Envoy config (remote JWKS, JWT filter, routing, clusters) |
| `app/src/main/kotlin/com/platform/api/JwksRoute.kt` | **New** — `GET /.well-known/jwks.json` serving RSA public key |
| `app/src/main/kotlin/com/platform/auth/JwtTokenGenerator.kt` | **New** — Kotlin CLI for generating signed JWTs |
| `app/src/main/kotlin/com/platform/api/Auth.kt` | Replace BearerAuthPlugin with HeaderIdentityPlugin |
| `app/src/main/kotlin/com/platform/Application.kt` | Remove auth params, add JWKS route |
| `collector/src/main/kotlin/com/platform/collector/api/Auth.kt` | **Delete** |
| `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt` | Remove auth |
| `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt` | Replace collectorUrl+appUrl with platformUrl |
| `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` | Use platformUrl for both clients |
| `deploy/docker-compose.yaml` | Add envoy service (stock image, volume mount), internalize app+collector |
| `.dockerignore` | **New** — exclude build/, .gradle/, .git/ from Docker context |
| `gradle/libs.versions.toml` | Add `java-jwt` dependency |
| `settings.gradle.kts` | Add `include("integration-tests")` |
| `integration-tests/build.gradle.kts` | **New** — TestContainers + java-jwt + Ktor client |
| `integration-tests/src/test/.../EnvoyAuthIntegrationTest.kt` | **New** — full-stack integration tests |
| `k8s/platform/envoy.yaml` | **New** — Deployment + Service + ConfigMap (no secrets) |
| `k8s/platform/secret.yaml` | RSA private key + pre-generated agent JWT + DB password |
| `k8s/agent/agent.yaml` | PLATFORM_URL, jwt-token from secret |
| App + collector + agent tests | Update for header-based identity + platformUrl |

---

## Security & Hardening Roadmap

Sequenced by when each capability is needed, not by implementation difficulty.

### Sandbox milestone (now) — unblock end-to-end pipeline
- [ ] RS256 JWT with `organizationId`, `cluster`, `role` claims
- [ ] App serves RSA public key at `/.well-known/jwks.json`
- [ ] Envoy validates JWT via remote JWKS, forwards claims as headers
- [ ] Backend reads identity from forwarded headers
- [ ] Token generation via Kotlin Gradle task (no shell scripts, no Python)
- [ ] Stock Envoy image (no custom Dockerfile, no entrypoint scripts)

### Before multi-tenant beta (2+ customers sharing the platform)

**Row-level security (RLS) in Postgres:**
- RLS policies on `services` and `captured_inputs` tables scoped by `organization_id`
- Even if app code has a bug, Postgres won't return another org's data
- Set `app.current_organization_id` session variable from the `X-Organization-Id` header before each transaction
- Policies: `CREATE POLICY org_isolation ON services USING (organization_id = current_setting('app.current_organization_id')::uuid)`
- Must come before the second customer onboards, not after

**Role claim enforcement:**
- Add `role` to JWT (`agent`, `admin`, `reader`)
- Envoy forwards as `X-Role` header
- Backend checks role before allowing operations:
  - `agent`: `POST /api/captured-inputs`, `GET /api/agent/config`, `POST /api/services`
  - `admin`: all endpoints
  - `reader`: `GET` endpoints only
- Prevents a leaked agent token from reading captured data or creating orgs

**Audit logging:**
- Log every authenticated request: org, cluster, role, endpoint, method, timestamp, response status
- Essential for debugging multi-tenant issues and customer trust
- Can be a simple structured log line per request (Envoy access log + app-level logging)

### Before GA / production customers

**Short-lived JWTs + client credentials (OAuth2 client_credentials flow):**
- Agent gets `client_id` + `client_secret` (long-lived, stored in K8s Secret)
- Agent calls `POST /api/auth/token` with credentials → receives JWT valid for 1 hour
- Agent refreshes before expiry (add refresh logic to `ConfigClient` or a new `AuthClient`)
- Platform can revoke client credentials immediately — next refresh fails
- Window of exposure: lifetime of last issued JWT (1 hour), not 365 days
- Token endpoint lives behind Envoy but is unauthenticated (it *is* the auth endpoint)

**Customer onboarding flow:**
- `POST /api/organizations` (admin-only) returns the org + a one-time bootstrap credential
- Customer runs: `kubectl create secret generic agent-credentials --from-literal=client-id=X --from-literal=client-secret=Y`
- Agent handles auth automatically on startup
- Future: CLI tool (`validation-cli agent install --org org-123 --cluster prod`) that automates secret creation

**Token rotation without downtime:**
- Support two active signing keys simultaneously (primary + previous)
- Envoy JWKS config accepts multiple keys
- Rotate: generate new key → add to JWKS → wait for all tokens signed with old key to expire → remove old key
- Prevents "rotate key = all agents break" scenario

**Rate limiting per org:**
- Envoy's `envoy.filters.http.ratelimit` filter with per-org descriptors
- Prevents one customer's agent from starving the collector for others
- Separate limits for ingest (`POST /api/captured-inputs`) vs reads

**Column-level security:**
- Restrict which fields are visible by role
- Agent role shouldn't see other agents' captured request/response bodies
- Less urgent than RLS (which prevents cross-org access entirely)
- Implement via Postgres column-level privileges or app-layer field filtering

### Additional hardening (ongoing)

**Network policies:**
- K8s NetworkPolicy: only Envoy can reach app/collector pods, only agent can reach Envoy
- App/collector cannot initiate outbound connections (except to Postgres)
- Prevents lateral movement if a pod is compromised

**mTLS between services:**
- Envoy → app/collector communication over mTLS
- Prevents a compromised pod from impersonating Envoy and injecting fake `X-Organization-Id` headers
- Envoy has native mTLS support; can also use a service mesh (Istio/Linkerd) if already deployed

**Secret encryption at rest:**
- Enable K8s `EncryptionConfiguration` for Secrets (etcd encryption)
- Or use an external secrets manager (AWS Secrets Manager, GCP Secret Manager, HashiCorp Vault)
- Prevents secrets from being readable in etcd backups

**Input validation hardening:**
- Request body size limits on Envoy (prevent OOM from oversized payloads beyond the app-level `MAX_BATCH_SIZE`)
- Request timeout enforcement on Envoy (prevent slow-loris attacks)
- Header size limits

**Dependency scanning:**
- Add `dependabot` or `renovate` for automated dependency updates
- CVE scanning on container images (Trivy, Snyk)

---

## Implementation order

1. **Phase 1** (config endpoint + APP_URL) — DONE (PRs #45, #47)
2. **Phase 2** (Envoy + JWT auth + backend simplification + agent PLATFORM_URL)
   - PR 1: Envoy config + backend auth simplification + agent PLATFORM_URL
   - PR 2: Token generation script + deployment artifacts (docker-compose, K8s)
3. **Phase 3** (K8s manifests for app, collector, postgres) — depends on Phase 2 for Envoy manifest
4. **Phase 4** (sandbox script) — ties it all together
5. **Phase 5** (verify) — manual on GKE
6. **Phase 6** (multi-tenant hardening) — RLS, role enforcement, audit logging
7. **Phase 7** (production auth) — short-lived JWTs, client credentials, onboarding flow
8. **Phase 8** (infrastructure hardening) — network policies, mTLS, secret encryption, rate limiting
