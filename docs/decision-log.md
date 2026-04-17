# Decision Log

Running log of all major decisions made during project development.

| Date | Decision | ADR | Rationale |
|------|----------|-----|-----------|
| 2026-04-16 | Java 21 LTS | — | Current LTS, virtual threads, records, pattern matching |
| 2026-04-16 | Gradle 8.10.2 with Kotlin DSL | — | Modern Gradle, type-safe build scripts |
| 2026-04-16 | Fluss 0.9.0 | — | Latest stable release |
| 2026-04-16 | Native Fluss Broker (no Kafka) | ADR-001 | Clean API, Fluss-native features |
| 2026-04-16 | Schema-stable v1 | ADR-002 | Predictable, no surprise mutations |
| 2026-04-16 | Iceberg optional in v1 | ADR-003 | Proves architecture, easy to disable |
| 2026-04-16 | Credit-based backpressure | ADR-004 | Bounded in-flight, natural pause/resume |
| 2026-04-16 | Hybrid consumer model | ADR-005 | Knative push + future direct Fluss reads |
| 2026-04-16 | data-plane/control-plane split | ADR-006 | Clean separation of concerns |
| 2026-04-16 | MinIO for S3-compatible tests | — | Official Testcontainers support, easy local setup |
| 2026-04-16 | Hive Metastore for Iceberg catalog | — | Upstream-aligned, widely supported (since revised — see 2026-04-17) |
| 2026-04-17 | JDBC catalog (replaces HMS) | ADR-008 | Matches Fluss quickstart, lighter footprint |
| 2026-04-17 | Fluss native tiering (no custom code) | ADR-003 | FIP-3 built-in, fluss-lake-iceberg plugin |
| 2026-04-16 | WireMock for subscriber stubs | — | Official Testcontainers module, rich scenario support |
| 2026-04-16 | fabric8 for K8s client | — | Mature Java client, CRD support, good Spring integration |
| 2026-04-16 | OkHttp for subscriber delivery | — | Fast, reliable, widely used |
| 2026-04-16 | Flink for tiering services | — | Upstream Fluss tiering uses Flink |
