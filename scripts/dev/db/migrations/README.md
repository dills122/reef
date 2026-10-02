# Dev DB Migrations

Forward-only migration convention for local development.

Domain folders:
- `runtime/`
- `auth/`
- `admin/`
- `boundary/`
- `command_log/`
- `orchestration/`
- `settlement/`
- `analytics/` (planned)

Current implementation note:
- runtime, boundary, and auth service initialization currently creates schema-qualified domain tables for local compatibility
- those service-side bootstrap paths are transitional compatibility bridges, not the target ownership model
- new durable schema work should land in these migration folders and should not add new root-level or search-path-dependent bootstrap tables
- `make dev-up`, `make dev-reset`, and `make dev-db-migrate` apply migrations through Docker Compose Postgres and record checksums in `public.reef_schema_migrations`
- clean-stack migration apply and live schema-placement tests are available; before removing service-side bootstrap, add CI coverage for the migration execution order

Rules:
- place SQL files in the owning domain folder only
- use monotonic prefixes (`0001_`, `0002_`, ...)
- do not edit existing applied migrations; add a new migration instead
- avoid cross-domain foreign keys
- use `$(JS_RUNTIME) scripts/dev/db/migrate.mjs --dry-run` to validate migration order and checksums without touching Docker

## Runtime 0051: required lifecycle state recovery

`runtime/0051_lifecycle_terminal_numeric_parity.sql` replaces incremental lifecycle
projection function. Cancelled/rejected orders now store zero remaining quantity
in both text and numeric columns. Its FILLED guard also matches full rebuild:
current quantity must be positive before zero remainder implies FILLED.

**Applying function replacement alone does not repair existing rows.** Already
empty dirty queues can coexist with incorrect numeric lifecycle quantities.
Schedule recovery separately from migration and performance measurements:

1. Select deployment explicitly; preserve backups, canonical facts, committed
   source watermarks, and pre-recovery business rows. Quiesce canonical writers
   and lifecycle/market maintainers. Keep only authorized rebuild API, with its
   background workers disabled.
2. Apply 0051 through normal checksum-ledger migration path.
3. Invoke full lifecycle rebuild, then full market refresh for each actual market
   projection/source pair. Bind source to recorded canonical projection name;
   do not assume the market worker's default source name matches deployment.
4. Verify all business columns, including both text and numeric quantities/prices,
   against corrected incremental/reference state; exclude only `updated_at`.
   Preserve expected corrections to pre-fix rows as evidence rather than requiring
   equality with known-corrupt baseline. Confirm unchanged canonical facts/source
   metadata, repeat-rebuild equivalence, and final drained queues before resuming.
   Empty queues or matching row counts alone are insufficient.

Existing routes are `POST /api/v1/orders/lifecycle-state`, followed by
`POST /api/v1/market-data/snapshots?projectionName=...&sourceProjectionName=...`.
Use existing deployment external-client authorization (`X-Client-Id`, configured
bearer token, required `Idempotency-Key`); do not disable auth or substitute an
admin token for external-client credentials. Full market refresh does not rebuild
lifecycle. Earlier execution context is preserved in [projection task plan](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/.planning/sustained-10k/task_plan.md); use current routes and validation requirements above.

## Disposable dirty-queue crash regression

Run `bun scripts/dev/projection-dirty-crash-test.mjs` from repository root when
checking dirty-queue durability across an unclean PostgreSQL restart. Harness
creates disposable `postgres:16-alpine` container, applies current runtime/auth/
admin/command-log migrations, and runs
`PostgresVenueEventBatchMaterializationIntegrationTest.retainsDirtyWorkAcrossUncleanPostgresRestart`
through service-local Gradle wrapper. Requires Docker and JVM toolchain. Default
loopback port is `25437`; `REEF_F02_CRASH_TEST_PORT` accepts `20000`–`65535`.
Harness removes only container it created and its attached volumes in cleanup.
It does not establish throughput or full-system recovery qualification.

Earlier observations and limits remain in [F02 investigation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_DIRTY_RECOVERY_F02_2026-09-26.md).

Local PostgreSQL regression
`PostgresLifecycleNumericParityIntegrationTest` failed before 0051 (five terminal
rows with text/numeric mismatch) and passed after across 12 lifecycle shapes,
full-row reference equality, explicit re-dirty repair, and no-op replay. Raw local
proof: `/private/tmp/reef-lifecycle-parity-proof/before.xml`, `after.xml`, and
`function.diff`. Hosted corrected evidence under `/tmp/reef-task2-corrected` is
pending; these local results do not establish hosted recovery or capacity.

## Runtime0052: serialize dirty invalidations

`runtime/0052_projection_dirty_serialization.sql` replaces status producer and
lifecycle/market maintenance functions. Conflicting invalidations update existing
markers, serializing against consumers that own those row locks. Consumers claim
a bounded set of IDs first, then recompute in a separate SQL statement with a
fresh READ COMMITTED snapshot while retaining locks. Functions remain VOLATILE;
batch limits, business formulas, replay checks, and queue schema are unchanged.

This fixes a lost-work race: `ON CONFLICT DO NOTHING` can discard a new producer
invalidation while a consumer uses an earlier snapshot and deletes that marker.
Conflict updates alone do not guarantee a fresh source snapshot after row claim.
Both parts of0052 are required. Queue conflict updates add work; capacity must be
remeasured without weakening correctness or frozen throughput/freshness limits.

Migration does not repair existing stale projections. Follow0051 quiesced recovery
sequence above, applying0052 before full lifecycle rebuild and market refresh.
Retain pre-repair mismatch evidence and verify unchanged canonical/source facts,
complete reference equality, and repeated rebuild equivalence. New online-load
equality and crash/rebuild proof remain required before throughput promotion.

`PostgresDirtyProjectionConcurrencyIntegrationTest` provides four deterministic
lifecycle/market races. All four fail before 0052; conflict updates alone leave
two snapshot failures; complete 0052 passes all four against local PostgreSQL16.
Quiesced repair, fresh online load, crash recovery and capacity remain separate
required hosted checks, not claims established by these regressions.
