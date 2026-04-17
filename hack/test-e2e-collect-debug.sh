#!/usr/bin/env bash
# test-e2e-collect-debug.sh — Collect logs and state from the Kind cluster for debugging.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[debug]${NC} $*"; }
error() { echo -e "${RED}[debug]${NC} $*"; exit 1; }

OUTPUT_DIR="${1:-./e2e-debug-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "${OUTPUT_DIR}"

info "Collecting debug info to ${OUTPUT_DIR}..."

# ── Cluster info ───────────────────────────────
kubectl cluster-info > "${OUTPUT_DIR}/cluster-info.txt" 2>&1 || true
kubectl get nodes -o wide > "${OUTPUT_DIR}/nodes.txt" 2>&1 || true

# ── All pods ───────────────────────────────────
kubectl get pods -A -o wide > "${OUTPUT_DIR}/all-pods.txt" 2>&1 || true
kubectl get events -A --sort-by='.lastTimestamp' > "${OUTPUT_DIR}/all-events.txt" 2>&1 || true

# ── Fluss ──────────────────────────────────────
kubectl get pods -l app.kubernetes.io/name=fluss -o wide > "${OUTPUT_DIR}/fluss-pods.txt" 2>&1 || true
kubectl logs -l app.kubernetes.io/component=coordinator --tail=100 > "${OUTPUT_DIR}/fluss-coordinator.log" 2>&1 || true
kubectl logs -l app.kubernetes.io/component=tablet --tail=100 > "${OUTPUT_DIR}/fluss-tablet.log" 2>&1 || true

# ── Controller ─────────────────────────────────
kubectl get pods -n knative-fluss-broker -o wide > "${OUTPUT_DIR}/controller-pods.txt" 2>&1 || true
kubectl logs -n knative-fluss-broker -l app.kubernetes.io/component=controller --tail=200 > "${OUTPUT_DIR}/controller.log" 2>&1 || true

# ── Knative ────────────────────────────────────
kubectl get pods -n knative-eventing -o wide > "${OUTPUT_DIR}/knative-pods.txt" 2>&1 || true
kubectl logs -n knative-eventing -l app=eventing-controller --tail=100 > "${OUTPUT_DIR}/knative-controller.log" 2>&1 || true

# ── Test namespace ─────────────────────────────
kubectl get all -n e2e-test > "${OUTPUT_DIR}/e2e-test-resources.txt" 2>&1 || true
kubectl get brokers,triggers -n e2e-test -o yaml > "${OUTPUT_DIR}/e2e-test-crds.yaml" 2>&1 || true
kubectl logs -n e2e-test -l app=e2e-sink --tail=50 > "${OUTPUT_DIR}/e2e-sink.log" 2>&1 || true

# ── Describe failing pods ─────────────────────
kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded -o name 2>/dev/null | while read -r pod; do
    safe_name=$(echo "$pod" | tr '/' '_' | tr ' ' '_')
    kubectl describe "$pod" > "${OUTPUT_DIR}/describe_${safe_name}.txt" 2>&1 || true
done

info "Debug info collected to ${OUTPUT_DIR}"
ls -la "${OUTPUT_DIR}"
