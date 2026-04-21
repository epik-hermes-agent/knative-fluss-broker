# Makefile for knative-fluss-broker
#
# Usage:
#   make test              # Unit tests only
#   make test-integration  # Integration tests (docker-compose Fluss required)
#   make test-e2e          # E2E tests against real Fluss (docker-compose)
#   make test-e2e-k8s      # Full K8s e2e with Kind + Knative + Fluss + Controller
#   make build             # Full build
#   make clean             # Clean build artifacts
#   make publish           # Send a sample CloudEvent
#   make watch             # Watch events in Fluss
#   make health            # Check all services

.PHONY: all build test test-integration test-e2e test-e2e-k8s clean \\
        kind-up kind-down kind-install kind-deploy kind-test kind-debug \\
        publish publish-batch watch health \\
        docker-up docker-up-lakehouse docker-down docker-logs \\
        cleanup cleanup-jobs cleanup-soft cleanup-all \\
        deploy-init deploy-plan deploy-apply deploy-destroy deploy-kubeconfig

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

# ─────────────────────────────────────────────
# Convenience — Publish & Observe
# ─────────────────────────────────────────────

## Send a sample order CloudEvent (binary mode)
publish:
	@./scripts/send-order.sh

## Send a sample order CloudEvent (structured mode)
publish-structured:
	@./scripts/publish-structured.sh com.example.order.created /orders '{"orderId":"demo-001","amount":42.0}'

## Batch-publish sample events from scripts/sample-events.json
publish-batch:
	@./scripts/publish-batch.sh scripts/sample-events.json

## Watch events in Fluss (polls every 5s)
watch:
	@./scripts/watch-events.sh --follow

## Show last 20 events (no follow)
watch-once:
	@./scripts/watch-events.sh --tail 20

## Check health of all services
health:
	@./scripts/health-check.sh

## Check health including lakehouse services
health-full:
	@./scripts/health-check.sh --lakehouse

# ─────────────────────────────────────────────
# Docker Infrastructure
# ─────────────────────────────────────────────

## Start core Fluss cluster (ZK + Fluss + LocalStack)
docker-up:
	docker compose -f docker/docker-compose.yml up -d
	@echo "Waiting for Fluss coordinator..."
	@sleep 10
	@docker exec fluss-coordinator /opt/fluss/bin/fluss cluster-info 2>/dev/null && echo "Fluss ready" || echo "Fluss starting..."

## Start full lakehouse stack (core + Flink + Polaris + tiering)
docker-up-lakehouse: docker-up
	docker compose -f docker/docker-compose.yml --profile lakehouse up -d
	@echo "Waiting for tiering job..."
	@sleep 15
	@echo "Lakehouse stack ready."
	@echo "  Flink UI:     http://localhost:8081"
	@echo "  SQL Gateway:  http://localhost:8083"
	@echo "  Run: make dashboard"

## Tear down all containers and volumes
docker-down:
	docker compose -f docker/docker-compose.yml --profile lakehouse down -v
	@echo "All containers and volumes removed"

## Tail logs from all services
docker-logs:
	docker compose -f docker/docker-compose.yml --profile lakehouse logs -f --tail=50

## Cancel all Flink jobs (keep containers)
cleanup-jobs:
	@./scripts/cleanup.sh --jobs

## Cancel jobs + wipe S3 data (keep containers)
cleanup-soft:
	@./scripts/cleanup.sh --soft

## Full cleanup: jobs + containers + volumes + data
cleanup:
	@./scripts/cleanup.sh

## Full cleanup including lakehouse profile
cleanup-all:
	@./scripts/cleanup.sh --lakehouse

# ─────────────────────────────────────────────
# AWS EKS Deployment (Terraform)
# ─────────────────────────────────────────────

deploy-init:
	cd deploy/terraform && terraform init

deploy-plan:
	cd deploy/terraform && terraform plan

deploy-apply:
	cd deploy/terraform && terraform apply

deploy-destroy:
	cd deploy/terraform && terraform destroy

deploy-kubeconfig:
	@cd deploy/terraform && \
		CLUSTER=$$(terraform output -raw cluster_name) && \
		REGION=$$(terraform output -raw cluster_endpoint | sed 's|https://.*\.\(.*\)\.eks.*|\1|') && \
		aws eks update-kubeconfig --name "$$CLUSTER" --region "$$REGION" && \
		echo "kubectl configured for $$CLUSTER"

# ─────────────────────────────────────────────
# TUI Dashboard
# ─────────────────────────────────────────────

## Launch the live TUI dashboard
dashboard:
	@./scripts/launch-tui.sh

## Build the TUI dashboard JAR
dashboard-build:
	./gradlew :tools:tui:shadowJar --no-daemon
