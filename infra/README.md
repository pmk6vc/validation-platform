# Validation Platform — GCP Infrastructure

Terraform code for deploying the validation platform on GCP.

## Stacks

| Stack | Directory | Cost | Purpose |
|-------|-----------|------|---------|
| **platform** | `infra/platform/` | ~$13/mo running, ~$2.50/mo with Cloud SQL paused | Platform + Collector Cloud Run, Cloud SQL, Artifact Registry, Secrets, IAM |
| **sandbox** | `infra/sandbox/` | ~$80/mo when running | GKE Standard cluster for test microservices |

The stacks are intentionally independent — sandbox can be destroyed and recreated without touching the platform stack.

## Prerequisites

- `gcloud` CLI authenticated: `gcloud auth application-default login`
- `terraform` >= 1.5 installed
- Billing enabled on project `zugzwang-381922`

## Bootstrap Procedure

### Step 1 — Enable required GCP APIs

```bash
gcloud services enable \
  compute.googleapis.com \
  container.googleapis.com \
  sqladmin.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  run.googleapis.com \
  dns.googleapis.com \
  --project=zugzwang-381922
```

The `apis.tf` in the platform stack also manages these via `google_project_service`, but enabling them here first avoids a chicken-and-egg problem during the first `terraform apply`.

### Step 2 — Create Terraform state buckets

```bash
chmod +x infra/bootstrap/create-state-buckets.sh
./infra/bootstrap/create-state-buckets.sh
```

This creates two versioned GCS buckets:
- `gs://zugzwang-381922-terraform-state-platform`
- `gs://zugzwang-381922-terraform-state-sandbox`

The script is idempotent; running it again when the buckets already exist is a no-op.

### Step 3 — Apply the platform stack

The platform stack requires the platform and collector container images. For the very first apply (before any images exist), point at the Cloud Run hello placeholder:

```bash
cd infra/platform
terraform init
terraform apply \
  -var="platform_image=us-docker.pkg.dev/cloudrun/container/hello" \
  -var="collector_image=us-docker.pkg.dev/cloudrun/container/hello"
```

Subsequent applies (after CI builds real images) pass the real image tags — see "Deploying New Images" below.

### Step 4 — Populate Secret Manager values

Terraform creates the secret resources but does **not** set secret values. Populate them manually after `terraform apply`:

```bash
# Database password
echo -n "your-strong-db-password" | gcloud secrets versions add validation-db-password \
  --project=zugzwang-381922 --data-file=-

# JWT private key (RSA PEM — generate with platform's JwtTokenGenerator or openssl)
gcloud secrets versions add validation-jwt-private-key \
  --project=zugzwang-381922 --data-file=path/to/private_key.pem
```

Secret values are never stored in Terraform state. Rotation is handled by adding a new secret version — Cloud Run picks up `latest` automatically on next deployment.

### Step 5 — Apply the sandbox stack (optional)

The sandbox cluster costs ~$80/mo when running. Only apply when you need it.

```bash
cd infra/sandbox
terraform init
terraform apply
```

To destroy the sandbox when not in use:

```bash
cd infra/sandbox
terraform destroy
```

## Cost Controls

### Pause Cloud SQL

When not actively developing, pause Cloud SQL to reduce cost to ~$2.50/mo (storage only):

```bash
cd infra/platform
terraform apply -var="cloudsql_active=false"
```

Resume it:

```bash
terraform apply -var="cloudsql_active=true"
```

### Scale to zero

Cloud Run services automatically scale to zero when there is no traffic. No action required.

## Deploying New Images

After building and pushing to Artifact Registry:

```bash
REGISTRY="us-central1-docker.pkg.dev/zugzwang-381922/validation"

# Push images
docker push ${REGISTRY}/platform:latest
docker push ${REGISTRY}/collector:latest

# Update Cloud Run (or use terraform apply with updated variables)
gcloud run services update validation-platform \
  --image=${REGISTRY}/platform:latest \
  --region=us-central1

gcloud run services update validation-collector \
  --image=${REGISTRY}/collector:latest \
  --region=us-central1
```

Alternatively, pass the new images as Terraform variables:

```bash
cd infra/platform
terraform apply \
  -var="platform_image=us-central1-docker.pkg.dev/zugzwang-381922/validation/platform:latest" \
  -var="collector_image=us-central1-docker.pkg.dev/zugzwang-381922/validation/collector:latest"
```

## Directory Layout

```
infra/
  bootstrap/
    create-state-buckets.sh   # one-time GCS bucket creation
  platform/
    main.tf                   # Terraform + provider config
    backend.tf                # GCS backend
    variables.tf              # project, region, cloudsql_active, images (required)
    outputs.tf                # connection name, registry URL, service URLs, SA emails
    apis.tf                   # google_project_service (all required APIs)
    cloudsql.tf               # PostgreSQL 16, db-f1-micro
    registry.tf               # Artifact Registry "validation" repo
    secrets.tf                # Secret Manager resources (no values)
    iam.tf                    # validation-platform-sa, validation-sandbox-sa
    cloudrun.tf               # Cloud Run: validation-platform, validation-collector
  sandbox/
    main.tf                   # Terraform + provider config
    backend.tf                # GCS backend
    variables.tf              # project, region, node_count
    outputs.tf                # cluster name, location, endpoint
    cluster.tf                # GKE Standard, spot e2-small node pool, Workload Identity
  README.md                   # this file
```
