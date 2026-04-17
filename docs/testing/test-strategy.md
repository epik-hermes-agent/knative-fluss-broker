# Test Strategy

## Test Layers

### Layer 1: Unit Tests (fast, no infra)
- Envelope model validation
- CloudEvent mapping
- Filter evaluation (exact match, wildcards)
- Schema inference and validation
- Credit bucket backpressure logic
- Retry queue scheduling
- Config objects

**Run:** `./gradlew test`

### Layer 2: Integration Tests (Docker required)
- Ingress → Fluss persistence
- Fluss → Dispatcher → WireMock subscriber
- Retry with backoff
- DLQ routing
- Schema registry operations
- Backpressure lane pause/resume

**Run:** `./gradlew integrationTest`

### Layer 3: E2E Tests (full stack)
- Producer → Ingress → Fluss → Dispatcher → Subscriber
- Slow subscriber backpressure scenario
- DLQ path for poison messages
- Iceberg tiering path (when enabled)

**Run:** `./gradlew e2eTest`

### Layer 4: Performance Smoke Tests
- Batch publish + fast subscriber throughput
- Slow subscriber bounded in-flight behavior
- Multi-trigger fairness

**Run:** `./gradlew performanceSmokeTest`

## Test Profiles

### Fast Profile (default)
Core broker behavior without Iceberg infrastructure.

```bash
./gradlew test integrationTest
```

### Lakehouse Profile
Full stack including Flink, MinIO, Hive Metastore.

```bash
docker compose --profile lakehouse up -d
./gradlew test integrationTest e2eTest -Plakehouse
```

## Testcontainers

| Container | Purpose |
|-----------|---------|
| Fluss | Event storage integration |
| MinIO | S3-compatible storage for Iceberg |
| WireMock | HTTP subscriber stubs |
| PostgreSQL | Hive Metastore backend (lakehouse) |

## WireMock Scenarios

| Scenario | Config |
|----------|--------|
| Healthy subscriber | 200 OK |
| Server error (retryable) | 500 |
| Client error (DLQ) | 400 |
| Rate limited | 429 + Retry-After |
| Timeout | Delayed response |

## Coverage Targets

- Unit test coverage: 80%+ for business logic
- Integration coverage: all data paths verified
- E2E coverage: happy path + 2 failure scenarios
