#!/usr/bin/env bash
# test-e2e-kind-up.sh — Create or reuse a Kind cluster for K8s e2e testing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[kind-up]${NC} $*"; }
warn()  { echo -e "${YELLOW}[kind-up]${NC} $*"; }
error() { echo -e "${RED}[kind-up]${NC} $*"; exit 1; }

CLUSTER_NAME="knative-fluss-broker"

# Check prerequisites
for cmd in kind kubectl docker; do
    if ! command -v "$cmd" &>/dev/null; then
        error "$cmd is required but not installed"
    fi
done

# Check if cluster already exists and is accessible
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    if kubectl cluster-info --context "kind-${CLUSTER_NAME}" &>/dev/null; then
        info "Cluster '${CLUSTER_NAME}' already exists and is accessible"
        exit 0
    else
        warn "Cluster '${CLUSTER_NAME}' exists but is not accessible. Deleting..."
        kind delete cluster --name "${CLUSTER_NAME}"
    fi
fi

info "Creating Kind cluster '${CLUSTER_NAME}'..."
kind create cluster --name "${CLUSTER_NAME}" --config "${SCRIPT_DIR}/kind-config.yaml" --wait 120s

info "Verifying cluster..."
kubectl cluster-info --context "kind-${CLUSTER_NAME}"
kubectl get nodes

info "Kind cluster ready"
