#!/usr/bin/env bash
# test-integration.sh — Run integration tests against the docker-compose Fluss cluster.
#
# Usage:
#   ./scripts/test-integration.sh            # All integration tests
#   ./scripts/test-integration.sh fluss      # Fluss-only integration tests
#   ./scripts/test-integration.sh e2e        # End-to-end tests
#   ./scripts/test-integration.sh perf       # Performance smoke tests
#   ./scripts/test-integration.sh lakehouse  # Lakehouse/Iceberg tests (requires --profile lakehouse)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Setup Java
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# Check Fluss is running
if ! nc -zv 127.0.0.1 9123 &>/dev/null 2>&1; then
    error "Fluss cluster not running at 127.0.0.1:9123. Run: ./scripts/bootstrap.sh"
fi
info "Fluss cluster detected at 127.0.0.1:9123"

cd "$PROJECT_DIR"

case "${1:-all}" in
    fluss)
        info "Running Fluss integration tests..."
        ./gradlew :test:integration:test --tests "*.FlussIntegrationTest" --tests "*.FlussSmokeTest" --no-daemon
        ;;
    ingress)
        info "Running Ingress integration tests..."
        ./gradlew :test:integration:test --tests "*.IngressIntegrationTest" --no-daemon
        ;;
    lakehouse)
        info "Running Lakehouse/Iceberg integration tests..."
        # Check LocalStack is running
        if ! nc -zv 127.0.0.1 4566 &>/dev/null 2>&1; then
            error "LocalStack not running at 127.0.0.1:4566. Run: docker compose up -d localstack"
        fi
        ./gradlew :test:integration:test --tests "*.FlussLakehouseIntegrationTest" --no-daemon
        ;;
    e2e)
        info "Running end-to-end tests..."
        ./gradlew :test:e2e:test --no-daemon
        ;;
    perf)
        info "Running performance smoke tests..."
        ./gradlew :test:performance-smoke:test --no-daemon
        ;;
    all)
        info "Running all integration + e2e + performance tests..."
        ./gradlew :test:integration:test :test:e2e:test :test:performance-smoke:test --no-daemon
        ;;
    *)
        echo "Usage: $0 [fluss|ingress|lakehouse|e2e|perf|all]"
        exit 1
        ;;
esac

info "Tests completed successfully!"
