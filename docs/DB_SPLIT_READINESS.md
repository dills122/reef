# DB Split-Readiness Guardrails

This document defines constraints for the local Postgres model so scoped DB extraction remains low-friction.

## Current domain schemas

- `runtime`
- `command_log` (immutable inbound command capture)
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

11. Pre-settlement and hot-cache policy
- canonical matched execution/trade facts must be durably recorded before downstream settlement or user-visible lifecycle claims depend on them
- Redis may hold transient reservations, hot counters, rate/backpressure state, cached read models, or leaderboard-style derived state
- Redis must not be the authoritative pre-settlement ledger for executions, trades, settlement obligations, or replay/audit facts

## Current local bootstrap model

- schema creation: `scripts/dev/db/init/001_create_domain_schemas.sql`
- runtime table ownership: migrations create schema-qualified `runtime.*` tables and runtime routines
- auth table ownership: migrations create schema-qualified `auth.*` role tables
- boundary table ownership: migrations create schema-qualified `boundary.*` idempotency and command-capture tables
- admin table bootstrap: admin-specific durable tables are still planned unless explicitly covered by runtime/auth storage
- command log table ownership: migrations create schema-qualified `command_log.commands`; runtime wiring is available behind `EXTERNAL_API_COMMAND_LOG_MODE`
- read-model bootstrap: planned under `read_model`
- boundary/projection DB bootstrap: Docker starts `boundary-postgres` and `projection-postgres`, and the dev migration runner applies the same forward migrations to both proof-slice databases

Runtime, boundary, and auth persistence now targets explicit domain schemas instead of relying on root-level tables or JDBC `currentSchema` placement. Migration files represent the live table shapes, and local startup applies migrations before the full stack starts. Docker/local runtime uses `RUNTIME_DB_BOOTSTRAP_MODE=validate` by default so Postgres-backed stores fail fast if migrated objects are missing. `compat` remains available as a local repair fallback.

`RUNTIME_PROJECTION_POSTGRES_JDBC_URL` enables a physical projection store. When set, `PostgresRuntimePersistence` keeps canonical append/reference/auth access on `RUNTIME_POSTGRES_JDBC_URL` and routes submit-result/order/execution/trade/runtime-event projection reads and writes through the projection JDBC pool. The stream-ack projector reads canonical payloads from the runtime DB, writes normalized projection rows to the projection DB, and advances projection-local watermarks. When unset, both paths share the runtime data source for backward compatibility.

`RUNTIME_DB_URL` points boundary/idempotency/stream-intake storage at `boundary-postgres` in local Docker. This keeps JetStream publish acceptance metadata and scoped idempotency rows off the canonical runtime database while preserving `202` only after durable JetStream publish acknowledgement.

## Current implementation checkpoint

- `PostgresRuntimePersistence` uses explicit `runtime.*` table/routine names for orders, executions, trades, runtime events, trace sequences, submit results, and reference data.
- `PostgresRuntimePersistence` can route normalized projection tables and watermarks to `RUNTIME_PROJECTION_POSTGRES_JDBC_URL` while keeping canonical command results and venue events on `RUNTIME_POSTGRES_JDBC_URL`.
- `PostgresRuntimePersistence` uses explicit `auth.*` table names for roles and actor-role bindings.
- `PostgresIdempotencyStore` uses explicit `boundary.api_idempotency_records`.
- `StreamCommandIntake` uses `RUNTIME_DB_URL`, which targets `boundary-postgres` in local Docker.
- `PostgresCommandCaptureStore` uses explicit `boundary.api_command_captures`.
- `CommandLogCommandCaptureStore` can append inbound API commands to `command_log.commands` behind `EXTERNAL_API_COMMAND_LOG_MODE`.
- Schema-name overrides are limited to simple identifiers before SQL interpolation.
- Domain migration files now represent the live runtime, auth, boundary, and command-log table shapes.
- `make dev-db-migrate` applies migrations in deterministic domain order and records checksums in `public.reef_schema_migrations`.
- Clean-stack verification passed with `make dev-db-migrate` against local Postgres on 2026-06-04.
- `PostgresSchemaMigrationIntegrationTest` verifies migration ledger entries, schema-owned table placement, validation-mode store construction, and command-capture writes with a JDBC URL that does not set `currentSchema`.
- Full local-stack smoke passed after applying migrations, including boundary command capture and `/api/v1` submit/cancel flow.
- `make dev-up` and `make dev-reset` start canonical, boundary, and projection Postgres services, apply migrations, then start the full stack.
- Docker/local runtime defaults to schema validation mode.
- Service-side compatibility bootstrap remains available through `RUNTIME_DB_BOOTSTRAP_MODE=compat`, not as the local default.

## HFT SQL audit checkpoint

The current HFT/finance SQL audit is tracked in [`HFT_SQL_AUDIT.md`](./HFT_SQL_AUDIT.md).

Immediate low-risk hardening from that audit is included in the command-log migration stream:

- `command_log/0014_integrity_audit_views.sql` adds `command_log.command_integrity_violations`.
- `command_log.command_integrity_summary()` groups integrity violations for CI, smoke, and operator checks.
- These diagnostics replace the visibility lost when hot-path same-schema foreign keys were removed, without adding write-time FK checks back onto command intake, queue, or result writes.
- `scripts/dev/command-log-prune.mjs` removes orphan command-log child rows in apply mode before terminal-history pruning.

## Next persistence-alignment work

1. Remove or narrow service-side `CREATE TABLE IF NOT EXISTS` compatibility code after the CI migration lane soaks.
2. Move long-term UI/query projections from overloaded `runtime.*` tables into a dedicated `read_model` schema contract.
3. Convert runtime fact columns that are still stored as text into typed Postgres facts.
   - Scope: `runtime.runtime_events`, `runtime.orders`, `runtime.executions`, `runtime.trades`, `runtime.submit_results`, `runtime.order_lifecycle_state`, `runtime.market_data_snapshots`, and canonical batch/outcome timestamp fields.
   - Use `UUID`/`TIMESTAMPTZ`/`NUMERIC` for canonical and query-critical facts where the domain contract is stable; keep raw payload JSON/text only at compatibility edges.
   - Update Kotlin compatibility bootstrap, schema validation expectations, row mappers, projector SQL, smoke scripts, and migration/integration tests in the same slice.
   - Preserve existing public API wire shapes while changing DB storage types.
   - Add compatibility cleanup for existing rows that cannot cast safely, with explicit failure or quarantine behavior instead of silent coercion.
   - Verification: clean-stack migration apply, migrated-stack apply, Postgres schema integration test for column types, projector replay/idempotency checks, market-data/order read smoke, and an `EXPLAIN` check for top-of-book/index-sensitive queries.
   - Checkpoint: `runtime/0028_typed_top_of_book_facts.sql` adds typed numeric companion columns for top-of-book lifecycle and market-data projection facts.
   - Checkpoint: `runtime/0029_typed_runtime_event_facts.sql` adds typed runtime event identity/time companion columns, typed ordering indexes, and a trigger to keep new writes populated.
   - Checkpoint: `runtime/0030_typed_submit_result_facts.sql` adds typed command-result event/time companion columns, typed audit indexes, and a trigger for persisted submit outcomes.
   - Checkpoint: `runtime/0031_typed_execution_trade_facts.sql` adds typed execution/trade event, time, quantity, and price companion columns plus typed history and intraday-bar indexes.
   - Checkpoint: `runtime/0032_typed_order_facts.sql` adds typed order accepted-time, quantity, and limit-price companion columns plus typed order-history indexes.
   - Checkpoint: `runtime/0033_typed_canonical_time_facts.sql` adds typed canonical command, venue-event, batch, and outcome timestamp companions for audit/range queries.
4. Reconcile the runtime event schema so `runtime.runtime_events` has one intentional typed contract instead of the current `0002` typed backbone followed by `0003` text compatibility.
5. Add a physical partition plan for high-volume append/canonical history tables before production-scale retention and replay data accumulates.
6. Mark `runtime.canonical_command_results` as legacy/compat or consolidate canonical command consumers onto `runtime.canonical_command_outcomes`.
7. Revisit the outbox/event-backbone routine once runtime event payloads and publisher behavior are implemented.

## Split readiness checks to enforce in CI

1. no cross-domain foreign keys in new migrations
2. no cross-domain repository imports in service code
3. no cross-schema joins in hot write-path queries
4. migration PRs touch one domain folder unless explicitly approved
5. critical write-path changes include routine contract tests
6. command-log writes remain append-only except explicit status/attempt transitions
7. read-model migrations do not add dependencies to hot runtime write paths
8. command-log integrity audit checks stay empty after queue reconstruction and prune jobs
