#!/usr/bin/env bash
# bootstrap.sh — One-command setup for knative-fluss-broker local development.
#
# Usage:
#   ./scripts/bootstrap.sh              # Full setup (Docker + build)
#   ./scripts/bootstrap.sh --core       # Core services only (Fluss + ZK + LocalStack)
#   ./scripts/bootstrap.sh --lakehouse  # Full lakehouse stack
#   ./scripts/bootstrap.sh --with-tests # Run tests after setup
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ─────────────────────────────────────────────
# Prerequisites check
# ─────────────────────────────────────────────
check_prerequisites() {
    info "Checking prerequisites..."

    # Docker
    if ! command -v docker &>/dev/null; then
        error "Docker is not installed. See https://docs.docker.com/get-docker/"
    fi
    if ! docker info &>/dev/null; then
        error "Docker is not running. Start Docker Desktop first."
    fi
    info "  ✓ Docker $(docker --version | awk '{print $3}' | tr -d ',')"

    # Java 21
    JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
    if [ -z "$JAVA_HOME" ]; then
        # Try Homebrew
        if [ -d "/opt/homebrew/opt/openjdk@21" ]; then
            JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
        else
            error "Java 21 not found. Install with: brew install openjdk@21"
        fi
    fi
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    JAVA_VERSION=$(java --version 2>&1 | head -1 | awk '{print $2}')
    info "  ✓ Java $JAVA_VERSION ($JAVA_HOME)"

    # Gradle wrapper
    if [ ! -f "$PROJECT_DIR/gradlew" ]; then
        error "gradlew not found in $PROJECT_DIR"
    fi
    info "  ✓ Gradle wrapper found"
}

# ─────────────────────────────────────────────
# Docker Compose services
# ─────────────────────────────────────────────
start_services() {
    local profile="${1:-}"
    cd "$PROJECT_DIR"

    if [ "$profile" = "lakehouse" ]; then
        info "Starting full lakehouse stack (Fluss + ZK + LocalStack + Polaris + Flink + tiering)..."
        docker compose -f docker/docker-compose.yml --profile lakehouse up -d
    else
        info "Starting core services (Fluss + ZK + LocalStack)..."
        docker compose -f docker/docker-compose.yml up -d fluss-coordinator fluss-tablet zookeeper localstack localstack-init
    fi

    # Wait for Fluss coordinator to be ready
    info "Waiting for Fluss cluster to be ready..."
    local retries=0
    while ! docker logs fluss-coordinator 2>&1 | grep -q "New tablet server callback"; do
        sleep 2
        retries=$((retries + 1))
        if [ $retries -ge 30 ]; then
            error "Fluss cluster did not become ready in 60 seconds"
        fi
    done
    info "  ✓ Fluss cluster is ready"

    # Verify ports
    if nc -zv 127.0.0.1 9123 &>/dev/null; then
        info "  ✓ Fluss coordinator reachable at 127.0.0.1:9123"
    else
        warn "  Fluss coordinator port 9123 not reachable"
    fi
}

# ─────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────
run_build() {
    cd "$PROJECT_DIR"
    info "Building project..."
    ./gradlew compileJava compileTestJava --no-daemon
    info "  ✓ Compilation successful"
}

# ─────────────────────────────────────────────
# Tests
# ─────────────────────────────────────────────
run_tests() {
    cd "$PROJECT_DIR"
    info "Running unit tests..."
    ./gradlew test --no-daemon
    info "  ✓ Unit tests passed"

    info "Running integration tests..."
    ./gradlew :test:integration:test --no-daemon
    info "  ✓ Integration tests passed"
}

# ─────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────
main() {
    local mode="core"
    local run_tests_flag=false

    while [[ $# -gt 0 ]]; do
        case $1 in
            --core) mode="core"; shift ;;
            --lakehouse) mode="lakehouse"; shift ;;
            --with-tests) run_tests_flag=true; shift ;;
            -h|--help)
                echo "Usage: $0 [--core|--lakehouse] [--with-tests]"
                echo "  --core        Start core Fluss services (default)"
                echo "  --lakehouse   Start full lakehouse stack"
                echo "  --with-tests  Run tests after setup"
                exit 0
                ;;
            *) error "Unknown option: $1" ;;
        esac
    done

    info "=== Knative Fluss Broker — Bootstrap ==="
    check_prerequisites
    start_services "$mode"
    run_build

    if [ "$run_tests_flag" = true ]; then
        run_tests
    fi

    info ""
    info "=== Setup complete! ==="
    info "Fluss coordinator: fluss://127.0.0.1:9123"
    info "LocalStack dashboard:  http://localhost:4566/_localstack/health"
    if [ "$mode" = "lakehouse" ]; then
        info "Flink UI:          http://localhost:8081"
        info "Polaris:           http://localhost:8181"
    fi
    info ""
    info "Run tests:  ./gradlew build --no-daemon"
    info "Stop:       docker compose -f docker/docker-compose.yml down -v"
}

main "$@"
