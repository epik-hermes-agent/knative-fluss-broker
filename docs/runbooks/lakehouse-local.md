# Lakehouse Local Setup

## Overview
The lakehouse profile adds Flink, MinIO, Hive Metastore, and PostgreSQL for testing the Fluss → Iceberg tiering path locally.

## Setup

```bash
# Start full stack
docker compose --profile lakehouse up -d

# Wait for services (check health)
docker compose ps

# Verify MinIO bucket exists
# Open http://localhost:9001 → login minioadmin/minioadmin
# Check "iceberg-warehouse" bucket exists

# Run tiering tests
./gradlew :data-plane:iceberg-tiering:test -Plakehouse
```

## Architecture

```
Fluss Log Table → Flink Tiering Job → Iceberg Table → MinIO (S3)
                                           ↓
                                     Hive Metastore
                                           ↓
                                     PostgreSQL
```

## Configuration

The Iceberg tiering config points to local services:

```yaml
iceberg:
  enabled: true
  catalog-type: "hive"
  warehouse: "s3a://iceberg-warehouse/"
  hive-endpoint: "thrift://localhost:9083"
  s3-endpoint: "http://localhost:9000"
  s3-access-key: "minioadmin"
  s3-secret-key: "minioadmin"
```

## Verifying Tiering

1. Send events to broker ingress
2. Wait for tiering interval (default 10 min, or trigger manually)
3. Check MinIO console for Parquet files in `iceberg-warehouse/`
4. Query via Flink SQL:

```sql
SELECT * FROM `fluss-catalog`.`knative_default`.`broker_default`;
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Hive Metastore fails to start | Check PostgreSQL is running first |
| MinIO bucket not created | Check minio-init container logs |
| Flink job fails | Check Flink UI at localhost:8081 |
| S3 access denied | Verify path-style access enabled |
