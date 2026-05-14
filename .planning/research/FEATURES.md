<!-- refreshed: 2026-05-13 -->
# Feature Research

**Domain:** Hosted B2B SaaS — production-traffic replay → staging validation → PASS/FAIL/INCONCLUSIVE verdict, with self-serve onboarding and PR-time decision surface
**Researched:** 2026-05-13
**Confidence:** MEDIUM-HIGH (competitor positioning and table-stakes well-attested; some differentiator framing is opinionated and unvalidated until design partners are in)

## Framing

This is a verdict product, not an observability product. Every feature is judged against one question: **does it make the PASS/FAIL/INCONCLUSIVE call faster, more trustworthy, or more actionable at PR time?** Generic dashboard chrome (charts, graphs, infinite slice/dice) that doesn't change that answer is anti-feature in v1.

Two strategic notes that shape the prioritization:

1. **Verdict trust is the gating concern, not feature breadth.** Speedscale, Diffy, GoReplay, k6 Cloud all "do replay." The reason design partners haven't already adopted them is some combination of: setup pain, false positives in diffs, no staging-real-deps story, and weak PR-time surfacing. Beating any one of those convincingly is more valuable than feature parity.
2. **The PR Check Run is the contract.** GitHub Check Run + comment + deep link is where the design partner decides whether to merge. Everything in the dashboard exists to defend or refute the verdict shown there. Treat the dashboard as "verdict drill-in," not "monitoring."

## Feature Landscape

### Table Stakes (Users Expect These)

Missing any of these and a design partner walks. They are the floor — not where we compete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Production HTTP/REST capture (read-only safe) | Every competitor (Speedscale, GoReplay, Diffy, Kubeshark) captures L7 HTTP. No traffic source = no product. | M | Already covered: tap + agent + collector. |
| Service registration / topology view | Users need to see what was discovered and what the agent is actually targeting. "Where is my data going" is the first onboarding question. | S | Platform `POST /api/services` + dashboard listing already implied. |
| Replay against staging — sequential mode | This is the floor. Every competitor offers at least sequential replay. Without it nothing else matters. | M | Listed under PROJECT.md `replay engine`. |
| Replay against staging — concurrency-capped ("actual") mode | Sequential alone is a credibility problem under realistic traffic shapes. Capped-concurrency is the bare minimum to claim "real traffic patterns." | M-L | Bounded by configured ceiling — not adaptive (per PROJECT.md). |
| Response diff between baseline and candidate | The single highest-signal evidence in any replay product. Diffy proved this; Speedscale, AREX, Rabobank Shadow Tool all built around it. | L | Field-level structured diff; JSON-aware; configurable ignore paths. |
| Latency comparison with statistical test | Eyeballing latency histograms loses to a Mann-Whitney U test. Threshold-based comparison ("p95 > 200ms") is what Argo Rollouts users have grown out of. | M | Already in PROJECT.md. Mann-Whitney U + effect size. |
| Error-rate comparison | Status-code class deltas (`2xx`/`4xx`/`5xx`) per endpoint, baseline vs candidate. Basic, expected. | S | Falls out of response-status field. |
| PASS / FAIL / INCONCLUSIVE verdict | The product *is* this. Argo Rollouts has it (canary verdict), Iter8 has it (SLO assessment). Without a verdict you're a dashboard, not a tool. | M | Per-dimension status rollup into a headline; explicit INCONCLUSIVE when evidence insufficient. |
| GitHub PR Check Run + comment with verdict + deep link | The PR is the decision point. Speedscale, k6 Cloud, Codecov, every modern CI-adjacent tool ships a Check Run. Absence makes the product feel offline. | M | Already in PROJECT.md. v1 = Check Run + single comment + link; not inline diff comments. |
| Slack notification on verdict / anomaly | Notification is the second decision surface. Read-only Slack is sufficient — actions still happen in the dashboard. | S | Webhook + per-org channel config. |
| Self-serve signup → org provisioning | "Self-serve from day 1" per PROJECT.md. Speedscale and Signadot both onboard self-serve; design partners expect this is 2026 baseline. | M | Email/password + org provisioning + JWT issued. |
| Helm-based agent install with copy-pasteable command | Speedscale, Kubeshark, Signadot, Pixie all install via Helm. CRDs, Operators, kubectl-apply are all viable but Helm is the lowest-friction default. | M | Existing agent + manifests; needs Helm chart + values for `PLATFORM_URL` / `COLLECTOR_URL` / `API_KEY`. |
| First-capture confirmation in dashboard | "Did my install work?" is the #1 onboarding failure mode. Need a visible "first capture landed" state — Signadot does this, Speedscale does this. | S | Polls `GET /api/captured-inputs?limit=1` on a service; flips a state flag. |
| PII / sensitive-header redaction (default-deny) | Captured-input bodies are the world's worst secondary secret store if mishandled. Default-deny on `Authorization`/`Cookie`/`Set-Cookie` is non-negotiable. Speedscale "Preventing PII in Test environments" is an entire blog post — this is industry baseline. | M | Already in PROJECT.md. Header allowlist + body redaction rules. |
| Captured-traffic explorer (list + drill-in to a single req/res) | Users will not trust the verdict if they can't inspect what was captured. Diffy users complained about this for years before Diffy was abandoned. | M | Already implied by dashboard scope. Don't over-build — pagination + JSON viewer is enough. |
| Validation-run history per service | "Has this been getting worse?" requires history. Even one run isn't useful without prior comparison. | S | Cursor-paginated runs per service. |
| Postgres tenancy isolation (RLS) | B2B SaaS without RLS in 2026 raises eyebrows. App-layer tenancy is fine but security review at any serious customer will ask. | M | Already in PROJECT.md. |
| Multi-tenant auth with org/service scoping | Every API call must be scoped to the JWT principal's org. Already done; expected to remain done. | — | Already implemented. |
| Health/status visibility for the agent | "Is my agent running, capturing, and pushing?" Speedscale, Pixie, Signadot all show agent status; without it onboarding bug-reports become "I don't know what's broken." | S | Last-heartbeat + last-capture-pushed-at per agent per cluster. |

### Differentiators (Competitive Advantage)

Where v1 invests real product effort to beat the alternatives. Each one is anchored to the core value: faster, more trustworthy, or more actionable verdicts at PR time.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Staging-with-real-deps replay** | The architectural pivot in PROJECT.md is the differentiator. Speedscale et al. mock dependencies — TLS-encrypted prod DBs make that mocking story brittle. Replaying against the customer's real staging cluster with real deps removes a whole class of false positives (mocked-DB drift, mocked-cache staleness). | — | Architecture, not a feature; but the *story* matters in the dashboard and marketing copy. |
| **Statistical verdict, not threshold-based** | Threshold alerting ("p95 > X") is what Argo Rollouts canary users have outgrown. Mann-Whitney U on latency + linear regression on memory + structured diff on responses is meaningfully more defensible. Differentiator vs k6 Cloud, Speedscale traffic-replay reports. | M | Already in PROJECT.md. Make the statistical framing visible in the verdict UX — show effect size + sample size, not just "passed." |
| **gRPC + HTTP/2 capture in v1** | Speedscale, GoReplay, Kubeshark support gRPC, but with varying fidelity. Native HTTP/2 framing + HPACK + length-prefixed gRPC in an eBPF tap (plaintext side) is competitive parity for any cloud-native customer. Without it, half the design-partner cluster is invisible. | L | Already in PROJECT.md (lifted from TAP-9). |
| **Sub-30-minute time-to-first-verdict** | This is *the* design-partner promise. Speedscale onboarding is multi-hour; Signadot is faster but requires sidecar changes. If we hit < 30 min self-serve we have a story. | L | The number anchors product decisions (Helm one-liner; opinionated defaults; no manual config files; dashboard onboarding state). |
| **Per-dimension verdict breakdown with evidence** | Argo Rollouts says "AnalysisRun: Failed" with little color. Diffy showed response diffs but had no scoring. Per-dimension (response / latency / errors / memory) + evidence + the specific endpoints that failed → trustable verdict. | M | The verdict UX is the dashboard's centerpiece. Sourced from Claude Design project. |
| **Pluggable storage backend (data stays in customer boundary)** | The skeptical-customer objection is "captured-input bodies contain our user data — can we keep that in our boundary?" Pluggable backend (own Postgres / S3 + metadata) is the answer without splitting the whole topology. Speedscale offers SaaS or self-host but not "their storage, our control plane." | L | Already in PROJECT.md. Behind the collector's repository layer. |
| **GitHub PR Check Run as the decision surface (not dashboard-first)** | k6 Cloud comments on PRs; Speedscale links from CI; none of them make the Check Run the *contract*. By treating Check Run + comment + deep link as the v1 product (and the dashboard as the drill-in surface) we beat the "I have to context-switch to look at the dashboard" friction. | M | Already in PROJECT.md. The Check Run summary needs to carry enough signal that 80% of decisions don't need the dashboard. |
| **Capture-window selection automatic from orchestration API** | Speedscale requires the user to "snapshot" a capture explicitly. If `POST /api/validations` picks a recent representative window automatically (last N minutes, balanced across endpoints) the UX is materially better. | M | Falls out of `POST /api/validations` orchestration. Could start dumb (last 5 minutes) and earn smarter selection later. |
| **Read-only by default, no DB-reset gymnastics** | Speedscale's default is "mock everything." That sidesteps DB writes but means staging never tests real DB behavior. Read-only replay against real staging deps means: no mocking, no reset hook required, no chance of staging-state divergence between sequential runs. | — | Already a design principle. Surface it as a feature: "no write traffic, no risk to staging." |
| **Verdict drill-in: per-endpoint, per-dimension** | When verdict says FAIL on latency, the user wants to know *which endpoints* and *which requests*. This is the place where the dashboard earns its keep — and where Argo Rollouts / k6 Cloud are weakest (you get a chart, not a "this specific endpoint at p95 regressed by 38%"). | L | Sourced from Claude Design. |
| **JWT signing-key rotation with `kid` header** | Not flashy but a real B2B SaaS security marker. Without it any security review at a serious customer slows down the deal. | M | Already in PROJECT.md. |
| **Resumable onboarding state in dashboard** | "I started signup, installed Helm, but then my laptop crashed" → user comes back, dashboard shows exactly where they are. Speedscale doesn't do this well; Signadot is OK; new SaaS like Vercel/Linear set the bar. | M | Onboarding-state machine per org, surfaced in dashboard. |
| **Anomaly detection during replay (memory trend over run)** | Speedscale tracks resource usage but doesn't do trend analysis. Linear-regression on memory during a long-running replay catches leaks that point-in-time metrics miss. | M | Already in PROJECT.md (per-dimension memory comparison). Make the leak-detection framing visible. |

### Anti-Features (Commonly Requested, Often Problematic)

Features that look reasonable on a competitor page but actively damage v1.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **LOAD-mode uncapped replay (10x / 100x prod rate)** | Speedscale markets traffic multiplication. Sounds powerful. | Melts customer staging. Surprise cost. No verdict-quality gain — once concurrency exceeds the production envelope, the comparison signal degrades, not improves. | `actual` mode capped at a configured ceiling (already in PROJECT.md). Document explicitly that we're not in the load-testing category. |
| **Write-traffic replay with DB reset hook** | Customers ask "but my POSTs aren't covered." | Requires customer ops cooperation, requires a reset story, double-runs the DB twice in sequence. Failure modes are nasty (partial reset, stale fixtures). For v1 it's a tar pit. | Read-only replay only. Document the limitation honestly. Address post-beta if a partner blocks on it. |
| **Adaptive concurrency / auto-tuning replay engine** | "Auto-figure-out-the-right-rate" sounds great. | The ceiling is the user's risk budget. Auto-tuning past it is the customer's worst nightmare. Also: more variables in the replay engine means harder-to-trust verdicts. | Configured ceiling. Surface the ceiling in the UI. Auto-tune is anti-feature. |
| **Mocked downstream dependencies (Speedscale-style sandwich)** | "Replay without staging" sounds simpler to onboard. | TLS terminates the bet — prod DBs over TLS can't be replayed without protocol-specific proxies. Already evaluated and rejected per PROJECT.md. | Staging-with-real-deps. The architectural pivot is the moat. |
| **Inline PR diff comments per regression** | "Show me where it broke right inside the code review." | Massive product surface (line mapping, stack-trace anchoring, comment lifecycle). For v1 the Check Run + comment + dashboard link is enough. | Single PR comment with verdict headline + dashboard deep link. Inline diffs post-beta if a partner explicitly asks. |
| **Multi-cluster federation** | "We have prod-us, prod-eu — capture across them." | Coordination cost is enormous. No design partner is asking for it. | Single-cluster scope per validation run. Per PROJECT.md and architecture principles. |
| **Message-queue capture (Kafka, PubSub, SNS, SQS)** | "Replay isn't complete without our async paths." | True in theory; no design partner asking; capture story is protocol-specific. | Defer per PROJECT.md. Revisit if a partner blocks on it. |
| **Generic full-text search across captured inputs** | "Let me find all requests matching X." | Builds a search product inside the verdict product. Easy to overscope into ElasticSearch-territory. | Filter by service + endpoint + time window + status code in the explorer. Full-text is post-beta. |
| **Real-time dashboard updates (WebSocket / SSE everywhere)** | "Status feels alive when it updates without refresh." | TanStack Query polling at ~5s is good enough for verdict-update cadence (runs take minutes anyway). WebSocket adds infra (Cloud Run cold-start, reconnection) without verdict-quality gain. | Polling. Reserve real-time for the agent-status indicator if a partner complains. |
| **CLI tool** | "Power users want scripting." | Dashboard + PR + Slack covers the design-partner use case. CLI doubles the API surface that has to stay backward-compat. | REST API exists; document it. CLI is post-beta. |
| **Mobile app** | "Be notified on the go." | Slack already covers notify-on-the-go. App store reviews, push tokens, native maintenance — not in scope. | Slack notifications. |
| **Comprehensive integrations marketplace (Datadog, NewRelic, PagerDuty, Jira, Linear...)** | "Connect us to everything." | Each integration is a maintenance burden and an attack surface. Slack + GitHub is enough for the decision surface. | Slack + GitHub PR Check Run only in v1. Webhook endpoint as escape hatch (out of scope unless a partner asks). |
| **BYO KMS / CMEK at rest** | "Enterprise security checkbox." | Enterprise feature; no design partner asking; pluggable storage already addresses the data-boundary concern. | Pluggable storage backend. Per PROJECT.md, CMEK is deferred. |
| **Pricing / billing / tiered plans in v1** | "Need to charge for design partners." | Design partners are paying or unpaid by direct contract. Self-serve billing is a different product. | Direct invoicing for design partners. Billing UI is GA-milestone, not v1. |
| **A/B test traffic-splitting between candidate and baseline in production** | Iter8/Argo Rollouts style — split live traffic, compare verdict | We're a *pre-production* verdict product. Splitting live traffic moves us into the progressive-delivery category (Argo / Flagger territory) and dilutes the positioning. | Compose with Argo Rollouts, don't replace it. Our verdict happens at PR time; progressive delivery happens at deploy time. |
| **Auto-rollback / deployment integration** | "If validation fails, roll back automatically." | Customer doesn't trust v1 enough to give it deploy authority. Slacking us into the deployment system makes a v1 bug catastrophic. | Inform-only: PR Check Run + Slack. Auto-rollback is GA-milestone if a partner asks. |
| **Generic dashboard widgets / drag-and-drop builder** | "Let users customize the verdict view." | Customizable dashboards are infinite-scope. Verdict UX should be opinionated. | One opinionated verdict layout from the Claude Design project. No widget builder. |

## Feature Dependencies

```text
Self-serve signup
    └──required for──> Helm-agent-install onboarding
                          └──required for──> First-capture confirmation
                                              └──required for──> First validation run
                                                                  └──required for──> First verdict (time-to-first-verdict goal)

eBPF tap + agent (existing) ──feeds──> Collector ingest
                                          └──feeds──> Pluggable storage backend (Postgres / S3)
                                                       └──feeds──> Replay engine input fetch
                                                                     └──drives──> Sequential replay
                                                                                    └──drives──> Actual (capped concurrency) replay
                                                                                                  └──drives──> Staging observation (Kubeshark/tap-in-staging + K8s Metrics)
                                                                                                                 └──drives──> Comparison engine
                                                                                                                                └──drives──> Verdict surface
                                                                                                                                               └──renders in──> Dashboard verdict drill-in
                                                                                                                                               └──renders in──> GitHub PR Check Run + comment
                                                                                                                                               └──renders in──> Slack notification

Orchestration API (POST /api/validations)
    └──coordinates──> Capture-window selection → Baseline replay → Candidate deploy → Candidate replay → Comparison → Verdict

PII redaction ──must-run-before──> Captured input persisted in collector
                                     └──affects──> Captured-traffic explorer (redacted fields are masked)
                                     └──affects──> Replay engine (redacted fields are present in the payload but redacted)

Postgres RLS ──retrofits──> All multi-tenant tables (organizations, services, captured_inputs, validation_runs, ...)
                              └──must-not-break──> App-layer tenancy
                              └──must-not-break──> Existing REST API contracts

JWT signing-key rotation ──must-not-break──> Existing agent sessions (validate against multiple active keys)
                                              └──prerequisite for──> Any future SOC2 / security review

gRPC capture (HTTP/2 + HPACK + length-prefixed)
    └──extends──> Native eBPF capture (TAP-3..8)
    └──prerequisite for──> gRPC verdicts (response diff understands proto)
    └──not-prerequisite for──> v1 HTTP-only verdict (REST design partners can ship without it)

Dashboard verdict drill-in ──requires──> Comparison engine output schema (per-dimension status + evidence)
GitHub PR Check Run ──requires──> Verdict surface + GitHub App registration (OAuth flow + per-installation tokens)
Slack notifications ──requires──> Verdict surface + Slack webhook per org

gRPC capture ──conflicts──> v1 timeline if it slips
    (so: TAP-3..8 must finish before gRPC framing is layered on; design partners are HTTP-first so this is sequenceable, not blocking)
```

### Dependency Notes

- **Self-serve onboarding requires every link in the chain to be reliable**: signup, Helm install command, agent boot, first capture push, validation run, verdict. A flaky agent boot kills the < 30-minute goal silently. Onboarding is "the longest chain" — every link must be tested end-to-end as a unit.
- **PII redaction must run before persistence, not after**: redacting after captured-inputs are written to Postgres means the secrets-in-Postgres window exists. Run redaction in the collector's ingest path (or even in the agent, pre-network). Schema/storage design should assume the row was already redacted.
- **Pluggable storage backend sits between collector ingest and replay-engine read**: replay engine fetches by `GET /api/captured-inputs` (per the architecture rule of HTTP-only cross-module access), so the storage backend is invisible to replay. Important: don't leak storage-backend-specific concerns up the API.
- **gRPC capture is a parallel track to HTTP-first validation**: design partners using REST get value without gRPC. Don't make gRPC capture a prerequisite for first verdict. Sequence gRPC as Phase 3 or later after the verdict loop closes on HTTP.
- **JWT rotation is retrofit, not greenfield**: the `kid` header and multi-key validation must roll out without breaking existing agent sessions. Plan a transition window (e.g. dual-key validate for 30 days) before retiring the old key.
- **Postgres RLS is retrofit, not greenfield**: app-layer tenancy already works; RLS adds belt-and-braces. The retrofit must be invisible to the API. Test that RLS doesn't break cursor pagination or batch ingest performance.
- **The verdict surface depends on three independent renderers** (dashboard, Check Run, Slack). Define the verdict schema once (per-dimension status + evidence + headline); each renderer consumes the same payload. Don't let the schema diverge per-surface.
- **Comparison engine depends on staging observation, not just replay**: the verdict needs CPU/memory deltas, which means K8s Metrics API + Kubeshark-or-tap-in-staging is on the critical path for "memory leak" verdicts. Without staging observation, the verdict is response-diff + latency only — still useful but weaker.
- **Capture-window selection is the only place orchestration decides something interesting**: everything else in `POST /api/validations` is mechanical sequencing. v1 can start with "last 5 minutes" and earn smarter selection later — don't over-design the picker.

## MVP Definition

### Launch With (v1) — Design-Partner Beta

The hard line: **a developer can sign up, install the Helm chart, push a PR, and get a verdict in under 30 minutes — and that verdict is trustworthy.** Everything below is needed for that loop to close.

- [ ] Self-serve signup → org provisioning → JWT issued — without it onboarding isn't self-serve
- [ ] Helm-based agent install with copy-pasteable command — without it onboarding isn't sub-30-min
- [ ] First-capture confirmation in dashboard — without it onboarding silently fails
- [ ] HTTP/REST production capture (existing eBPF tap + Go agent, cutover from Kubeshark) — without it no traffic source
- [ ] gRPC + HTTP/2 capture — without it half the design-partner cluster is invisible (per PROJECT.md, lifted from TAP-9)
- [ ] PII / sensitive-header redaction default-deny — without it no design partner ships
- [ ] Pluggable storage backend (default hosted Postgres) — without it the skeptical-customer story is missing; default path still works
- [ ] Replay engine: sequential mode — without it no replay
- [ ] Replay engine: actual (capped concurrency) mode — without it sequential is a credibility problem
- [ ] Staging observation: outbound connections + K8s Metrics during replay — without it the memory/leak dimension is absent
- [ ] Comparison engine: response diff + latency (Mann-Whitney U) + error rate + memory trend — without it no verdict
- [ ] PASS / FAIL / INCONCLUSIVE verdict surface — without it we are a dashboard, not a tool
- [ ] Orchestration API (`POST /api/validations`) — without it the verdict loop is manual
- [ ] Web dashboard: verdict drill-in + captured-traffic explorer + validation-run history + onboarding state + agent install — without it the design partner has no surface
- [ ] GitHub PR Check Run + comment + deep link — without it the verdict isn't at the PR
- [ ] Slack notifications on verdict — without it the team doesn't notice
- [ ] Postgres RLS — without it security review stalls
- [ ] JWT signing-key rotation with `kid` header — without it any security review stalls
- [ ] Agent health/status indicator — without it onboarding bug reports are unsolvable
- [ ] Beta operations: per-customer health, capture rate, verdict throughput; runbooks — without it we can't keep design partners healthy

### Add After Validation (v1.x — after the verdict loop is closing on real PRs)

- [ ] Smarter capture-window selection (representative endpoint coverage instead of "last 5 minutes")
- [ ] Webhook-out for verdicts (escape hatch for teams without Slack)
- [ ] Per-endpoint diff ignore rules (timestamps, UUIDs, correlation IDs)
- [ ] Configurable verdict thresholds per service (effect-size cutoffs, sample-size minimums)
- [ ] Captured-traffic export (JSONL or HAR) for partner debugging
- [ ] Agent autoupdate or version-skew warnings in the dashboard
- [ ] Validation-run cancellation (today: wait for it to finish)
- [ ] Multi-baseline comparison (compare candidate against last-N runs, not just one)

### Future Consideration (v2+ / GA milestone)

- [ ] Public marketing site, pricing, billing, SLA, status page, ToS flow (GA polish, per PROJECT.md)
- [ ] CMEK / BYO KMS at rest (revisit when a partner asks)
- [ ] Customer-side full collector deployment (pluggable storage already covers most of the want)
- [ ] CLI wrapping the REST API (if a partner asks)
- [ ] Inline PR diff comments (a much larger product surface)
- [ ] Multi-cluster federation (if a partner asks)
- [ ] Message-queue capture (Kafka / PubSub / SNS / SQS)
- [ ] Write-traffic replay with DB reset hook
- [ ] TLS uprobes for encrypted-traffic capture (mesh-terminated paths only in v1)
- [ ] Deployment integration / auto-rollback (Argo / Flux hooks)
- [ ] Anomaly correlation with recent deploys
- [ ] Web UI for endpoint-classification overrides (safe/mutating)
- [ ] AI-generated verdict summaries ("This regression looks like a database connection-pool exhaustion in `/api/orders`")

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| HTTP/REST capture (Go tap cutover) | HIGH | HIGH (already in flight) | P1 |
| gRPC + HTTP/2 capture | HIGH | HIGH | P1 |
| Replay engine — sequential | HIGH | MEDIUM | P1 |
| Replay engine — actual (capped) | HIGH | HIGH | P1 |
| Staging observation | HIGH | MEDIUM | P1 |
| Comparison engine (4 dimensions) | HIGH | HIGH | P1 |
| Verdict surface (schema + headline + per-dimension) | HIGH | MEDIUM | P1 |
| Orchestration API (`POST /api/validations`) | HIGH | MEDIUM | P1 |
| Web dashboard (verdict drill-in + explorer + onboarding) | HIGH | HIGH | P1 |
| Self-serve signup + Helm install + first-capture state | HIGH | HIGH | P1 |
| GitHub PR Check Run + comment | HIGH | MEDIUM | P1 |
| Slack notifications | MEDIUM | LOW | P1 |
| PII / header redaction | HIGH | MEDIUM | P1 |
| Pluggable storage backend | MEDIUM (for hosted path) / HIGH (for skeptical partners) | HIGH | P1 |
| Postgres RLS retrofit | MEDIUM (security review unblocker) | MEDIUM | P1 |
| JWT key rotation | MEDIUM (security review unblocker) | MEDIUM | P1 |
| Agent health/status indicator | MEDIUM | LOW | P1 |
| Beta operations (per-customer health, runbooks) | MEDIUM (us-facing, but partner-facing through reliability) | MEDIUM | P1 |
| Smarter capture-window selection | MEDIUM | MEDIUM | P2 |
| Per-endpoint diff ignore rules | MEDIUM | LOW | P2 |
| Configurable verdict thresholds | MEDIUM | LOW | P2 |
| Captured-traffic export | LOW | LOW | P2 |
| Webhook-out for verdicts | LOW | LOW | P2 |
| Validation-run cancellation | LOW | LOW | P2 |
| CLI | LOW | MEDIUM | P3 |
| Inline PR diff comments | MEDIUM | HIGH | P3 |
| Mobile app | LOW | HIGH | P3 |
| BYO KMS / CMEK | LOW (no partner asking) | HIGH | P3 |
| Auto-rollback | LOW (trust deficit) | HIGH | P3 |
| Multi-cluster federation | LOW (no partner asking) | HIGH | P3 |
| Write-traffic replay | MEDIUM (when asked) | HIGH | P3 |
| Message-queue capture | LOW (no partner asking) | HIGH | P3 |

**Priority key:**
- P1: Must have for design-partner beta (v1 done bar)
- P2: Should have, add as v1.x once verdict loop is closing
- P3: Future consideration, GA-milestone or later

## Competitor Feature Analysis

| Feature | Speedscale | Diffy (Twitter, archived) | k6 Cloud | Argo Rollouts | Signadot | GoReplay (OSS) | Our Approach |
|---------|------------|---------------------------|----------|---------------|----------|----------------|--------------|
| Production traffic capture | Sidecar / eBPF agent | Proxy fanout (no capture) | None — synthetic load only | None | None — sandboxes route live traffic | libpcap / on-host daemon | eBPF tap + Go agent (existing, cutover in Phase 1) |
| Capture protocols | HTTP, gRPC, some binary | HTTP only | HTTP only | N/A | HTTP, gRPC | HTTP; binary in Pro | HTTP + gRPC in v1 (plaintext side) |
| Replay engine | Sequential + multiplied LOAD | Live fan-out (no record-then-replay) | Synthetic VU-based load | N/A | Live sandbox routing | Sequential + multiplied | Sequential + capped `actual` only — no LOAD multiplication |
| Dependency mocking | Yes — captured-response mocks | No (live three-way fanout) | No (you script mocks) | N/A | No — real deps via routing | No | No mocking — staging-with-real-deps |
| Response diff | Yes | Yes (the original) | No | No | Optional via tools | Plugin | Yes, JSON-aware, ignore rules in v1.x |
| Latency comparison | Threshold + percentile | Histograms | Thresholds (SLO Pass/Fail) | Per-metric provider | Via job results | Via reports | Mann-Whitney U + effect size |
| Memory / leak detection | Resource graphs | No | No (load only) | Per-metric provider | No | No | Linear regression over run |
| Verdict surface | Report dashboard | "Discrepancies" list | Pass/Fail thresholds | AnalysisRun verdict | Job pass/fail | None | PASS / FAIL / INCONCLUSIVE + per-dimension evidence |
| GitHub PR integration | CI script + link | None | `cloud-comment-on-pr` flag | None (it's a controller) | Limited | None | Check Run + comment + deep link |
| Slack notifications | Yes | No | Yes | Via integrations | Yes | No | Yes (read-only) |
| Self-serve onboarding | Hours (sidecar/agent setup) | Setup-heavy (proxy) | Minutes (script-based) | Operator install + CRDs | Minutes (operator + Helm) | Hours (daemon + tuning) | < 30 min target |
| PII redaction | Yes (sanitization rules) | No built-in | N/A | N/A | Limited | Plugin-based | Default-deny + allowlist + body rules |
| Data residency / pluggable storage | Self-hosted option | Self-host only | SaaS only | Self-host (it's a controller) | Self-hosted | Self-hosted | Hosted default + pluggable backend |
| Statistical rigor | Threshold-based | Diff-counting | Threshold-based | Per-provider (Prometheus expr) | N/A | N/A | Non-parametric stats + sample-size awareness |
| Multi-cluster | Yes | No | N/A | Per cluster | Per cluster | Per host | Single-cluster v1 |
| Write-traffic replay | Yes (with caveats) | N/A | Yes (script it) | N/A | Yes (via real deps) | Yes (DIY) | No — read-only v1 |
| LOAD multiplication | Yes (marketed) | N/A | Yes (synthetic) | N/A | No | Yes | No — anti-feature |

**What this tells us:**
1. **Verdict surface is the clearest white space.** Diffy lists discrepancies; Speedscale gives reports; Argo Rollouts gives a single AnalysisRun verdict. Nobody offers a per-dimension, evidence-anchored PR-time verdict with statistical rigor *and* a good dashboard drill-in.
2. **Self-serve onboarding is the second-clearest white space.** Speedscale and Signadot are the closest, and Signadot's only because their value-prop is sandbox creation speed. A real "< 30 min from signup to first verdict" goal is differentiating in 2026.
3. **Real-deps replay is a moat.** Every alternative either mocks (Speedscale), live-routes (Signadot, Diffy), or runs synthetic load (k6, GoReplay). Capturing prod and replaying against a real-deps staging cluster is a meaningfully different product position.
4. **gRPC parity is non-negotiable.** Speedscale, Signadot, and Kubeshark all do gRPC. Shipping v1 HTTP-only would feel dated immediately.
5. **The PR Check Run is underused industry-wide.** k6 Cloud has the most polished version and it's still just a link. Making the Check Run the contract (not a notification) is a real product position.

## Sources

- [Speedscale — Traffic Replay overview](https://speedscale.com/blog/traffic-replay-production-without-production-risk/) and [Definitive Guide 2026](https://speedscale.com/blog/definitive-guide-to-traffic-replay/) — capture, sanitization, replay, load multiplication
- [Speedscale — Preventing PII in Test environments](https://speedscale.com/blog/preventing-pii-in-test-environments/) — PII as table stakes for the category
- [Diffy — SourceForge mirror](https://sourceforge.net/projects/diffy.mirror/) and [Microsoft Engineering Playbook — Shadow Testing](https://microsoft.github.io/code-with-engineering-playbook/automated-testing/shadow-testing/) — original three-way fanout pattern and its limits
- [Argo Rollouts — Analysis & Progressive Delivery](https://argo-rollouts.readthedocs.io/en/stable/features/analysis/) — AnalysisTemplate, AnalysisRun, metric providers, canary verdict mechanism
- [Iter8 — Canary testing](https://iter8.tools/0.13/tutorials/integrations/kserve/canary-testing/) and [The New Stack — Iter8 A/B/n testing](https://thenewstack.io/iter8-simple-a-b-n-testing-of-kubernetes-apps-ml-models/) — SLO assessment + analytics-driven verdict
- [Signadot — Best Microservices Testing Tools 2025](https://www.signadot.com/articles/best-microservices-testing-solution-for-kubernetes-in-2025/) and [Shadow Testing Superpowers](https://www.signadot.com/blog/shadow-testing-superpowers-four-ways-to-bulletproof-apis/) — sandbox-routing alternative to record-replay
- [k6 — Performance testing with GitHub Actions](https://grafana.com/blog/performance-testing-with-grafana-k6-and-github-actions/) and [k6 docs](https://grafana.com/docs/k6/latest/) — `cloud-comment-on-pr`, threshold-based pass/fail in CI
- [GoReplay docs](https://goreplay.org/docs/) and [Capture/Replay guide](https://github.com/probelabs/goreplay/blob/master/docs/Capturing-and-replaying-traffic.md) — OSS baseline for capture-and-replay; sanitization plugins
- [Rabobank shadow-tool](https://github.com/rabobank/shadow-tool) — diff-logging shadow pattern, modern open-source incarnation
- PROJECT.md (`/Users/prathameshkulkarni/repos/validation-platform/.planning/PROJECT.md`) — v1 scope, Out of Scope grid, Key Decisions, Constraints
- Architecture analysis (`/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/ARCHITECTURE.md`) — existing component layout

---

*Feature research for: hosted B2B SaaS production-traffic replay → staging verdict at PR time*
*Researched: 2026-05-13*
