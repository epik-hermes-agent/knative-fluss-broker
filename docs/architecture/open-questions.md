# Open Questions

Known ambiguities and unresolved design decisions, documented per [§13 of the project charter](https://github.com/knative/fluss/issues).

---

## Q1: Schema Evolution Post-v1

**Context**: v1 is schema-stable — no automatic ALTER TABLE. New schema versions require versioned table rollout.

**Open question**: What schema evolution strategy should v2 adopt?
- **Option A**: Fluss-native schema evolution (add columns, widen types)
- **Option B**: Table versioning with backward-compatible reads
- **Option C**: External schema registry with Avro/Protobuf for full evolution

**Decision deferred to v2.** For now, v1's schema-stable contract is documented in ADR 002.

---

## Q2: Multi-Tenant Isolation

**Context**: Each Broker maps to a Fluss database (`knative_{namespace}`). Events from different namespaces are in different databases.

**Open question**: Is namespace-level isolation sufficient, or do we need per-broker isolation?
- Namespace isolation gives one database per namespace
- Multiple Brokers in the same namespace share a database but have separate tables
- No cross-namespace event leakage is possible (different databases)
- Table-level access control within a database is not enforced by Fluss in 1.0-SNAPSHOT

**Status**: Namespace-level isolation is acceptable for v1. Per-broker database isolation can be added if needed.

---

## Q3: Metrics Backend

**Context**: BrokerMetrics uses Micrometer with Prometheus registry. The Prometheus client library is included as a dependency.

**Open question**: Should the broker expose a Prometheus `/metrics` endpoint natively, or rely on sidecar/service mesh scraping?
- **Option A**: Spring Boot Actuator with Micrometer Prometheus registry (planned, not yet applied)
- **Option B**: Embedded Prometheus HTTP server (lightweight, no Spring dependency)
- **Option C**: Delegate to Kubernetes service mesh (Istio/Linkerd) for network-level metrics

**Status**: Metrics infrastructure (Micrometer + Prometheus) is in place. Endpoint exposure is deferred until Spring Boot is applied to runtime modules.

---

## Q4: Production Deployment Model

**Context**: The control plane uses fabric8 Kubernetes client for CRD management. The data plane runs as a standalone JVM process.

**Open question**: How should the data plane be deployed in production?
- **Option A**: Sidecar container in each Broker pod (Knative default pattern)
- **Option B**: Dedicated dispatcher deployment shared across triggers
- **Option C**: Per-trigger deployment (maximum isolation, highest resource cost)

**Status**: v1 focuses on single-JVM local development. Production deployment model is deferred to v2.

---

## Q5: Direct Fluss Reader Access (Hybrid Consumer)

**Context**: ADR 005 documents the hybrid consumer model — trusted internal consumers can read Fluss directly.

**Open question**: What authentication/authorization model for direct Fluss readers?
- Fluss 1.0-SNAPSHOT does not have built-in ACLs
- Direct readers bypass Knative Trigger semantics (no filtering, no delivery tracking)
- Should direct readers have a different Fluss user, or share the broker's connection?

**Status**: The `SampleDirectReader` example in `docs/examples/` demonstrates the concept. Authorization is deferred until Fluss adds native ACL support.

---

## Q6: Iceberg Tiering Failure Semantics (RESOLVED)

**Context**: ADR 003 makes Iceberg optional. When enabled, tiering failures should not break core broker behavior.

**Resolution (2026-04-17)**: Fluss's native tiering runs as an independent Flink job (`fluss-flink-tiering`). The tiering service is completely isolated from the ingress/dispatch path by design:
- It's a separate Flink job with its own lifecycle
- Fluss handles retry and checkpointing natively
- If tiering fails, data stays in Fluss (no data loss)
- Best-effort model — failures are logged and retried on next checkpoint
- No custom failure-handling code needed in our broker

---

## Q7: Fluss Log Compaction and Retention

**Context**: Fluss log tables support retention and compaction policies. The broker stores events in log tables.

**Open question**: What retention policy for broker event tables?
- Events should be retained long enough for replay (at-least-once delivery)
- After all triggers have consumed an event, should it be compacted?
- How does Iceberg tiering interact with Fluss retention (does tiering release local storage)?

**Status**: Default Fluss retention is used in v1. Retention tuning is deferred until production deployment patterns are established.

---

## Q8: CloudEvents Extension Attributes

**Context**: The Envelope model supports arbitrary `attributes` as `MAP<STRING, STRING>`.

**Open question**: Which CloudEvents extension attributes should be first-class columns vs map entries?
- ` correlationid`, `traceparent`, `region` are common extensions
- First-class columns enable typed filtering and indexing
- Map entries are flexible but untyped

**Status**: v1 stores all extensions in the `attributes` map. Common extensions can be promoted to first-class columns in v2 if filtering/indexing demands it.
