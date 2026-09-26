# Projection dirty-queue crash recovery — F02 investigation

Status: design investigation on `codex/projection-dirty-recovery`; no recovery
implementation or throughput qualification. Tracks GitHub issue #367 F02.

## Verified failure boundary

- Migration `runtime/0042_unlogged_projection_dirty_queues.sql` makes
  `order_lifecycle_dirty` and `market_data_snapshot_dirty` UNLOGGED.
- Canonical projection writes durable normalized rows and frontiers, then
  downstream lifecycle and market workers consume dirty markers. Their startup
  paths only poll; they do not reconstruct lost markers.
- An unclean PostgreSQL restart truncates UNLOGGED queues while committed
  normalized facts and frontiers survive. A previously pending order or market
  update can remain absent from derived state with no queue entry to retry.
- `runtime/0057_market_data_idle_metadata.sql` permits idle metadata advancement
  when source lag and both dirty queues are zero. Lost queues make that premise
  unsafe after a crash. Current HTTP readiness marks both downstream workers
  ready from configuration alone; it does not prove recovered state.
- Full lifecycle and market rebuild entry points exist, but are operator calls
  and run in separate transactions. Automatic recovery requires a cross-process
  gate and a retryable coordinator, not just a call from one worker's `start()`.

Sources: [audit F02](SQL_DATA_ARCHITECTURE_AUDIT_2026-09-25.md),
[queue migration](../../scripts/dev/db/migrations/runtime/0042_unlogged_projection_dirty_queues.sql),
[worker startup](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/RuntimeLoopStarter.kt),
[readiness](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt),
[idle metadata](../../scripts/dev/db/migrations/runtime/0057_market_data_idle_metadata.sql).

## Candidate paths

| Path | Correctness mechanism | Cost and proof needed |
| --- | --- | --- |
| Make both queues LOGGED | Pending markers survive PostgreSQL crash with their producing transaction. No generation-specific read gate. | Hot-path WAL and table/index churn return; `ALTER TABLE ... SET LOGGED` takes an exclusive rewrite lock. Measure on a matched full-pipeline run before promotion. |
| Retain UNLOGGED queues | Durable postmaster-generation state forces one coordinated reseed or verified full rebuild after restart. All read-serving roles reject fresh status until recovery, including interrupted/retried recovery. | More protocol and read-path work. Prove no stale window, concurrent writers, multi-projector ownership, restart during repair, direct reads, and exact rebuild parity. |

The historical 5k/s unlogged-queue A/B reduced projection WAL from about
2.01 GB to 1.79 GB for roughly 300k accepted commands, but also changed
conflict-update behavior and still failed freshness. It does not price a
current-image LOGGED migration or justify accepting silent crash loss.
[Performance learnings](../PERFORMANCE_LEARNINGS.md).

## Disposable database-level crash result

On September 26, an isolated `postgres:16-alpine` container (image
`sha256:c05eced0bdb41ea9b95a656472a6aa4d50cad0d8a2e33d14eb1c53fd6204f2ae`)
held one durable order, frontier `42`, stale lifecycle/market rows, and one
pending marker in each queue. Tables used Reef's queue names and the same
`ALTER TABLE ... SET UNLOGGED` storage choice, but a minimal schema rather than
full Reef migrations. After container `SIGKILL` and restart, order/frontier and
stale derived rows survived; both queues were empty (`1|42|0|0|STALE|STALE`,
`relpersistence=u`). After changing both queues to LOGGED, reseeding the same
markers, and repeating `SIGKILL`/restart, both markers survived
(`1|42|1|1|STALE|STALE`, `relpersistence=p`). The task-owned container and
anonymous volume were removed.

This proves the database storage failure and LOGGED survival in isolation.
It does not prove Reef worker drain, public-read gating, exact business parity,
or throughput impact.

## Next bounded experiment

1. Extend the crash fixture to the full Reef schema and runtime: commit
   normalized facts and dirty work, stop downstream consumers, force an
   unclean database restart, and capture public readiness and market metadata.
   Do not crash the normal developer database.
2. Trial LOGGED queues on that fixture first. Require pending work to survive
   crash, bounded workers to drain it, and lifecycle/market rows to match a
   rollback-only full rebuild. Repeat after a second crash during drain.
3. Record migration lock behavior, WAL/rows/CPU and same-cohort freshness in a
   fresh matched full-pipeline control/treatment. If LOGGED queues consume
   unacceptable capacity, implement the UNLOGGED recovery protocol with a
   durable generation gate and repeat the same crash and load proofs.

Until one path passes crash, parity, and freshness checks, F02 remains open.
Neither option explains the September 26 projection-split throughput no-go.
