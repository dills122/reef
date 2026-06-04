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
- runtime table bootstrap: runtime persistence initialization creates schema-qualified `runtime.*` tables and runtime routines for local compatibility
- auth table bootstrap: runtime persistence initialization creates schema-qualified `auth.*` role tables for local compatibility
- boundary table bootstrap: boundary idempotency and command-capture persistence initialize schema-qualified `boundary.*` tables for local compatibility
- admin table bootstrap: admin-specific durable tables are still planned unless explicitly covered by runtime/auth storage
- command log bootstrap: planned under `command_log`
- read-model bootstrap: planned under `read_model`

This is transitional only. Runtime, boundary, and auth bootstrap now targets explicit domain schemas instead of relying on root-level tables or JDBC `currentSchema` placement. Migration files now represent the live table shapes, but local startup still keeps service-side bootstrap as a compatibility fallback until the migration runner is verified against a clean stack.

## Current implementation checkpoint

- `PostgresRuntimePersistence` uses explicit `runtime.*` table/routine names for orders, executions, trades, runtime events, trace sequences, submit results, and reference data.
- `PostgresRuntimePersistence` uses explicit `auth.*` table names for roles and actor-role bindings.
- `PostgresIdempotencyStore` uses explicit `boundary.api_idempotency_records`.
- `PostgresCommandCaptureStore` uses explicit `boundary.api_command_captures`.
- Schema-name overrides are limited to simple identifiers before SQL interpolation.
- Domain migration files now represent the live runtime, auth, and boundary table shapes.
- `make dev-db-migrate` applies migrations in deterministic domain order and records checksums in `public.reef_schema_migrations`.
- Clean-stack verification passed with `make dev-db-migrate` against local Postgres on 2026-06-04.
- `PostgresSchemaMigrationIntegrationTest` verifies migration ledger entries and schema-owned table placement with a JDBC URL that does not set `currentSchema`.
- Full local-stack smoke passed after applying migrations, including boundary command capture and `/api/v1` submit/cancel flow.
- Service-side bootstrap remains a compatibility bridge, not the steady state.

## Next persistence-alignment work

1. Decide whether migrations run automatically in `dev-up`/runtime startup or remain an explicit setup command.
2. Remove or narrow service-side `CREATE TABLE IF NOT EXISTS` bootstrap once local setup and CI prove migration execution order.
3. Add the schema-placement integration test to a CI lane with an ephemeral Postgres service.
4. Revisit the outbox/event-backbone routine once runtime event payloads and publisher behavior are implemented.

## Split readiness checks to enforce in CI

1. no cross-domain foreign keys in new migrations
2. no cross-domain repository imports in service code
3. no cross-schema joins in hot write-path queries
4. migration PRs touch one domain folder unless explicitly approved
5. critical write-path changes include routine contract tests
6. command-log writes remain append-only except explicit status/attempt transitions
7. read-model migrations do not add dependencies to hot runtime write paths
