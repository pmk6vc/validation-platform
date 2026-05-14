# External Integrations

**Analysis Date:** 2026-05-14

## APIs & External Services

**Kubeshark Traffic Capture (Agent only):**
- Service: Kubeshark v53+ WebSocket API
  - Endpoint: `/api/wsFull` (persistent WebSocket)
  - Protocol: KFL (Kubeshark Filter Language) server-side filtering + HAR-ish JSON responses
  - Used by: Agent Loop 3 (traffic capture via `KubesharkClient` in `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt`)
  - Response format: `KubesharkEntry` wire-format (request body at `request.postData.text`, response body at `response.content.text` with base64 encoding when `content.encoding == "base64"`)
  - Connection model: Persistent session with reconnect dedup (5-second lookback window); bounded channel (capacity 1000) buffers entries with TCP backpressure

**Kubernetes API (Agent service discovery):**
- SDK: Fabric8 Kubernetes Client 6.10.0
  - Used by: Agent Loop 1 (K8s service discovery via `K8sServiceDiscovery` in `agent/src/main/kotlin/com/platform/agent/K8sServiceDiscovery.kt`)
  - Operations: List K8s Service resources, watch for changes
  - Authentication: In-cluster service account (RBAC via `k8s/agent/overlays/sandbox/rbac.yaml`)

**Platform Internal APIs:**
- Platform server (port 8080):
  - `POST /api/services` - Agent registers discovered services (Loop 1)
  - `GET /api/agent/config` - Agent polls dynamic config (sampling rate, namespace filters, target services)
  - `GET /.well-known/jwks.json` - Public RSA key for JWT validation
  - Authentication: RS256 JWT bearer token in `Authorization` header

- Collector server (port 8081):
  - `POST /api/captured-inputs` - Agent pushes captured HTTP traffic (Loop 3)
  - `GET /api/captured-inputs` - Query captured inputs
  - `DELETE /api/captured-inputs?serviceId={id}` - Purge inputs for a service
  - Authentication: RS256 JWT bearer token

## Data Storage

**Databases:**
- PostgreSQL 16
  - Primary: Cloud SQL (`validation-postgres` instance in GCP, us-central1, `db-f1-micro` tier)
  - Local: Docker image `postgres:16-alpine` via Docker Compose (`deploy/docker-compose.yaml`)
  - Connection: 
    - Cloud Run: IAM-authenticated via cloud-sql-jdbc-socket-factory (service account as Postgres user type `CLOUD_IAM_SERVICE_ACCOUNT`)
    - Local/Docker: Basic auth (username/password via environment variables)
  - Client: Exposed ORM 0.57.0 + JDBC driver
  - Database: `validation`
  - Migrations: Flyway 9.22.3 (V0001–V0007 in `shared/src/main/resources/db/migration/`)
  - Tables owned by modules:
    - Platform: `organizations`, `services` (via `platform/src/main/kotlin/com/platform/database/`)
    - Collector: `captured_inputs` (via `collector/src/main/kotlin/com/platform/collector/database/`)

**File Storage:**
- None - local file storage not used; all data persists in PostgreSQL

**Caching:**
- None - in-memory only (agent config stored in `MutableStateFlow<DynamicConfig>` for reactive updates)

## Authentication & Identity

**Auth Provider:**
- Custom RS256 JWT (self-signed)
  - Implementation: `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt`
  - Token generation: `JwtTokenGenerator` (accessed via `./gradlew :platform:generateToken`)
  - Algorithm: RS256 (RSA with SHA-256)
  - Signing: Private key stored in `JWT_PRIVATE_KEY` environment variable (PEM-encoded)
  - Validation: Both platform and collector call `installJwtAuth()` from shared library; validates token signature and extracts claims
  - Required claims: `organizationId` (UUID), `cluster` (string)
  - Optional claims: `role` (string)
  - Token delivery: `Authorization: Bearer <token>` header on all `/api/*` routes
  - Public key endpoint: `GET /.well-known/jwks.json` (returns RSA public key in JWK format, derives from private key)

**Agent Authentication (In-Cluster):**
- Kubernetes Service Account: `vp-tap` (for TAP/Pod Informer) and implicit default account
- Secret management: API key (JWT) stored in Kubernetes Secret `platform-api-key/jwt-token`
- Read at startup: Agent reads from `secretKeyRef` in Pod spec (no plaintext in env)

## Monitoring & Observability

**Error Tracking:**
- None detected - no Sentry, Rollbar, or similar integration

**Logs:**
- Logback 1.5.26 + logstash-logback-encoder 8.1
  - Format: JSON (via `LogstashEncoder`) for structured log aggregation
  - Output: stdout (Cloud Run logs to Cloud Logging automatically)
  - No persistent log storage configuration in code (GCP Cloud Logging handles retention)

**Metrics:**
- None - no Prometheus, Datadog, or CloudMonitoring instrumentation in codebase
- Future: K8s Metrics API observation planned for replay engine (CPU/memory during validation runs)

**Tracing:**
- None - no OpenTelemetry or distributed tracing integration detected

## CI/CD & Deployment

**Hosting:**
- Cloud Run (platform + collector microservices, `validation-platform` and `validation-collector` services)
- GKE (agent + vp-tap DaemonSet, test services for staging validation)
- Local: Docker Compose (via `./gradlew dockerUp`)

**CI Pipeline:**
- Not visible in repo (configured separately in CI/CD system, likely Cloud Build or GitHub Actions)
- Artifact Registry: Images pushed with SHA tags after build (`scripts/platform-up.sh` resolves `latest_image`)

**Deployment Mechanism:**
- Terraform 1.x (infrastructure provisioning)
  - Platform config: `infra/platform/` (Cloud SQL, Cloud Run, Artifact Registry, IAM, VPC)
  - Modules:
    - `cloudsql.tf` - PostgreSQL 16 instance (activation policy controlled by `var.cloudsql_active`)
    - `cloudrun.tf` - Service definitions (platform on 8080, collector on 8081)
    - `iam.tf` - Service accounts and IAM bindings (Workload Identity for Cloud SQL)
    - `secrets.tf` - Cloud Secret Manager secrets (JWT_PRIVATE_KEY)
    - `registry.tf` - Artifact Registry
    - `wif.tf` - Workload Identity Federation setup
- Kubernetes manifests: Kustomize + kubectl
  - Platform: `k8s/platform/` (base Kubernetes manifests for local testing)
  - Agent: `k8s/agent/base/` (base) + `k8s/agent/overlays/sandbox/` (GKE overlay)
  - TAP: `k8s/tap/` (DaemonSet + RBAC)
  - Test services: `k8s/test-services/` (base + GKE overlay)

**Scripts:**
- `scripts/platform-up.sh` - Bring Cloud Run stack up (Terraform apply with CloudSQL active=true)
- `scripts/platform-down.sh` - Pause Cloud SQL (activation_policy=NEVER, saves costs)
- `scripts/platform-delete.sh` - Destroy entire GCP stack (Terraform destroy)
- `scripts/bootstrap-db.sh` - One-time DB setup (grants public schema ownership to service account)
- `scripts/bootstrap.sh` - Unified bootstrap (platform-up + bootstrap-db)
- `scripts/seed-org.sh` - Create initial organization + JWT for testing
- `scripts/sandbox-up.sh` - Full GKE sandbox: cluster + test services + agent + Kubeshark
- `scripts/sandbox-down.sh` - Tear down sandbox

## Environment Configuration

**Required Environment Variables (Production - Cloud Run):**
- `SECRETS_PROVIDER=gcp` - Enable Cloud Secret Manager integration
- `DATABASE_URL` - Cloud SQL JDBC URL with socket factory and IAM auth
- `DATABASE_USER` - Service account email (IAM user)
- `DATABASE_AUTH_MODE=iam` - Use IAM authentication (no password)
- `PORT` - Set by Cloud Run from container port (8080/8081)
- Secret Manager resource names (via Cloud Run UI/Terraform):
  - `JWT_PRIVATE_KEY` - RSA private key resource name in Cloud Secret Manager

**Required Environment Variables (Local/Docker):**
- `POSTGRES_DB` - Database name
- `POSTGRES_USER` - Postgres username
- `POSTGRES_PASSWORD` - Postgres password
- `JWT_PRIVATE_KEY` - PEM-encoded RSA private key
- `DATABASE_URL` - JDBC URL pointing to local Postgres
- `DATABASE_USER` - Postgres username
- `DATABASE_PASSWORD` - Postgres password

**Agent Environment Variables (Kubernetes):**
- `PLATFORM_URL` - Platform server URL (default: `http://platform.validation.svc.cluster.local:8080`)
- `COLLECTOR_URL` - Collector server URL (default: falls back to `PLATFORM_URL`)
- `API_KEY` - JWT bearer token (injected from Secret `platform-api-key/jwt-token`)
- `KUBESHARK_URL` - Kubeshark WebSocket endpoint (default: `http://kubeshark-front.default:80`)
- `NODE_NAME` - Kubernetes downward API (agent Pod Informer uses this to scope watches)

**Secrets Location:**
- Development: `.env` file (Git-ignored, not committed)
- Production (Cloud Run): Cloud Secret Manager
  - Secret: `jwt-private-key` (resource name: `projects/{project}/secrets/{secret_id}/versions/latest`)
- Kubernetes (agent): Secret `platform-api-key` in `validation` namespace
  - Key: `jwt-token` (contains the full Bearer JWT)

## Webhooks & Callbacks

**Incoming:**
- None configured - platform does not expose webhook receivers

**Outgoing:**
- None configured - platform does not currently trigger external webhooks (planned for future GitHub/GitLab PR integration)

## External Service Dependencies (Testing)

**Test Workloads (k3s Integration Tests):**
- Namespaces: `infrastructure`, `production`, `external`
- Services (deployed via `k8s/test-services/base/`):
  - Infrastructure: `orders-db` (PostgreSQL 16), `redis` (7-alpine), `kafka` (apache/kafka:3.7.0, KRaft mode)
  - Production: `api-gateway`, `order-service`, `notification-service`, `traffic-generator`
  - External: `webhook-stub`
- Purpose: Validate agent discovery and traffic capture against realistic microservice topology
- Deployment: Kustomize + kubectl (local k3s via TestContainers)

---

*Integration audit: 2026-05-14*
