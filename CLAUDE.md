# Knative Fluss Broker

A Knative-native event broker backed by Apache Fluss for durable, low-latency event storage.

## Architecture

- **Data Plane**: Ingress (HTTP CloudEvents), Dispatcher (credit-based backpressure), Storage (Fluss Log Tables), Schema Registry, Delivery (HTTP push), Iceberg Tiering (optional)
- **Control Plane**: Kubernetes CRDs (Broker, Trigger), Reconcilers using fabric8
- **API Group**: `eventing.fluss.io/v1alpha1`

## Tech Stack

- Java 21, Gradle 8.10+ (Kotlin DSL), Version Catalogs
- Spring Boot 3.3.x (planned, not yet applied to modules)
- Fluss 0.7.x, CloudEvents SDK 2.5, fabric8 6.13
- OkHttp for HTTP delivery, Jackson for JSON
- Testcontainers, JUnit 5, AssertJ, Mockito, WireMock

## Build Commands

```bash
./gradlew build                      # Full build
./gradlew build -x integrationTest   # Skip integration tests
./gradlew :data-plane:common:build   # Build single module
./gradlew test                       # Unit tests only
./gradlew integrationTest            # Integration tests (Docker required)
```

## Project Layout

```
data-plane/common/       # Envelope model, config, metrics, CloudEvents utils
data-plane/ingress/      # HTTP ingress handler
data-plane/dispatcher/   # Per-trigger dispatcher with backpressure
data-plane/storage-fluss/# Fluss client and table management
data-plane/schema/       # Schema registry and validation
data-plane/delivery/     # HTTP delivery and tracking
data-plane/iceberg-tiering/ # Optional Iceberg compaction
control-plane/api/       # CRD models (Broker, Trigger)
control-plane/controller/# Kubernetes reconcilers
test/testlib/            # Shared test utilities
test/containers/         # Testcontainers definitions
test/wiremock/           # WireMock scenarios
test/integration/        # Integration tests
test/e2e/                # End-to-end tests
test/performance-smoke/  # Performance benchmarks
```

## Code Standards

- Java 21 features: records, sealed classes, pattern matching, virtual threads
- Package root: `com.knative.fluss.broker`
- All public APIs must have Javadoc
- SLF4J for logging (no System.out)
- Constructor injection preferred over field injection
- Immutable value objects as records where possible
- Test naming: `methodName_shouldBehavior_whenCondition`

## Key Domain Concepts

- **Envelope**: Fixed schema for storing events in Fluss (event_id, event_type, data, schema_id, etc.)
- **Credit Bucket**: Token-bucket backpressure per delivery lane
- **Trigger**: Knative filter + subscriber binding, maps to one dispatcher instance
- **Broker**: Knative namespace-scoped event router backed by a Fluss log table
