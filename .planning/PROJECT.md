# Validation Platform v1

## What This Is

A hosted B2B SaaS that lets engineering teams validate every change against real production traffic before deployment: capture live req/res traffic with eBPF, replay it against a staging cluster, and return a PASS / FAIL / INCONCLUSIVE verdict with per-dimension evidence (response diffs, latency, error rate, memory trend). v1 ships as a design-partner beta with fully self-serve onboarding, a web dashboard as the primary surface, PR Check Runs as the decision touch point, and Slack notifications for alerts.

## Core Value

**Replay real production traffic against staging and return a trustworthy go/no-go decision — within a self-serve developer experience that a design partner installs and gets value from in under 30 minutes.** Everything else in the product exists to make this single loop fast, accurate, and safe.

## Requirements

### Validated

<!-- Existing capabilities inherited from the brownfield codebase. -->

- ✓ Kotlin Ktor platform server (port 8080) with organizations + services + JWKS + agent-config endpoints — existing
- ✓ Kotlin Ktor collector server (port 8081) with batch captured-input ingest + list/delete — existing
- ✓ RS256 JWT validated in-app via shared `installJwtAuth()`; `AgentIdentity` principal populated from JWT claims — existing
- ✓ PostgreSQL schema via Flyway migrations V0001–V0007 — existing
- ✓ Modular monolith with module-owned tables and HTTP-only cross-module access — existing
- ✓ Cursor-paginated REST API with type-safe value-class IDs — existing
- ✓ Pluggable cluster bring-up: `platform-up.sh` (Cloud Run + Cloud SQL), `sandbox-up.sh` (GKE + test workloads), `bootstrap-db.sh` (one-time schema ownership) — existing
- ✓ Test infrastructure: `DatabaseTestBase`, `KubernetesWorkloadTestBase` (k3s), `TestJwtKeys`, `authedTestApplication` exposed via `java-test-fixtures` — existing
- ✓ `tap/` Go module bootstrap, CO-RE build via `bpf2go`, CI integration — existing (TAP-2)
- ✓ eBPF L7 capture spike on GKE COS kernel ≥ 6.12 — existing (TAP-1)
- ✓ K8s informer for `cgroup_id` → pod metadata attribution — existing (just merged, VAL-55 PR2)
- ✓ Test microservices in k3s (api-gateway, order-service, notification-service, webhook-stub, traffic-generator) for traffic generation — existing

### Active

This is the v1 scope. Each bullet is a capability area; detailed requirement IDs live in `REQUIREMENTS.md`. The bracketed phase pointers are indicative — the roadmapper assigns final phase numbers.

- [ ] **Native eBPF capture cutover** [Phase 1] — finish the Go tap (TAP-3), port the Kotlin agent's non-capture surface to Go (TAP-4), wire the capture pipeline (TAP-5), production-harden with Helm + probes + metrics + backpressure (TAP-6), cut over the sandbox cluster from Kubeshark (TAP-7), and decommission the Kotlin agent + Kubeshark in small reviewable PRs (TAP-8). This is the prior scoping reframed as one phase with multiple plans.
- [ ] **gRPC + HTTP/2 capture** — extend the userspace dissector with HTTP/2 frame parsing, HPACK decoding, and gRPC length-prefixed message support. Was deferred as TAP-9; now in v1.
- [ ] **Pluggable storage backend** — the collector exposes a storage interface. Default: hosted Postgres (existing). Customers can configure it to write to their own Postgres (or S3 + Postgres metadata) so prod data never leaves their boundary. Single collector deployment, swap backend at config time.
- [ ] **Replay engine** — fetch captured inputs by service + time window, replay against a staging target in one of two fidelities: `sequential` (one request at a time) and `actual` (emulate production concurrency and rate up to a configured ceiling so staging is never melted). Read-only by default.
- [ ] **Staging observation** — during replay, collect outbound-connection counts and call patterns via Kubeshark-or-tap in the staging cluster; collect pod CPU/memory via the K8s Metrics API.
- [ ] **Comparison engine** — compare baseline (current version) and candidate (PR version) replay runs across response diffs, latency (Mann-Whitney U), error rate, and memory trend (linear regression). Produce per-dimension status + evidence.
- [ ] **Verdict surface** — headline PASS / FAIL / INCONCLUSIVE plus per-dimension breakdown with evidence. UX shape per the Claude Design referenced in onboarding notes; renders in the web dashboard and is summarized in the PR Check Run + Slack notification.
- [ ] **Orchestration API** — `POST /api/validations` accepts a target service and candidate image; orchestrates capture-window selection → baseline replay → candidate deploy → candidate replay → comparison → verdict; exposes status via `GET /api/validations/{id}`.
- [ ] **Web dashboard** — primary surface. Vite + React + TypeScript + Tailwind + TanStack Query + TanStack/React Router + shadcn/ui. Static bundle served from Cloud Run (nginx) or GCS+CDN. Auth via the existing JWT. Surfaces: org + services, captured traffic explorer, validation runs, verdict drill-in, agent install instructions, settings.
- [ ] **Self-serve onboarding** — signup → org provisioning → agent install instructions (Helm) → first capture lands → first validation run → first verdict — completable end-to-end in under 30 minutes by a developer with no prior context. Onboarding state is visible and resumable in the dashboard.
- [ ] **GitHub PR integration** — a GitHub App posts a Check Run (PASS / FAIL / INCONCLUSIVE) and a PR comment with the headline verdict + a deep link to the dashboard. Required check optional per repo. No inline diff comments in v1.
- [ ] **Slack notifications** — verdict notifications and anomaly alerts pushed to a configured Slack channel; read-only — actions still happen in the dashboard.
- [ ] **Postgres row-level security (RLS)** — RLS policies on every multi-tenant table, scoped by the JWT principal's `organizationId`. Belt-and-braces on top of the existing app-layer tenancy.
- [ ] **JWT signing-key rotation** — `kid` header in issued JWTs; `installJwtAuth()` validates against multiple active keys; rotation without downtime. Closes the existing "private key in env var, no rotation" concern.
- [ ] **Header / PII redaction** — default-deny on sensitive request headers (`Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`); configurable allowlist + body-redaction rules. Captured inputs do not become a secondary secret store.
- [ ] **Performance at the medium envelope** — sustain per-customer: up to 50 services, ~1k RPS captured, ~50 validation runs/day, 30-day retention. Documented benchmarks on the sandbox cluster + a Cloud Run-shaped load test for the platform side.
- [ ] **Beta operations** — design-partner observability (per-customer health, capture rate, verdict throughput), oncall runbooks, customer-facing status indicators in the dashboard, a way to onboard a new design partner without a code change.

### Out of Scope

<!-- Explicit boundaries with reasoning. -->

- **Public GA polish** — pricing tiers, billing integration, public marketing site, SLA, status page, terms-of-service flow — design-partner beta is v1 done; GA polish is the next milestone.
- **BYO encryption key (CMEK)** — customer-managed KMS keys for at-rest encryption of captured-input bodies. Not in v1; revisit when a design partner asks. Pluggable storage already addresses the data-boundary concern for the most paranoid customers.
- **Customer-side collector deployment** — pluggable storage backend lets data stay in the customer boundary without moving the whole collector. Full customer-side deployment deferred.
- **LOAD-mode replay (uncapped prod-rate)** — replaced by `actual` mode with our configured ceiling so we don't melt staging or burn surprise spend. Uncapped LOAD is intentionally not v1.
- **Write-traffic replay with DB reset hook** — read-only replay is the v1 bar. Write replay requires customer ops cooperation and a reset story; deferred.
- **Adaptive concurrency in replay beyond the configured ceiling** — explicit per the existing design principle ("Replay fidelity stops at LOAD mode"). The ceiling is a knob; the engine doesn't try to auto-tune.
- **Multi-cluster federation** — single-cluster capture and single-cluster replay per the existing platform principle. Federation isn't justified by v1 demand.
- **Message queue capture (Kafka / PubSub / SNS / SQS)** — already deferred at the architecture level; no design partner is asking for it.
- **CLI** — useful for power users, but the dashboard + PR + Slack surfaces cover the design-partner use case. Add post-beta if a partner asks.
- **Mobile app** — web-first; mobile is not in scope.
- **TLS uprobes for encrypted-traffic capture** — gRPC support in v1 assumes plaintext-side capture (e.g. after a service-mesh sidecar terminates TLS) or non-TLS workloads on the design-partner cluster. Customer environments without that path land post-beta.
- **Inline PR diff comments** — Check Run + comment + deep link is the v1 bar. Inline diffs are higher-signal but a much larger product surface; deferred.

## Context

- The product is grounded in an existing brownfield codebase (`shared/`, `platform/`, `collector/`, `agent/`, `e2e-tests/`, `test-services/`, `tap/`). The existing platform and collector wire contracts (RS256 JWT, `POST /api/services`, `GET /api/agent/config`, `POST /api/captured-inputs`) are stable and the v1 product builds on top.
- Phase 1 (capture cutover) replaces the existing Kubeshark + Kotlin agent capture path with the Go tap + Go agent that's already being built. The cutover is the proof point that the v1 capture path holds up; everything else assumes that proof.
- "Design-partner beta" means 2–5 paying or unpaid design partners using the product on their real services and PR workflows. Functionality and trust matter more than GA polish; billing and pricing are explicitly deferred.
- The web dashboard is greenfield — no frontend exists in the repo today. Tech stack: Vite + React + TypeScript + Tailwind + TanStack Query + TanStack/React Router + shadcn/ui. Static bundle, no Node runtime in production. UX shape sourced from a Claude Design project (link maintained by the user; not in version control).
- "Self-serve from day 1" raises the polish bar materially: onboarding state needs to be resumable, errors need to be actionable, and the dashboard has to walk a developer from signup to first verdict without a human in the loop.
- Security is called out as paramount. Three concrete v1 hardening items: Postgres RLS, JWT signing-key rotation, header/PII redaction. BYO key is deferred but the architecture should not preclude it.
- Pluggable storage backend is the architectural answer to "skeptical customers want data in their boundary." The default hosted Postgres path stays in place; the configurable backend lets a customer point the collector at their own Postgres or S3.
- The capture layer is being doubled: REST (HTTP/1.1 + HTTP/2) AND gRPC. Previously deferred as TAP-9; the gRPC requirement comes from the design-partner conversation, not the original roadmap.
- Existing Linear projects under the `Validation-platform` team: [Replay Engine](https://linear.app/validation-platform/project/replay-engine-a9b7d282ff76), [Customer Onboarding](https://linear.app/validation-platform/project/customer-onboarding-1d3a825ed8d6), [Tech Debt](https://linear.app/validation-platform/project/tech-debt-ff5e67ba9787). The v1 work will pull tickets across all three.
- The bilingual codebase rule still applies: Kotlin and Go are separate ecosystems with their own toolchains; the new frontend adds a third (Node-at-build-time for Vite, no Node in production). No unifying wrappers across ecosystems.

## Constraints

- **Tech stack** — backend stays Kotlin (Ktor 3, Exposed, Flyway, JDK 21); capture stack is Go (`cilium/ebpf`, `bpf2go`, `client-go`, stdlib `net/http`); frontend is Vite + React + TypeScript + Tailwind. PostgreSQL is the default storage backend.
- **Hosting** — GCP-native: Cloud Run (platform, collector, web dashboard), Cloud SQL (Postgres), GKE (customer-side agent + tap; sandbox cluster), Artifact Registry. UI ships as a static bundle on Cloud Run with nginx (or GCS + CDN — TBD by the team).
- **Wire contracts** — existing platform/collector REST API stays stable. The pluggable storage backend lives behind the collector's repository layer; external contract unchanged. The Go agent speaks the existing contracts byte-for-byte during the cutover.
- **Performance** — design and benchmark to the medium envelope (50 services, ~1k RPS captured, ~50 validation runs/day, 30-day retention per design partner). Sandbox cluster is the realistic benchmark rig.
- **Security** — RLS retrofit must not break existing app-layer tenancy or the public REST API; JWT key rotation must not break existing sessions; redaction must not destroy captured-input fidelity for safe content (validation-run accuracy depends on it).
- **Self-serve onboarding** — sub-30-minute time-to-first-verdict for a developer with no prior context. This is a product metric, not a vibe — it constrains how the dashboard, Helm install, and agent registration flow are designed.
- **Schedule** — design-partner beta is the v1 done bar. No specific deadline named; the project ships when the loop works end-to-end on a real design partner's cluster + PRs and the team is comfortable inviting more.
- **PR shape** — small, reviewable PRs. The capture-cutover phase in particular has a hard constraint on chunked deletion; later phases inherit this norm.
- **Reversibility** — feature flags or clean revert paths for cutover-style changes (sandbox swap, RLS retrofit, JWT rotation) until they're validated.
- **Brand / UX** — ergonomic user experience is paramount; the dashboard is the differentiated surface, not an admin console. Investments in motion, copy, and empty states are not nice-to-haves.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Project scope = full v1 (capture → replay → verdict → UI → onboarding → security), not just capture cutover | The prior "project" was actually one phase; the broader product needs phase-level structuring or downstream work won't sequence right | — Pending |
| Hosted SaaS with pluggable storage backend | Default-hosted lets us move fast; pluggable backend unblocks skeptical customers without splitting the whole topology | — Pending |
| Design-partner beta is v1 done (not public GA) | Validates the core promise commercially without paying the GA polish tax; public GA is the next milestone | — Pending |
| REST + gRPC capture in v1 (HTTP/2 + HPACK + length-prefixed gRPC framing) | Design-partner conversation surfaced gRPC as a hard requirement; the previously-deferred TAP-9 moves into MVP | — Pending |
| Replay fidelity: `sequential` + `actual` (capped concurrency emulation) | Sequential is the floor; `actual` matches production behavior without uncapped LOAD-mode risk to staging | — Pending |
| Verdict UX sourced from external Claude Design project | The user has an explicit design artifact; PROJECT.md references rather than re-describes it. Design-phase plans will plan against that artifact | — Pending |
| PR integration: Check Run + comment, no inline diffs in v1 | Captures the go/no-go where the decision is made; deeper inline-diff product is post-beta | — Pending |
| Frontend: Vite + React + TypeScript + Tailwind (no Next.js, no Node in prod) | Dashboard is auth-gated CSR; Next adds server runtime and conceptual surface that doesn't pay off; static bundle on Cloud Run keeps the runtime story simple | — Pending |
| Self-serve onboarding from day 1, not concierge-then-self-serve | Pays off when flipping to public GA; forces investment in actually-good onboarding instead of slide-deck onboarding | — Pending |
| Security v1: Postgres RLS, JWT key rotation, header/PII redaction; BYO key deferred | Three concrete hardening items address compounding risk now; CMEK is enterprise territory that no design partner has asked for yet | — Pending |
| Single-cluster scope retained | No design-partner demand for federation; the coordination cost isn't justified | — Pending |
| Bilingual+ codebase OK (Kotlin + Go + TS) | "Don't couple where coupling doesn't exist." Three ecosystems is fine; no unifying wrappers, each builds and tests independently | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-14 after re-initialization (project rescoped from capture-cutover phase to full v1)*
