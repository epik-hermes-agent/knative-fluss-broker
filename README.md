# Knative Fluss Broker

A Knative-native event broker backed by [Apache Fluss](https://fluss.apache.org/) for durable, low-latency event storage with optional Apache Iceberg lakehouse tiering (via Fluss's built-in capability).

## Overview

This project implements the [Knative Broker and Trigger](https://knative.dev/docs/eventing/) specification using Fluss as the underlying storage engine. Events are stored in Fluss Log Tables, enabling direct stream access for subscribers with at-least-once delivery guarantees.

Key features:
- **Native Fluss Backend** - Uses Fluss Log Tables for durable event storage (not Kafka compatibility mode)
- **Knative Broker/Trigger** - Full CRD implementation with CloudEvents support
- **Credit-Based Backpressure** - Per-subscriber delivery with configurable concurrency and backpressure
- **Schema-Aware** - Explicit schema_id and schema_version columns in the event envelope
- **Optional Iceberg Tiering** - Fluss's built-in lakehouse tiering via `fluss-lake-iceberg` plugin
- **At-Least-Once Delivery** - Retry with exponential backoff and Dead Letter Queue support

## Architecture

```
+-------------------+      +------------------+      +-------------------+
|   Event Source    | ---> |  Ingress Handler | ---> |  Fluss Log Table  |
+-------------------+      +------------------+      +-------------------+
                                                              |
                                                              v
                                                    +------------------+
                                                    |   Dispatcher     |
                                                    | (per-Trigger)    |
                                                    +------------------+
                                                     /        |        \
                                                    v         v         v
                                              +--------+ +--------+ +--------+
                                              |  Sub A | |  Sub B | |  Sub C |
                                              +--------+ +--------+ +--------+
                                                              |
                                    [Optional]                v
                          +-------------------+      +------------------+
                          | Fluss Tiering Job | <--> |  Iceberg Table   |
                          | (built-in JAR)    |      |  (S3 + JDBC Cat) |
                          +-------------------+      +------------------+
```

## Prerequisites

- **Java 21** (LTS)
- **Gradle 8.10+** (wrapper included)
- **Docker** and **Docker Compose** (for integration tests and local dev)
- **kubectl** configured against a Kubernetes cluster (for deployment)

## Project Structure

```
knative-fluss-broker/
  data-plane/common/       # Shared models, CloudEvent envelope, config records
  data-plane/ingress/      # HTTP ingress accepting CloudEvents
  data-plane/dispatcher/   # Per-trigger event dispatcher with backpressure
  data-plane/storage-fluss/# Fluss client and table management
  data-plane/schema/       # Schema registry and validation
  data-plane/delivery/     # HTTP delivery and tracking
  control-plane/api/       # CRD models (Broker, Trigger)
  control-plane/controller/# Kubernetes reconcilers
  test/testlib/            # Shared test utilities
  test/containers/         # Testcontainers definitions
  test/wiremock/           # WireMock scenarios
  test/integration/        # Integration tests
  test/e2e/                # End-to-end tests
  test/performance-smoke/  # Performance benchmarks
  docs/                    # Architecture docs, ADRs, runbooks
  gradle/                  # Gradle wrapper and version catalog
```

## Build

```bash
# Full build with all tests
./gradlew build

# Build without integration tests (faster)
./gradlew build -x integrationTest

# Build a specific module
./gradlew :data-plane:ingress:build
```

## Test

```bash
# Unit tests only
./gradlew test

# Integration tests (requires Docker)
./gradlew integrationTest

# All tests with coverage report
./gradlew build jacocoTestReport

# Run a specific test class
./gradlew test --tests "com.knative.fluss.broker.ingress.IngressHandlerTest"
```

## Run Locally

```bash
# Start infrastructure (Fluss, ZooKeeper, MinIO)
docker compose -f docker/docker-compose.yml up -d

# Run the broker
./gradlew :broker-runtime:bootRun --args='--spring.profiles.active=local'
```

See [docs/runbooks/local-dev.md](docs/runbooks/local-dev.md) for the full local development workflow.

## Iceberg Tiering (Optional)

Fluss provides built-in Iceberg tiering — no custom code needed.

```bash
# Start full lakehouse stack (adds PostgreSQL, Flink, tiering job)
docker compose --profile lakehouse up -d

# Tiering is configured via Fluss's FLUSS_PROPERTIES in docker-compose.yml:
#   datalake.format: iceberg
#   datalake.iceberg.type: jdbc
#   datalake.iceberg.uri: jdbc:postgresql://postgres:5432/iceberg
#   datalake.iceberg.warehouse: s3a://iceberg-warehouse/

# Tables opt in with:
#   CREATE TABLE events (...) WITH ('table.datalake.enabled' = 'true')
```

See [docs/architecture/iceberg-tiering.md](docs/architecture/iceberg-tiering.md) for details.

## Configuration

Key configuration properties:

```yaml
fluss:
  cluster:
    endpoint: "fluss://localhost:9123"
  schema-registry:
    enabled: true
    auto-register: true

knative:
  broker:
    namespace: "default"
    name: "fluss-broker"
  dispatcher:
    max-concurrency: 10
    initial-credits: 100
    credit-refill-rate: 10
    retry:
      max-attempts: 5
      backoff-multiplier: 2.0
      initial-delay-ms: 1000
      max-delay-ms: 60000
```

Iceberg tiering is configured server-side (not in the broker config):
```yaml
# In docker-compose.yml FLUSS_PROPERTIES:
datalake.format: iceberg
datalake.iceberg.type: jdbc
datalake.iceberg.uri: jdbc:postgresql://postgres:5432/iceberg
datalake.iceberg.warehouse: s3a://iceberg-warehouse/
```

## Architecture Diagrams

Interactive SVG diagrams (open in any browser):

- [Fluss as Knative Broker — Event Flow](docs/diagrams/fluss-knative-broker.html)
- [Fluss + Iceberg Streaming Lakehouse](docs/diagrams/streaming-lakehouse.html)
- [Test Harness Deployment](docs/diagrams/test-harness-deployment.html)
- [Apache Fluss Internal Architecture](docs/diagrams/fluss-internals.html)

## Documentation

- [Architecture Overview](docs/architecture/overview.md)
- [Data Plane](docs/architecture/data-plane.md)
- [Control Plane](docs/architecture/control-plane.md)
- [Schema Model](docs/architecture/schema-model.md)
- [Backpressure Dispatcher](docs/architecture/backpressure-dispatcher.md)
- [Iceberg Tiering](docs/architecture/iceberg-tiering.md)
- [Test Strategy](docs/testing/test-strategy.md)
- [Local Dev Runbook](docs/runbooks/local-dev.md)
- [Failure Scenarios](docs/runbooks/failure-scenarios.md)
- [Lakehouse Local Setup](docs/runbooks/lakehouse-local.md)
- [Architecture Decision Records](docs/adr/)

## License

Apache License 2.0
