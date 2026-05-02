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

**Goal:** Create the dummy org in the platform, generate an agent JWT, and store it in Secret Manager so the sandbox cluster can pick it up.

**Why first:** The agent can't do anything without a token. The token requires an organization to exist. One small script bootstraps both.

#### Steps

1. Generate a temp admin JWT locally with `./gradlew :platform:generateToken` (reads `JWT_PRIVATE_KEY` from env / Secret Manager).
2. `POST <CLOUD_RUN_PLATFORM_URL>/api/organizations` → captures org ID for `sandbox-org`.
3. (Optional, for a richer demo) `POST /api/services` for `api-gateway`, `order-service`, `notification-service`. The agent's discovery loop will eventually do this on its own; pre-seeding lets the demo show captured traffic immediately.
4. Generate an agent JWT: `./gradlew :platform:generateToken --args="--org $ORG_ID --cluster validation-sandbox"`.
5. Write the agent JWT to Secret Manager: `gcloud secrets versions add validation-jwt-token --data-file=-`.

#### Files

- `scripts/seed-org.sh` — **new**, idempotent (skips creating the org if it already exists).
- `infra/platform/secrets.tf` — confirm `validation-jwt-token` secret exists; create it if not.

#### Verification

- `curl -H "Authorization: Bearer $TOKEN" $PLATFORM_URL/api/organizations` → returns `sandbox-org`.
- `gcloud secrets versions access latest --secret=validation-jwt-token` → returns a valid JWT.

#### Milestone

Agent JWT lives in Secret Manager. Re-running `seed-org.sh` is a no-op.

---

### Phase B: Agent + Kubeshark on the sandbox cluster

**Goal:** Deploy Kubeshark and the agent into the sandbox cluster. Agent connects to Kubeshark over WebSocket, captures HTTP traffic from the test services, and pushes it to the Cloud Run collector.

#### Steps

1. **Kubeshark.** Add a step to `sandbox-up.sh` that runs `kubeshark tap --set tap.namespaces='{production}' -n kubeshark --headless`, or apply a pinned Helm chart. Keep it scoped to the `production` namespace to avoid capturing infrastructure traffic.
2. **Agent overlay.** Create `k8s/agent/overlays/gke/` mirroring the test-services overlay pattern:
   - Override the image to `${REGISTRY}/validation-agent:latest` (or `:dev-<sha>` for `--build-local`).
   - Patch `imagePullPolicy: Always`.
   - Set env vars: `PLATFORM_URL` and `COLLECTOR_URL` to the Cloud Run URLs (Terraform output), `KUBESHARK_URL=http://kubeshark-front.kubeshark:80`.
   - Workload Identity ServiceAccount bound to `validation-agent-sa` (read access to `validation-jwt-token`).
   - `validation-jwt-token` is loaded directly via the GCP Secret Manager CSI driver, or — simpler — fetched at pod start by an initContainer that writes `/tmp/jwt-token` and the agent reads from there. Decide based on what's already wired into the cluster (no ESO).
3. **Wire into `sandbox-up.sh`.** After test services are up: install Kubeshark, then `kubectl apply -k k8s/agent/overlays/gke/`. Same `--build-local` story as test services so we can iterate on agent code.
4. **Update sandbox-down.sh / lifecycle docs** to mention Kubeshark and the agent.

#### Files

- `k8s/agent/overlays/gke/kustomization.yaml` — **new**.
- `k8s/agent/overlays/gke/service-account.yaml` — **new**, KSA + Workload Identity annotation.
- `k8s/agent/overlays/gke/secret-volume-patch.yaml` (or CSI-driver SecretProviderClass) — **new**, mounts the JWT into the agent.
- `scripts/sandbox-up.sh` — extend with Kubeshark install + agent deploy.
- `infra/sandbox/iam.tf` — **new**, Workload Identity binding for `validation-agent-sa`.

#### Verification

1. `kubectl logs deployment/validation-agent -n validation` → no auth errors; sees `Target services updated`.
2. `kubectl logs deployment/validation-agent -n validation | grep "Captured"` → entries flowing.
3. `curl -H "Authorization: Bearer $TOKEN" $COLLECTOR_URL/api/captured-inputs` → captured inputs from the test services.
4. Traffic generator running: `kubectl logs deployment/traffic-generator -n production | tail -20`.

#### Milestone

End-to-end traffic flow on GCP: test services → Kubeshark → agent (sandbox GKE) → Cloud Run collector. Captured inputs queryable via the public API.

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
| A | `seed-org.sh` + agent JWT in Secret Manager | Org bootstrap, token sync |
| B | Agent + Kubeshark on sandbox cluster | Cross-environment traffic flow (sandbox GKE → Cloud Run), Workload Identity for the agent |
| C | Idempotent end-to-end bring-up, README | Repeatability, cost control |

---

## Out of scope (for this plan)

These were called out in the original plan or surfaced during the pivots, and are deliberately not on the path to the end-to-end demo:

- TLS / managed cert on the sandbox side (Cloud Run is already HTTPS by default).
- Replay engine, staging observation, comparison, verdicts (Phases 4–5 in `CLAUDE.md` delivery plan — separate workstream).
- Rich health checks (DB connectivity probes), Prometheus metrics, NetworkPolicy.
- Short-lived JWTs / client credentials flow, RBAC role enforcement, RLS, rate limiting per org.
- Multi-cluster / multi-region. Single sandbox cluster is enough to prove the pattern.
