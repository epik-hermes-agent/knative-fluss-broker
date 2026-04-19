# Upstream Patterns Reference

Patterns and conventions extracted from upstream repositories and documentation, used as inspiration (not copied verbatim) for the knative-fluss-broker project.

---

## 1. Knative Eventing — Control Plane Patterns

**Source**: [knative/eventing](https://github.com/knative/eventing)

### Broker/Trigger Reconciliation
- Broker reconciler creates the data-plane infrastructure (ingress, filter, dispatcher deployments)
- Trigger reconciler maps each Trigger to a subscriber delivery config
- Status conditions follow the `Ready`, `Addressable`, `FilterReady`, `DeliveryReady` pattern
- Each reconciler uses a `Reconcile(ctx, key)` pattern with exponential backoff on errors

### CRD Conventions
- API group: `eventing.knative.dev/v1`
- Broker spec includes `config` reference, `delivery` (retry, backoff, DLQ), `class`
- Trigger spec includes `broker`, `filter` (attributes), `subscriber` (URI), `delivery`
- Status subresource with `conditions[]` array following the Knative condition convention

### Data Plane Separation
- Control plane creates/updates Kubernetes Deployments for ingress and dispatcher
- Data plane pods handle the actual event flow
- Config maps propagate broker/trigger config to data plane pods

---

## 2. Knative Kafka Broker — Data Plane Patterns

**Source**: [knative-extensions/eventing-kafka-broker](https://github.com/knative-extensions/eventing-kafka-broker)

### Ingress Pattern
- HTTP CloudEvents receiver validates the event
- Maps event to a Kafka topic (analogous to our Fluss table)
- Persists event and returns 202 Accepted
- Schema validation happens at ingress, not dispatch time

### Dispatcher Pattern
- Per-trigger consumer group reading from the event topic
- CloudEvent attribute filtering before delivery
- HTTP push delivery with exponential backoff
- Dead Letter Sink (DLS) for terminal failures
- Offset management for at-least-once semantics

### Reconciler Pattern
- Separate reconciler per resource type (Broker, Trigger)
- Reconciler reads desired state from CRD, computes diff, applies changes
- Uses owner references for garbage collection
- Status updates happen after successful reconciliation

---

## 3. Apache Fluss — Client Integration Patterns

**Source**: [apache/fluss](https://github.com/apache/fluss)

### Connection Management
- Single `Connection` per application (thread-safe)
- `Admin` client is NOT thread-safe — create per-operation
- `Table` handles are obtained from `Connection.getTable(TablePath)`

### Log Table Writes
- `AppendWriter` for log tables (append-only)
- `AppendWriter` is NOT thread-safe — serialize writes or create per-thread
- `append(row)` returns `CompletableFuture<AppendResult>` — await acknowledgment
- `flush()` after batch writes

### Log Table Reads
- `LogScanner` for reading log tables
- Subscribe to buckets explicitly: `scanner.subscribeFromBeginning(bucket)`
- `scanner.poll(Duration)` returns `Iterable<ScanRecord>`
- Each trigger should have its own `LogScanner` (not thread-safe)

### DDL Operations
- `Admin.createDatabase(name, descriptor, ignoreIfExists)` 
- `Admin.createTable(path, descriptor, ignoreIfExists)`
- `Admin.dropTable(path, ignoreIfExists)`
- All DDL operations return `CompletableFuture<Void>` — await with timeout

### Docker Configuration (1.0-SNAPSHOT)
- Requires dual listeners: `INTERNAL` (server-to-server) and `CLIENT` (external)
- `bind.listeners`: what the server binds to inside the container
- `advertised.listeners`: what external clients use to connect
- `internal.listener.name`: which listener is for server-to-server communication
- Coordinator discovers tablet servers via ZooKeeper registrations

---

## 4. WireMock Testcontainers — Subscriber Stub Patterns

**Source**: [wiremock/wiremock-testcontainers-java](https://github.com/wiremock/wiremock-testcontainers-java)

### Scenario Setup
- WireMock stubs for healthy responses (200, 202)
- WireMock stubs for overload (429 with `Retry-After`, 503)
- WireMock stubs for transient failures (500 then 200)
- Request verification: `verify(exactly(n), postRequestedFor(urlEqualTo(...)))`

### Our Adaptation
- We use WireMock standalone (not the Testcontainers module) via `WireMockTestServer`
- OkHttp MockWebServer provides lightweight HTTP stubs
- The `test/wiremock/` module provides reusable scenario builders
- For full-container WireMock, the `libs.wiremock` dependency is available

---

## 5. LocalStack — S3 + STS Harness

**Source**: [docs.localstack.cloud](https://docs.localstack.cloud/)

### Why LocalStack over MinIO
- Provides S3 + STS + IAM on a single port (4566)
- STS token delegation works via `s3.assumed.role.sts.endpoint`
- Last free community edition (4.6.0) — no auth required
- `test`/`test` dummy credentials

### Our Adaptation
- Docker Compose: `localstack/localstack:4.6.0` with `SERVICES=s3,sts,iam`
- Bucket creation via `localstack-init` service using `awslocal`
- Fluss config: `s3.endpoint: http://localstack:4566` + `s3.assumed.role.sts.endpoint: http://localstack:4566`
- Path-style access configured via `s3.path-style-access: true`

---

## 6. Fluss Native Iceberg Tiering

**Source**: [fluss.apache.org/docs/streaming-lakehouse/integrate-data-lakes/iceberg](https://fluss.apache.org/docs/streaming-lakehouse/integrate-data-lakes/iceberg/)

### Architecture
- Fluss server has built-in lake connector plugin (`fluss-lake-iceberg`)
- Server-side config: `datalake.format`, `datalake.iceberg.*`
- Table opt-in: `table.datalake.enabled = 'true'`
- Tiering Flink job: `fluss-flink-tiering-1.0-SNAPSHOT.jar`
- Automatic schema mapping with `__bucket`, `__offset`, `__timestamp` system columns
- Union read: transparently combines hot (Fluss) + cold (Iceberg) data

### Plugin Architecture
- `FLUSS_HOME/plugins/fluss-fs-s3/` — S3 filesystem for `remote.data.dir`
- `FLUSS_HOME/plugins/iceberg/` — Iceberg lake connector
- `FLINK_HOME/lib/` — Fluss connector, Iceberg runtime, Hadoop, AWS, JDBC driver
- `FLINK_HOME/opt/` — Fluss tiering service JAR

### Catalog Options
- `datalake.iceberg.type: rest` — REST catalog (Polaris, recommended)
- `datalake.iceberg.type: hive` — Hive Metastore
- `datalake.iceberg.type: hadoop` — Hadoop catalog (file-based, for simple setups)

### Our Adaptation
- Docker Compose configures `datalake.*` in `FLUSS_PROPERTIES`
- Polaris REST catalog (replaces JDBC/HMS)
- NO custom tiering Java code — Fluss handles everything natively
- Tables created by Broker controller set `table.datalake.enabled = 'true'`
