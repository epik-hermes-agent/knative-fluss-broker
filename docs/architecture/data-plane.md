# Data Plane Architecture

The data plane handles the flow of events from sources to subscribers, covering ingress, storage, and delivery. This document describes the ingress handler, dispatcher, delivery model, and backpressure mechanisms.

## Data Flow Summary

```
Source -> Ingress -> Fluss Log Table -> Dispatcher -> Subscriber
                 |                                      |
                 v                                      v
           Schema Registry                     [Failure] -> DLQ
                 |                                      |
                 v                                      v
           Envelope Build                   [Retry Queue -> Backoff]
```

## Ingress Handler

### HTTP Endpoint

The ingress exposes a single HTTP endpoint that accepts CloudEvents in both structured (JSON) and binary content modes:

```
POST /{namespace}/{broker}
Content-Type: application/cloudevents+json  (structured)
  -- or --
Content-Type: application/json
ce-specversion: 1.0
ce-type: com.example.event
ce-source: /myapp/service
ce-id: abc-123  (binary)
```

### Ingress Pipeline

```
1. Receive HTTP request
   |
2. Parse CloudEvent (structured or binary mode)
   |
3. Validate required attributes:
   - specversion == "1.0"
   - type is non-empty
   - source is non-empty
   - id is non-empty
   |
4. Resolve schema (if schema registry enabled):
   - Lookup by (event_type, content_type)
   - If not found: register new schema
   - If found: use existing schema_id, increment schema_version if evolved
   |
5. Build envelope row:
   - event_id = CloudEvent id
   - event_source = CloudEvent source
   - event_type = CloudEvent type
   - event_time = CloudEvent time or now()
   - content_type = data content type
   - data = CloudEvent data (bytes)
   - schema_id = resolved schema ID
   - schema_version = resolved schema version
   - attributes = CloudEvent extensions (map)
   - ingestion_time = server timestamp
   - ingestion_date = server date (partition key)
   |
6. Write to Fluss log table:
   - Batch write if multiple events in single request
   - Async acknowledgment (ack on Fluss write confirmation)
   |
7. Return 202 Accepted with:
   - event_id
   - ingestion_time
   - schema_id
   - schema_version
```

### Write Path Configuration

```yaml
ingress:
  fluss:
    write-mode: "log"           # Log table mode (append-only)
    batch-size: 100             # Max events per batch write
    batch-timeout-ms: 50        # Max wait for batch fill
    ack-timeout-ms: 5000        # Max wait for Fluss ack
    retry:
      max-attempts: 3
      backoff-ms: 100
  schema:
    auto-register: true         # Auto-register unknown schemas
    enforce: false              # v1: lenient, v2: strict validation
```

### Ingress Error Handling

| Scenario | HTTP Status | Behavior |
|----------|------------|----------|
| Invalid CloudEvent format | 400 | Return error, do not write |
| Missing required attributes | 400 | Return error, do not write |
| Schema validation failure | 400 | Return error (when enforce=true) |
| Fluss write timeout | 503 | Return 503, client should retry |
| Fluss unavailable | 503 | Return 503, circuit breaker may trip |
| Fluss write error | 500 | Log error, return 500 |

## Dispatcher

The dispatcher is the delivery engine. Each trigger gets its own dispatcher instance that reads events from the Fluss table and delivers them to the subscriber.

### Dispatcher Lifecycle

```
Trigger Created
  |
  v
Reconciler creates DispatcherConfig
  |
  v
Dispatcher starts:
  1. Open Fluss scan on log table
  2. Establish HTTP connection to subscriber
  3. Initialize credit pool
  4. Begin delivery loop
  |
  v
Delivery Loop (per lane):
  while (hasCredits() && hasEvents()) {
    event = readFromFluss(offset)
    credits.decrement()
    deliver(event, callback)
    // callback: onAck -> advance offset, credits.refill(1)
    //           onNack -> retry or DLQ
  }
```

### Delivery Modes

#### Push Delivery (Default)

Events are delivered via HTTP POST to the subscriber endpoint:

```
POST {subscriber.uri}
Content-Type: application/cloudevents+json
```

The dispatcher sends the full CloudEvent, reconstructing it from the Fluss envelope.

#### Direct Fluss Access (Advanced)

For high-throughput subscribers, the dispatcher can expose a Fluss scan cursor directly. The subscriber connects to Fluss and reads events itself, reporting offsets back.

Configuration:
```yaml
dispatcher:
  delivery-mode: "push"           # or "direct-fluss"
  direct-fluss:
    fluss-endpoint: "fluss://localhost:9123"
    table-path: "knative_default.broker_default"
```

### At-Least-Once Delivery

The dispatcher guarantees at-least-once delivery through:

1. **Offset Tracking**: The dispatcher tracks the last successfully delivered offset per trigger.
2. **Delivery Confirmation**: Events are only acknowledged after the subscriber returns 2xx.
3. **Retry on Failure**: Failed deliveries are retried with exponential backoff.
4. **Dead Letter Queue**: Events that exhaust retries are sent to the configured DLQ (itself a Fluss table).

### Retry Policy

```
Delivery attempt N:
  |
  +-- Subscriber returns 2xx --> Ack event, advance offset
  |
  +-- Subscriber returns 4xx (non-retryable, e.g., 400) --> DLQ immediately
  |
  +-- Subscriber returns 5xx or timeout --> Schedule retry
       |
       v
     Delay = min(initial_delay * (multiplier ^ attempt), max_delay)
              + jitter(random, 0-250ms)
       |
       v
     Retry queue (delay-priority queue)
       |
       v
     Re-deliver after delay

Default retry configuration:
  max-attempts: 5
  initial-delay: 1s
  multiplier: 2.0
  max-delay: 60s
  jitter: 0-250ms random
```

### Dead Letter Queue

When an event exhausts all retry attempts or receives a non-retryable 4xx response, it is sent to the dead letter queue.

DLQ implementation:
- Dedicated Fluss log table: `knative_{namespace}.dlq_{triggerName}`
- Original event envelope preserved
- Added metadata: `dlq_reason`, `dlq_attempts`, `dlq_last_error`, `dlq_timestamp`
- DLQ events can be replayed via a separate management API

## Backpressure Model

The dispatcher uses a **credit-based backpressure** system to prevent slow subscribers from stalling the pipeline.

### Credit System

Each subscriber lane (concurrency slot) has a credit counter:

```
Initial credits: 100 (configurable)
Credit refill: 10/second (configurable)

Flow:
1. Dispatcher has N credits
2. Read next event from Fluss
3. If credits > 0: deliver event, decrement credit
4. If credits == 0: pause lane (stop reading from Fluss)
5. On ack/nack: credit returns to pool
6. Refill timer adds credits periodically
7. When credits > 0 again: resume lane
```

### Lane Management

```
Trigger with max-concurrency: 3
  |
  +-- Lane 0: credit=45, active, delivering event #101
  +-- Lane 1: credit=30, active, delivering event #102
  +-- Lane 2: credit=0,  paused (waiting for credits)
  |
  Global:
    events-read: 102
    events-acked: 95
    events-nacked: 2
    events-retrying: 5
```

### Fairness

Fairness across multiple triggers:
- Each trigger's dispatcher runs independently
- Triggers do not compete for resources at the dispatcher level
- Fluss scan reads are per-trigger (separate cursors)
- Backpressure is per-trigger, not global

Fairness within a trigger (multiple lanes):
- Round-robin event distribution across lanes
- Independent credit pools per lane
- A stuck lane (slow subscriber) does not block other lanes

### Backpressure Tuning

```yaml
dispatcher:
  max-concurrency: 10        # Number of concurrent delivery lanes
  initial-credits: 100       # Starting credits per lane
  credit-refill-rate: 10     # Credits added per second per lane
  credit-refill-interval: 100ms  # How often to run refill
  pause-threshold: 0         # Pause lane when credits reach this value
  resume-threshold: 10       # Resume lane when credits reach this value
```

## Ingress and Dispatcher Scaling

### Ingress Scaling

- Ingress is stateless (except for schema registry, which is backed by Fluss)
- Scale horizontally via Kubernetes HPA on CPU/request rate
- Each replica writes to the same Fluss table
- No coordination needed between ingress replicas

### Dispatcher Scaling

- One dispatcher per trigger (not scaled horizontally in v1)
- The dispatcher can be CPU-intensive for high-throughput triggers
- Future: shard dispatcher by Fluss partition for horizontal scaling
- For now, increase `max-concurrency` and credit parameters

### Monitoring Metrics

Key metrics exposed (Prometheus):

```
# Ingress
fluss_broker_ingress_events_total         (counter)
fluss_broker_ingress_errors_total         (counter)
fluss_broker_ingress_latency_seconds     (histogram)

# Dispatcher
fluss_broker_dispatcher_events_delivered_total  (counter)
fluss_broker_dispatcher_events_retried_total    (counter)
fluss_broker_dispatcher_events_dlq_total        (counter)
fluss_broker_dispatcher_delivery_latency_seconds (histogram)
fluss_broker_dispatcher_credits_available       (gauge)
fluss_broker_dispatcher_lanes_paused            (gauge)
fluss_broker_dispatcher_lanes_active            (gauge)

# Fluss
fluss_broker_fluss_write_latency_seconds       (histogram)
fluss_broker_fluss_scan_lag                    (gauge)
```
