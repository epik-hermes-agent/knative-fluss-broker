# ADR-003: Iceberg Included in v1 but Optional/Toggleable

## Status
Accepted

## Context
Iceberg integration adds value for analytics but also adds operational complexity. We need to decide whether to include it in v1.

## Decision
Include Iceberg in v1 behind a **clean feature toggle** (`table.datalake.enabled`). Real integration, easy to disable.

## Rationale
1. **Proof of architecture** — the tiering path must work for the design to be credible.
2. **Toggleable** — disabled by default, no impact on core broker.
3. **Testable** — layered test profiles (fast vs lakehouse).
4. **Future-proof** — building it in v1 avoids retrofitting later.

## Update (2026-04-17)

Initial implementation used custom tiering code (`IcebergTieringJob`, `IcebergCatalogManager`). After discovering Fluss 0.9.0's **native Iceberg tiering support** (FIP-3, `fluss-lake-iceberg` plugin), we switched to the built-in approach:

- The `data-plane/iceberg-tiering/` module was **removed entirely**
- Tiering is now configured via server-side `datalake.*` properties
- The pre-built `fluss-flink-tiering-0.9.0-incubating.jar` handles compaction
- Tables opt in with `table.datalake.enabled = 'true'`
- JDBC catalog (PostgreSQL) replaces Hive Metastore (matches Fluss quickstart)

## Consequences
- Docker Compose includes MinIO, PostgreSQL, Flink in `lakehouse` profile.
- Integration tests validate tiering-enabled and tiering-disabled paths.
- Iceberg failures don't affect core broker write/dispatch paths.
- No custom tiering Java code — Fluss handles everything natively.
