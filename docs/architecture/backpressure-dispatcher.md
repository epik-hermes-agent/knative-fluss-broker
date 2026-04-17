# Backpressure Dispatcher

The dispatcher implements a credit-based backpressure system that prevents slow subscribers from stalling the event pipeline. This document details the credit model, lane management, and fairness guarantees.

## Problem Statement

Without backpressure:
- A slow subscriber causes the dispatcher to buffer events in memory
- Memory pressure can cause OOM kills
- Event lag grows unbounded
- Other subscribers (even fast ones) may be affected by shared resources

## Credit-Based Model

### Overview

Each delivery lane (concurrency slot) maintains a credit counter. Credits are consumed when events are delivered and returned when delivery completes (ack or nack). When credits are exhausted, the lane pauses reading from Fluss.

```
Credits Pool (per lane)
  |
  +-- [100] initial credits
  |
  +-- event delivered -> [99]
  +-- event delivered -> [98]
  +-- event acked     -> [99]  (credit returned)
  +-- event nacked    -> [99]  (credit returned)
  +-- event delivered -> [98]
  |
  +-- credits == 0 -> PAUSE lane
  |
  +-- [refill timer] -> +10 credits -> [10] -> RESUME lane
```

### Token Bucket Implementation

The credit system uses a token bucket algorithm per lane:

```java
public class CreditBucket {
  private final int maxCredits;
  private final int refillRate;        // tokens per refill
  private final Duration refillInterval;
  private int availableCredits;
  private Instant lastRefill;

  public synchronized boolean tryAcquire() {
    refill();
    if (availableCredits > 0) {
      availableCredits--;
      return true;
    }
    return false;
  }

  public synchronized void release() {
    availableCredits = Math.min(availableCredits + 1, maxCredits);
  }

  private void refill() {
    var now = Instant.now();
    var elapsed = Duration.between(lastRefill, now);
    var periods = elapsed.dividedBy(refillInterval);
    if (periods > 0) {
      int added = (int) periods * refillRate;
      availableCredits = Math.min(availableCredits + added, maxCredits);
      lastRefill = now;
    }
  }
}
```

### Configuration Parameters

```yaml
dispatcher:
  backpressure:
    # Initial credits per lane when dispatcher starts
    initial-credits: 100
    
    # Maximum credits a lane can accumulate
    max-credits: 200
    
    # Credits added per refill interval
    refill-rate: 10
    
    # How often the refill timer runs
    refill-interval: 100ms
    
    # Pause lane when credits reach this value
    pause-threshold: 0
    
    # Resume lane when credits reach this value (must be > pause-threshold)
    resume-threshold: 10
    
    # Maximum events buffered per lane (in-memory queue before delivery)
    lane-buffer-size: 50
```

## Lane Management

### Lane Structure

```
Trigger Dispatcher
  |
  +-- Lane 0
  |     creditBucket: CreditBucket(credits=45)
  |     status: ACTIVE
  |     inflightEvents: [event-101, event-102, event-103]
  |     deliveryThread: Thread-0
  |     flussCursor: offset=102
  |
  +-- Lane 1
  |     creditBucket: CreditBucket(credits=30)
  |     status: ACTIVE
  |     inflightEvents: [event-104]
  |     deliveryThread: Thread-1
  |     flussCursor: offset=104
  |
  +-- Lane 2
        creditBucket: CreditBucket(credits=0)
        status: PAUSED
        inflightEvents: []
        deliveryThread: Thread-2 (parked)
        flussCursor: offset=105
```

### Lane States

| State | Description | Scanning? | Delivering? |
|-------|------------|-----------|-------------|
| ACTIVE | Normal operation | Yes | Yes |
| PAUSED | No credits available | No | Draining inflight |
| DRAINING | Shutting down, waiting for inflight | No | Draining inflight |
| STOPPED | Fully stopped | No | No |

### Pause/Resume Mechanism

```java
public class DeliveryLane {
  private final CreditBucket credits;
  private final BlockingQueue<CloudEvent> buffer;
  private final SubscriberClient subscriber;
  private volatile LaneStatus status = LaneStatus.ACTIVE;
  
  public void deliveryLoop() {
    while (status != LaneStatus.STOPPED) {
      // Check if we can acquire a credit
      if (!credits.tryAcquire()) {
        // No credits: pause reading from Fluss
        status = LaneStatus.PAUSED;
        
        // Wait for credits to be refilled
        credits.awaitCredits(resumeThreshold);
        
        status = LaneStatus.ACTIVE;
        continue;
      }
      
      // Read event from buffer (blocks if buffer empty)
      var event = buffer.poll(100, TimeUnit.MILLISECONDS);
      if (event == null) {
        credits.release();  // Return unused credit
        continue;
      }
      
      // Deliver event
      inflightEvents.add(event);
      deliverWithRetry(event)
        .whenComplete((response, error) -> {
          inflightEvents.remove(event);
          credits.release();  // Return credit on completion
          
          if (error != null || !response.isSuccess()) {
            handleDeliveryFailure(event, error, response);
          } else {
            acknowledgeEvent(event);
          }
        });
    }
  }
}
```

### Fluss Scan Pausing

When a lane is paused, it stops polling from Fluss:

```java
public class FlussEventScanner {
  private final Map<Integer, LaneScanner> laneScanners;
  
  public void scanLoop() {
    // Check which lanes are active
    var activeLanes = laneScanners.entrySet().stream()
      .filter(e -> e.getValue().getLane().isActive())
      .toList();
    
    if (activeLanes.isEmpty()) {
      // All lanes paused: stop scanning entirely
      Thread.sleep(scanPauseInterval);
      return;
    }
    
    // Read events from Fluss
    var events = flussClient.scan(tablePath, cursor, batchSize);
    
    // Distribute to active lanes (round-robin)
    int laneIdx = 0;
    for (var event : events) {
      var targetLane = activeLanes.get(laneIdx % activeLanes.size());
      targetLane.getValue().getLane().enqueue(event);
      laneIdx++;
    }
    
    cursor.advance(events.size());
  }
}
```

## Retry and Credit Interaction

### Retry Credit Model

When an event is retried, it consumes credits differently from initial delivery:

```
Initial delivery:
  - Consume 1 credit from lane pool
  - Credit returned on ack/nack (including after retries)

Retry delivery:
  - Does NOT consume additional credit
  - Original credit stays "spent" until final ack/nack
  - Prevents retry storms from draining credits
```

### Retry Queue

```
Retry Queue (delay-priority queue)
  |
  +-- event-105, retry-at: T+1s, attempt: 2
  +-- event-108, retry-at: T+2s, attempt: 3
  +-- event-103, retry-at: T+4s, attempt: 4
  |
  Delivery loop checks retry queue each iteration:
    if (retryQueue.peek().retryAt <= now) {
      var retryEvent = retryQueue.poll();
      deliverWithRetry(retryEvent);  // No new credit consumed
    }
```

### DLQ Credit Behavior

When an event is sent to DLQ (retries exhausted):
- Original credit is returned
- Event is written to DLQ table
- Dispatcher continues with next event

## Fairness

### Cross-Trigger Fairness

Each trigger has its own independent dispatcher. Fairness across triggers is managed at the Fluss scan level:

```
Fluss Cluster
  |
  +-- knative_default.broker_default
        |
        +-- Trigger-A Dispatcher: scan offset=1000
        +-- Trigger-B Dispatcher: scan offset=500
        +-- Trigger-C Dispatcher: scan offset=2000
```

- Each trigger maintains its own Fluss cursor
- Triggers scan independently (no shared read lock)
- Fluss handles concurrent scans efficiently
- No trigger can block another trigger's reads

### Intra-Trigger Fairness (Lanes)

Within a trigger, fairness is managed by round-robin event distribution:

```
Incoming events: [E1, E2, E3, E4, E5, E6]
  |
  v
Round-robin distribution:
  Lane 0: [E1, E4]
  Lane 1: [E2, E5]
  Lane 2: [E3, E6]
```

If a lane is paused (no credits), events skip that lane:

```
Incoming events: [E1, E2, E3, E4, E5, E6]
Lane 1 is PAUSED
  |
  v
Round-robin (skip paused):
  Lane 0: [E1, E4]
  Lane 1: (paused, skipped)
  Lane 2: [E2, E5, E3, E6]  (gets extra events)
```

### Slow Subscriber Isolation

A slow subscriber affects only its own lane:
- Lane runs out of credits and pauses
- Other lanes continue reading and delivering
- No memory buildup (lane buffer is bounded)
- Fluss cursor advances only for delivered events

### Burst Handling

For bursty traffic:

```
Burst arrives: 1000 events
  |
  v
Lane 0: has 100 credits, delivers 100 events, pauses
Lane 1: has 100 credits, delivers 100 events, pauses
Lane 2: has 100 credits, delivers 100 events, pauses
  |
  v
Remaining 700 events stay in Fluss
  |
  v
Refill timer kicks in (10 credits/100ms per lane):
  100ms: +30 credits total -> 30 more events delivered
  200ms: +30 credits total -> 30 more events delivered
  ...
  |
  v
Steady state: ~300 events/second throughput
  (10 credits/100ms * 3 lanes * 10 = 300/s)
```

## Monitoring

### Backpressure Metrics

```
# Per-trigger metrics
fluss_broker_dispatcher_credits_available{trigger="my-trigger", lane="0"} 45
fluss_broker_dispatcher_credits_available{trigger="my-trigger", lane="1"} 30
fluss_broker_dispatcher_credits_available{trigger="my-trigger", lane="2"} 0

fluss_broker_dispatcher_lane_status{trigger="my-trigger", lane="0"} 1  # 1=active, 0=paused
fluss_broker_dispatcher_lane_status{trigger="my-trigger", lane="1"} 1
fluss_broker_dispatcher_lane_status{trigger="my-trigger", lane="2"} 0

fluss_broker_dispatcher_inflight_events{trigger="my-trigger"} 4

fluss_broker_dispatcher_retry_queue_size{trigger="my-trigger"} 3

# Aggregated
fluss_broker_dispatcher_lanes_paused 1
fluss_broker_dispatcher_lanes_active 2
```

### Alerting Rules

```yaml
# Alert when a lane is paused for too long
- alert: DispatcherLanePaused
  expr: |
    fluss_broker_dispatcher_lane_status == 0
    and
    fluss_broker_dispatcher_lane_status offset 5m == 0
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Lane {{ $labels.lane }} for trigger {{ $labels.trigger }} paused for 5+ minutes"

# Alert when credits are consistently low
- alert: DispatcherLowCredits
  expr: |
    fluss_broker_dispatcher_credits_available < 10
  for: 2m
  labels:
    severity: info
  annotations:
    summary: "Low credits on trigger {{ $labels.trigger }} lane {{ $labels.lane }}"
```

## Tuning Guide

### Low-Latency Setup

For subscribers that need low-latency delivery:

```yaml
dispatcher:
  backpressure:
    initial-credits: 500
    max-credits: 1000
    refill-rate: 50
    refill-interval: 50ms
  max-concurrency: 1
```

### High-Throughput Setup

For high-throughput subscribers:

```yaml
dispatcher:
  backpressure:
    initial-credits: 200
    max-credits: 500
    refill-rate: 20
    refill-interval: 100ms
  max-concurrency: 20
```

### Slow/Unreliable Subscriber

For subscribers that are slow or intermittently available:

```yaml
dispatcher:
  backpressure:
    initial-credits: 50
    max-credits: 100
    refill-rate: 5
    refill-interval: 200ms
  max-concurrency: 3
  delivery:
    retry: 10
    backoff-delay: "PT5S"
```
