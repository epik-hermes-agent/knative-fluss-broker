# Failure Scenarios Runbook

## Scenario 1: Subscriber Down
**Symptom:** Dispatcher shows retries, increasing delivery latency.
**Behavior:** Events retry with exponential backoff. After max attempts → DLQ.
**Test:** `DispatcherIntegrationTest.shouldHandleSubscriberFailure()`

## Scenario 2: Slow Subscriber
**Symptom:** Lane credits deplete, lane pauses.
**Behavior:** Credit bucket drains, Fluss scan pauses for that lane. Other lanes unaffected.
**Test:** `BackpressureIntegrationTest.shouldPauseWhenCreditsExhausted()`

## Scenario 3: Fluss Unavailable
**Symptom:** Ingress returns 503.
**Behavior:** Client should retry. Circuit breaker may trip.
**Test:** Integration test with Fluss container stopped.

## Scenario 4: Poison Message
**Symptom:** Event always fails (e.g., malformed data).
**Behavior:** Retry exhaustion → DLQ. DLQ handler logs metadata.
**Test:** `DispatcherIntegrationTest.shouldHandleClientError()`

## Scenario 5: Iceberg Tiering Failure
**Symptom:** Tiering job logs errors, Fluss data not compacted.
**Behavior:** Data stays in Fluss. Next tiering run retries. Core broker unaffected.
**Test:** Lakehouse integration test with S3 unavailable.

## Scenario 6: Schema Incompatibility
**Symptom:** Ingress rejects event with 400.
**Behavior:** Event not persisted. Client sees schema diff in response.
**Test:** `SchemaRegistryTest` with incompatible evolution.
