# ADR-004: Credit-Based Backpressure Dispatcher

## Status
Accepted

## Context
Without backpressure, slow subscribers cause unbounded memory growth and cascade failures. We need a delivery model that prevents this.

## Decision
Use a **credit-based / token-bucket** dispatch model per subscriber lane.

## Rationale
1. **Bounded in-flight** — each lane has a fixed credit pool.
2. **Natural pause/resume** — no events read when credits exhausted.
3. **Retry isolation** — retries don't consume new credits.
4. **Fairness** — round-robin across lanes, paused lanes skip.
5. **Observable** — credit levels are a clear backpressure signal.

## Consequences
- Each trigger gets N lanes (maxConcurrency).
- Each lane has independent CreditBucket.
- Fluss scan pauses when all lanes paused.
- Retry-After from subscribers respected.
