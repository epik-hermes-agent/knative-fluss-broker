# System Architecture Overview

This document describes the high-level architecture of the Knative Fluss Broker, covering the major components, their interactions, and the data flow from event source to subscriber.

## Component Diagram

```
                          Kubernetes Cluster
+---------------------------------------------------------------------+
|                                                                       |
|  +------------------+     +-------------------+                      |
|  |  Broker CRD      | --> | Reconciler        |                      |
|  |  Trigger CRD     |     | (Control Plane)   |                      |
|  +------------------+     +-------------------+                      |
|                          /          |          \                       |
|                         v           v           v                     |
|              +--------------+ +----------+ +---------------+         |
|              | Config Maps  | | Secrets  | | Service Accts |         |
|              +--------------+ +----------+ +---------------+         |
|                                                                       |
|  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -    |
|  Data Plane                                                            |
|                                                                       |
|  +----------------+     +------------------+     +------------------+ |
|  | Event Source   | --> | Ingress (HTTP)   | --> | Fluss Log Table  | |
|  | (CloudEvents)  |     | Validate + Store |     | (durable store)  | |
|  +----------------+     +------------------+     +------------------+ |
|                                                       |               |
|                                                       v               |
|                                              +------------------+     |
|                                              |  Dispatcher      |     |
|                                              |  (per-Trigger)   |     |
|                                              +------------------+     |
|                                               /    |    \              |
|                                              v     v     v             |
|                                          +------+ +------+ +------+   |
|                                          |Sub A | |Sub B | |Sub C |   |
|                                          +------+ +------+ +------+   |
|                                                                       |
|  [Optional]                                                           |
|  +------------------+     +------------------+                        |
|  | Iceberg Tiering  | --> | Iceberg Table    |                        |
|  | (compaction)     |     | (S3 + Catalog)   |                        |
|  +------------------+     +------------------+                        |
|                                                                       |
+---------------------------------------------------------------------+
```

## Component Descriptions

### Broker CRD / Trigger CRD

Kubernetes Custom Resource Definitions that provide the Knative-compatible public API surface. Users create `Broker` and `Trigger` resources to configure event routing.

- **Broker**: Defines the broker instance and its configuration (Fluss cluster connection, Iceberg tiering toggle).
- **Trigger**: Defines an event filter and subscriber target. Maps to a single dispatcher instance.

### Reconciler (Control Plane)

The Kubernetes controller that watches Broker and Trigger CRDs and reconciles the desired state with the actual infrastructure state.

Responsibilities:
- Create/manage Fluss databases and log tables per broker
- Configure dispatcher instances per trigger
- Manage schema registration and versioning
- Update CRD status conditions (Ready, DispatcherReady, TableReady)

### Ingress Handler (Data Plane - Ingress)

HTTP endpoint that accepts incoming CloudEvents from event sources.

Pipeline:
1. Validate CloudEvent format (required attributes: id, source, type, specversion)
2. Resolve or register schema (if schema registry is enabled)
3. Build the event envelope row with metadata columns
4. Write to the Fluss log table
5. Return 202 Accepted with delivery confirmation

### Fluss Log Table (Data Plane - Storage)

Fluss provides the durable event storage layer. Each broker namespace gets a dedicated Fluss database, and each broker instance gets a log table.

Table structure:
```
fluss database: knative_{namespace}
fluss table:    broker_{brokerName}
  partitioning: by date (ingestion_date)
  columns:
    - event_id         (STRING, PK)
    - event_source     (STRING)
    - event_type       (STRING)
    - event_time       (TIMESTAMP)
    - content_type     (STRING)
    - data             (BYTES)
    - schema_id        (INT)
    - schema_version   (INT)
    - attributes       (MAP<STRING, STRING>)
    - ingestion_time   (TIMESTAMP)
    - ingestion_date   (DATE)
```

### Dispatcher (Data Plane - Delivery)

Per-trigger component responsible for reading events from the Fluss table and delivering them to subscribers.

Design:
- One dispatcher instance per active trigger
- Credit-based backpressure model (see [backpressure-dispatcher.md](backpressure-dispatcher.md))
- Configurable concurrency per subscriber
- At-least-once delivery with retry and exponential backoff
- Dead letter queue support for poison messages

### Iceberg Tiering (Optional)

Background compaction job that periodically moves older events from Fluss Log Tables to Apache Iceberg tables stored on S3.

Activation:
- Disabled by default
- Enabled via `iceberg.enabled=true` in broker configuration
- Uses Fluss's built-in tiering mechanism
- Hive Metastore as the Iceberg catalog (locally and in dev)

### Schema Registry

Embedded schema registry that manages CloudEvent data schemas.

Responsibilities:
- Register schemas for event types
- Assign schema_id and schema_version on ingress
- Provide schema resolution for dispatchers
- Support schema evolution (backward-compatible changes only in v1)

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java | 21 |
| Build | Gradle | 8.10+ |
| Event Storage | Apache Fluss | 0.7.x |
| Lakehouse | Apache Iceberg | 1.4.x |
| Object Store | MinIO (S3-compatible) | latest |
| Catalog | Hive Metastore | 3.1.3 |
| Framework | Spring Boot | 3.3.x |
| Kubernetes | fabric8 client | 6.x |
| Testing | JUnit 5 + Testcontainers | - |

## Design Principles

1. **Knative Compatibility**: The public API surface is standard Knative Broker/Trigger. Fluss is an implementation detail.
2. **Fluss-Native**: Uses Fluss's Log Table API directly, not the Kafka compatibility layer. This gives us lower latency and better integration with Fluss features like tiering.
3. **Schema-Aware from Day One**: Even though schema enforcement is lenient in v1, the envelope always carries schema metadata. This prepares for future schema evolution and lakehouse integration.
4. **Iceberg is Optional**: The system works correctly without Iceberg. Tiering is a value-add for analytics, not a correctness dependency.
5. **Backpressure First**: The dispatcher uses credit-based backpressure to prevent slow subscribers from affecting the broker or other subscribers.
6. **Testability**: Every component is testable in isolation with Testcontainers providing real infrastructure.
