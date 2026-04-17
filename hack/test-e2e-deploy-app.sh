#!/usr/bin/env bash
# test-e2e-deploy-app.sh — Build and deploy the controller.
# Supports two modes:
#   - In-cluster (Docker image + Kind load) — default for CI
#   - Local process   (runs on host)         — fallback when Docker Hub is slow
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[deploy]${NC} $*"; }
warn()  { echo -e "${YELLOW}[deploy]${NC} $*"; }
error() { echo -e "${RED}[deploy]${NC} $*"; exit 1; }

IMAGE_NAME="fluss-broker-controller"
IMAGE_TAG="dev"
CLUSTER_NAME="knative-fluss-broker"
CONTROLLER_PID_FILE="/tmp/fluss-controller.pid"

# macOS-compatible timeout
docker_build_with_timeout() {
    local timeout_secs=$1
    shift
    "$@" &
    local pid=$!
    ( sleep "$timeout_secs" && kill "$pid" 2>/dev/null ) &
    local watchdog=$!
    if wait "$pid" 2>/dev/null; then
        kill "$watchdog" 2>/dev/null || true
        return 0
    else
        kill "$watchdog" 2>/dev/null || true
        return 1
    fi
}

# Check prerequisites
for cmd in kubectl; do
    if ! command -v "$cmd" &>/dev/null; then
        error "$cmd is required but not installed"
    fi
done

# ── Apply CRDs (always) ──────────────────────────
info "Applying CRDs..."
kubectl apply -f config/crd/broker-crd.yaml
kubectl apply -f config/crd/trigger-crd.yaml

# ── Deploy namespace + RBAC (always) ─────────────
info "Deploying namespace and RBAC..."
kubectl apply -f config/manifests/namespace.yaml
kubectl apply -f config/manifests/service-account.yaml
kubectl apply -f config/manifests/cluster-role.yaml
kubectl apply -f config/manifests/cluster-role-binding.yaml

# ── Try Docker build first ───────────────────────
BUILT_IMAGE=false
if command -v docker &>/dev/null && command -v kind &>/dev/null; then
    info "Attempting Docker build (120s timeout)..."
    cd "$PROJECT_DIR"
    if docker_build_with_timeout 120 docker build -f docker/Dockerfile.controller -t "${IMAGE_NAME}:${IMAGE_TAG}" .; then
        info "Loading image into Kind cluster..."
        kind load docker-image "${IMAGE_NAME}:${IMAGE_TAG}" --name "${CLUSTER_NAME}"
        kubectl apply -f config/manifests/controller-deployment.yaml
        kubectl set image deployment/fluss-broker-controller \
            controller="${IMAGE_NAME}:${IMAGE_TAG}" \
            -n knative-fluss-broker
        kubectl rollout status deployment/fluss-broker-controller -n knative-fluss-broker --timeout=120s
        BUILT_IMAGE=true
        info "Controller deployed in-cluster"
    else
        warn "Docker build timed out — falling back to local process mode"
    fi
fi

# ── Fallback: local process ──────────────────────
if [ "$BUILT_IMAGE" = false ]; then
    info "Starting controller as local process..."

    # Find Java
    if command -v java &>/dev/null; then
        JAVA_BIN="java"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    else
        # Try Homebrew OpenJDK
        BREW_JAVA=$(find /opt/homebrew/Cellar/openjdk@*/libexec/openjdk.jdk/Contents/Home/bin/java 2>/dev/null | head -1)
        if [ -n "$BREW_JAVA" ] && [ -x "$BREW_JAVA" ]; then
            export JAVA_HOME="${BREW_JAVA%/bin/java}"
            JAVA_BIN="$BREW_JAVA"
        else
            error "No Java found. Install with: brew install openjdk@21"
        fi
    fi
    info "Using Java: $JAVA_BIN ($($JAVA_BIN --version 2>&1 | head -1))"

    # Build if needed
    cd "$PROJECT_DIR"
    export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $JAVA_BIN))}"
    ./gradlew :control-plane:controller:installDist --no-daemon -q 2>&1

    CONTROLLER_BIN="$PROJECT_DIR/control-plane/controller/build/install/controller/bin/controller"

    # Kill any existing local controller
    if [ -f "$CONTROLLER_PID_FILE" ]; then
        OLD_PID=$(cat "$CONTROLLER_PID_FILE")
        kill "$OLD_PID" 2>/dev/null || true
        rm -f "$CONTROLLER_PID_FILE"
    fi

    # Start controller with kubeconfig pointing to Kind
    export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
    "$CONTROLLER_BIN" > /tmp/fluss-controller.log 2>&1 &
    CONTROLLER_PID=$!
    echo "$CONTROLLER_PID" > "$CONTROLLER_PID_FILE"
    sleep 3

    if kill -0 "$CONTROLLER_PID" 2>/dev/null; then
        info "Controller running locally (PID: $CONTROLLER_PID)"
        info "Logs: tail -f /tmp/fluss-controller.log"
    else
        cat /tmp/fluss-controller.log
        error "Controller failed to start (see above log)"
    fi
fi

kubectl get pods -n knative-fluss-broker 2>/dev/null || true
info "Deploy complete"
