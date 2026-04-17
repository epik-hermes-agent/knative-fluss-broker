# Iceberg Tiering Architecture

## Overview

The Iceberg tiering subsystem provides optional long-term storage of events via Apache Iceberg tables on S3-compatible storage. It is a value-add for analytics, **not** a correctness dependency for the core broker.

## Architecture

```
Fluss Log Table (hot)
  |
  | [Tiering job runs periodically]
  v
Iceberg Table on S3 (cold)
  |
  v
Hive Metastore (catalog)
  |
  v
Query engines (Spark, Trino, Flink SQL)
```

## Components

### Tiering Job
- Background job (Flink or scheduled Java task)
- Reads events from Fluss older than the tiering threshold
- Writes to Iceberg table via Hive Metastore catalog
- Commits Iceberg transaction

### Storage Layer
- **MinIO** (local/CI) — S3-compatible object storage
- **AWS S3** (production) — primary object store
- Data stored in Parquet format (Iceberg default)

### Catalog
- **Hive Metastore** — serves as Iceberg catalog
- Backed by PostgreSQL (local) or RDS (production)

## Feature Toggle

Iceberg is disabled by default. Enable via:

```yaml
spec:
  config:
    iceberg:
      enabled: true
```

When disabled:
- No tiering jobs are scheduled
- No S3/Metastore connections are created
- Core broker behavior is unaffected
- All tests pass without Iceberg infrastructure

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `iceberg.enabled` | `false` | Master toggle |
| `iceberg.catalog-type` | `hive` | Catalog type |
| `iceberg.warehouse` | `s3a://iceberg-warehouse/` | S3 warehouse path |
| `iceberg.hive-endpoint` | `thrift://localhost:9083` | Hive Metastore URI |
| `iceberg.tiering-interval-minutes` | `10` | How often to tier |
| `iceberg.commit-batch-size` | `1000` | Events per Iceberg commit |

## Iceberg Table Schema

Same envelope schema as Fluss, with additions:
- `_fluss_offset` (long): Fluss offset at compaction time
- `_compaction_time` (timestamp): When row was compacted

## Local Development

```bash
# Start full lakehouse stack
docker compose --profile lakehouse up -d

# Run with Iceberg enabled
./gradlew :data-plane:iceberg-tiering:test -Plakehouse

# Run without Iceberg (faster)
./gradlew test -x :data-plane:iceberg-tiering:test
```

## Failure Boundaries

- Iceberg tiering failures do NOT affect ingress or dispatch
- Tiering job retries on next scheduled run
- Failed compactions leave data in Fluss (no data loss)
- If S3 is unreachable, tiering pauses but broker continues
