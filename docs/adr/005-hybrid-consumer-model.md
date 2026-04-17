# ADR-005: Hybrid Consumer Model

## Status
Accepted

## Context
Some internal consumers may want to read directly from Fluss for higher throughput, bypassing the Knative push model.

## Decision
Design for a **hybrid model**: Knative Triggers for normal push subscribers, future direct Fluss clients for trusted internal consumers.

## Rationale
1. **Knative compatibility** — standard Triggers work for all external subscribers.
2. **Performance option** — high-throughput internal services can read Fluss directly.
3. **Clean separation** — direct Fluss readers are outside Trigger semantics (documented as experimental).

## Consequences
- Repo structure supports both modes.
- Direct Fluss reader example provided as experimental.
- Trigger-based delivery is the default and primary path.
