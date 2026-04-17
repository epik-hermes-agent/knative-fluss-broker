#!/usr/bin/env bash
# test-e2e-install-knative.sh — Install Knative Eventing on the Kind cluster.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[knative]${NC} $*"; }
error() { echo -e "${RED}[knative]${NC} $*"; exit 1; }

KNATIVE_VERSION="knative-v1.20.3"

for cmd in kubectl; do
    if ! command -v "$cmd" &>/dev/null; then
        error "$cmd is required but not installed"
    fi
done

# Check if Knative is already installed
if kubectl get namespace knative-eventing &>/dev/null; then
    info "Knative Eventing already installed, skipping"
    exit 0
fi

info "Installing Knative Eventing ${KNATIVE_VERSION}..."

# Install Knative Eventing CRDs
kubectl apply -f "https://github.com/knative/eventing/releases/download/${KNATIVE_VERSION}/eventing-crds.yaml"

# Install Knative Eventing core
kubectl apply -f "https://github.com/knative/eventing/releases/download/${KNATIVE_VERSION}/eventing-core.yaml"

# Wait for Knative Eventing pods to be ready
info "Waiting for Knative Eventing pods..."
kubectl wait --for=condition=Ready pods --all -n knative-eventing --timeout=300s

# Install the InMemoryChannel (for local testing)
info "Installing InMemoryChannel..."
kubectl apply -f "https://github.com/knative/eventing/releases/download/${KNATIVE_VERSION}/in-memory-channel.yaml"

kubectl wait --for=condition=Ready pods --all -n knative-eventing --timeout=120s

info "Knative Eventing ${KNATIVE_VERSION} installed successfully"
kubectl get pods -n knative-eventing
