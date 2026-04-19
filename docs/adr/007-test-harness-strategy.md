# ADR 007: Test Harness Strategy

**Date:** 2026-04-17
**Status:** Accepted
**Deciders:** Project charter §9, implementation team

## Context

The project requires a comprehensive automated test strategy covering unit, integration, e2e, performance, and K8s e2e layers. The test harness must validate the Fluss-backed broker with real infrastructure (Fluss cluster, WireMock subscribers, MinIO/S3, Kubernetes) while remaining fast enough for local development and CI.

## Decision

We adopted a **layered test profile strategy** with three distinct modes:

### 1. Fast Core Profile (default)
- Unit tests: pure in-memory, no Docker required
- Integration tests using **docker-compose** Fluss cluster (not Testcontainers for Fluss)
- WireMock-based subscriber stubs via OkHttp MockWebServer (lightweight, no container)
- All tests in this profile connect to `127.0.0.1:9123` (host-mapped Fluss port)

### 2. Full Lakehouse Profile (`--profile lakehouse`)
- Testcontainers for LocalStack
- Testcontainers for DebugFlussTest (ZooKeeper + Fluss spinup)
- Docker Compose lakehouse services: PostgreSQL, Flink

### 3. K8s E2E Profile (`make test-e2e-k8s`)
- **Kind** cluster for local Kubernetes testing
- **Knative Eventing** installed (full Broker/Trigger/Channel infrastructure)
- **ZooKeeper** via Bitnami Helm chart
- **Fluss** via official Apache Fluss Helm chart
- **Controller** built as Docker image, loaded into Kind, deployed as K8s Deployment
- Shell-based e2e test creates Broker → Trigger → Sink → asserts CRD lifecycle
- `hack/test-e2e-collect-debug.sh` captures logs on failure

### Why docker-compose for Fluss instead of Testcontainers?

1. **Startup time**: Fluss requires ZooKeeper + Coordinator + Tablet server. Spinning these via Testcontainers on every test class adds 30-60s per test class. Docker Compose runs them once as shared infrastructure.
2. **Fluss 1.0-SNAPSHOT complexity**: The Fluss server needs specific `bind.listeners` / `advertised.listeners` / `internal.listener.name` configuration that is fragile to wire dynamically in Testcontainers environment variables.
3. **Debuggability**: When tests fail, engineers can inspect the running Fluss cluster via `docker logs`, the Flink UI (port 8081), and MinIO console (port 9001) — all persistent across test runs.
4. **Pragmatic CI**: The `scripts/bootstrap.sh` script starts the docker-compose stack, and CI runs `./gradlew build` against it. No per-test-container lifecycle management needed.

### Why Kind for K8s e2e?

1. **No cloud required**: Developers can run the full K8s e2e locally
2. **Real Kubernetes**: Kind runs actual kube-apiserver, etcd, kubelet — not a mock
3. **Fast**: Kind cluster creation takes ~30s
4. **CI-compatible**: GitHub Actions supports Kind natively
5. **Standard**: Widely used in the Kubernetes ecosystem for e2e testing

### Why WireMock over Testcontainers WireMock module?

The `WireMockTestServer` in `test/wiremock/` uses WireMock standalone (not the Testcontainers module) with OkHttp MockWebServer. This avoids a Docker container per test and gives millisecond-level scenario setup. The WireMock Testcontainers module is available via `libs.wiremock` for future use if containerized WireMock scenarios are needed.

### Why Testcontainers for DebugFlussTest only?

`DebugFlussTest` uses `FlussZooKeeperCluster` (Testcontainers-based) as a smoke test to verify the Testcontainers-based Fluss cluster bring-up works. It's a single test that validates the container definitions are correct, and is not run in the default fast profile.

## Consequences

- **Positive**: Fast local dev loop (~90s for full build including integration tests). Reliable CI with shared infrastructure. Easy debugging. Full K8s lifecycle testing.
- **Negative**: Developers must run `docker compose up -d` before integration tests. K8s e2e requires `kind`, `helm`, `kubectl` installed.
- **Mitigation**: `scripts/bootstrap.sh` handles the full setup. `make test-e2e-k8s` handles the K8s path. The `CLAUDE.md` and `README.md` document this clearly.

## Related

- ADR 001: Native Fluss Broker (why Fluss, not Kafka)
- ADR 008: S3/Catalog Approach (LocalStack + Polaris REST catalog for lakehouse profile)
- §9 of project charter: Test Strategy
