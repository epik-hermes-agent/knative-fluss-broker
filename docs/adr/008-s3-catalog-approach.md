# ADR 008: S3-Compatible Storage and Iceberg Catalog Approach

**Date:** 2026-04-17
**Updated:** 2026-04-18 — Switched from MinIO to LocalStack, from JDBC to Polaris REST catalog
**Status:** Accepted
**Deciders:** Project charter §3.8, §8.6, §9.5

## Context

The project includes Apache Iceberg integration in v1 as an optional/toggleable feature. Iceberg requires:
1. An object store (S3 or compatible) for data files
2. A catalog service (Hive Metastore, JDBC, REST, Glue, Nessie, etc.)

For local development and CI, we need a self-contained setup that mirrors production behavior without requiring AWS accounts or external services.

> **2026-04-18 Update:** The original MinIO + JDBC catalog choices below have been superseded.
> See the [Updated Decisions](#updated-decisions-2026-04-18) section at the end.

## Decision (Original — 2026-04-17)

### S3-Compatible Storage: MinIO (pgsty/minio)

**Choice**: [MinIO](https://min.io/) via Docker Compose, using the `pgsty/minio` fork.

**Rationale**:
- MinIO is the de facto standard S3-compatible emulator for local dev and CI
- `pgsty/minio` provides a leaner image than upstream `minio/minio`
- Docker Compose deployment provides a persistent MinIO instance at `localhost:9000`
- Pre-configured buckets (`iceberg-warehouse`, `fluss-data`) via `minio-init` sidecar
- Path-style access (`http://localhost:9000/bucket`) avoids DNS wildcards needed for virtual-hosted-style
- MinIO console at `localhost:9001` aids debugging

**Configuration pattern**:
```yaml
# Fluss S3 filesystem (remote.data.dir)
s3.endpoint: http://minio:9000
s3.access-key: minioadmin
s3.secret-key: minioadmin
s3.region: us-east-1
s3.path-style-access: true

# Iceberg S3FileIO (datalake.iceberg.s3.*)
datalake.iceberg.s3.endpoint: http://minio:9000
datalake.iceberg.s3.access-key-id: minioadmin
datalake.iceberg.s3.secret-access-key: minioadmin
datalake.iceberg.s3.path.style.access: "true"
```

Note: Fluss uses TWO separate S3 configurations:
- `s3.*` — Fluss's own `fluss-fs-s3` plugin for `remote.data.dir` (tiered log segments)
- `datalake.iceberg.s3.*` — Iceberg's S3FileIO for data files (passed through to Iceberg)

### Iceberg Catalog: JDBC (v1, matching Fluss quickstart)

**Choice**: JDBC catalog backed by PostgreSQL (replaces original Hive Metastore choice).

**Rationale**:
1. **Upstream alignment**: Fluss's official Iceberg quickstart uses JDBC catalog with PostgreSQL
2. **Lighter footprint**: No separate Thrift service needed — PostgreSQL handles metadata directly
3. **Already in stack**: PostgreSQL is already deployed for the lakehouse profile
4. **Standard Iceberg catalog**: `datalake.iceberg.type: jdbc` is a built-in Iceberg catalog type
5. **Production-proven**: JDBC catalog is widely used and well-tested

**Why NOT Hive Metastore**:
- HMS requires a separate Thrift service (~500MB RAM overhead)
- JDBC achieves the same catalog purpose with less infrastructure
- Fluss's quickstart uses JDBC, making our setup directly comparable to upstream

**Why NOT other catalogs in v1**:
- **AWS Glue**: Requires AWS account, not suitable for local/CI
- **REST Catalog**: Newer Iceberg standard, but JDBC is simpler for local dev
- **Nessie**: Adds another service. Valuable for multi-table transactions, but not needed for v1 tiering validation
- **Hadoop catalog**: Simple but lacks concurrent access guarantees; JDBC is better for integration tests

### Lakehouse Profile Structure

The `lakehouse` Docker Compose profile includes:
- PostgreSQL (JDBC catalog backend, databases: default + `iceberg`)
- Flink JobManager + TaskManager (tiering services)
- Flink init container (downloads JARs)
- Tiering job submission (one-shot, submits `fluss-flink-tiering` to Flink)

Tests that need the lakehouse stack use the `--profile lakehouse` flag or connect to the already-running services.

### Fluss Plugin Architecture

Fluss uses a plugin system for extensibility. Required plugins for our setup:

| Plugin | Directory | Purpose |
|--------|-----------|---------|
| `fluss-fs-s3` | `FLUSS_HOME/plugins/fluss-fs-s3/` | S3 filesystem for `remote.data.dir` |
| `fluss-lake-iceberg` | `FLUSS_HOME/plugins/iceberg/` | Iceberg lake connector for `datalake.format: iceberg` |

These plugins are bundled in the Fluss Docker image and mounted via `fluss-plugins` volume for Hadoop client JARs.

## Consequences

- **Positive**: Matches Fluss upstream quickstart patterns. Lighter than HMS. Reliable local tiering path. No cloud dependencies for CI.
- **Negative**: JDBC catalog is simpler than HMS but has less ecosystem support (e.g., Spark/Delta integrations often prefer HMS). Acceptable for v1.
- **Mitigation**: Switching to HMS or REST catalog later is a config change, not a code change.

## Related

- ADR 003: Iceberg Optional in v1
- ADR 007: Test Harness Strategy (layered profiles)
- §8.6 of project charter: Iceberg Integration Design

## Updated Decisions (2026-04-18)

### S3-Compatible Storage: LocalStack 4.6.0

**Supersedes:** MinIO (pgsty/minio)

**Rationale**:
- LocalStack provides S3 + STS + IAM on a single port (4566)
- Last free community edition (4.6.0) — no auth required
- S3 operations work via `localstack:4566` with dummy credentials (`test`/`test`)
- STS token delegation can now be redirected to LocalStack via `s3.assumed.role.sts.endpoint` (fixed in Fluss 1.0-SNAPSHOT, PR #3067)

**Configuration pattern** (replaces MinIO):
```yaml
# Fluss S3 filesystem (remote.data.dir)
s3.endpoint: http://localstack:4566
s3.access-key: test
s3.secret-key: test
s3.region: us-east-1
s3.path-style-access: true

# Iceberg S3FileIO (datalake.iceberg.s3.*)
datalake.iceberg.s3.endpoint: http://localstack:4566
datalake.iceberg.s3.access-key-id: test
datalake.iceberg.s3.secret-access-key: test
datalake.iceberg.s3.path.style.access: "true"
datalake.iceberg.s3.region: us-east-1
```

### Iceberg Catalog: Apache Polaris 1.3.0 REST Catalog

**Supersedes:** JDBC catalog (PostgreSQL)

**Rationale**:
1. **Standards-compliant**: Polaris provides a full Iceberg REST catalog (`datalake.iceberg.type: rest`)
2. **Simpler deployment**: Single container, no PostgreSQL database needed for catalog metadata
3. **OAuth2 authentication**: Built-in security with bootstrap credentials
4. **In-memory mode**: Perfect for dev/CI — no persistent state needed
5. **Engine-agnostic**: Any Iceberg-compatible engine (Flink, Spark, Trino) can connect

**Configuration pattern** (replaces JDBC):
```yaml
datalake.iceberg.type: rest
datalake.iceberg.uri: http://polaris:8181/api/catalog
datalake.iceberg.warehouse: fluss
datalake.iceberg.credential: root:s3cr3t
datalake.iceberg.scope: PRINCIPAL_ROLE:ALL
```

**Helm chart**: `apache-polaris/polaris` on ArtifactHub (for K8s deployment)
