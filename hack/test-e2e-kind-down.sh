#!/usr/bin/env bash
# test-e2e-kind-down.sh — Delete the Kind cluster used for K8s e2e testing.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[kind-down]${NC} $*"; }
error() { echo -e "${RED}[kind-down]${NC} $*"; exit 1; }

CLUSTER_NAME="knative-fluss-broker"

for cmd in kind; do
    if ! command -v "$cmd" &>/dev/null; then
        error "$cmd is required but not installed"
    fi
done

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    info "Deleting Kind cluster '${CLUSTER_NAME}'..."
    kind delete cluster --name "${CLUSTER_NAME}"
    info "Cluster deleted"
else
    info "Cluster '${CLUSTER_NAME}' does not exist, nothing to do"
fi
