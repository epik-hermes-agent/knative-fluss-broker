# Schema Model

The schema model defines how event data schemas are managed, stored, and versioned across the broker. It covers the envelope schema, schema registry, and evolution strategy.

## Schema Overview

The schema model has two layers:

1. **Envelope Schema**: The fixed column structure used to store events in Fluss (and optionally Iceberg).
2. **Event Schema**: The user-defined schema for the event `data` field, managed by the schema registry.

## Envelope Schema

Every event stored in the broker uses a fixed envelope schema. This schema defines the metadata columns that surround the event data.

### Fluss Log Table Schema

```
Table: knative_{namespace}.broker_{brokerName}
Storage: Fluss Log Table (append-only, no primary key)
Distribution: HASH(event_id) with 4 buckets
Datalake: enabled (Iceberg tiering)

Column Name         | Type                   | Nullable | Description
--------------------|------------------------|----------|-------------------------------------------
event_id            | STRING                 | NO       | CloudEvent id (unique per event)
event_source        | STRING                 | NO       | CloudEvent source attribute
event_type          | STRING                 | NO       | CloudEvent type attribute
event_time          | TIMESTAMP(3)           | YES      | CloudEvent time (null if not provided)
content_type        | STRING                 | NO       | MIME type of data field
data                | BYTES                  | YES      | CloudEvent data payload (raw bytes)
schema_id           | INT                    | YES      | Schema identifier from registry
schema_version      | INT                    | YES      | Schema version number
attributes          | MAP<STRING, STRING>    | YES      | CloudEvent extension attributes
ingestion_time      | TIMESTAMP(3)           | NO       | Server-side ingestion timestamp
ingestion_date      | DATE                   | NO       | Date derived from ingestion_time
```

### Envelope Design Decisions

**Why explicit schema_id and schema_version columns?**

Storing schema metadata as first-class columns rather than burying it in the attributes map provides:

1. **Queryable schema info**: Analytics queries on the Iceberg side can filter by schema version.
2. **Fast schema resolution**: Dispatchers can look up schemas by ID/version without parsing attributes.
3. **Lakehouse alignment**: Iceberg tables benefit from typed, explicit columns.
4. **Schema evolution tracking**: We can query "how many events are on schema v1 vs v2" for migration planning.

**Why BYTES for data rather than STRING?**

The data field can contain arbitrary binary payloads (Protobuf, Avro, etc.). Storing as BYTES avoids encoding issues and supports all content types.

**Why MAP for attributes?**

CloudEvent extensions are unbounded key-value pairs. A MAP type handles arbitrary extensions without schema changes.

### Iceberg Table Schema

When Iceberg tiering is enabled, events are compacted into Iceberg tables with the same envelope schema:

```
Table: {catalog}.knative_{namespace}_broker_{brokerName}
Storage: Iceberg on S3
Partitioning: ingestion_date (Iceberg partition spec)

Column Name         | Iceberg Type           | Nullable | Notes
--------------------|------------------------|----------|-------
event_id            | string                 | NO       |
event_source        | string                 | NO       |
event_type          | string                 | NO       |
event_time          | timestamp              | YES      | microsecond precision
content_type        | string                 | NO       |
data                | binary                 | YES      |
schema_id           | int                    | YES      |
schema_version      | int                    | YES      |
attributes          | map<string, string>    | YES      |
ingestion_time      | timestamp              | NO       |
ingestion_date      | date                   | NO       | Partition column
```

Iceberg-specific additions:
- `_fluss_offset` (long): Fluss log offset at compaction time (for dedup)
- `_compaction_time` (timestamp): When this row was compacted to Iceberg

## Schema Registry

The schema registry manages event data schemas (not the envelope schema, which is fixed).

### Registry Design

The schema registry is embedded within the broker and backed by a Fluss table:

```
Fluss table: knative_{namespace}.schema_registry

Column Name       | Type              | Description
------------------|-------------------|----------------------------------------
schema_id         | INT (auto)        | Unique schema identifier
event_type        | STRING            | CloudEvent type this schema applies to
content_type      | STRING            | MIME type (application/json, etc.)
schema_version    | INT               | Monotonically increasing per event_type
schema_body       | STRING            | Schema definition (JSON Schema, etc.)
created_at        | TIMESTAMP         | Registration time
```

### Registration Flow

```
Ingress receives event:
  |
  v
1. Extract (event_type, content_type) from event
  |
  v
2. Lookup schema_registry table:
   SELECT schema_id, schema_version
   FROM schema_registry
   WHERE event_type = ? AND content_type = ?
   ORDER BY schema_version DESC LIMIT 1
  |
  +-- Found: use existing schema_id, schema_version
  |
  +-- Not found: register new schema
       |
       v
     a. Assign next schema_id (sequence)
     b. schema_version = 1
     c. Infer schema from event data (if content_type=application/json)
     d. INSERT INTO schema_registry
     e. Use new schema_id, schema_version
  |
  v
3. Write event with schema_id and schema_version
```

### Schema Inference

For JSON events, the schema is inferred from the event data at registration time:

```json
// Event data
{
  "orderId": "12345",
  "customer": {
    "name": "Alice",
    "email": "alice@example.com"
  },
  "items": [
    {"sku": "ABC", "qty": 2, "price": 29.99}
  ],
  "total": 59.98
}

// Inferred schema (JSON Schema)
{
  "type": "object",
  "properties": {
    "orderId": { "type": "string" },
    "customer": {
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "email": { "type": "string" }
      }
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "sku": { "type": "string" },
          "qty": { "type": "integer" },
          "price": { "type": "number" }
        }
      }
    },
    "total": { "type": "number" }
  }
}
```

## Schema Evolution

### Evolution Rules (v1)

Schema evolution in v1 is **backward-compatible only**:

| Change | Allowed? | Behavior |
|--------|----------|----------|
| Add new optional field | Yes | New schema version created |
| Add new required field | No | Rejected (return 400 on ingress) |
| Remove field | No | Rejected |
| Change field type | No | Rejected |
| Rename field | No | Rejected |

### Evolution Flow

```
Event with new field received:
  |
  v
1. Infer schema from event data
  |
  v
2. Compare with latest registered schema for (event_type, content_type)
  |
  v
3. If compatible (new optional fields only):
   |  a. Create new schema_version = latest + 1
   |  b. Store new schema body
   |  c. Write event with new schema_version
   |
   +-- If incompatible:
        a. Reject with 400 and schema diff in response
```

### Schema Resolution at Dispatch

When dispatching events to subscribers, the dispatcher resolves schemas for potential transformation:

```
Dispatcher has event with schema_id=42, schema_version=3
  |
  v
1. Fetch schema from registry: schema_id=42, version=3
  |
  v
2. If subscriber expects different schema:
   |  a. Apply backward-compatible transformation (add defaults for new fields)
   |  b. Log transformation metrics
   |
   +-- If subscriber schema is incompatible:
        a. Log warning
        b. Deliver event as-is (subscriber must handle)
        c. Future: configurable reject policy
```

## Versioning Strategy

### Per-Event-Type Versioning

Each event type has its own version counter:

```
com.example.order.created: v1, v2, v3 (3 versions)
com.example.order.cancelled: v1 (1 version)
com.example.payment.processed: v1, v2 (2 versions)
```

### Global Schema IDs

Schema IDs are globally unique (not per event type). This allows:
- Efficient lookup by schema_id alone
- Simpler Fluss table indexing
- Deduplication by schema_id in Iceberg

### Version Migration (Future v2)

For v2, we plan to support:
- Full schema evolution (add, remove, rename, type change)
- Schema compatibility modes (BACKWARD, FORWARD, FULL)
- Automatic subscriber schema negotiation
- Schema deprecation and sunset timelines

## Configuration

```yaml
schema:
  enabled: true
  auto-register: true
  enforce: false              # v1: lenient mode
  
  inference:
    enabled: true
    max-depth: 10             # Max nesting depth for JSON inference
    max-properties: 100       # Max properties per object
  
  registry:
    fluss-table: "schema_registry"
    cache-size: 1000          # In-memory cache size
    cache-ttl-minutes: 60     # Cache TTL
```

## Querying Schemas

### Management API

```
GET /api/v1/schemas
  ?namespace=my-namespace
  &eventType=com.example.order.created
  -> List of schema versions with metadata

GET /api/v1/schemas/{schemaId}
  -> Full schema definition

GET /api/v1/schemas/{schemaId}/compatibility
  -> Compatibility report against latest version
```

### Fluss Queries (for debugging)

```sql
-- List all schemas for an event type
SELECT * FROM knative_default.schema_registry
WHERE event_type = 'com.example.order.created'
ORDER BY schema_version DESC;

-- Find events using an old schema
SELECT COUNT(*) FROM knative_default.broker_default
WHERE schema_id = 42 AND schema_version = 1
AND ingestion_date >= '2024-01-01';
```
