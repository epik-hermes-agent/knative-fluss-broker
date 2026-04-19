# Iceberg Tiering Architecture

## How It Works

Fluss provides **built-in Iceberg tiering** — we do NOT implement custom tiering code. The tiering is handled by Fluss's lake connector plugin and a pre-built Flink job.

### Data Flow

```
┌──────────────────┐       ┌──────────────────────┐       ┌────────────────────┐
│  Fluss Log Table │ ────▶ │  Fluss Tiering Job   │ ────▶ │  Iceberg Table     │
│  (hot, real-time)│       │  (flink-tiering JAR) │       │  (cold, on S3)     │
└──────────────────┘       └──────────────────────┘       └────────────────────┘
                                                                  │
                                                                  ▼
                                                           ┌──────────────────┐
                                                           │ Polaris REST     │
                                                           │ Catalog          │
                                                           └──────────────────┘
```

### Architecture

- **Hot tier**: Fluss Log Tables — sub-second latency, RocksDB + local disk
- **Warm tier**: Fluss tiered storage — historical log segments offloaded to S3/LocalStack (`remote.data.dir`)
- **Cold tier**: Iceberg tables on LocalStack (S3) — Parquet files, ACID, schema evolution
- **Union read**: Fluss transparently combines hot + cold data for queries

## Configuration

### Server-side (docker-compose FLUSS_PROPERTIES)

Tiering is configured in Fluss's server config, NOT in our Java code:

```yaml
FLUSS_PROPERTIES: |
  # Local storage (RocksDB + log segments)
  data.dir: /tmp/fluss-data

  # Fluss tiered storage (log segments offloaded to S3)
  remote.data.dir: s3://fluss-data/remote
  s3.endpoint: http://localstack:4566
  s3.access-key: test
  s3.secret-key: test
  s3.region: us-east-1
  s3.path-style-access: true

  # Iceberg datalake (lake connector plugin handles this)
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

### Table opt-in

To enable tiering for a specific table, set `table.datalake.enabled = 'true'`:

```sql
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

### Table Properties

| Property | Default | Description |
|----------|---------|-------------|
| `table.datalake.enabled` | `false` | Enable Iceberg tiering for this table |
| `table.datalake.freshness` | `1m` | How often to compact data to Iceberg |
| `table.datalake.auto-maintenance` | `true` | Automatic maintenance (compaction, expiration) |

## What Fluss Handles Automatically

| Capability | How Fluss Does It |
|------------|-------------------|
| Catalog connection | Server-side `datalake.iceberg.type`, `datalake.iceberg.uri` |
| S3/LocalStack config | Server-side `datalake.iceberg.s3.*` prefix-stripping |
| Schema mapping | Maps Fluss table schema → Iceberg schema with `__bucket`, `__offset`, `__timestamp` system columns |
| Data compaction | Flink tiering job: reads Fluss → writes Parquet → commits Iceberg snapshot |
| Union read | Fluss transparently unions hot (Fluss) + cold (Iceberg) data for queries |
| Scheduling | Configurable `table.datalake.freshness` (e.g., `30s`, `5m`) |
| Primary key support | Auto-partitions by `bucket(pk, N)` and sorts by `__offset` |
| Maintenance | Auto-compaction, snapshot expiration, orphan file cleanup |

## JAR Dependencies

### Fluss Server plugins (`FLUSS_HOME/plugins/`)

| JAR | Directory | Purpose |
|-----|-----------|---------|
| `fluss-fs-s3-1.0-SNAPSHOT.jar` | `plugins/fluss-fs-s3/` | S3 filesystem for `remote.data.dir` |
| `fluss-lake-iceberg-1.0-SNAPSHOT.jar` | `plugins/iceberg/` | Iceberg lake connector plugin |

### Flink classpath (`FLINK_HOME/lib/`)

| JAR | Purpose |
|-----|---------|
| `fluss-flink-1.20-1.0-SNAPSHOT.jar` | Fluss Flink connector |
| `fluss-lake-iceberg-1.0-SNAPSHOT.jar` | Fluss Iceberg lake connector |
| `iceberg-flink-runtime-1.20-1.10.1.jar` | Iceberg Flink integration |
| `iceberg-aws-bundle-1.10.1.jar` | S3 support for Iceberg |
| `hadoop-client-api-3.3.5.jar` | Hadoop client (required by Iceberg) |
| `hadoop-client-runtime-3.3.5.jar` | Hadoop client runtime |
| `postgresql-42.7.4.jar` | JDBC driver (not needed with REST catalog; kept for compatibility) |

### Flink `opt/` (`FLINK_HOME/opt/`)

| JAR | Purpose |
|-----|---------|
| `fluss-flink-tiering-1.0-SNAPSHOT.jar` | The pre-built tiering Flink job |

## Iceberg Table Schema

Fluss automatically maps the Fluss table schema to Iceberg with three additional system columns:

| Column | Type | Description |
|--------|------|-------------|
| `__bucket` | INT | Fluss bucket identifier for data distribution |
| `__offset` | BIGINT | Fluss log offset for ordering and seeking |
| `__timestamp` | TIMESTAMP_LTZ | Fluss log timestamp for temporal ordering |

## Catalog Choice: Polaris REST (not JDBC or Hive Metastore)

We use the **Polaris REST catalog** (Apache Polaris 1.3.0-incubating) as the Iceberg catalog:

- Polaris provides a standards-compliant Iceberg REST catalog (`datalake.iceberg.type: rest`)
- Runs as a single container with no external dependencies (in-memory mode for dev/CI)
- No JDBC database or Hive Metastore Thrift service needed
- Supports OAuth2 authentication (bootstrap credentials: `root:s3cr3t`)
- Polaris manages catalog/namespace/table metadata directly
- Readable by any Iceberg-compatible engine (Flink, Spark, Trino)

## Local Development

```bash
# Start core services only (Fluss + ZK + LocalStack)
docker compose up -d

# Start full lakehouse stack (adds Polaris, Flink, tiering job)
docker compose --profile lakehouse up -d

# Run all tests
./gradlew build

# Run integration tests (requires Fluss + LocalStack running)
./gradlew :test:integration:test
```

## Failure Boundaries

- Iceberg tiering failures do NOT affect ingress or dispatch
- The tiering job runs as an independent Flink job — isolated failure domain
- Fluss handles retry and checkpointing natively
- If S3 is unreachable, tiering pauses but broker continues
- Failed compactions leave data in Fluss (no data loss)
