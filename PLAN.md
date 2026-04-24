# Plan: End-to-End Deployment on GKE

## Context

The platform works locally (docker-compose, minikube) but has never been deployed to a real cloud environment. The goal is to reach a milestone where:
- Test services run in a **dummy GKE cluster** generating traffic
- The **agent** in that cluster captures traffic via Kubeshark and sends it to the platform
- The **platform** runs in a separate **platform GKE cluster** in a production-ready configuration (Cloud SQL, ESO, TLS)
- The full auth flow (JWT generation → Envoy validation → claim forwarding) works across clusters

### What's already done (old Phases 1-3)

- Agent config endpoint (`GET /api/agent/config`) + `PLATFORM_URL` env var
- JWT auth via Envoy reverse proxy (RS256, JWKS endpoint, claim forwarding)
- Platform K8s manifests (postgres, platform, collector, envoy) in `k8s/platform/`
- E2E tests for the full Envoy + platform + collector stack
- Validated on minikube: all pods healthy, health → 200, unauthenticated API → 401

---

## Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Cluster topology | Two GKE Standard clusters | Exercises real cross-cluster networking. Scale to 0 when idle. |
| GKE mode | Standard (not Autopilot) | Kubeshark requires privileged DaemonSet + eBPF. Autopilot blocks both. Known product constraint. |
| Database | Cloud SQL from day one | No migration step. Persists across cluster restarts. ~$10/mo. |
| Secrets | ESO + Google Secret Manager | Workload Identity already needed for Cloud SQL. GitOps-safe ExternalSecret CRDs. |
| Telemetry | Structured JSON logging | Cloud Logging parses JSON natively. Low effort, high value. |
| Container registry | Artifact Registry | GCR is deprecated. AR supports multi-region, vulnerability scanning. |
| Platform exposure | LoadBalancer Service | Agent in dummy cluster reaches platform via public endpoint. |
| Infrastructure provisioning | Terraform | Declarative state, dependency graph, targeted destroy for cost control. Shell scripts for K8s deployment. |

### Known Product Constraint: eBPF Compatibility

Kubeshark's eBPF DaemonSet requires privileged access, blocking: GKE Autopilot, EKS Fargate, Azure ACI. Our agent only works on clusters allowing privileged DaemonSets (GKE Standard, EKS on EC2, AKS node pools, self-managed K8s). Future options: sidecar capture, service mesh integration, application SDK.

---

## Phase 1: GCP Infrastructure

**Goal:** Provision all GCP resources both clusters depend on. Zero application code changes.

**Why first:** Cloud SQL takes ~10 min to provision. Artifact Registry must exist before image pushes. Secret Manager must exist before ESO can sync. This is the longest lead time.

### What to create

1. **Artifact Registry repo**
   - `us-central1-docker.pkg.dev/$PROJECT/validation/`
   - All images: `validation-platform`, `validation-collector`, `validation-agent`, test services

2. **Cloud SQL PostgreSQL 16**
   - Instance: `validation-platform`, tier `db-f1-micro`, region `us-central1`
   - Database: `platform`, user: `platform`
   - Instance connection name: `$PROJECT:us-central1:validation-platform`

3. **Two GKE Standard clusters**
   - `validation-platform` — platform stack (Envoy, platform, collector)
   - `validation-sandbox` — test services + Kubeshark + agent
   - Both: `--workload-pool=$PROJECT.svc.id.goog`, `--spot`, `--no-enable-autoupgrade`

4. **Google Secret Manager secrets**
   - `validation-db-password` — Cloud SQL password
   - `validation-jwt-private-key` — RSA private key PEM
   - `validation-jwt-token` — placeholder (populated after org seeding in Phase 3)

5. **IAM Service Accounts + Workload Identity**
   - `validation-platform-sa` (GCP) → KSA `platform-sa` in `validation` namespace on platform cluster
     - Roles: `roles/cloudsql.client`, `roles/secretmanager.secretAccessor`
   - `validation-eso-sa` (GCP) → KSA used by ESO controller
     - Role: `roles/secretmanager.secretAccessor`
   - `validation-agent-sa` (GCP) → KSA `agent-sa` in `validation` namespace on sandbox cluster
     - Role: `roles/secretmanager.secretAccessor` (reads JWT token)

6. **External Secrets Operator** (Helm install on both clusters)
   - `ClusterSecretStore` referencing GCP Secret Manager on each cluster

### Provisioning: Terraform

All GCP resources in this phase are managed by Terraform. State stored in a GCS bucket.

**Why Terraform over shell scripts for infra:**
- Dependency graph — Cloud SQL, IAM, Workload Identity, GKE have complex interdependencies that Terraform resolves automatically
- Targeted destroy — `terraform destroy -target=google_container_cluster.platform` tears down clusters (~$150/mo) while keeping Cloud SQL (~$10/mo) and secrets
- Idempotent by design — `terraform apply` is safe to re-run; no check-before-create guards needed
- Drift detection — `terraform plan` shows what changed vs. what's declared

**Cost control workflow:**
```bash
# Developing: everything up
terraform apply

# Done for the day: destroy clusters only (~$4/mo residual for Cloud SQL + secrets + state bucket)
terraform destroy -target=google_container_cluster.platform -target=google_container_cluster.sandbox

# Next session: clusters recreated, everything else untouched
terraform apply

# Done with project entirely: everything gone
terraform destroy
```

### Files to create

- `infra/main.tf` — **new** provider config, GCS backend for state
- `infra/clusters.tf` — **new** two GKE Standard clusters with Workload Identity
- `infra/database.tf` — **new** Cloud SQL instance, database, user
- `infra/secrets.tf` — **new** Secret Manager secrets (db-password, jwt-private-key, jwt-token)
- `infra/iam.tf` — **new** GCP service accounts, IAM bindings, Workload Identity bindings
- `infra/registry.tf` — **new** Artifact Registry repo
- `infra/variables.tf` — **new** project, region, zone, cluster config
- `infra/outputs.tf` — **new** Cloud SQL connection name, Artifact Registry URL, cluster endpoints
- `infra/setup-eso.sh` — **new** script for Helm install + ClusterSecretStore (ESO is K8s-side, not Terraform-managed)

**Note:** ESO installation (Helm chart + ClusterSecretStore CRD) stays as a shell script because it targets K8s clusters, not GCP APIs. It runs after `terraform apply` creates the clusters.

### Verification
- `gcloud sql instances describe validation-platform` → RUNNABLE
- `gcloud artifacts repositories describe validation --location=us-central1` → exists
- `gcloud secrets versions access latest --secret=validation-db-password` → returns password
- `kubectl get clustersecretstore gcp-secret-manager` → Valid on both clusters

### Milestone
GCP infrastructure provisioned. No applications deployed.

---

## Phase 2: Platform on GKE with Cloud SQL

**Goal:** Platform server, collector, and Envoy running in the platform cluster, backed by Cloud SQL, with Envoy exposed via LoadBalancer.

**Why second (de-risking):** Highest risk piece. Combines Cloud SQL Auth Proxy sidecar, Artifact Registry pulls, Flyway migrations against Cloud SQL, JWT auth through Envoy, and the LoadBalancer. If this works, everything else is configuration.

### 2a. Build and push images to Artifact Registry

Add platform + collector to the image build/push loop in `sandbox-up.sh`:
```
docker build -t $REGISTRY/validation-platform:latest -f deploy/Dockerfile.platform .
docker push $REGISTRY/validation-platform:latest
# Same for collector
```

### 2b. GKE Kustomize overlay for platform

Create `k8s/platform/overlays/gke/` following the pattern from `k8s/test-services/overlays/gke/`:

- **Remove** `postgres.yaml` from resources (replaced by Cloud SQL)
- **Override** images to Artifact Registry refs (with `GCP_PROJECT` placeholder)
- **Patch** `imagePullPolicy` from Never → Always
- **Patch** `DATABASE_URL` to `jdbc:postgresql://localhost:5432/platform` (Cloud SQL Auth Proxy sidecar is localhost)
- **Add** Cloud SQL Auth Proxy sidecar to platform + collector Deployments
- **Add** ServiceAccount with Workload Identity annotation
- **Add** ExternalSecret CRD that syncs `platform-api-key` from Google Secret Manager
- **Change** Envoy Service type to `LoadBalancer`

### 2c. Deploy and verify

```bash
kubectl apply -k k8s/platform/overlays/gke/   # after sed for GCP_PROJECT
kubectl wait --for=condition=available deployment/platform -n validation --timeout=180s
```

### Files to create

- `k8s/platform/overlays/gke/kustomization.yaml` — image overrides, patches, resources
- `k8s/platform/overlays/gke/cloudsql-sidecar-patch.yaml` — Auth Proxy sidecar for platform + collector
- `k8s/platform/overlays/gke/service-account.yaml` — KSA with Workload Identity
- `k8s/platform/overlays/gke/external-secret.yaml` — ExternalSecret for platform-api-key
- `k8s/platform/overlays/gke/envoy-lb-patch.yaml` — Change Envoy Service to LoadBalancer

### Verification
1. `curl http://<ENVOY_LB_IP>:8082/health` → OK
2. `curl http://<ENVOY_LB_IP>:8082/.well-known/jwks.json` → RSA public key
3. `curl http://<ENVOY_LB_IP>:8082/api/services` (no auth) → 401
4. Platform logs: Flyway migrations completed against Cloud SQL
5. `kubectl get externalsecret -n validation` → SecretSynced

### Milestone
Platform running on GKE + Cloud SQL. Envoy exposed via LoadBalancer. JWT auth working.

---

## Phase 3: Org Seeding + Agent Token Generation

**Goal:** Create the dummy org in the platform, generate a JWT for it, store it in Secret Manager so ESO can sync it to the sandbox cluster.

**Why third:** Platform is running. Now we need the org + token that the agent will use.

### 3a. Seed script

Create `scripts/seed-org.sh` that:
1. Generates a temp admin JWT (using `./gradlew :platform:generateToken`)
2. `POST /api/organizations` → creates "sandbox-org", captures org ID
3. `POST /api/services` for each test service (api-gateway, order-service, notification-service)
4. Generates an agent JWT with `--org $ORG_ID --cluster validation-sandbox`
5. Stores the agent JWT in Google Secret Manager (`validation-jwt-token`)

### 3b. ESO syncs the token to sandbox cluster

The ExternalSecret in the sandbox cluster picks up the new `validation-jwt-token` value on its next refresh interval.

### Files to create
- `scripts/seed-org.sh` — **new** org seeding script

### Verification
1. `curl http://<ENVOY_LB_IP>:8082/api/organizations -H "Authorization: Bearer $TOKEN"` → lists sandbox-org
2. `curl http://<ENVOY_LB_IP>:8082/api/services -H "Authorization: Bearer $TOKEN"` → lists 3 test services
3. `gcloud secrets versions access latest --secret=validation-jwt-token` → valid JWT
4. `kubectl get secret platform-api-key -n validation --context=sandbox-cluster -o jsonpath='{.data.jwt-token}' | base64 -d` → same JWT

### Milestone
Dummy org exists. Agent JWT stored in Secret Manager and synced to sandbox cluster.

---

## Phase 4: Test Services + Agent in Sandbox Cluster

**Goal:** Deploy test services, Kubeshark, and the agent to the sandbox cluster. Agent captures traffic and pushes it to the platform cluster. End-to-end flow proven.

### 4a. Update test-services GKE overlay

- Change image refs from `gcr.io/` to Artifact Registry (`us-central1-docker.pkg.dev/`)

### 4b. Create agent GKE overlay

Create `k8s/agent/overlays/gke/` that:
- Overrides image to Artifact Registry ref
- Patches `imagePullPolicy` from Never → Always
- Sets `PLATFORM_URL` to the platform cluster's Envoy LoadBalancer IP/DNS: `http://<ENVOY_LB_IP>:8082`
- Adds ServiceAccount with Workload Identity annotation
- Adds ExternalSecret for `platform-api-key` (syncs `jwt-token` from Secret Manager)

### 4c. Deploy Kubeshark
```bash
kubeshark tap --set tap.namespaces='{production}'
```

### 4d. Deploy test services + agent
```bash
kubectl apply -k k8s/test-services/overlays/gke/   # after sed
kubectl apply -k k8s/agent/overlays/gke/            # after sed
```

### Files to create/modify
- `k8s/agent/overlays/gke/kustomization.yaml` — **new**
- `k8s/agent/overlays/gke/external-secret.yaml` — **new**
- `k8s/agent/overlays/gke/service-account.yaml` — **new**
- `k8s/test-services/overlays/gke/kustomization.yaml` — update image refs to Artifact Registry

### Verification (the money test)
1. Agent logs: `kubectl logs deployment/validation-agent -n validation | grep "Target services updated"`
2. Agent logs: `grep "Captured .* entries"`
3. Traffic in collector: `curl http://<ENVOY_LB_IP>:8082/api/captured-inputs -H "Authorization: Bearer $TOKEN"` → returns captured entries
4. Traffic generator running: `kubectl logs deployment/traffic-generator -n production | tail -20`

### Milestone
**End-to-end flow proven.** Test services → Kubeshark → agent → (cross-cluster) → Envoy → collector. Traffic visible in the API.

---

## Phase 5: Structured JSON Logging

**Goal:** Replace plaintext logging with JSON so Cloud Logging can parse and search logs.

**Why last:** The system is running. This makes it observable.

### Changes
- Add `net.logstash.logback:logstash-logback-encoder` dependency to `gradle/libs.versions.toml`
- Replace console encoder with `LogstashEncoder` in all three logback.xml files
- Add `customFields` per service: `{"service":"platform"}`, `{"service":"collector"}`, `{"service":"agent"}`

### Files to modify
- `gradle/libs.versions.toml` — add logstash-logback-encoder
- `platform/build.gradle.kts`, `collector/build.gradle.kts`, `agent/build.gradle.kts` — add dependency
- `platform/src/main/resources/logback.xml` — switch to LogstashEncoder
- `collector/src/main/resources/logback.xml` — switch to LogstashEncoder
- `agent/src/main/resources/logback.xml` — switch to LogstashEncoder

### Verification
1. Rebuild + push images, rolling restart
2. `kubectl logs deployment/platform -n validation` → JSON output
3. Cloud Console → Logging → filter `resource.labels.namespace_name="validation"` → structured entries with searchable fields

### Milestone
JSON structured logs flowing to Cloud Logging. Searchable by service, level, message.

---

## Phase 6: Update sandbox-up.sh

**Goal:** Single script that brings up the entire deployment end-to-end, using Terraform for infra and kustomize for K8s.

### Flow
1. `terraform apply` in `infra/` (GCP infra — idempotent)
2. `infra/setup-eso.sh` (install ESO on both clusters — idempotent)
3. Build + push all images to Artifact Registry
4. Deploy platform stack to platform cluster (kustomize GKE overlay)
5. Wait for platform healthy
6. Seed org + services + generate agent JWT → store in Secret Manager
7. Deploy test services + agent to sandbox cluster
8. Wait for agent healthy
9. Print access instructions (Envoy LB IP, port-forward commands, verification curls)

### Lifecycle commands
```bash
./scripts/sandbox-up.sh                     # Full bring-up (terraform + deploy + seed)
terraform -chdir=infra destroy \            # Pause: destroy clusters, keep Cloud SQL + secrets
  -target=google_container_cluster.platform \
  -target=google_container_cluster.sandbox
terraform -chdir=infra apply                # Resume: recreate clusters
./scripts/sandbox-up.sh                     # Re-deploy apps (terraform apply is idempotent)
terraform -chdir=infra destroy              # Full teardown
```

### Files to modify
- `scripts/sandbox-up.sh` — rewrite to orchestrate terraform + deploy + seed
- `scripts/sandbox-down.sh` — remove (replaced by `terraform destroy -target`)
- `scripts/sandbox-destroy.sh` — remove (replaced by `terraform destroy`)

### Verification
- From scratch: `./scripts/sandbox-up.sh` completes without errors
- `curl http://<ENVOY_LB_IP>:8082/api/captured-inputs -H "Authorization: Bearer $TOKEN"` → traffic flowing
- `./scripts/sandbox-down.sh` → both clusters at 0 nodes
- `./scripts/sandbox-up.sh` again → resumes (idempotent)

### Milestone
One-command deployment of the full two-cluster topology.

---

## Phase Summary

| Phase | Deliverable | Risk De-risked |
|-------|------------|----------------|
| 1 | GCP infra provisioned | Cloud SQL, Workload Identity, ESO, Artifact Registry |
| 2 | Platform on GKE + Cloud SQL | Auth Proxy sidecar, Flyway on Cloud SQL, JWT through Envoy, LoadBalancer |
| 3 | Org seeded, agent JWT in Secret Manager | Token generation, ESO sync across clusters |
| 4 | End-to-end traffic flow | Cross-cluster networking, agent auth, Kubeshark on GKE |
| 5 | Structured JSON logging | Cloud Logging integration |
| 6 | One-command sandbox script | Repeatable deployment |

---

## Future (not in this plan)

- TLS on Envoy LoadBalancer (GCP managed cert or cert-manager)
- CI/CD pipeline (build + push + deploy on merge to main)
- Rich health endpoints (DB connectivity checks)
- Prometheus metrics
- Envoy access logs
- NetworkPolicy for namespace isolation
- Short-lived JWTs + client credentials flow
- RBAC (role claim enforcement)
- Row-level security (RLS) in Postgres
- Rate limiting per org
- mTLS between services