# Lakehouse Local Setup

## Overview
The lakehouse profile adds Flink, PostgreSQL (Iceberg JDBC catalog), and the Fluss tiering job for testing the Fluss → Iceberg tiering path locally.

## Quick Start

```bash
# Start full lakehouse stack (Fluss + ZK + MinIO + PostgreSQL + Flink + tiering job)
docker compose --profile lakehouse up -d

# Wait for services
docker compose ps

# Verify MinIO buckets
# Open http://localhost:9001 → login minioadmin/minioadmin
# Check "iceberg-warehouse" and "fluss-data" buckets exist

# Verify Flink UI
# Open http://localhost:8081
```

## Architecture

```
Fluss Log Table (hot)
       │
       ▼
Fluss Tiering Flink Job (fluss-flink-tiering JAR)
       │
       ▼
Iceberg Table (cold, Parquet on MinIO)
       │
       ▼
JDBC Catalog (PostgreSQL)
```

## Configuration

The Iceberg tiering config is set server-side in Fluss's `FLUSS_PROPERTIES`:

```yaml
# Fluss remote storage
remote.data.dir: s3://fluss-data/remote
s3.endpoint: http://minio:9000
s3.access-key: minioadmin
s3.secret-key: minioadmin
s3.region: us-east-1
s3.path-style-access: true

# Iceberg datalake
datalake.format: iceberg
datalake.iceberg.type: jdbc
datalake.iceberg.uri: jdbc:postgresql://postgres:5432/iceberg
datalake.iceberg.jdbc.user: iceberg
datalake.iceberg.jdbc.password: iceberg
datalake.iceberg.warehouse: s3a://iceberg-warehouse/
datalake.iceberg.s3.endpoint: http://minio:9000
datalake.iceberg.s3.access-key-id: minioadmin
datalake.iceberg.s3.secret-access-key: minioadmin
datalake.iceberg.s3.path.style.access: "true"
```

## Creating a Tiered Table

```sql
-- Via Flink SQL Client (connected to Fluss catalog)
CREATE TABLE broker_events (
    event_id STRING,
    event_type STRING,
    data BYTES,
    PRIMARY KEY (event_id) NOT ENFORCED
) WITH (
    'table.datalake.enabled' = 'true',
    'table.datalake.freshness' = '30s'
);
```

## Verifying Tiering

1. Write events to the table
2. Wait for the freshness interval (default 30s)
3. Check MinIO console for Parquet files in `iceberg-warehouse/`
4. Query via Fluss's union read (combines hot + cold data)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| PostgreSQL fails to start | Check volume permissions, `docker compose logs postgres` |
| MinIO bucket not created | Check `minio-init` container logs |
| Flink init fails to download JARs | Check network connectivity, retry `docker compose up flink-init` |
| Tiering job not submitted | Check `flink-tiering-submit` logs, verify Flink cluster is up |
| S3 access denied | Verify path-style access enabled, check MinIO credentials |
| Iceberg tables not created | Verify `table.datalake.enabled = 'true'` on the table |
