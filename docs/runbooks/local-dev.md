# Local Development Runbook

## Prerequisites
- Java 21 (via Homebrew: `brew install openjdk@21`)
- Docker and Docker Compose
- Git
- For K8s e2e: `kind`, `kubectl`, `helm` (install via `brew install kind kubectl helm`)

## Quick Start

```bash
# Clone and enter repo
cd knative-fluss-broker

# Start infrastructure (Fluss + ZooKeeper)
docker compose up -d

# Run unit tests
make test

# Run integration tests (Docker required)
make test-integration

# Run all tests
make build
```

## Full Lakehouse Stack

```bash
# Start everything including LocalStack, Polaris, Flink
docker compose --profile lakehouse up -d

# Run with Iceberg tests
make test test-integration test-e2e

# Access Flink UI: http://localhost:8081
# Access LocalStack S3: http://localhost:4566
# Access Polaris: http://localhost:8181
```

## K8s E2E (Kind + Knative + Fluss)

Full Kubernetes end-to-end testing with Kind:

```bash
# One command: creates cluster, installs everything, runs test
make test-e2e-k8s

# Or step by step:
make kind-up         # Create/reuse Kind cluster
make kind-install    # Install Knative Eventing + ZooKeeper + Fluss (Helm)
make kind-deploy     # Build controller image, deploy CRDs + controller
make kind-test       # Create Broker → Trigger → Sink, assert lifecycle
make kind-debug      # Collect logs (if test fails)
make kind-down       # Tear down Kind cluster
```

### What the K8s e2e test does:

1. Creates a Kind cluster (3 nodes)
2. Installs Knative Eventing v1.16
3. Installs ZooKeeper (Bitnami chart) + Fluss (Apache Helm chart)
4. Builds the controller Docker image, loads into Kind
5. Applies CRDs + controller Deployment + RBAC
6. Creates a test namespace
7. Deploys a sink service (http-echo)
8. Creates a Broker CRD → verifies controller reconciles it
9. Creates a Trigger CRD → verifies controller reconciles it
10. Verifies the sink is reachable
11. Reports pass/fail

### Troubleshooting K8s e2e:

```bash
# Check Kind cluster status
kubectl cluster-info --context kind-knative-fluss-broker

# Check all pods
kubectl get pods -A

# Check Fluss pods specifically
kubectl get pods -l app.kubernetes.io/name=fluss
kubectl logs -l app.kubernetes.io/component=coordinator

# Check controller logs
kubectl logs -n knative-fluss-broker -l app.kubernetes.io/component=controller

# Check e2e test namespace
kubectl get all,brokers,triggers -n e2e-test

# Collect all debug info
make kind-debug
```

## Service Endpoints

| Service | Endpoint | Purpose |
|---------|----------|---------|
| Fluss | localhost:9123 | Event storage |
| ZooKeeper | localhost:2181 | Fluss coordination |
| LocalStack | localhost:4566 | S3 + STS + IAM |
| Polaris | localhost:8181 | Iceberg REST catalog (lakehouse) |
| Flink | localhost:8081 | Tiering services (lakehouse) |

## Useful Commands

```bash
# Build specific module
./gradlew :data-plane:ingress:build

# Run specific test
./gradlew test --tests "EnvelopeTest"

# Clean build
make clean

# View dependency tree
./gradlew :data-plane:dispatcher:dependencies

# Stop infrastructure
docker compose down -v

# Stop lakehouse stack
docker compose --profile lakehouse down -v

# Full cleanup including Kind
make clean-all
```

## IDE Setup

1. Import as Gradle project
2. Set Java SDK to 21
3. Enable annotation processing (for fabric8 CRD generator)
4. Run configurations: use `./gradlew` tasks or `make` targets
