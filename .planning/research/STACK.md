# Stack Research

**Domain:** B2B SaaS validation platform — eBPF capture → staging replay → statistical verdict, with a web dashboard, GitHub App, Slack notifications, RLS-hardened Postgres, and pluggable body storage.
**Researched:** 2026-05-14
**Confidence:** HIGH for FE / charts / stats / GitHub App / Slack / storage SDKs; MEDIUM for some replay-engine ergonomics where convention varies.

This file only covers the **net-new** v1 capability areas. The existing backend stack (Kotlin 2.2.21, Ktor 3.3.3, Exposed 0.57.0, Flyway 9.22.3, JDK 21, HikariCP 5.1.0, java-jwt 4.4.0, Fabric8 6.10.0, kotlinx-serialization 1.7.3, logback + logstash-encoder, TestContainers 2.0.3, Jib 3.4.4, ktlint 1.5.0) and the existing tap stack (Go + `cilium/ebpf` + `bpf2go` + `client-go`) are documented in `.planning/codebase/STACK.md` and are not duplicated here. Treat them as fixed inputs.

---

## Recommended Stack — Net-New Areas Only

### 1. Web Dashboard (greenfield)

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Vite | 6.x (latest LTS line) | Build tool, dev server | Already locked in PROJECT.md. Stay on 6.x for v1 — Vite 7/8 ship Rolldown but are still settling; Vite 6 is the conservative "boring" pick that React + TanStack tooling has been hardened against for 18+ months. Revisit after beta. [HIGH] |
| React | 19.2.x | UI runtime | Already locked in PROJECT.md. 19.2 is the current minor (released Oct 2025); 19 is stable since Dec 2024. Gives us `useActionState`, the stable transitions API, and React Compiler eligibility without committing to RSC (we don't need a server runtime). [HIGH] |
| TypeScript | 5.6+ | Language | Required by TanStack Router for `tsr generate`; React 19 types are clean. [HIGH] |
| TanStack Query | 5.x (≥ 5.100) | Server-state cache | Already locked in PROJECT.md. v5 is ~20% smaller than v4, stable Suspense hooks, `gcTime` semantics — this is the de facto React server-state library for the verdict dashboard. [HIGH] |
| TanStack Router | 1.x (≥ 1.169) | Type-safe routing | Already locked in PROJECT.md. File-based routing with end-to-end-typed search params (matters for verdict drill-in URLs like `/validations/:id?run=baseline`). Avoid React Router v7 because TanStack Router's type-safety story is materially better for a typed Kotlin backend. [HIGH] |
| Tailwind CSS | 4.x | Styling | Already locked. Tailwind 4 Oxide engine is Rust-based, 5-100x faster builds, CSS-first config via `@theme`. shadcn/ui's official path is now Tailwind 4. [HIGH] |
| shadcn/ui | latest CLI (project-pinned per component) | Component primitives | Already locked. Not an npm dep — you copy components into the repo. v4 components ship with `data-slot` for styling and use `tw-animate-css` instead of `tailwindcss-animate`. Pairs with Radix Primitives 1.x under the hood. [HIGH] |
| Recharts | 3.x | Dashboard charts | shadcn/ui's official chart component now uses Recharts 3. The platform UX is dashboard-shaped (latency distributions, error-rate bars, memory-trend lines, verdict timelines) — Recharts is the right fit and integrates without an abstraction layer. Don't pull in ECharts or Visx. [HIGH] |
| Zod | 4.x | Runtime schema validation | API response parsing, form validation, search-param schemas (TanStack Router accepts Zod directly). Zod 4 closed most of the v3 performance/bundle gap. [HIGH] |
| React Hook Form | 7.x | Forms | Backend-heavy form workload (settings, agent install config, redaction rules) — RHF is the lightweight, low-rerender choice, and has first-class Zod resolver support. Don't use TanStack Form for v1; smaller community and the type-inference advantage doesn't pay off when forms are sparse. [HIGH] |
| Vitest | 3.x | Unit / component tests | Already implicitly the Vite-native choice. Use Vitest Browser Mode for the few components that need real-DOM testing. [HIGH] |
| Playwright | 1.50+ | E2E tests | 80%+ market share for new projects; multi-browser; works against Cloud Run preview deploys. Don't pick Cypress. [HIGH] |

**Static-bundle deployment note**: Cloud Run + nginx is the simpler default; GCS + Cloud CDN works too. Pick one and keep the dashboard build pipeline independent of the Kotlin Gradle build (per "no unifying wrappers" principle). The CI for the FE is `npm` + Vite, not Gradle.

### 2. Replay Engine (Kotlin, in the platform process — new module)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| Ktor Client CIO (already in stack) | 3.3.3 | HTTP client for replaying captured requests to staging | Already used by the agent. Coroutine-native, supports HTTP/2, gzip. No new dependency. [HIGH] |
| Resilience4j Kotlin | 2.2.x | Rate limiting (`actual` mode ceiling), retry, circuit breaker | Resilience4j has first-class Kotlin-coroutines extensions; its `RateLimiter` uses `delay()` to suspend, integrating cleanly with the coroutine-based replayer. Token-bucket variant (`AtomicRateLimiter`) is the right primitive for the prod-rate ceiling. [HIGH] |
| kotlinx-coroutines `Semaphore` + `Channel` | bundled with Kotlin | Sequential vs concurrent dispatch | For `sequential` mode this is all you need; no library required. For `actual` mode pair it with Resilience4j `RateLimiter`. [HIGH] |

**Do not introduce a JVM HTTP load-testing library (Gatling, JMeter).** Those are CLI/scripting-oriented; replaying captured HAR-shaped requests with our own coroutine driver is more controllable and ships less code than wrapping Gatling.

### 3. Comparison / Verdict Engine (statistics, Kotlin)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| Hipparchus Core + Hipparchus Stat | 4.0.x (`org.hipparchus:hipparchus-stat:4.0.1`) | Mann-Whitney U test, linear regression, descriptive stats | Hipparchus is the actively maintained fork of Apache Commons Math (which has been stuck at 3.6.1 since 2016). Maintained by the same Orekit team; modular (you can pull just `hipparchus-stat`). Exposes `MannWhitneyUTest`, `SimpleRegression`, `OLSMultipleLinearRegression`. [HIGH] |

**Why not Apache Commons Math 3.6.1**: stale since 2016. The Hipparchus fork is by the original maintainers. Same APIs, current.

**Why not roll our own**: Mann-Whitney with tie correction and continuity correction is non-trivial; linear regression with proper standard-error estimation is non-trivial. Use a library and don't relitigate the math.

**Why not move stats to Go**: stats live on the platform side (Kotlin), not in the tap. Keep the language boundary clean.

### 4. GitHub App Integration (Kotlin)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| hub4j github-api | 1.327+ (`org.kohsuke:github-api`) | GitHub REST API, Check Runs, PR comments, App auth flow | Most mature Java/Kotlin GitHub client; specifically supports the JWT-then-installation-token App auth pattern via `GitHubBuilder().withJwtToken(...)` → `GHApp.getInstallationById(id).createToken().create()`. Note: GitHub is rolling out a new installation-token format April–June 2026; verify the library is on a current release. [HIGH] |
| Nimbus JOSE+JWT | 9.40+ (`com.nimbusds:nimbus-jose-jwt`) | RS256 JWT generation for GitHub App auth (the App-auth JWT, distinct from our agent JWT) | Already need this for GitHub App auth; broader feature set than `auth0/java-jwt` and ~2.5x the artifact adoption. Could also reuse the existing `auth0/java-jwt` 4.4.0 for symmetry — both work. Slight preference for Nimbus here because GitHub App auth often grows into OIDC/JWKS adjacent flows. [MEDIUM — either is fine] |
| BouncyCastle (already in stack) | 1.79 | PEM key loading | Already in stack for our own JWT signing; will be reused to load the GitHub App private key (PEM-only download, not JVM-native). [HIGH] |

**Why not Spotify `github-java-client`**: smaller community, fewer App-auth code paths. hub4j is the convention.
**Why not Octokit/Kiota-generated SDKs**: GitHub's new Kiota-generated SDKs ship for Go and .NET, not Java/Kotlin.

**Implementation note**: hub4j's `JWTTokenProvider` does not cache installation tokens. Add a small app-level cache keyed by `(installationId, expiry)` with refresh ~5 minutes before token expiry; this is a documented gap, not a library bug.

### 5. Slack Notifications (Kotlin)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| `com.slack.api:slack-api-client` | 1.45+ | Slack Web API client (post messages, incoming webhooks) | Part of the official `slackapi/java-slack-sdk`. v1 only needs outbound notifications, so we use the API client / webhook surface, **not** the full Bolt framework. Avoids the Slack-events-receiver complexity. [HIGH] |

**Don't use Bolt-for-Java**: it's the right tool when you need to receive Slack events / handle interactivity / slash-commands. v1 only pushes notifications; Bolt is over-budget on conceptual surface.

**Don't use `slack-webhook`-style micro libraries**: the official `slack-api-client` covers webhook posting and gives a clean migration path to the full SDK if v2 adds Slack interactivity.

### 6. JWT Key Rotation (Kotlin)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| `com.auth0:java-jwt` (already in stack) | 4.4.0 | Continue using for sign + validate; add `kid` header on issued JWTs | Already in shared `installJwtAuth()`. java-jwt does NOT do JWKS-by-`kid` lookup natively, but you can layer it: parse the header, look up the key by `kid`, hand the right `Algorithm.RSA256(...)` to `JWT.require(...)`. Multi-key validation pattern is ~30 lines, well-trodden. [HIGH] |
| `com.auth0:jwks-rsa` | 0.22.x | Optional: cached JWKS fetcher if we ever consume external JWKS endpoints | Not strictly needed for our self-signed case (we own the keys) but cheap to add if the platform later validates externally-signed tokens. [MEDIUM] |
| Google Cloud KMS (asymmetric signing key, `RSA_SIGN_PKCS1_2048_SHA256`) | google-cloud-kms 2.47+ | Move private-key custody to KMS instead of env var | KMS does NOT auto-rotate asymmetric keys (this is by design — verify-with-old-key needs to keep working). Manual version creation; the `kid` header is the key version resource name. This is the right hardening for the existing "private key in env var, no rotation" concern. [HIGH] |
| Google Cloud Secret Manager (already in stack) | 2.54+ | Fallback: continue storing PEM in Secret Manager with version aliases | If KMS asymmetric-sign latency (~10–30ms per signature) is unacceptable for our token-issuance rate, fall back to PEMs in Secret Manager and rotate via secret versions + `kid`. Secret Manager now supports version aliases (e.g. `current`, `previous`) — perfect for rotation. [HIGH] |

**Recommendation**: For v1, use **Secret Manager with version aliases + `kid` header**, not KMS. Reason: token issuance is not in the hot path of every request (agent re-uses a long-lived token; users get a token per session), but the JWKS endpoint is. KMS asymmetric-sign adds latency on the issuance path and requires keeping the public key in JWKS anyway. KMS is the right answer for v2 if a customer requires HSM-backed key custody.

### 7. Pluggable Body Storage (Kotlin — new collector subsystem)

| Library | Version | Purpose | Why Recommended |
|---------|---------|---------|-----------------|
| Existing Exposed/Postgres path | unchanged | Default backend: large bodies as `BYTEA` in Postgres, capped per row | Already works; ship the interface, keep Postgres as the default. [HIGH] |
| AWS SDK for Java v2 — `software.amazon.awssdk:s3` | 2.31+ | S3 / S3-compatible object storage backend | v2 is the current line; async client; no `software.amazon.awssdk:s3-transfer-manager` needed for our object sizes. Works against AWS S3, Cloudflare R2, Backblaze B2, Garage, SeaweedFS — the "pluggable" surface. [HIGH] |
| `google-cloud-storage` | 2.67+ | GCS backend (use S3 SDK for everything else) | First-party for our own GCP environment; idiomatic for GCS. [HIGH] |

**Do not use MinIO's Java SDK as the abstraction layer**: MinIO Community Edition's GitHub repo was archived in Feb 2026 and is read-only; the MinIO SDK is now upstream-stale relative to the AWS S3 SDK. Use AWS SDK v2 against any S3-compatible endpoint.

**Architecture**: `BodyStore` interface in `collector/`; implementations `PostgresBodyStore` (default), `S3BodyStore` (any S3-compatible), `GcsBodyStore`. Selected via env var at startup. The `captured_inputs` row stores a pointer (`storage_uri`) when the body is offloaded; null pointer = body inline in Postgres (small bodies). This keeps the wire contract unchanged.

### 8. Postgres Row-Level Security (no new library, just discipline)

No library needed. Implementation pattern:

- Flyway migration adds `ENABLE ROW LEVEL SECURITY` and policies on every multi-tenant table (`organizations`, `services`, `captured_inputs`, future `replay_runs`, etc.), scoped to `current_setting('app.current_org_id')::uuid`.
- In `HikariCP` wrap `Connection.unwrap()` or use a `ConnectionInitSql` / per-transaction `SET LOCAL app.current_org_id = '<uuid>'` issued from the Ktor `installJwtAuth()` interceptor right after JWT validation.
- **Critical**: use `SET LOCAL`, not `SET`, so it resets at transaction end (matters because HikariCP reuses connections).
- The Ktor JWT auth interceptor extracts `organizationId` and issues `SET LOCAL` before the route handler runs. Exposed's `transaction { ... }` block is the natural seam.

**Performance**: composite indexes with `organization_id` as the **leading column** on every multi-tenant table are non-negotiable. Without them, RLS policy evaluation is two orders of magnitude slower.

### 9. Header / PII Redaction (no new library)

Default-deny header allowlist + body-redaction predicates live in the Go tap (cheap to do at capture time, avoids ever putting secrets on the wire to the collector) with a second pass in the collector for defense in depth. No library — bespoke is correct here because the rules are short and the cost of a wrong abstraction is high (over-redaction destroys verdict fidelity).

### 10. Onboarding / Magic-Link or OIDC (TBD, but flagging)

PROJECT.md says self-serve onboarding in < 30 minutes from signup. The existing JWT model is service-to-service (agent → platform); user-facing auth for the dashboard is a separate question:

| Option | Recommendation |
|--------|---------------|
| Google Sign-In via OIDC (no library beyond `nimbus-jose-jwt`) | **Recommended for v1.** Design partners are engineering teams; Google SSO covers them. Add GitHub OIDC later if a partner asks. Avoids running our own password store. [HIGH] |
| Magic-link email (Postmark / Resend transactional) | Fallback for non-Google customers. Resend has a clean Java/HTTP API. [MEDIUM] |
| Auth0 / Clerk / WorkOS managed | **Don't, in v1.** Cost + dependency for what is fundamentally an OIDC redirect flow. [HIGH] |

Defer: this question is owned by the onboarding phase, not this stack research.

---

## Installation Snippets

### Frontend (new `web/` directory)

```bash
# Scaffold
npm create vite@latest web -- --template react-ts

# Core deps
npm install react@^19.2 react-dom@^19.2 \
  @tanstack/react-query@^5.100 \
  @tanstack/react-router@^1.169 \
  zod@^4 \
  react-hook-form@^7 @hookform/resolvers@^3 \
  recharts@^3 \
  clsx tailwind-merge class-variance-authority

# Tailwind 4 + shadcn (Tailwind 4 ships as a Vite plugin)
npm install -D tailwindcss@^4 @tailwindcss/vite@^4 \
  tw-animate-css

# shadcn CLI is run on demand, not installed as a dependency
npx shadcn@latest init      # CLI version pinned per-component-add

# Tests
npm install -D vitest@^3 @vitest/browser playwright@^1.50 \
  @testing-library/react @testing-library/jest-dom
```

### Backend additions (`platform/build.gradle.kts`, `collector/build.gradle.kts`)

```kotlin
// Statistics
implementation("org.hipparchus:hipparchus-core:4.0.1")
implementation("org.hipparchus:hipparchus-stat:4.0.1")

// Replay rate limiting
implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")
implementation("io.github.resilience4j:resilience4j-ratelimiter:2.2.0")
implementation("io.github.resilience4j:resilience4j-retry:2.2.0")

// GitHub App
implementation("org.kohsuke:github-api:1.327")
implementation("com.nimbusds:nimbus-jose-jwt:9.40.1")

// Slack
implementation("com.slack.api:slack-api-client:1.45.3")

// Object storage (collector only)
implementation(platform("software.amazon.awssdk:bom:2.31.10"))
implementation("software.amazon.awssdk:s3")
implementation("com.google.cloud:google-cloud-storage:2.67.0")
```

Versions should be re-verified at the moment of adding because the surrounding Kotlin/Ktor ecosystem moves; the numbers above are accurate as of 2026-05-14.

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Recharts 3 (via shadcn) | Apache ECharts | If we ever need 3D, geo-maps, or >100k point series. Not v1. |
| Recharts 3 (via shadcn) | Visx | If we need pixel-perfect custom viz. The verdict dashboard is conventional dashboard shapes; Visx is over-budget. |
| Recharts 3 (via shadcn) | Tremor | Tremor is now mostly absorbed into the shadcn/ui charts ecosystem; using Tremor directly adds a second component system on top of shadcn — don't. |
| React Hook Form | TanStack Form | Choose TanStack Form only if forms grow to deeply-nested-array-shape (multi-step config builders). Not v1. |
| TanStack Router | React Router 7 | Choose React Router if/when we add SSR (we won't in v1). |
| Zod 4 | Valibot | Valibot is ~90% smaller bundle; choose it only if the dashboard JS budget becomes critical. Not v1. |
| Hipparchus | Apache Commons Math 3.6.1 | Never. Stale since 2016. |
| AWS SDK v2 S3 | MinIO Java SDK | Never. MinIO CE archived Feb 2026. |
| AWS SDK v2 S3 | google-cloud-storage | Use GCS SDK only when targeting GCS specifically; AWS SDK v2 talks to GCS via S3-compat but with quirks. |
| Secret Manager + version aliases | GCP KMS asymmetric sign | Choose KMS when a customer requires HSM-backed key custody (CMEK adjacent). v2. |
| hub4j github-api | Spotify github-java-client | Spotify client is fine but smaller community; hub4j is the convention. |
| `slack-api-client` (push only) | Bolt-for-Java | Bolt only if/when we add Slack slash-commands or interactive responses. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Next.js (App Router) | Drags in a Node runtime, RSC mental model, server actions — we don't need any of it for an auth-gated CSR dashboard. Adds GCP runtime complexity for zero gain. | Vite + React 19 + TanStack Router (already decided in PROJECT.md). |
| React Router 7 (Remix) | Same critique as Next: SSR machinery for no upside. | TanStack Router (typed). |
| Material UI / Ant Design / Chakra | Heavy, opinionated theming, fight the brand work that PROJECT.md says is paramount. | shadcn/ui (copy-paste primitives over Radix; full theming control). |
| Apache Commons Math 3.6.1 | Last release 2016; unmaintained. | Hipparchus 4.x. |
| MinIO Java SDK | MinIO CE archived Feb 2026; SDK going stale. | AWS SDK v2 against any S3-compatible endpoint. |
| Bolt-for-Java (for v1) | Conceptual surface for events/interactivity we don't need yet. | `slack-api-client` direct. |
| Octokit-style code generators for Java | GitHub's Kiota SDKs ship for Go and .NET, not JVM. | hub4j github-api. |
| Per-request KMS asymmetric-sign for our own JWTs | ~10–30 ms latency on issuance, adds GCP API dependency to login flow. | PEMs in Secret Manager with version aliases + `kid` header. |
| `tailwindcss-animate` (Tailwind 4) | Deprecated. | `tw-animate-css` (shadcn default). |
| `cacheTime` / `useFormState` patterns | TanStack Query v5 renamed `cacheTime` → `gcTime`; React 19 renamed `useFormState` → `useActionState`. Old names = old docs. | The new names — verify any LLM-generated snippets. |
| New gonum/stat for Mann-Whitney | gonum/stat does NOT have Mann-Whitney (8+ year-old open issue). | Stats live in Kotlin (Hipparchus); the Go tap doesn't do stats. |

---

## Stack Patterns by Variant

**If the customer requires data residency in their cloud account:**
- Use the `BodyStore` interface with `S3BodyStore` pointed at the customer's S3 bucket.
- Collector metadata (URLs, headers, latencies) still lives in our hosted Postgres; only the bodies move.
- This is the explicit pluggable-storage answer in PROJECT.md.

**If the customer is a Google Workspace shop:**
- Default sign-in path is Google OIDC.
- `BodyStore` defaults to `GcsBodyStore`.

**If the dashboard needs to support a partner with restrictive CSP:**
- Vite build → static bundle → Cloud Run + nginx with strict CSP headers. No third-party JS, no inline scripts. shadcn/ui + Recharts are CSP-friendly.

---

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| React 19.2 | TanStack Query v5, TanStack Router v1, RHF 7 | All have shipped React 19 support. |
| Tailwind 4 | shadcn/ui (Tailwind v4 path), Vite plugin `@tailwindcss/vite` | shadcn/ui has explicit Tailwind 4 docs; older Tailwind 3 components need re-init via CLI. |
| Recharts 3 | shadcn/ui chart components | shadcn moved to Recharts 3 in 2025. Recharts 2 also works but ages out. |
| TanStack Query v5 | React ≥ 18.0 | Requires `useSyncExternalStore`. |
| TanStack Router v1.169 | TypeScript 5.6+ | Older TS produces wrong inference on search params. |
| Hipparchus 4.x | JDK 21 | Pure Java, no JVM gotchas. |
| Resilience4j 2.2 | Kotlin coroutines 1.9+ | Use `-kotlin` artifact for suspend-fn ergonomics. |
| AWS SDK v2 2.31+ | JDK 21, Netty 4.1.x | Apache HTTP client also available for blocking environments. |
| hub4j github-api 1.327+ | Future GitHub installation-token format | GitHub is rolling out a new installation-token format April–June 2026; verify hub4j release notes at integration time. |
| Ktor 3.4.0 | OpenAPI plugin requires extra `ktor-server-routing-openapi` dep | Known bug; fixed in 3.4.1. Pin to 3.3.3 (current) or wait for 3.4.1 if OpenAPI generation is wanted. |

---

## Sources

### High-confidence (official docs / changelogs / GitHub releases)

- React releases — https://github.com/facebook/react/releases — verified React 19.2.x is current minor; 19.2.0 released Oct 2025
- TanStack Query npm — https://www.npmjs.com/package/@tanstack/react-query — current `5.100.x`
- TanStack Router npm — https://www.npmjs.com/package/@tanstack/react-router — current `1.169.x`
- Tailwind CSS v4 docs (shadcn) — https://ui.shadcn.com/docs/tailwind-v4 — confirms `data-slot`, `tw-animate-css`, OKLCH
- shadcn/ui charts docs — https://ui.shadcn.com/docs/components/radix/chart — confirms Recharts 3 integration
- Hipparchus Math — https://www.hipparchus.org/ + https://github.com/Hipparchus-Math/hipparchus — confirms active maintenance, Commons Math fork lineage
- Resilience4j Kotlin coroutines docs — https://resilience4j.readme.io/docs/getting-started-4 — confirms suspend-fn integration, token-bucket variants
- hub4j github-api JWT auth — https://github-api.kohsuke.org/githubappjwtauth.html — confirms JWT → installation token flow; need BouncyCastle for PEM
- GitHub installation-token format change — https://github.blog/changelog/2026-04-24-notice-about-upcoming-new-format-for-github-app-installation-tokens/
- slackapi/java-slack-sdk — https://github.com/slackapi/java-slack-sdk — confirms `slack-api-client` is the official lightweight surface; Bolt is the heavyweight framework
- GCP KMS rotation docs — https://cloud.google.com/kms/docs/key-rotation — confirms asymmetric keys do NOT auto-rotate
- GCP Secret Manager aliases — https://docs.cloud.google.com/secret-manager/docs/assign-alias-to-secret-version — confirms version aliases are GA
- Auth0 navigating RS256 and JWKS — https://auth0.com/blog/navigating-rs256-and-jwks/ — confirms `kid` lookup pattern
- MinIO archive status — https://en.wikipedia.org/wiki/MinIO + community discussion — confirms CE archived Feb 2026

### Medium-confidence (reviewed comparisons, vendor-adjacent content)

- TanStack Form vs RHF in 2026 — https://blog.logrocket.com/tanstack-form-vs-react-hook-form/ — used for the "stick with RHF for v1" call
- Zod 4 vs Valibot vs ArkType — https://dev.to/gabrielanhaia/zod-4-vs-valibot-vs-arktype-a-type-system-teardown-4lha — used for "Zod 4 is fine, defer Valibot" call
- Vegeta vs k6 — https://github.com/tsenart/vegeta — used to confirm the "don't pull in a load-testing lib; coroutines + Resilience4j is correct" call
- Postgres RLS multi-tenant pattern — https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/ — confirms `SET LOCAL` + `current_setting()` pattern
- nimbus-jose-jwt vs jose4j vs java-jwt — https://medium.com/naukri-engineering/generating-jwt-nimbusds-vs-jose4j-c34e881dbbe0 — used to compare ecosystem reach

### Low-confidence (worth verifying at integration time)

- Exact Hipparchus 4.0.1 version: verify on Maven Central at integration time
- hub4j github-api 1.327 specific version: verify against the April–June 2026 installation-token rollout

---

*Stack research for: hosted B2B SaaS validation platform v1 — net-new capability areas only*
*Researched: 2026-05-14*
