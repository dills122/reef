# DB Split-Readiness Guardrails

This document defines constraints for the single-Postgres local model so future scoped DB extraction remains low-friction.

## Current domain schemas

- `runtime`
- `command_log` (planned; immutable inbound command capture)
- `read_model` (planned; query/UI projections)
- `auth`
- `admin`
- `boundary`
- `orchestration` (planned; scheduler/job-runner state)
- `analytics` (planned; transformed query-optimized projections)

## Guardrails

1. No cross-domain foreign keys
- foreign keys must remain inside a single domain schema
- cross-domain references should use identifiers and application-level validation

2. No cross-domain repository coupling
- repositories/services should not query another domain schema directly
- cross-domain interactions happen through application-layer commands/events

3. Forward-only migrations by domain
- each domain owns its own migration folder under `scripts/dev/db/migrations/`
- no editing of applied migration files

4. Boundary persistence isolation
- API idempotency and boundary-specific persistence remain in `boundary` schema
- runtime write-model persistence remains in `runtime` schema

5. Orchestration state isolation
- scheduled job definitions, run states, retries, and artifacts belong to `orchestration`
- job-runner must not depend on cross-schema joins to execute state transitions

6. Analytics isolation from hot write path
- transformed analytics tables/views belong to `analytics`
- runtime request path must not require analytics schema queries

7. Cross-domain access policy
- read access across domains should occur through explicit interfaces/events
- emergency direct SQL exceptions must be documented and time-bounded

8. Procedure-first mutation policy
- write-path mutations should be executed via schema-owned Postgres routines where practical
- ad hoc multi-statement mutation logic in service code is disallowed for critical workflows
- routine ownership follows domain schema ownership to keep future DB extraction low-friction

9. Command-log isolation
- inbound client commands belong in an append-only `command_log` slice
- command-log tables should not depend on runtime write-model tables through foreign keys
- command intake should be able to scale and retain independently from downstream domain processing

10. Read-model isolation
- UI/query projections belong in `read_model`
- read-model writes must not be required for the order-command hot path to acknowledge or complete
- projections should be rebuildable from runtime state/events

## Current local bootstrap model

- schema creation: `scripts/dev/db/init/001_create_domain_schemas.sql`
- runtime/admin table bootstrap: runtime persistence initialization
- boundary idempotency table bootstrap: boundary idempotency persistence initialization
- command log bootstrap: planned under `command_log`
- read-model bootstrap: planned under `read_model`

This is acceptable for current phase, with the migration folders now established for forward-only evolution.

## Split readiness checks to enforce in CI

1. no cross-domain foreign keys in new migrations
2. no cross-domain repository imports in service code
3. no cross-schema joins in hot write-path queries
4. migration PRs touch one domain folder unless explicitly approved
5. critical write-path changes include routine contract tests
6. command-log writes remain append-only except explicit status/attempt transitions
7. read-model migrations do not add dependencies to hot runtime write paths
