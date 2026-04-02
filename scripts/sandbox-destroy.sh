#!/usr/bin/env bash
set -euo pipefail

# Completely delete the sandbox cluster and all resources.
# This stops ALL charges (including disk storage).

CLUSTER_NAME="${CLUSTER_NAME:-validation-sandbox}"
ZONE="${ZONE:-us-central1-a}"
PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"

if [[ -z "$PROJECT" ]]; then
  echo "Error: No GCP project set. Run: gcloud config set project YOUR_PROJECT"
  exit 1
fi

echo "This will permanently delete cluster '$CLUSTER_NAME' and all its resources."
read -p "Are you sure? [y/N] " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 0
fi

echo "Deleting cluster..."
gcloud container clusters delete "$CLUSTER_NAME" \
  --zone "$ZONE" \
  --project "$PROJECT" \
  --quiet

# Clean up container images from Artifact Registry
echo "Cleaning up container images..."
IMAGES=("test-api-gateway" "test-order-service" "test-notification-service" "test-webhook-stub" "test-traffic-generator")
for img in "${IMAGES[@]}"; do
  gcloud artifacts docker images delete "us-docker.pkg.dev/$PROJECT/gcr.io/$img" --quiet --delete-tags 2>/dev/null || true
done

echo ""
echo "Sandbox destroyed. All charges stopped."