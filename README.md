# Knative Fluss Broker

A Knative-native event broker backed by [Apache Fluss](https://fluss.apache.org/) for durable, low-latency event storage with optional Apache Iceberg lakehouse tiering.

## Overview

This project implements the [Knative Broker and Trigger](https://knative.dev/docs/eventing/) specification using Fluss as the underlying storage engine. Events are stored in Fluss Log Tables, enabling direct stream access for subscribers with at-least-once delivery guarantees.

Key features:
- **Native Fluss Backend** - Uses Fluss Log Tables for durable event storage (not Kafka compatibility mode)
- **Knative Broker/Trigger** - Full CRD implementation with CloudEvents support
- **Credit-Based Backpressure** - Per-subscriber delivery with configurable concurrency and backpressure
- **Schema-Aware** - Explicit schema_id and schema_version columns in the event envelope
- **Optional Iceberg Tiering** - Toggleable lakehouse tiering via Fluss -> Iceberg compaction with S3 storage
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
                          |  Iceberg Tiering  | <--> |  Iceberg Table   |
                          +-------------------+      |  (S3 + Catalog)  |
                                                     +------------------+
```

## Prerequisites

- **Java 21** (LTS)
- **Gradle 8.10+** (wrapper included)
- **Docker** and **Docker Compose** (for integration tests and local dev)
- **kubectl** configured against a Kubernetes cluster (for deployment)

## Project Structure

```
knative-fluss-broker/
  broker-common/          # Shared models, CloudEvent envelope, schema registry client
  broker-ingress/         # HTTP ingress accepting CloudEvents
  broker-dispatcher/      # Per-trigger event dispatcher with backpressure
  broker-reconciler/      # Kubernetes reconciler for Broker/Trigger CRDs
  broker-iceberg/         # Iceberg tiering integration (optional module)
  broker-runtime/         # Spring Boot application assembly
  docs/                   # Architecture docs, ADRs, runbooks
  gradle/                 # Gradle wrapper and version catalog
```

## Build

```bash
# Full build with all tests
./gradlew build

# Build without integration tests (faster)
./gradlew build -x integrationTest

# Build a specific module
./gradlew :broker-ingress:build
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
./gradlew test --tests "com.fluss.knative.broker.ingress.IngressHandlerTest"
```

## Run Locally

```bash
# Start infrastructure (Fluss, ZooKeeper, MinIO, Hive Metastore)
docker compose -f docker/docker-compose.yml up -d

# Run the broker
./gradlew :broker-runtime:bootRun --args='--spring.profiles.active=local'

# Or run with Iceberg tiering enabled
./gradlew :broker-runtime:bootRun --args='--spring.profiles.active=local,iceberg'
```

See [docs/runbooks/local-dev.md](docs/runbooks/local-dev.md) for the full local development workflow.

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

iceberg:
  enabled: false
  catalog-type: "hive"
  warehouse: "s3a://iceberg-warehouse/"
  hive-metastore:
    endpoint: "thrift://localhost:9083"
  tiering:
    interval-minutes: 10
    commit-batch-size: 1000
```

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
