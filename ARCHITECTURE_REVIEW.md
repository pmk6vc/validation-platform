# Architecture Review — Validation Platform

**Last updated:** 2026-05-02
**Reviewer:** Claude (architecture-reviewer agent)
**Scope:** Full-system audit through Phase B completion (PR #107). Focus on go-live readiness, customer onboarding security, and reliability. Replaces the previous incremental-issue format with two catalogs: MVP path and tech debt.

---

## Catalog 1: Path to Customer Onboarding

The platform has completed its capture loop on real GCP (agent → Kubeshark → Cloud Run collector on live traffic). The next gate is: can we deploy the agent in a customer cluster without a security review blocking it? Below is the ordered work to get there.

Items are sequenced: read P0s top-to-bottom as the suggested execution order.

---

### P0 — Must Ship to Onboard Customer #1

These block any customer with a competent security team. A CISO who reads the agent manifest and your onboarding docs will stop the conversation on these.

---

**MVP-1: Agent-Side PII / Header Redaction Before Data Leaves the Cluster**

- **What:** Add a configurable redaction layer in `TrafficTransformer.transform()`. Default-deny on `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `Proxy-Authorization` (these headers ship raw today — see `TrafficTransformer.kt:66–68`). Add `redactionRules` to `DynamicConfig`: header denylist and optional JSON-path body field stripping. The platform's per-service settings page controls which rules are pushed down at config-poll time.
- **Why P0:** Headers containing bearer tokens and session cookies are currently captured verbatim and shipped to Cloud Run storage. A single customer's security review will catch this, and "your agent exfiltrates our Authorization headers" is a conversation-ender, not a negotiation. This is not a nice-to-have. The TODO comment in `TrafficTransformer.kt:43` already acknowledges this gap.
- **Acceptance:** Default config redacts all headers in the denylist before POST to collector. Test: verify no `Authorization` header appears in captured input payload when the request carried one. Configurable per-service allowlist can restore specific headers for services where the customer has decided to share them.
- **Effort:** M (1–2 weeks: `DynamicConfig` schema change + transformer logic + platform settings storage + config-poll propagation + tests)
- **Depends on:** None

---

**MVP-2: Helm Chart for Agent Deployment**

- **What:** Replace `k8s/agent/overlays/sandbox/` with a Helm chart in `charts/validation-agent/`. Required values: `platformUrl`, `collectorUrl`, `apiKey` (as a secret ref), `imageTag`. Optional values: `kubesharkUrl`, `discoveryNamespaces`, `samplingRate`. Bundle the RBAC (currently `rbac.yaml` in the sandbox overlay) and NetworkPolicy (MVP-3) inside the chart. Default to per-namespace `RoleBinding` scoped to `discoveryNamespaces`; add a `clusterWide: false` flag that upgrades to `ClusterRoleBinding` when the customer explicitly opts in.
- **Why P0:** The sandbox overlay uses sed-substitution of `__PLACEHOLDER__` strings via `scripts/sandbox-up.sh`. That is not a customer-facing install path. No serious engineering team will run a bash script that does string replacement on their YAML. The Helm chart is how every other production agent (Datadog, New Relic, Prometheus node-exporter) ships. Without it, onboarding is a hand-holding engagement, not a product.
- **Acceptance:** `helm install validation-agent charts/validation-agent --set platformUrl=https://... --set collectorUrl=https://... --set apiKey.secretName=...` deploys a working agent to a vanilla GKE cluster with no manual YAML editing. `helm upgrade` rolls a new version cleanly.
- **Effort:** M (1–2 weeks)
- **Depends on:** MVP-3

---

**MVP-3: NetworkPolicy — Egress Allowlist for the Agent Pod**

- **What:** Add a `NetworkPolicy` resource to the Helm chart (and sandbox overlay) that restricts the agent pod's egress to: (a) platform Cloud Run URL, (b) collector Cloud Run URL, (c) Kubeshark front service in-cluster. Deny all other egress. Add an `ingress: []` rule since the agent never accepts inbound connections.
- **Why P0:** Without a NetworkPolicy, the agent pod has unrestricted egress on the customer's cluster network. That means: if the agent is ever compromised (supply chain attack on our Jib build, malicious dependency, etc.), it has a foothold on the customer's internal network with no egress restrictions. This is the item a customer's CISO will ask about specifically — it's a standard control for any third-party agent that runs with cluster access.
- **Acceptance:** Agent pod can reach platform, collector, and Kubeshark front. `kubectl exec` into agent and `curl` a non-allowlisted in-cluster address returns connection refused or timeout. Bundled in the Helm chart as a default-enabled resource.
- **Effort:** S (≤1 week)
- **Depends on:** None (can add to sandbox overlay immediately; Helm chart bundles it)

---

**MVP-4: Pod Security Standards — Harden the Agent Manifest**

- **What:** Add `securityContext` to the agent Deployment in `k8s/agent/base/agent.yaml` and the sandbox overlay: `readOnlyRootFilesystem: true` (the agent only writes `/tmp/agent-alive`; mount `/tmp` as an `emptyDir`), `allowPrivilegeEscalation: false`, `capabilities: drop: ["ALL"]`, `seccompProfile: type: RuntimeDefault`, `runAsNonRoot: true` (already handled by the `USER agent` in Dockerfile.agent). Ensure the pod complies with Kubernetes `restricted` Pod Security Standard.
- **Why P0:** The Dockerfile.agent already runs as non-root (`USER agent`), which is good. But the manifest today sets no `securityContext` at all. A customer's admission controller enforcing `restricted` PSS will reject the pod on first deploy. This is a deployment blocker in any security-conscious environment, not a nice-to-have hardening. It also matters for Calico, Cilium, and OPA Gatekeeper policies commonly deployed in enterprise clusters.
- **Acceptance:** `kubectl --dry-run=server apply -k k8s/agent/base` succeeds in a cluster with `restricted` PSS enforced on the `validation` namespace. Pod starts and capture loop operates normally.
- **Effort:** S (≤1 week — manifest changes + emptyDir volume for /tmp)
- **Depends on:** None

---

**MVP-5: Short-Lived JWT Tokens + Documented Rotation Runbook**

- **What:** Reduce default token expiry in `JwtTokenGenerator.kt` from 365 days to 30 days. Add an explicit `--expiry-days` flag with a 30-day default (currently defaults to 365 via fallback). Update `seed-org.sh` to generate 30-day tokens. Write a rotation runbook in `docs/JWT_ROTATION.md`: (1) generate new token via `generateToken`, (2) `kubectl create secret ... --dry-run=client | kubectl apply` to update the K8s Secret, (3) `kubectl rollout restart deployment/validation-agent -n validation`. The long-term fix (short-lived tokens with a refresh endpoint) is MVP-13; the immediate fix is reducing the blast radius of a leaked token.
- **Why P0:** A 365-day token with no revocation path means a compromised agent JWT gives an attacker a year of write access to the collector for any captured data stamped with that org's ID, plus the ability to register phantom services. An enterprise customer's security team will ask "what's the token lifetime?" and "how do you revoke it?" — both answers are currently bad. 30-day tokens + a documented runbook is the minimum acceptable answer for customer #1. The fact that the private key is in Secret Manager is good; the problem is the token lifetime at the leaf.
- **Acceptance:** `JwtTokenGenerator` default expiry is 30 days. `seed-org.sh` generates 30-day tokens. `docs/JWT_ROTATION.md` exists with a clear, tested runbook. Existing 365-day token behavior still accessible via `--expiry-days 365` for local dev convenience.
- **Effort:** S (≤1 week)
- **Depends on:** None

---

**MVP-6: Captured Data Retention Policy + Auto-Purge**

- **What:** Add a `retentionDays` field to the Organization model (default 30). Add a Flyway migration to store it. Add a scheduled job (Cloud Scheduler → Cloud Run job, or a background coroutine in the platform service with a daily trigger) that deletes `captured_inputs` rows where `capturedAt < now() - retentionDays * interval '1 day'` for each org. Expose `retentionDays` in org creation and update APIs.
- **Why P0:** Without a retention policy, the platform permanently stores all production traffic bodies, including any PII that slips through the redaction layer (MVP-1). GDPR's right to erasure and data minimization principles require a defined retention period and a mechanism to enforce it. This is not a compliance nicety — it is a legal requirement in any European customer relationship and a blocking question in any enterprise security review. A customer whose traffic includes user PII (which is essentially all HTTP services) will ask "how long do you keep this?" and "how does it get deleted?" today the answer is "forever."
- **Acceptance:** Org creation accepts `retentionDays` (default 30, min 1, max 365). Purge job deletes expired rows and logs count. Integration test verifies rows older than `retentionDays` are deleted and newer rows are preserved.
- **Effort:** M (1–2 weeks: model change + migration + purge job + API changes + tests)
- **Depends on:** None

---

**MVP-7: gRPC and Istio Compatibility Tests (Capture Compatibility Matrix)**

- **What:** Run two targeted experiments in the sandbox cluster before onboarding customer #1: (a) Deploy a gRPC test service. Capture with Kubeshark. Observe what `request.postData.text` looks like — is it binary protobuf, base64, or something else? Determine whether the agent can pass gRPC traffic to the collector today, and what the `inputType` should be. (b) Install Istio sidecar injection on one test-service namespace. Verify Kubeshark can still capture traffic through the sidecar's mTLS. If it can't, document the workaround (PeerAuthentication permissive mode, or agent-side flag). Publish results in `docs/CAPTURE_COMPATIBILITY.md`.
- **Why P0:** These are not hypothetical. gRPC is table stakes in backend engineering — every customer running Go, Java, or .NET services likely uses it somewhere. Istio is deployed in a significant fraction of production K8s environments. If we find out about incompatibility from customer #1 instead of before them, we've burned trust and potentially the relationship. The compatibility tests are cheap (a few days in the sandbox). The discovery conversation with a customer mid-onboarding is expensive. The PLAN.md calls these out explicitly; this item makes them a tracked deliverable, not a "run in parallel" suggestion.
- **Acceptance:** `docs/CAPTURE_COMPATIBILITY.md` exists with tested results for: HTTP/1.1 plaintext (confirmed working), gRPC over h2c (result: X), Istio mTLS (result: X). "Result: X" means a concrete finding, not "we'll look into it." The document states what we capture today and what we don't, so the sales team has a factual answer.
- **Effort:** S (≤1 week — sandbox experiments, not production code changes)
- **Depends on:** None (sandbox already has test services)

---

### P1 — Must Ship Before Customer #5

These items become visible at scale or under a more rigorous security review than customer #1 typically applies.

---

**MVP-8: Postgres Row-Level Security (RLS) on `captured_inputs` and `services`**

- **What:** Enable RLS on `captured_inputs` and `services` tables. Add a Flyway migration: `ALTER TABLE captured_inputs ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_isolation ON captured_inputs USING (organization_id = current_setting('app.current_organization_id')::uuid);`. The application sets `SET LOCAL app.current_organization_id = '...'` at transaction start. The platform SA bypasses RLS via `SECURITY DEFINER` or by being a superuser on Cloud SQL; this is already how IAM auth works — assess whether a `BYPASSRLS` grant is needed or if the SA already has it.
- **Why P1:** JWT scoping is layer 1; it works. But it's the only layer. A route bug, a SQL injection via a future ORM query, or a developer's ad-hoc query during an incident can expose another tenant's data. RLS is the database-enforced backstop that makes cross-tenant exposure structurally impossible from within the query layer. This is cheap to add now and nearly impossible to retrofit correctly on a production database with active tenants. The PLAN.md explicitly calls it out; don't defer past customer #3.
- **Acceptance:** RLS policies exist in a V0008 migration. Integration test verifies that executing a raw query with `organization_id` set to org A cannot read org B's rows. Application queries for org A are unaffected in performance (index on `organization_id` already exists).
- **Effort:** M (1–2 weeks: migration + app-layer session setup + test)
- **Depends on:** None

---

**MVP-9: GitHub Action — `validation-platform/run-validation@v1`**

- **What:** Create `.github/actions/run-validation/action.yml` as a composite action (or a separate public repo `validation-platform/github-action`). Inputs: `platform-url`, `api-token`, `service-name`, `candidate-image`. The action calls `POST /api/validations` (once that endpoint exists), polls for verdict, and posts a structured PR comment with PASS/FAIL/INCONCLUSIVE + evidence. Before the orchestration API exists, the action can call `GET /api/captured-inputs?serviceId=X` to confirm capture is live and post a "capture active, N requests captured" status comment.
- **Why P1:** The user identified this as the most common interaction pattern. Every engineering team already has GitHub Actions. "Install our action, get validation comments on your PRs" is the activation path with lowest friction. It's the difference between "you have to call our API to get results" and "validation runs automatically when you open a PR." Build the capture-status version now (before Track 1 MVP completes) to establish the integration point and train users on the workflow, then upgrade it to full verdict when the orchestration API lands.
- **Acceptance:** Action installs via `uses: validation-platform/run-validation@v1`. On a PR, it posts a comment showing either "capture active for service X (N requests in last 7d)" or a full validation verdict. Action fails the CI step if the verdict is FAIL.
- **Effort:** M (1–2 weeks for the action + capture-status version; L for full verdict integration)
- **Depends on:** MVP-5 (token rotation) for the `api-token` input

---

**MVP-10: Image Signing + SBOM**

- **What:** Add Cosign signing to the CI workflow (`push_main.yml`) after each image push. Sign with keyless signing (OIDC identity, no static key) using the GitHub Actions OIDC token. Publish an SPDX SBOM via `syft` or `trivy` alongside each image push. The SBOM should be attached to the image in the registry as an OCI artifact. Add a verification step to `sandbox-up.sh` that confirms the agent image signature before deployment.
- **Why P1:** Customer security teams increasingly require image signing and SBOM as table stakes for any agent that runs in their cluster — not just for enterprise. The SBOM enables customers to scan our dependencies for known CVEs before allowing deployment. Keyless signing is cheap to set up with the existing WIF infrastructure. Not having this means a customer's security tooling (Sigstore policy controller, Kyverno, OPA) will flag unsigned images as a policy violation.
- **Acceptance:** Every image pushed by CI is signed. `cosign verify --certificate-identity-regexp=...` succeeds. SBOM is available in the registry. A new Helm chart value `verifyImageSignature: true` runs `cosign verify` before applying the deployment.
- **Effort:** S (≤1 week — CI pipeline additions)
- **Depends on:** None

---

**MVP-11: Agent HA — Multi-Replica with Leader Election for Loop 1 and Capture**

- **What:** Today there is one agent replica. When it restarts (Kubernetes eviction, OOM, or spot node preemption — the sandbox runs on spot instances), the capture loop goes dark and Loop 1 stops registering services. Add lease-based leader election (using the K8s coordination API `leases.coordination.k8s.io`) for the service discovery loop and Kubeshark drain loop. Non-leaders skip those loops but maintain the liveness probe. The K8s client is already Fabric8 (`K8sServiceDiscovery.kt`), which supports the Lease API.
- **Why P1:** Spot instances. The sandbox already uses `spot: true` nodes. Any customer running GKE Autopilot, Spot VMs, or AWS Spot will see routine evictions. A spot eviction kills the single agent replica and traffic goes uncaptured until the pod reschedules (typically 60–120s, but possibly longer). For the capture-loop use case this is acceptable data loss; for the service discovery loop it means new services go unregistered. Multi-replica with leader election is the standard K8s pattern for this. It's also what Datadog and New Relic agents do.
- **Acceptance:** Two replicas run. Kill the leader pod — the standby becomes leader within two lease renewal intervals. Capture and discovery continue without interruption (within the Kubeshark 5s dedup window). Unit test for leader election logic.
- **Effort:** L (2–4 weeks)
- **Depends on:** MVP-2 (Helm chart — replica count and lease config belong in values)

---

**MVP-12: Per-Org Global Capture Rate Limit + Cost Dashboard**

- **What:** Add a `maxCaptureRps` field to Organization (default: 1000 req/s). Enforce it in the collector's batch ingest endpoint — compute the ingest rate per org over a sliding window and return `429` when exceeded. Add a `GET /api/orgs/{id}/metrics` endpoint returning: captured requests last 24h, last 7d, storage used (byte count of `captured_inputs` rows), and ingest rate over last 60s.
- **Why P1:** The current `samplingRate` is per-service and has no global budget. A customer with 100 services all at `samplingRate=1.0` and 100 req/s each sends 10,000 req/s to the collector. At 1KB average body size, that's 10 MB/s, 864 GB/day, filling Cloud SQL in hours. The platform has no protection against this today. Before customer #5 (which means multiple concurrent organizations), this is a billing and availability risk — one customer's traffic spike could cause Cloud SQL disk exhaustion for all tenants.
- **Acceptance:** Org with `maxCaptureRps=100` receives `429` from collector when ingest rate exceeds 100 req/s. Dashboard endpoint returns accurate metrics. Load test confirms the rate limiter kicks in correctly under sustained load.
- **Effort:** M (1–2 weeks)
- **Depends on:** None

---

### P2 — Important for Scale-Out

---

**MVP-13: Short-Lived Tokens with Refresh Endpoint**

- **What:** Add `POST /api/auth/token/refresh` that accepts a long-lived "refresh token" (stored in Secret Manager, not a JWT) and returns a short-lived JWT (15-minute TTL, `aud: "validation-agent"`, `iss: "validation-platform"` — fixing SECURITY-4 below). The agent polls this endpoint before each config-poll cycle. The Helm chart ships the refresh token as a separate `Secret`, separate from the JWT.
- **Why P2:** 30-day JWTs (MVP-5) reduce the blast radius enough for customer #1. Short-lived JWTs with refresh are the right long-term answer, but the refresh endpoint requires careful design (revocation, replay prevention, replay window) and is not blocking for the first customer.
- **Acceptance:** Agent uses short-lived JWT in all API calls. JWT rotation is transparent — no `kubectl rollout restart` required. A compromised JWT expires within 15 minutes without any operator action.
- **Effort:** L (2–4 weeks)
- **Depends on:** MVP-5

---

**MVP-14: OLAP Export for `captured_inputs`**

- **What:** Move high-volume captured-input storage out of Cloud SQL. Export to BigQuery (or a customer-owned GCS bucket in Parquet). Cloud SQL keeps: org metadata, service registry, replay run metadata, verdicts. BigQuery/GCS keeps: `captured_inputs`, `replay_responses` (planned), `observation_data` (planned). The replay engine reads from BigQuery, not Postgres. Use Cloud SQL's logical replication or a daily export job; the platform's `POST /api/captured-inputs` continues to write to Postgres as the staging buffer, and a downstream exporter copies rows to BigQuery and deletes them after `retentionDays`.
- **Why P2:** At 1,000 services × 100 req/s × 30-day retention, the `captured_inputs` table grows to approximately 260 billion rows. Cloud SQL `db-f1-micro` has 10 GB storage — it will fill in hours at this rate. The OLAP export is the architectural answer to the storage problem. It is not needed for customer #1 (where traffic volume is bounded by the design partner's services), but it is required for any mid-sized customer and blocking for enterprise.
- **Acceptance:** A batch job exports `captured_inputs` rows older than 24h to BigQuery and deletes them from Postgres. Cloud SQL storage stays bounded. Replay engine can query BigQuery for captured inputs by service ID and time window.
- **Effort:** XL (>4 weeks)
- **Depends on:** Replay engine (Track 1 MVP), MVP-6

---

**MVP-15: Platform + Agent Telemetry (Prometheus + OpenTelemetry)**

- **What:** Expose a `/metrics` endpoint from both platform and collector Cloud Run services (Prometheus format). Key metrics: capture_rate_rps per org, capture_channel_depth (agent-side), registration_outcome_total by outcome type, replay_run_duration_seconds, verdict_distribution, collector_batch_size_histogram. Add OpenTelemetry tracing for the capture pipeline and replay engine. Ship a Grafana dashboard JSON.
- **Why P2:** Without this, debugging a customer's "the agent isn't capturing anything" complaint requires shelling into their cluster and reading logs. With metrics, it's a dashboard lookup. This is an operational investment that pays off at customer #3 when support volume starts growing.
- **Acceptance:** `/metrics` endpoint exists on platform and collector. Agent exposes metrics to stdout in structured logs (no separate port needed). Grafana dashboard renders capture rate and channel depth in real time. Runbook references the dashboard for common failure modes.
- **Effort:** L (2–4 weeks)
- **Depends on:** None

---

## Catalog 2: Tech Debt / Improvements

---

### P0 — Production Reliability Blocker

These will bite within the first month of a real customer deployment.

---

**ARCH-6: Cursor Pagination on `captured_inputs` Uses Agent-Supplied `capturedAt` — Clock Skew Causes Silent Replay Gaps**

- **Status:** Open
- **Description:** The pagination cursor for `GET /api/captured-inputs` sorts on `captured_at` (agent wall-clock time from Kubeshark's `entry.timestamp`). See `CapturedInputRepository.kt:find()`. This column is neither monotonic at insert time nor controlled by the database. Consequences: (1) NTP jitter between agent and collector causes overlapping `captured_at` ranges across batches — cursor-paginated reads skip rows in the gap. (2) Kubeshark's 5s out-of-order delivery window means a batch can contain entries with timestamps before the previous batch's cursor. (3) Agent retries insert the same `captured_at` values, causing potential duplicates in replay.
- **Risk if unaddressed:** The replay engine fetches captured inputs via `GET /api/captured-inputs` with pagination. Silent gaps mean requests are never replayed. Silent duplicates mean a request is replayed twice. Both failure modes produce a verdict that is wrong without any error signal. This will be discovered in the first replay run and will look like a bug in the comparison engine when it's actually a data integrity issue.
- **Fix:** V0008 migration: `ALTER TABLE captured_inputs ADD COLUMN collected_at TIMESTAMPTZ NOT NULL DEFAULT now(); CREATE INDEX idx_captured_inputs_collected_at ON captured_inputs(collected_at);`. Sort and cursor on `(collected_at, id)` in `CapturedInputRepository.find()`. Retain `captured_at` as a queryable analytics field.
- **Effort:** S (migration + two-line repository change + test)

---

**OPS-3: Cloud SQL `db-f1-micro` Is a Single Point of Failure for Multi-Tenant Production**

- **Status:** Open (new)
- **Description:** `infra/platform/cloudsql.tf` provisions `db-f1-micro` (1 shared vCPU, 0.6 GB RAM, 10 GB storage) with `max_connections = 100`. Cloud Run has `min_instance_count = 0` (cold-starts) and `max_instance_count = 3` for both platform and collector. HikariCP defaults to a pool size of 10 per app instance. At max scale (3 platform + 3 collector = 6 instances × 10 pool connections), the platform consumes 60 of the 100 available Cloud SQL connections, leaving 40 for admin access, migrations, and any future service. A traffic spike that cold-starts all 6 instances simultaneously will cause connection pool exhaustion — HikariCP `connectionTimeout` defaults to 30s, then starts throwing and the platform returns 503.
- **Risk if unaddressed:** Correlated cold-starts under load will cause connection exhaustion and a cascade of 503s. The platform has no PgBouncer or connection proxy layer between Cloud Run and Cloud SQL. At customer scale this will happen under load testing before production use.
- **Fix (near-term):** Set `DATABASE_POOL_SIZE=5` in Cloud Run env vars to halve per-instance pool usage. Add `maximumPoolSize: 5, minimumIdle: 1` to HikariCP config (both already configurable via `DatabaseFactory`). Set Cloud Run `max_instance_count = 2` while on `db-f1-micro`. (Long-term): PgBouncer sidecar or upgrade to `db-g1-small`; Cloud SQL `max_connections = 100` is the instance cap and cannot be raised on shared-core tiers.
- **Effort:** S (env var changes in `cloudrun.tf`)

---

**SECURITY-5: `POST /api/organizations` Creates Orgs for Any Valid JWT — No Admin Gate**

- **Status:** Open (was SECURITY-2; re-scoped with new context)
- **Description:** `Routes.kt:69` has a TODO comment acknowledging that org creation is open to any authenticated caller. `seed-org.sh` exploits this by minting a throwaway JWT with a random `organizationId` UUID to bootstrap the first org. This means any agent JWT — including one minted for an existing customer — can create new orgs. In a multi-tenant production environment, this allows an agent with a valid JWT to inflate org count, exhaust org-namespaced resources, or probe for org-level data by creating orgs and testing config responses.
- **Risk if unaddressed:** Supply chain or credential compromise of any customer's agent JWT allows arbitrary org creation. The existing behavior was acceptable for solo-developer use; it is a privilege escalation risk in multi-tenant production.
- **Fix:** Add a `role` claim check: `if (identity.role != "admin") return@post call.respond(HttpStatusCode.Forbidden, ...)`. Admin tokens are generated separately from agent tokens — `generateToken` gets an `--role admin` flag. The seeding script uses an admin token. Agent tokens never have `role: admin`.
- **Effort:** S (one-line route guard + `generateToken` flag + update `seed-org.sh`)

---

### P1 — Material Risk If Left Unaddressed

---

**SECURITY-4: JWT Has No `iss` or `aud` Claims**

- **Status:** Open (carried forward from previous review)
- **Description:** `JwtAuth.kt:42` builds the verifier with `JWT.require(algorithm).build()` — no `.withIssuer()` or `.withAudience()` check. `JwtTokenGenerator.kt` does not set `iss` or `aud` claims. Any RS256 token signed with the platform's private key is accepted by both platform and collector with no service binding. When short-lived tokens and a refresh endpoint (MVP-13) land, the audience binding becomes critical — the refresh token and the agent JWT must not be interchangeable.
- **Risk if unaddressed:** As more services share the signing key, a token issued for one service (e.g., a future admin CLI) is accepted by the collector's ingest endpoint. Audience checking is the standard defense against this.
- **Fix:** Add `withIssuer("validation-platform")` and `withAudience(expectedAudience)` parameters to `installJwtAuth()`. Update `JwtTokenGenerator` to set `iss = "validation-platform"` and `aud = "validation-agent"` (or `aud = listOf("validation-platform", "validation-collector")`). Breaking change — requires minting new tokens after deployment.
- **Effort:** S

---

**SECURITY-6: Agent Dockerfile Uses Alpine (musl) — Potential Native Library Incompatibility**

- **Status:** Open (new)
- **Description:** `deploy/Dockerfile.agent` uses `eclipse-temurin:21-jre-alpine` (musl libc). `deploy/Dockerfile.platform` explicitly uses `eclipse-temurin:21-jre` (glibc) with the comment "cloud-sql-jdbc-socket-factory pulls in netty-tcnative, whose native libs are built against glibc and SIGSEGV on musl at startup." The agent does not use cloud-sql-jdbc-socket-factory, but it does use Fabric8 Kubernetes client, which pulls in native TLS dependencies (OkHttp's Okio, Netty in some configurations). If a dependency upgrade pulls in a glibc-linked native library, the agent silently fails on alpine with a `SIGSEGV` or `UnsatisfiedLinkError` that is hard to debug in a customer cluster.
- **Risk if unaddressed:** A dependency upgrade (Fabric8, Ktor, or their transitive deps) could add a glibc-native lib, breaking the agent in production on alpine. The failure mode is a crash at startup, not a compilation error, and is not caught by CI which runs tests on the JVM directly (not inside the container image).
- **Fix:** Switch `Dockerfile.agent` to `eclipse-temurin:21-jre` (glibc). Image size increases by ~30 MB but eliminates the musl risk class entirely. This is the right call given the platform already made this decision for the same reason.
- **Effort:** S (one-line Dockerfile change)

---

**QUALITY-1: `DynamicConfig` Not Validated After Deserialization**

- **Status:** Open (carried forward)
- **Description:** `ConfigClient.kt` deserializes `DynamicConfig` from the platform's JSON response with no bounds checking. A platform bug or misconfiguration could send `captureInterval = 0ms` (tight-spin CPU loop in `trafficCaptureLoop`), `samplingRate = -0.5` (all entries pass the `random.nextDouble() < samplingRate` check since all doubles are less than -0.5? No — actually `nextDouble()` returns [0,1), so a negative samplingRate drops everything silently), or `batchSize = 0` (divide-by-zero or empty-batch tight loop). The agent has no protection against these.
- **Risk if unaddressed:** Platform misconfiguration causes agent CPU spike (captureInterval=0), silent capture dropout (samplingRate<0), or tight loop (batchSize=0). All three are silent from the customer's perspective.
- **Fix:** Add `fun DynamicConfig.validate(): DynamicConfig?` in `AgentConfig.kt`: clamp `samplingRate` to [0.0, 1.0], clamp `batchSize` to [1, 10000], clamp `captureInterval` to [100ms, 60s]. Log a warning for each clamped value. Return `null` (and keep previous config) only for structurally invalid configs. `ConfigClient.fetchConfig()` calls `validate()` before returning.
- **Effort:** S

---

**OPS-2: JWT Tokens Have 365-Day Default Expiry**

- **Status:** Partially addressed by MVP-5 (reduce to 30 days); the deeper fix is MVP-13.
- **Description:** `JwtTokenGenerator.kt` defaults to 365-day expiry. See prior entry in this document. MVP-5 reduces the default to 30 days. The structural gap (no revocation, no refresh) remains until MVP-13.
- **Risk if unaddressed:** A leaked token (agent pod log exfiltration, git history leak of `.platform/sandbox-org-id` combined with the private key, etc.) is valid for up to a year.
- **Effort:** S (MVP-5 closes the immediate gap)

---

**OPS-4: No Circuit Breaker on `GET /api/agent/config` — Platform Outage Causes Agent CPU Spin**

- **Status:** Open (new)
- **Description:** `configPollLoop` in `AgentApplication.kt:171–185` catches all non-cancellation exceptions and immediately retries after `configPollInterval`. If `configPollInterval` was set to 0 (see QUALITY-1) or if the platform is returning 200 with a 0ms interval in a misconfigured state, the loop tight-spins. Even with a valid interval, if the platform Cloud Run service scales to zero (`min_instance_count = 0` in `cloudrun.tf`), every config poll triggers a cold start. Under poor network conditions, `ConfigClient.fetchConfig()` may throw, log, and immediately retry — no exponential backoff in the config poll loop (unlike `CollectorClient` which has proper backoff). There is no circuit breaker.
- **Risk if unaddressed:** A sustained platform outage (or Cloud Run cold-start latency spike) causes the agent to log-spam and potentially CPU-spin on config polling, which competes with the capture loop's CPU budget.
- **Fix:** Add exponential backoff to the config poll loop failure path (mirror `CollectorClient`'s pattern). Add `min_instance_count = 1` to the platform Cloud Run service in `cloudrun.tf` to eliminate cold starts on the control path (collector can stay at 0 since it receives pushes, not pulls).
- **Effort:** S

---

**ARCH-2: Repositories Are `object` Singletons**

- **Status:** Open (carried forward)
- **Description:** `OrganizationRepository`, `ServiceRepository`, `CapturedInputRepository` are all Kotlin `object` singletons. Every route test requires a live database via TestContainers. The pattern cannot be unit-tested in isolation.
- **Risk if unaddressed:** Test suite speed grows proportionally with route complexity. A module that today has 5 routes and takes 30s to test will take 5 minutes with 50 routes. The pattern will spread to the replay engine module.
- **Fix:** Convert to classes injected via Ktor's `Application.attributes` or a DI framework. This is a refactor, not an emergency.
- **Effort:** M

---

### P2 — Quality / Maintainability

---

**QUALITY-6: `ignoreUnknownKeys` on Server-Side JSON Deserialization**

- **Status:** Open (carried forward)
- **Description:** Both `platform/Application.kt` and `CollectorApplication.kt` configure kotlinx.serialization with `ignoreUnknownKeys = true` on the server side. A client typo like `{"organizationid": "..."}` (lowercase 'i') silently produces a missing-field error rather than a "field not recognized" error, making the bug harder to diagnose.
- **Risk if unaddressed:** Developer experience issue. Integration debugging becomes harder as the API surface grows.
- **Fix:** Remove `ignoreUnknownKeys = true` from server-side Json configuration. Keep it on the agent (client side) where it enables additive API evolution.
- **Effort:** S

---

**QUALITY-9: `automountServiceAccountToken: false` Not Set on Agent Pod**

- **Status:** Open (new)
- **Description:** The agent pod mounts the default K8s service account token (a JWT granting access to the K8s API for the `validation-agent` ServiceAccount). The `automountServiceAccountToken` field is not set in `agent.yaml`, so it defaults to `true`. The agent uses Fabric8's `KubernetesClientBuilder().build()` which reads the token from the well-known mounted path (`/var/run/secrets/kubernetes.io/serviceaccount/token`). This is correct and needed for Loop 1. However, the token is mounted as a file inside the container with read access to any process in the pod. If the agent is compromised, the mounted token gives the attacker K8s API access with the `validation-agent` ServiceAccount's permissions.
- **Risk if unaddressed:** Low today because the `validation-agent` ClusterRole only has `list,watch` on `services`. But if the RBAC is ever broadened (e.g., to watch pods, configmaps, or events for future features), the blast radius of an agent compromise grows proportionally. The attack surface should be the minimum necessary.
- **Fix:** This one is a documentation clarification, not a code fix: add a comment in `agent.yaml` and the Helm chart explicitly noting that `automountServiceAccountToken` must remain `true` because Fabric8 needs it, and that the ClusterRole must remain read-only on `services` only. Add a CI check (OPA or kube-score) that validates the ClusterRole never gains write permissions. Do NOT set `automountServiceAccountToken: false` — that would break K8sServiceDiscovery.
- **Effort:** S

---

**QUALITY-7: `OrderService` No Connection Pool**

- **Status:** Open (carried forward, test-service only)
- **Description:** `test-services/order-service` uses `DriverManager.getConnection(...)` per request. Under traffic-generator load, this creates and tears down a new Postgres connection per HTTP request, which is ~5–10ms of latency overhead per request and saturates Cloud SQL connection slots quickly.
- **Risk if unaddressed:** Test services don't reflect realistic behavior. Replay comparison results from the sandbox are artificially penalized by connection-setup latency, making baseline latency appear worse than it would be in a real service.
- **Fix:** Replace with `HikariDataSource` (10-line change).
- **Effort:** S

---

**ARCH-3: No Request Timeout on Outbound Agent HTTP Calls**

- **Status:** Open (new)
- **Description:** `buildAgentPlatformHttpClient()` and `buildAgentCollectorHttpClient()` in `AgentApplication.kt` configure `ContentNegotiation` and `ContentEncoding` but set no `HttpTimeout` plugin. If the platform or collector hangs (Cloud Run revision stuck, TCP connection accepted but not processed), the agent's coroutines suspend indefinitely on `httpClient.post(...)` and `httpClient.get(...)`. Structured cancellation from the outer `coroutineScope` won't fire because the HTTP request is not cancelled — the CIO engine holds an open TCP connection. The capture loop stalls.
- **Risk if unaddressed:** A hung Cloud Run revision (e.g., during a slow deployment) could deadlock the agent's capture loop indefinitely. The liveness probe writes `HEARTBEAT_FILE` before the collector send (`captureOneBatch:383`), so the heartbeat would fire on successful drain but the pod would not be restarted — it would silently stop forwarding traffic.
- **Fix:** Install `HttpTimeout` plugin in both `configurePlatform()` and `configureCollector()` with `requestTimeoutMillis = 30_000`. For the collector client, this should be higher than the retry backoff max (30s) to avoid a timeout that arrives before the retry can fire.
- **Effort:** S

---

**QUALITY-10: `seed-org.sh` Caches Org ID in `.platform/sandbox-org-id` — Git Leak Risk**

- **Status:** Open (new)
- **Description:** `seed-org.sh:57` writes the sandbox org UUID to `.platform/sandbox-org-id`. This path is presumably in `.gitignore`, but the file is created in the repo root's `.platform/` directory. If a developer accidentally commits it (the directory doesn't exist until the script runs, so there's no existing `.gitignore` entry), the sandbox org ID leaks into git history. The org ID by itself is not a secret, but combined with a leaked JWT private key it confirms which org ID to target.
- **Risk if unaddressed:** Low probability, low severity. But the file should be in `.gitignore` explicitly.
- **Fix:** Add `.platform/` to the root `.gitignore`. Verify it's already there or add it.
- **Effort:** S (one-line `.gitignore` change)

---

### P3 — Cosmetic / Future-Proofing

---

**QUALITY-2: Doc Drift from Recent PRs**

- **Status:** Partially addressed by this document; CLAUDE.md still needs a sync pass.
- **Description:** CLAUDE.md still references `KubernetesAdapter`/`ManualSeedAdapter` (deleted in PR #102), the `discoverServices()` stub (replaced in PR #104), and `k8s/agent/agent.yaml` (moved to `k8s/agent/base/agent.yaml` in PR #103). See PLAN.md "Doc drift to clean up" section for the full list.
- **Risk if unaddressed:** New contributors follow stale paths and waste time.
- **Effort:** S

---

**QUALITY-3: `test-services/overlays/gke/` Should Be Renamed `sandbox/`**

- **Status:** Open (new — from PLAN.md doc drift section)
- **Description:** The test-services overlay is named `gke` but it is specifically the sandbox GKE deployment, not a generic GKE overlay. The agent overlay uses `sandbox/` for the same environment. Naming inconsistency causes confusion.
- **Effort:** S (rename only)

---

**ARCH-4: HikariCP Pool Size Is Mis-Calibrated for Cloud Run**

- **Status:** Open (see OPS-3 for the production blocker; this is the code-level tracking item)
- **Description:** `DatabaseFactory.kt:72` reads `DATABASE_POOL_SIZE` from the environment, defaulting to 10. At 6 Cloud Run instances × 10 pool connections = 60 of the 100 available Cloud SQL connections. No `DATABASE_POOL_SIZE` env var is set in `cloudrun.tf`, so the default of 10 applies. The connection pool sizing for Cloud Run requires `max_connections / max_instances` per service, accounting for migrations, admin access, and headroom.
- **Risk if unaddressed:** Connection exhaustion under load (see OPS-3).
- **Fix:** Set `DATABASE_POOL_SIZE=5` in `cloudrun.tf` for both platform and collector until Cloud SQL is upgraded.
- **Effort:** S

---

## Items Removed from Previous Review

The following items from the previous ARCHITECTURE_REVIEW.md are **resolved** and removed from tracking:

| Previous ID | Resolution |
|-------------|------------|
| ARCH-7 | Resolved: PR #100 split Flyway migration ownership between platform (MIGRATE) and collector (VALIDATE). |
| QUALITY-8 | Resolved: PR #95 distinguishes idle Kubeshark (heartbeat) from disconnected Kubeshark (no heartbeat), fixing the liveness probe false-positive. |
| OPS-1 | Resolved: PR #93 addressed the operational gap. |
| ARCH-5 | Resolved: PR #93. |
| SECURITY-3 | Resolved: PR #93. |
| ARCH-1 | Resolved: PR #102 removed the dead `platform/adapters` package entirely. No adapter code remains to misplace. |

The following item is **superseded**:

| Previous ID | Supersession |
|-------------|-------------|
| QUALITY-4 (HikariCP) | Positive pattern #21 in the previous review confirmed HikariCP is now in use. The concern was that `DatabaseFactory` used Exposed's internal connection management — that gap is closed. The remaining concern is pool sizing (see ARCH-4). |

---

## Positive Patterns Worth Preserving

Items marked in the previous review as positive are still valid. The following patterns from Phase B are specifically worth calling out:

1. **`CollectorClient` retry model with backpressure propagation** is correctly designed: the retry suspends the entire capture loop, which stops draining the Kubeshark channel, which applies TCP backpressure. No in-memory retry queue, no data loss path other than Kubeshark's own buffer.
2. **`KFL_NO_MATCH` sentinel** in `KubesharkClient.buildKflQuery()` is the right paranoid default: if all service names are unsafe to embed, the query falls back to a no-match sentinel rather than an unfiltered `"http"` query. Refusing to over-capture is the correct security posture.
3. **`isKflSafeToEmbed()` whitelisting approach** (check for `"`, `\`, and control characters only) is correct. It does not try to be a name validator — the platform owns that — and it only prevents the specific injection vectors that would break KFL query semantics.
4. **WIF for CI/CD with repository-scoped attribute condition** is correct. The `attribute_condition = "assertion.repository == 'pmk6vc/validation-platform'"` constraint prevents any other GitHub repository from impersonating the CI/CD SA. No JSON key anywhere.
5. **IAM database auth for Cloud SQL** (no static password, OAuth token via socket factory) eliminates an entire credential rotation class. The `bootstrap-db.sh` approach of using a temporary password to grant schema ownership and then rotating it to an unknown value is pragmatic and correct for the one-time bootstrap case.
6. **`RegistrationOutcome` sealed class** correctly distinguishes transient from permanent failures. The per-service `permanentlyFailed` set prevents the discovery loop from hammering the platform with a service name that the platform will always reject (e.g., name too long for RFC 1123). This distinction matters when the platform returns 400 vs 429 — permanently excluding a 429 would be wrong.

---

## Key Divergences from PLAN.md

These are places where this review takes a different position than PLAN.md's suggested sequencing:

1. **Compatibility tests (MVP-7) should be P0, not "run in parallel with Track 1."** PLAN.md lists gRPC and Istio tests as "run in parallel with Track 1" without a hard delivery gate. This review classifies them as P0 because: if we onboard customer #1 and they have gRPC services, we have already made an implicit promise about capture completeness. Finding out gRPC doesn't work after onboarding is worse than delaying onboarding by one week to run the test. The tests are cheap (sandbox + one gRPC service); the discovery from a customer is expensive.

2. **PII redaction (MVP-1) is P0, not phase 5 hardening.** PLAN.md places PII redaction in "customer #1 hardening" but doesn't give it urgency. The capture loop is running on real GCP *right now* with real traffic. `Authorization` headers are being stored in Cloud SQL today. This isn't a future hardening item — it is a current data handling risk that should be addressed before the first paying customer, and arguably before the first design partner whose traffic contains authentication tokens.

3. **The GitHub Action (MVP-9) should start before Track 1 MVP completes**, not after. The "capture-status" version of the action (does the agent see traffic for this service?) can be built now without any replay engine. It establishes the integration pattern and activates users on the GitHub workflow before the validation verdict is available. Deferring the action until full orchestration is ready means waiting 3+ months for any CI integration.
