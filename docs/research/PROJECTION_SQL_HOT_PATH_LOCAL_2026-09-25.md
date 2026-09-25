# Projection SQL hot-path local diagnostic — 2026-09-25

This is a bounded follow-up to the [projection scaling plan](../PROJECTION_THROUGHPUT_SCALING_PLAN.md) and targeted SQL review. It does not qualify sustained throughput or replace the C28–C32 remote evidence. All rows below were synthetic in a disposable PostgreSQL 16.15 container; no production data or existing Docker volume was used.

## Lag-only backpressure read

The stream-command backpressure sampler needs projection name and lag. Its prior call to full `projectionStatus` also executed `SELECT COUNT(*) FROM runtime.submit_results`; public status and availability reads still need that exact count. The new internal `projectionLag` path shares the existing watermark/lag calculation but skips the submit-result count in both single-store and separated-store PostgreSQL layouts. Public `projectionStatus` is unchanged.

The live integration test verified lag parity with full status in both layouts while another connection held `ACCESS EXCLUSIVE` on `submit_results`. Both lag calls completed before the five-second test deadline; full status had earlier returned the exact two-row count. This proves the new path does not read that table in the tested layouts, not an end-to-end throughput gain.

With 200,000 aged synthetic `submit_results`, `EXPLAIN (ANALYZE, BUFFERS, WAL)` for the exact count reported a sequential scan of 200,000 rows, 3,066 shared buffer hits, and 20.988ms execution in a warm, otherwise idle local database. C30's 614.8ms mean for a matching count query under hosted load is a separate observation; its caller attribution and causal effect are unproven.

## Active status/fill statement

Applied all 58 current runtime migrations to an empty disposable schema, loaded 200,000 synthetic submit results, then called `runtime.runtime_persist_submit_outcome_status_stage` once with 500 new accepted limit orders and no executions or trades. `auto_explain` nested statements, `ANALYZE`, buffers and WAL were enabled only for that call. The combined status/fill statement took 30.619ms; its measured modify nodes were 9.087ms for `submit_results`, 12.381ms for `orders`, and 7.629ms for `order_lifecycle_dirty`. The wrapper call took 47.519ms and generated 524,578 WAL bytes. The dirty queue is unlogged, so its node reported zero WAL bytes. Plan-node times are nested, not an additive wall-clock budget.

This one-call, no-fill fixture does not reproduce C30's mixed 10k/s concurrency or isolate a safe sub-write to change. C30 remains the stronger hotspot signal: its status/fill statement averaged 357.8ms and tracked 8.93GB WAL, without per-table attribution. Before changing SQL, capture plans and table-write/lock counters on a matched aged mixed cohort; change one demonstrated sub-write and require full business/replay parity and frozen freshness gates.

## Aged index build and lock behavior

Loaded 200,000 synthetic lifecycle rows and 200,000 canonical outcomes. Rebuilding the 0055 currency index took 138.098ms; 0058's drop/rebuild of the canonical partition-sequence index took 70.509ms for `CREATE INDEX` plus 4.868ms commit. These are local build times on small, warm tables, not an active-database rollout budget. A concurrent lifecycle `UPDATE` hit `lock_timeout = 500ms` while an equivalent ordinary currency-index build transaction remained open after its create. Thus rollout needs an aged target-specific blocking window or a planned write pause.

## Verification and retained evidence

- Focused Kotlin tests passed for backpressure and PostgreSQL status. The latter ran against the disposable container and covered both storage layouts.
- Full `cd services/platform-runtime && ./gradlew test` with the disposable PostgreSQL URL passed: 623 tests, zero skipped/failures/errors. The first full run failed 13 command-log tests because only runtime migrations had been applied; after applying the 15 command-log migrations, the unchanged suite passed.
- `git diff --check` passed. No migration, business-row formula, public route, or freshness threshold changed in this slice.
- Synthetic SQL and raw `auto_explain` output are retained locally under `artifacts/sustained-10k-20260924/local-sql-review-20260925/` (git-ignored). The raw plan output SHA-256 is `e0300471cb704ec1eec683168a50fa97645c3208af73d75e74c4eceb15bd5357`.

Already-applied function versions in 0053 and 0055 are migration history; 0057 is the active market function on a fully migrated database. The zero-scan submit-result time index is still present. C31's isolated removal failed its predeclared rule and supplies no basis to drop it.
