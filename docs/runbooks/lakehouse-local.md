# Lakehouse Local Setup

## Overview
The lakehouse profile adds Polaris (Iceberg REST catalog), Flink, and the Fluss tiering job for testing the Fluss → Iceberg tiering path locally.

## Quick Start

```bash
# Start full lakehouse stack (Fluss + ZK + LocalStack + Polaris + Flink + tiering job)
docker compose --profile lakehouse up -d

# Wait for services
docker compose ps

# Verify LocalStack S3
awslocal --endpoint-url=http://localhost:4566 s3 ls
# Should show: fluss-data, iceberg-warehouse

# Verify Polaris
curl -sf -X POST http://localhost:8181/api/catalog/v1/oauth/tokens \
  -d 'grant_type=client_credentials&client_id=root&client_secret=s3cr3t&scope=PRINCIPAL_ROLE:ALL'

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
Iceberg Table (cold, Parquet on LocalStack S3)
       │
       ▼
Polaris REST Catalog (Apache Polaris 1.3.0)
```

## Configuration

The Iceberg tiering config is set server-side in Fluss's `FLUSS_PROPERTIES`:

```yaml
# Fluss remote storage
remote.data.dir: s3://fluss-data/remote
s3.endpoint: http://localstack:4566
s3.access-key: test
s3.secret-key: test
s3.region: us-east-1
s3.path-style-access: true

# Iceberg datalake
datalake.format: iceberg
datalake.iceberg.type: rest
datalake.iceberg.uri: http://polaris:8181/api/catalog
datalake.iceberg.warehouse: fluss
datalake.iceberg.credential: root:s3cr3t
datalake.iceberg.scope: PRINCIPAL_ROLE:ALL
datalake.iceberg.s3.endpoint: http://localstack:4566
datalake.iceberg.s3.access-key-id: test
datalake.iceberg.s3.secret-access-key: test
datalake.iceberg.s3.path.style.access: "true"
datalake.iceberg.s3.region: us-east-1
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
3. Check LocalStack S3 for Parquet files: `awslocal --endpoint-url=http://localhost:4566 s3 ls s3://iceberg-warehouse/`
4. Query via Fluss's union read (combines hot + cold data)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Polaris fails to start | Check `docker compose logs polaris`, verify port 8181 |
| LocalStack S3 bucket not created | Check `localstack-init` container logs |
| Flink init fails to download JARs | Check network connectivity, retry `docker compose up flink-init` |
| Tiering job not submitted | Check `flink-tiering-submit` logs, verify Flink cluster is up |
| S3 access denied | Verify path-style access enabled, check LocalStack is healthy |
| Iceberg tables not created | Verify `table.datalake.enabled = 'true'` on the table |
| Polaris auth failed | Verify bootstrap credentials: `root`/`s3cr3t` |
