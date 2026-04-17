# ADR-001: Native Fluss Broker vs Kafka Compatibility

## Status
Accepted

## Context
Knative Eventing supports multiple broker implementations. The Knative Kafka Broker uses Kafka as the backend. We need to decide whether to build a Kafka-compatible layer or use Fluss natively.

## Decision
Build a **native Fluss Broker** — no Kafka compatibility layer.

## Rationale
1. **Fluss Log Tables** are the natural storage format — wrapping them in Kafka protocol adds complexity without value.
2. **Fluss-native features** (tiering, lakehouse integration) are first-class only with direct Fluss API.
3. **Lower latency** — no protocol translation overhead.
4. **Schema-aware storage** — Fluss tables support typed columns natively, Kafka doesn't.
5. **Simpler codebase** — one less abstraction layer to maintain.

## Consequences
- Producers must use CloudEvents HTTP (not Kafka protocol) — this is the Knative standard anyway.
- Future direct Fluss consumers use Fluss client, not Kafka client.
- No Kafka ecosystem tools work directly — need Fluss-native equivalents.
