#!/usr/bin/env bash
# test-e2e-install-fluss.sh — Install ZooKeeper + Fluss into the Kind cluster.
# Uses local Helm chart from /Users/pasha/hermes/fluss-main/helm/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[fluss]${NC} $*"; }
error() { echo -e "${RED}[fluss]${NC} $*"; exit 1; }

for cmd in helm kubectl; do
    if ! command -v "$cmd" &>/dev/null; then
        error "$cmd is required but not installed"
    fi
done

# ── ZooKeeper (direct Deployment — no Helm, standard image) ──
info "Deploying ZooKeeper..."
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: fluss-zk
  namespace: default
spec:
  ports:
    - port: 2181
      targetPort: 2181
  selector:
    app: fluss-zk
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fluss-zk
  namespace: default
spec:
  replicas: 1
  selector:
    matchLabels:
      app: fluss-zk
  template:
    metadata:
      labels:
        app: fluss-zk
    spec:
      containers:
        - name: zookeeper
          image: zookeeper:3.9
          ports:
            - containerPort: 2181
          env:
            - name: ZOOKEEPER_CLIENT_PORT
              value: "2181"
EOF

kubectl rollout status deployment/fluss-zk --timeout=120s

# ── Fluss (from local Helm chart at fluss-main/helm/) ──
FLUSS_CHART="/Users/pasha/hermes/fluss-main/helm"

if [ ! -d "$FLUSS_CHART" ]; then
    error "Local Helm chart not found at $FLUSS_CHART. Build Fluss first."
fi

info "Installing Fluss from local chart at $FLUSS_CHART..."
helm upgrade --install fluss "$FLUSS_CHART" \
    -f "$PROJECT_DIR/hack/fluss-values.yaml" \
    --timeout 180s

# Wait for pods
info "Waiting for Fluss pods..."
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=fluss --timeout=180s 2>/dev/null || \
kubectl wait --for=condition=ready pod -l app=fluss --timeout=180s 2>/dev/null || \
info "Pods may take additional time to become ready"

kubectl get pods | grep fluss
info "Fluss installed"
