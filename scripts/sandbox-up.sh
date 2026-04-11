#!/usr/bin/env bash
set -euo pipefail

# Configuration — override via environment variables
CLUSTER_NAME="${CLUSTER_NAME:-validation-sandbox}"
ZONE="${ZONE:-us-central1-a}"
PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-standard-4}"
NODE_COUNT="${NODE_COUNT:-1}"
DISK_SIZE="${DISK_SIZE:-50}"

if [[ -z "$PROJECT" ]]; then
  echo "Error: No GCP project set. Run: gcloud config set project YOUR_PROJECT"
  exit 1
fi

echo "Project:  $PROJECT"
echo "Cluster:  $CLUSTER_NAME"
echo "Zone:     $ZONE"
echo "Machine:  $MACHINE_TYPE"
echo "Nodes:    $NODE_COUNT"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Cluster ---

if gcloud container clusters describe "$CLUSTER_NAME" --zone "$ZONE" --project "$PROJECT" &>/dev/null; then
  echo "Cluster exists. Checking node pool size..."

  CURRENT_SIZE=$(gcloud container clusters describe "$CLUSTER_NAME" \
    --zone "$ZONE" --project "$PROJECT" \
    --format="value(currentNodeCount)" 2>/dev/null || echo "0")

  if [[ "$CURRENT_SIZE" == "0" || -z "$CURRENT_SIZE" ]]; then
    echo "Scaling node pool from 0 to $NODE_COUNT..."
    gcloud container clusters resize "$CLUSTER_NAME" \
      --node-pool default-pool \
      --num-nodes "$NODE_COUNT" \
      --zone "$ZONE" \
      --project "$PROJECT" \
      --quiet
    echo "Node pool scaled up."
  else
    echo "Node pool already has $CURRENT_SIZE node(s)."
  fi
else
  echo "Creating cluster..."
  gcloud container clusters create "$CLUSTER_NAME" \
    --zone "$ZONE" \
    --project "$PROJECT" \
    --machine-type "$MACHINE_TYPE" \
    --num-nodes "$NODE_COUNT" \
    --disk-size "$DISK_SIZE" \
    --spot \
    --no-enable-autoupgrade \
    --no-enable-autorepair
  echo "Cluster created."
fi

# Get credentials
gcloud container clusters get-credentials "$CLUSTER_NAME" \
  --zone "$ZONE" --project "$PROJECT"

# --- Images ---

echo "Building test service images (amd64 for GKE)..."
cd "$PROJECT_ROOT"
./gradlew testServicesBuild -Djib.arch=amd64

echo "Building agent image (amd64 for GKE)..."
./gradlew :agent:jibDockerBuild -Djib.arch=amd64

IMAGES=("test-api-gateway" "test-order-service" "test-notification-service" "test-webhook-stub" "test-traffic-generator" "validation-agent")
REGISTRY="gcr.io/$PROJECT"

echo "Pushing images to $REGISTRY..."
for img in "${IMAGES[@]}"; do
  docker tag "$img:latest" "$REGISTRY/$img:latest"
  docker push "$REGISTRY/$img:latest"
done

# --- Deploy ---

echo "Deploying test services via kustomize..."
kubectl kustomize "$PROJECT_ROOT/k8s/test-services/overlays/gke" \
  | sed "s/GCP_PROJECT/$PROJECT/g" \
  | kubectl apply -f -

echo "Waiting for deployments..."
kubectl wait --for=condition=available deployment --all -n infrastructure --timeout=180s 2>/dev/null || true
kubectl wait --for=condition=available deployment --all -n external --timeout=60s 2>/dev/null || true
kubectl wait --for=condition=available deployment --all -n production --timeout=180s 2>/dev/null || true

# Deploy the validation agent.
# k8s/agent/agent.yaml is minikube-oriented (imagePullPolicy: Never, unqualified image).
# Rewrite the image reference to the GCR-pushed image and drop the Never pull policy.
echo "Deploying validation agent..."
sed -e "s|image: validation-agent:latest|image: $REGISTRY/validation-agent:latest|" \
    -e "/imagePullPolicy: Never/d" \
    "$PROJECT_ROOT/k8s/agent/agent.yaml" \
  | kubectl apply -f -

kubectl wait --for=condition=available deployment --all -n validation --timeout=120s 2>/dev/null || true

echo ""
echo "=== Sandbox is ready ==="
kubectl get pods -A --no-headers | grep -v kube-system
echo ""
echo "To access the API Gateway:"
echo "  kubectl port-forward -n production svc/api-gateway 8080:8080"
echo "  curl http://localhost:8080/api/health"
echo ""
echo "The validation-agent is deployed but requires Kubeshark to be running"
echo "in the same cluster. Start it in a separate terminal with:"
echo "  kubeshark tap"
echo "(The agent expects kubeshark-front in the 'default' namespace.)"
echo ""
echo "To shut down (stop paying): ./scripts/sandbox-down.sh"