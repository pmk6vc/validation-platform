# Plan: Finish End-to-End Traffic Flow on GCP

## Context

Most of the original GKE-based deployment plan has shipped — but the architecture pivoted twice along the way:

1. **Envoy was dropped** (PR #75). JWT is validated in-app via the shared `installJwtAuth()` library on both `platform` and `collector`.
2. **Platform + collector moved to Cloud Run**, not GKE. Cloud SQL is reached via the JDBC socket factory (PR #86), not an Auth Proxy sidecar. Secrets are read directly from Secret Manager via `SecretsProvider` (PR #82) — no ESO.

The deployed topology today is:

- **Cloud Run** (region `us-central1`): `validation-platform`, `validation-collector` — public HTTPS, JWT-authenticated, talking to Cloud SQL via JDBC socket factory.
- **GKE Standard sandbox cluster** (`infra/sandbox/`): on-demand, brought up by `scripts/sandbox-up.sh`. Currently runs the test microservices only.
- **Cloud SQL** PostgreSQL 16, **Artifact Registry**, **Secret Manager**, **WIF for CI** — all Terraform-managed (`infra/platform/`).
- **CI** (PR #66): terraform fmt/validate on PRs; on merge to main, build → push → `gcloud run update`.

What's missing to close the end-to-end loop is the **agent + Kubeshark on the sandbox cluster**, plus an **org-seeding script** to mint the agent's JWT.

---

## What's already done

| Area | Status | Reference |
|------|--------|-----------|
| Terraform for Cloud Run, Cloud SQL, Artifact Registry, Secret Manager, IAM, WIF | Done | `infra/platform/` (PRs #61, #66) |
| Sandbox GKE cluster Terraform | Done | `infra/sandbox/cluster.tf` |
| Public schema bootstrap for Cloud SQL IAM auth | Done | `scripts/bootstrap-db.sh` (PRs #89, #90) |
| Lifecycle scripts | Done | `scripts/{bootstrap,platform-up,platform-down,platform-delete,sandbox-up,sandbox-down}.sh` (PRs #65, #67, #68) |
| Cloud Run JDBC socket factory + IAM DB auth | Done | PRs #86, #87, #88 |
| In-app RS256 JWT auth (Envoy removed) | Done | PRs #71–#75 |
| Per-tenant authorization on `/api/*` | Done | PR #81 |
| Agent: split `PLATFORM_URL` / `COLLECTOR_URL` | Done | PR #74 |
| Agent → collector gzip POST | Done | PR #80 |
| Structured JSON logging (LogstashEncoder) | Done | PR #78 |
| Test services deploy to GKE sandbox | Done | `k8s/test-services/overlays/gke/`, `scripts/sandbox-up.sh` (PR #79) |
| CI: build + push + deploy on merge to main | Done | `.github/workflows/push_main.yml` (PR #66) |

---

## What's left

The "money test" from the original plan — production-style traffic flowing through Kubeshark → agent → collector — has not yet been demonstrated on real GCP. To get there, we need three things on the sandbox cluster: an agent overlay, Kubeshark, and a JWT for the agent to use.

### Phase A: Org seeding + agent JWT

**Goal:** Create the dummy org in the platform, mint an agent JWT, and write it directly to a Kubernetes Secret in the sandbox cluster.

**Why first:** The agent can't do anything without a token. The token requires an organization to exist. One small script bootstraps both.

**Why not Secret Manager:** the only consumer is the agent, in one ephemeral cluster. `agent.yaml` already reads `API_KEY` from a K8s Secret called `platform-api-key`. Routing the JWT through Secret Manager would force us to add a GCP SA for the agent, a Workload Identity binding, a `secretAccessor` role, and a CSI driver or initContainer — all to deliver a value we just generated locally. `kubectl create secret` is the right tool.

#### Steps

1. Pull the JWT private key from Secret Manager: `gcloud secrets versions access latest --secret=validation-jwt-private-key`. Export as `JWT_PRIVATE_KEY` for the next two steps.
2. Generate a temp admin JWT with `./gradlew :platform:generateToken --args="--org <new-uuid> --cluster validation-sandbox"`. (No org exists yet, but the platform doesn't validate the org claim against the DB on `POST /api/organizations` — see TODO in `Routes.kt`.)
3. `POST <CLOUD_RUN_PLATFORM_URL>/api/organizations` with `{"name":"sandbox-org"}`, capture org ID from the response.
4. Mint the real agent JWT: `./gradlew :platform:generateToken --args="--org $ORG_ID --cluster validation-sandbox"`.
5. Write it to the sandbox cluster as a K8s Secret:
   ```bash
   kubectl create secret generic platform-api-key \
     --from-literal=jwt-token="$AGENT_JWT" \
     --namespace=validation \
     --dry-run=client -o yaml | kubectl apply -f -
   ```
   Idempotent via `apply`.

#### Files

- `scripts/seed-org.sh` — **new**, idempotent (re-running picks up an existing org by name and re-mints the JWT).

#### Verification

- `curl -H "Authorization: Bearer $TOKEN" $PLATFORM_URL/api/organizations` → returns `sandbox-org`.
- `kubectl get secret platform-api-key -n validation -o jsonpath='{.data.jwt-token}' | base64 -d` → matches the minted JWT.

#### Milestone

Agent JWT in place in the sandbox cluster. Re-running `seed-org.sh` is a no-op for the org and refreshes the Secret.

---

### Phase B: Agent service discovery + agent + Kubeshark on the sandbox cluster

**Goal:** Implement the agent's K8s service discovery loop, deploy Kubeshark and the agent into the sandbox cluster. Agent surfaces test services to the platform via `POST /api/services`, captures their traffic from Kubeshark, and pushes it to the Cloud Run collector.

#### B1. Implement Agent Loop 1 (service discovery)

`agent/src/main/kotlin/com/platform/agent/AgentApplication.kt:227` is a stub today. Implement it:

- Add a Fabric8 `KubernetesClient` to the agent module (it can use the in-cluster ServiceAccount automatically).
- New file `agent/src/main/kotlin/com/platform/agent/K8sServiceDiscovery.kt`: list `Service` resources across configured namespaces (default: `production`, configurable via `DynamicConfig.namespaceFilters`), filter out headless / system services, return `(namespace, name)` pairs.
- New file `agent/src/main/kotlin/com/platform/agent/PlatformClient.kt` (or extend `ConfigClient`): `POST /api/services` with `{namespace, name, provider: "KUBERNETES"}`. Treat 409 / already-exists as success.
- Wire into `discoverServices()`: each tick, list current services, diff against an in-memory set of "already registered", POST new ones. The platform's `GET /api/agent/config` will then include them in `targetServices` on the next config poll, and the StateFlow propagates to `KubesharkClient`.
- Tests: unit test the diff logic against a fake K8s client + a fake platform; integration test against `KubernetesWorkloadTestBase` (already has 7 K8s Services in 3 namespaces — perfect fixture).

The agent doesn't need GCP Workload Identity for this — the in-cluster KSA is enough to talk to the K8s API. RBAC: a ClusterRole granting `list`/`watch` on `services` in the target namespaces, bound via RoleBinding.

#### B2. Kubeshark

Add a step to `sandbox-up.sh` that installs Kubeshark scoped to the `production` namespace:

```bash
kubeshark tap --set tap.namespaces='{production}' --set tap.proxy.front.port=8899 -n kubeshark --headless
```

Or pin a Helm chart version if `kubeshark` CLI isn't acceptable in the script. Skip if already installed.

#### B3. Agent overlay

Create `k8s/agent/overlays/gke/` mirroring the test-services overlay pattern:

- Override the image to `${REGISTRY}/validation-agent:latest` (or `:dev-<sha>` for `--build-local`).
- Patch `imagePullPolicy: Always`.
- Set env vars: `PLATFORM_URL` and `COLLECTOR_URL` to the Cloud Run URLs (Terraform outputs), `KUBESHARK_URL=http://kubeshark-front.kubeshark:80`.
- ServiceAccount `validation-agent` (no GCP IAM annotation — pure K8s).
- ClusterRole + RoleBinding granting `list`/`watch` on Services in `production` (and any other discovery namespaces).

The `platform-api-key` Secret is created out-of-band by `seed-org.sh` (Phase A); the overlay doesn't manage it.

#### B4. Wire into `sandbox-up.sh`

Order: Terraform → test services → `seed-org.sh` (Cloud Run platform must be up first, but that's already true post platform-up) → Kubeshark → agent. Same `--build-local` flow as test services so we can iterate on agent code.

#### Files

- `agent/src/main/kotlin/com/platform/agent/K8sServiceDiscovery.kt` — **new**.
- `agent/src/main/kotlin/com/platform/agent/PlatformClient.kt` — **new** (or merge into `ConfigClient.kt`).
- `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` — replace `discoverServices()` stub.
- `agent/build.gradle.kts` — add Fabric8 K8s client dependency (the platform module already uses it; bump `gradle/libs.versions.toml` if pinned).
- `agent/src/test/...` — discovery loop tests.
- `k8s/agent/overlays/gke/kustomization.yaml` — **new**.
- `k8s/agent/overlays/gke/rbac.yaml` — **new**, ServiceAccount + ClusterRole + RoleBinding for service-listing.
- `scripts/sandbox-up.sh` — extend with Kubeshark install + `seed-org.sh` call + agent deploy.

#### Verification

1. `kubectl logs deployment/validation-agent -n validation` → discovery loop logs `Registered service api-gateway` (×3) on first run, no errors.
2. `curl -H "Authorization: Bearer $TOKEN" $PLATFORM_URL/api/services` → returns the test services discovered by the agent (not pre-seeded).
3. `kubectl logs deployment/validation-agent -n validation | grep "Target services updated"` → fires after the next config poll.
4. `kubectl logs deployment/validation-agent -n validation | grep "Captured"` → entries flowing.
5. `curl -H "Authorization: Bearer $TOKEN" $COLLECTOR_URL/api/captured-inputs` → captured inputs from the test services.

#### Milestone

End-to-end traffic flow on GCP: test services → agent discovers and registers them → Kubeshark → agent (sandbox GKE) → Cloud Run collector. Services and captured inputs queryable via the public API.

---

### Phase C: Tighten the sandbox loop

**Goal:** Make the sandbox demo robust enough to leave running for a few hours of testing without hand-holding.

#### Items

- **Agent CI.** The `push_main.yml` pipeline builds and pushes platform + collector on merge to main. Confirm agent image is pushed to Artifact Registry on the same trigger; add it if not.
- **Sandbox idempotency.** Re-running `sandbox-up.sh` should be safe whether the cluster already exists or not. Same for Kubeshark and the agent overlay.
- **Cost guard.** `sandbox-down.sh` should leave Cloud Run + Cloud SQL untouched. Confirm Terraform targets in `sandbox-down.sh` only destroy the sandbox cluster, never platform infra.
- **README pass.** Document the full bring-up (`bootstrap.sh` → `platform-up.sh` → `bootstrap-db.sh` → `seed-org.sh` → `sandbox-up.sh`) in one place. Today the sequence is implicit across multiple scripts and `CLAUDE.md` notes.

#### Verification

- From a clean GCP project: bootstrap + platform-up + bootstrap-db + seed-org + sandbox-up runs end-to-end without manual edits.
- `sandbox-down.sh` deletes the sandbox cluster only; Cloud Run and Cloud SQL keep running.

#### Milestone

One-command bring-up of the full demo from scratch, repeatable.

---

## Phase summary

| Phase | Deliverable | Risk de-risked |
|-------|-------------|----------------|
| A | `seed-org.sh` creates org and writes agent JWT to a K8s Secret in the sandbox cluster | Org bootstrap, agent auth |
| B | Agent service discovery + Kubeshark + agent overlay on sandbox cluster | Cross-environment traffic flow (sandbox GKE → Cloud Run), agent surfacing services to the platform |
| C | Idempotent end-to-end bring-up, README | Repeatability, cost control |

---

## Out of scope (for this plan)

These were called out in the original plan or surfaced during the pivots, and are deliberately not on the path to the end-to-end demo:

- TLS / managed cert on the sandbox side (Cloud Run is already HTTPS by default).
- Replay engine, staging observation, comparison, verdicts (Phases 4–5 in `CLAUDE.md` delivery plan — separate workstream).
- Rich health checks (DB connectivity probes), Prometheus metrics, NetworkPolicy.
- Short-lived JWTs / client credentials flow, RBAC role enforcement, RLS, rate limiting per org.
- Multi-cluster / multi-region. Single sandbox cluster is enough to prove the pattern.
