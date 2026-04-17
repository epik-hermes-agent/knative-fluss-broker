# Makefile for knative-fluss-broker
#
# Usage:
#   make test              # Unit tests only
#   make test-integration  # Integration tests (docker-compose Fluss required)
#   make test-e2e          # E2E tests against real Fluss (docker-compose)
#   make test-e2e-k8s      # Full K8s e2e with Kind + Knative + Fluss + Controller
#   make build             # Full build
#   make clean             # Clean build artifacts

.PHONY: all build test test-integration test-e2e test-e2e-k8s clean \
        kind-up kind-down kind-install kind-deploy kind-test kind-debug

# ─────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────

all: build

build:
	./gradlew build --no-daemon

compile:
	./gradlew compileJava compileTestJava --no-daemon

# ─────────────────────────────────────────────
# Tests (no K8s)
# ─────────────────────────────────────────────

test:
	./gradlew test --no-daemon

test-integration:
	./gradlew :test:integration:test --no-daemon

test-e2e:
	./gradlew :test:e2e:test --no-daemon

test-perf:
	./gradlew :test:performance-smoke:test --no-daemon

# ─────────────────────────────────────────────
# K8s E2E (Kind + Knative + Fluss)
# ─────────────────────────────────────────────

test-e2e-k8s: kind-up kind-install kind-deploy kind-test
	@echo "K8s e2e complete"

kind-up:
	./hack/test-e2e-kind-up.sh

kind-down:
	./hack/test-e2e-kind-down.sh

kind-install:
	./hack/test-e2e-install-knative.sh
	./hack/test-e2e-install-fluss.sh

kind-deploy:
	./hack/test-e2e-deploy-app.sh

kind-test:
	./hack/test-e2e-run.sh

kind-debug:
	./hack/test-e2e-collect-debug.sh

# ─────────────────────────────────────────────
# Clean
# ─────────────────────────────────────────────

clean:
	./gradlew clean --no-daemon

clean-all: kind-down clean
	@echo "Full cleanup complete"
