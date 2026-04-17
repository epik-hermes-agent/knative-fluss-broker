# ADR-003: Iceberg Included in v1 but Optional/Toggleable

## Status
Accepted

## Context
Iceberg integration adds value for analytics but also adds operational complexity. We need to decide whether to include it in v1.

## Decision
Include Iceberg in v1 behind a **clean feature toggle** (`iceberg.enabled`). Real integration, easy to disable.

## Rationale
1. **Proof of architecture** — the tiering path must work for the design to be credible.
2. **Toggleable** — disabled by default, no impact on core broker.
3. **Testable** — layered test profiles (fast vs lakehouse).
4. **Future-proof** — building it in v1 avoids retrofitting later.

## Consequences
- Docker Compose includes MinIO, Hive Metastore, Flink in `lakehouse` profile.
- Integration tests validate tiering-enabled and tiering-disabled paths.
- Iceberg failures don't affect core broker write/dispatch paths.
