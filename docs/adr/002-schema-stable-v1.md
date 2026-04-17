# ADR-002: Schema-Stable v1 with Iceberg in Harness

## Status
Accepted

## Context
Schema evolution is complex. Iceberg supports schema evolution natively. We need to decide how much schema flexibility to offer in v1.

## Decision
**Schema-stable v1**: explicit schema_id + schema_version columns, no automatic ALTER TABLE. Iceberg tiering uses the same fixed envelope.

## Rationale
1. **Predictable behavior** — no surprise table mutations in production.
2. **Versioned rollout** — new schemas use new table versions, not in-place changes.
3. **Iceberg compatibility** — stable schemas make tiering reliable.
4. **v1 correctness** — we don't depend on immature schema evolution for core delivery.

## Consequences
- New event type schemas get new schema IDs, not table alterations.
- Schema evolution limited to backward-compatible additions in v1.
- Future v2 can add proper schema evolution with compatibility modes.
