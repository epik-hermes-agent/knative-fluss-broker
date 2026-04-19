# Test Strategy

## Test Layers

### Layer 1: Unit Tests (fast, no infra)
- Envelope model validation
- CloudEvent mapping
- Filter evaluation (exact match, wildcards)
- Schema inference and validation
- Credit bucket backpressure logic
- Retry queue scheduling
- Config objects

**Run:** `make test` or `./gradlew test`

### Layer 2: Integration Tests (Docker required)
- Ingress → Fluss persistence
- Fluss → Dispatcher → WireMock subscriber
- Retry with backoff
- DLQ routing
- Schema registry operations
- Backpressure lane pause/resume

**Run:** `make test-integration` or `./gradlew integrationTest`

### Layer 3: E2E Tests — Data Plane (docker-compose Fluss)
- Producer → Ingress → Fluss → Dispatcher → Subscriber
- Real Fluss cluster, no mocking of storage
- WireMock subscriber stubs
- DLQ path for poison messages

**Run:** `make test-e2e` or `./gradlew :test:e2e:test`

### Layer 4: Performance Smoke Tests
- Batch publish + fast subscriber throughput
- Slow subscriber bounded in-flight behavior
- Multi-trigger fairness

**Run:** `make test-perf` or `./gradlew :test:performance-smoke:test`

### Layer 5: K8s E2E Tests (Kind + Knative)
- Full Kubernetes deployment via Kind
- Knative Eventing installed
- ZooKeeper + Fluss deployed via Helm
- Controller deployed as K8s Deployment
- Broker and Trigger CRD lifecycle
- End-to-end event flow through real K8s infrastructure

**Run:** `make test-e2e-k8s` or individual phases:
```bash
make kind-up       # Create/reuse Kind cluster
make kind-install  # Install Knative + ZooKeeper + Fluss
make kind-deploy   # Build + deploy controller + CRDs
make kind-test     # Run e2e assertions
make kind-debug    # Collect logs (on failure)
make kind-down     # Tear down
```

## Test Profiles

### Fast Profile (default)
Core broker behavior without Iceberg infrastructure.

```bash
make test test-integration
```

### Lakehouse Profile
Full stack including Flink, LocalStack, Polaris.

```bash
docker compose --profile lakehouse up -d
make test test-integration test-e2e
```

### K8s Profile
Full Kubernetes deployment with Kind.

```bash
make test-e2e-k8s
```

## Testcontainers

| Container | Purpose |
|-----------|---------|
| Fluss | Event storage integration |
| LocalStack | S3-compatible storage for Iceberg |
| WireMock | HTTP subscriber stubs |
| Polaris | Iceberg REST catalog (lakehouse) |

## WireMock Scenarios

| Scenario | Config |
|----------|--------|
| Healthy subscriber | 200 OK |
| Server error (retryable) | 500 |
| Client error (DLQ) | 400 |
| Rate limited | 429 + Retry-After |
| Timeout | Delayed response |

## Coverage Targets

- Unit test coverage: 80%+ for business logic
- Integration coverage: all data paths verified
- E2E coverage: happy path + 2 failure scenarios
- K8s e2e: CRD lifecycle + controller reconciliation
