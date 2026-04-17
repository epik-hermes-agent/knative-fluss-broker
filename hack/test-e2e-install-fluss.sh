#!/usr/bin/env bash
# test-e2e-install-fluss.sh — Install ZooKeeper + Fluss into the Kind cluster.
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

# ── Fluss (from Apache dist tarball) ──────────────
info "Adding Fluss Helm repo..."
HELM_DIR="$PROJECT_DIR/helm-fluss"
if [ ! -d "$HELM_DIR" ]; then
    mkdir -p "$HELM_DIR"
    curl -fsSL "https://downloads.apache.org/incubator/fluss/helm-chart/fluss-0.9.0-incubating.tgz" -o /tmp/fluss-helm.tgz
    tar xzf /tmp/fluss-helm.tgz -C "$HELM_DIR"
    rm /tmp/fluss-helm.tgz
fi

FLUSS_CHART="$HELM_DIR/fluss"
if [ ! -d "$FLUSS_CHART" ]; then
    # Some tarballs extract with a different structure
    FLUSS_CHART="$HELM_DIR"
fi

info "Installing Fluss from chart at $FLUSS_CHART..."
helm upgrade --install fluss "$FLUSS_CHART" \
    --set image.tag=0.9.0-incubating \
    --set image.repository=apache/fluss \
    --set coordinatorServer.replicaCount=1 \
    --set tabletServer.replicaCount=1 \
    --set zookeeper.enabled=false \
    --set configurationOverrides."zookeeper\.address"=fluss-zk:2181 \
    --timeout 180s

# Wait for pods
info "Waiting for Fluss pods..."
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=fluss --timeout=180s 2>/dev/null || \
kubectl wait --for=condition=ready pod -l app=fluss --timeout=180s 2>/dev/null || \
info "Pods may take additional time to become ready"

kubectl get pods | grep fluss
info "Fluss installed"
