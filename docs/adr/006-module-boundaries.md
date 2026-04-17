# ADR-006: Module Boundaries

## Status
Accepted

## Context
Need clear module separation for independent development, testing, and deployment.

## Decision
Two top-level module groups: `data-plane` and `control-plane`, with shared `test` infrastructure.

## Rationale
1. **Data plane** handles event flow: ingress, storage, dispatch, schema, delivery.
2. **Control plane** handles K8s reconciliation: CRDs, controllers.
3. **Test modules** are separate to avoid circular dependencies.
4. **Clean dependency graph**: common → storage → schema → ingress/dispatcher.

## Module Dependency Graph
```
common ← storage-fluss ← schema ← ingress
                    ↑          ↓
              delivery ← dispatcher
              
api ← controller
```
