#!/usr/bin/env bash
set -euo pipefail

# Scale the node pool to 0 — stops compute charges while keeping the cluster.
# The free-tier zonal control plane stays up at no cost.

CLUSTER_NAME="${CLUSTER_NAME:-validation-sandbox}"
ZONE="${ZONE:-us-central1-a}"
PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"

if [[ -z "$PROJECT" ]]; then
  echo "Error: No GCP project set. Run: gcloud config set project YOUR_PROJECT"
  exit 1
fi

echo "Scaling $CLUSTER_NAME node pool to 0..."
gcloud container clusters resize "$CLUSTER_NAME" \
  --node-pool default-pool \
  --num-nodes 0 \
  --zone "$ZONE" \
  --project "$PROJECT" \
  --quiet

echo ""
echo "Sandbox paused. Compute charges stopped."
echo "Residual cost: ~\$2/month for persistent disks."
echo ""
echo "To resume: ./scripts/sandbox-up.sh"