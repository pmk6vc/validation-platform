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

## Phase 2: Basic bearer token auth

**Why:** No auth means any pod in the cluster can push arbitrary traffic or read captured data.

### 2a. Create shared auth interceptor
- **File:** `shared/src/main/kotlin/com/platform/auth/BearerAuthPlugin.kt`
- Ktor plugin that reads `API_KEY` from env, compares to `Authorization: Bearer <token>` header
- Skip auth for `/health` and `/` paths
- Return 401 if missing/invalid
- Put in `shared/` since both app and collector need it

### 2b. Install in both servers
- **File:** `app/src/main/kotlin/com/platform/Application.kt` — install the plugin
- **File:** `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt` — install the plugin
- Both read `API_KEY` from environment

### 2c. Update existing tests
- All route tests in app and collector need to either:
  - Set `API_KEY` env/config to a known value in test setup, OR
  - Make auth configurable (e.g., `module(initDatabase = false, apiKey = "test-key")`) and pass the token in test requests
- Simpler approach: make the plugin skip auth when `API_KEY` env var is unset (no key = no auth = dev/test mode)

### 2d. Move API_KEY to K8s Secret
- **File:** `k8s/platform/secret.yaml` — create Secret with `api-key: <value>`
- Update `k8s/agent/agent.yaml` to use `secretKeyRef` instead of plain text
- Update platform manifests (Phase 3) to reference the same secret

---

## Phase 3: K8s manifests for platform

**Why:** App, collector, and postgres have no K8s manifests — only docker-compose for local dev.

### 3a. Create platform manifests
- **New dir:** `k8s/platform/`
- **`postgres.yaml`**: Deployment + Service + PVC (5Gi) + ConfigMap (db name/user) + Secret (password). Port 5432. Readiness probe: `pg_isready`.
- **`app.yaml`**: Deployment (image `validation-app:latest`) + Service (port 8080). Env vars: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `API_KEY` from secrets. Health: `GET /health`.
- **`collector.yaml`**: Same pattern, port 8081, image `validation-collector:latest`.
- **`namespace.yaml`**: `validation` namespace (if not already created by agent.yaml)
- **`kustomization.yaml`**: Aggregate all platform resources

### 3b. Deployment order matters
- Postgres first (wait for ready) → app (runs Flyway, wait for healthy) → collector (Flyway is idempotent) → agent
- Both app and collector share the same DB. Flyway advisory locks handle concurrent migration safely, but deploying app first is cleaner.

---

## Phase 4: Update sandbox-up.sh

**Why:** Script needs to build/push platform images and deploy platform manifests.

### Changes to `scripts/sandbox-up.sh`:
1. Build app and collector images: `docker build --platform linux/amd64 -t validation-app -f deploy/Dockerfile.app .` (and same for collector)
2. Add `validation-app` and `validation-collector` to `IMAGES` array
3. Deploy platform manifests after test services, before agent:
   ```
   kubectl apply -f k8s/platform/
   kubectl wait postgres ready
   kubectl wait app available
   kubectl wait collector available
   ```
4. Update agent.yaml sed to also inject `APP_URL`
5. Seed data: `curl POST /api/organizations` + `curl POST /api/services` for each test service, so `GET /api/agent/config` returns real `targetServices`
6. Update final output with platform port-forward instructions

---

## Phase 5: End-to-end verification

After `sandbox-up.sh` completes:

1. **Platform health:** `curl` app and collector health endpoints via port-forward
2. **Auth works:** Unauthenticated request gets 401; authenticated request gets 200
3. **Agent config:** `GET /api/agent/config` returns targetServices with test service IDs
4. **Agent logs:** `kubectl logs -n validation deployment/validation-agent` shows "Captured N entries" batches
5. **Data flows:** `GET /api/captured-inputs` via collector returns captured traffic from test services
6. **Performance:** Agent CPU/memory within limits, no OOMs, no excessive restarts

---

## Files to modify/create

| File | Action |
|------|--------|
| `app/src/main/kotlin/com/platform/api/Routes.kt` | Add `GET /api/agent/config` |
| `agent/src/main/kotlin/com/platform/agent/AgentConfig.kt` | Add `appUrl` field |
| `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` | Fix ConfigClient URL |
| `shared/src/main/kotlin/com/platform/auth/BearerAuthPlugin.kt` | **New** — shared auth plugin |
| `app/src/main/kotlin/com/platform/Application.kt` | Install auth plugin |
| `collector/src/main/kotlin/com/platform/collector/CollectorApplication.kt` | Install auth plugin |
| `k8s/platform/namespace.yaml` | **New** |
| `k8s/platform/postgres.yaml` | **New** |
| `k8s/platform/app.yaml` | **New** |
| `k8s/platform/collector.yaml` | **New** |
| `k8s/platform/secret.yaml` | **New** — API key + DB password |
| `k8s/platform/kustomization.yaml` | **New** |
| `k8s/agent/agent.yaml` | Add `APP_URL`, use secretKeyRef for `API_KEY` |
| `scripts/sandbox-up.sh` | Build/push/deploy platform, seed data |
| App + collector route tests | Add auth token to requests |
| Agent config tests | Test new `appUrl` field |
| App routes test | Test `GET /api/agent/config` |

---

## Implementation order

Phases 1, 2, and 3 are independent — can be done in parallel or any order. Suggested serial order for smallest PRs:

1. **Phase 1** (config endpoint + APP_URL) — unblocks agent config polling
2. **Phase 2** (auth) — unblocks secure deployment
3. **Phase 3** (K8s manifests) — unblocks GKE deployment
4. **Phase 4** (sandbox script) — ties it all together
5. **Phase 5** (verify) — manual on GKE
