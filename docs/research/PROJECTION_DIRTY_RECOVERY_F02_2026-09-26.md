# Projection dirty-queue crash recovery — F02 investigation

Status: LOGGED queue candidate passes disposable full-schema crash and rebuild
proof and one matched hosted capacity pair. It remains a draft candidate:
public-read readiness after crash, freshness qualification, and target-specific
DDL lock scheduling remain open. Tracks GitHub issue #367 F02.

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

## Full-schema crash regression — September 26

`bun scripts/dev/projection-dirty-crash-test.mjs` creates a disposable
`postgres:16-alpine` container and applies the full runtime, auth, admin, and
command-log migrations. Its focused Kotlin test uses
`PostgresRuntimePersistence` to commit canonical outcomes and frontiers, create
one pending lifecycle marker and one pending market marker, then SIGKILL and
restart PostgreSQL. After lifecycle drain, it repeats SIGKILL before market
drain. Fresh connections validate surviving canonical data, frontiers, and
markers; drained lifecycle and market business rows equal full rebuild results
excluding only refresh timestamps. The runner removes its container and volume.

RED on migrations through `0068`: after the first SIGKILL, committed canonical
outcome survived but expected lifecycle marker count `1` was `0`.
GREEN with `0069_logged_projection_dirty_queues.sql`: both crash boundaries,
worker drains, and rebuild comparisons passed. This proves the tested
persistence path, not public HTTP readiness or sustained capacity.

## Disposable migration lock probe — September 26

On a separate `postgres:16-alpine` container, a Reef-shaped UNLOGGED fixture
held 100,000 order markers and 64 market markers with the same primary and
`dirtied_at` indexes. An open reader transaction made `ALTER TABLE
runtime.order_lifecycle_dirty SET LOGGED` hit a 500 ms `lock_timeout`, confirming
that the migration waits for readers. Once the reader committed, converting both
tables succeeded in 0.24 seconds wall time on local Docker; resulting relation
sizes were 9,142,272 and 49,152 bytes, and both had `relpersistence=p`. The
disposable container and volume were removed. This is a small local DDL probe,
not an aged-host migration budget or a throughput result. Schedule the actual
migration while projection writers are stopped; measure it on any large target.

## Hosted setup correction — September 26

The first fresh c-32 `10k/300s` control with UNLOGGED queues is invalid as a
capacity comparison. It accepted and materialized 2,999,951 commands, but the
projector collected only 1,021 and repeatedly failed in migration `0068`'s
timeline replay check with PostgreSQL `ON CONFLICT DO UPDATE command cannot
affect row a second time`. Matching engine reused static rejection event IDs
and order-derived modification IDs across commands. The original failed
artifact is retained locally under
`artifacts/projection-dirty-f02-20260926/failed-control/`.

The unrelated event-identity correction is isolated on
`codex/rejection-event-ids` at `fabc556c`; the full matching-engine Go suite
passes. Both rerun arms use the same corrected matching-engine image, the same
runtime image, fresh volumes, and the same fixture/observer settings. The
correction is a shared prerequisite, not a queue-logging throughput effect.

## Matched hosted queue-storage trial — September 26

Fresh-volume UNLOGGED control and LOGGED treatment ran sequentially on one
`sfo3` `c-32` for `10k/300s`, with the same C43-shaped fixture, six
materializers, 16 canonical projector owners, four lifecycle workers plus the
nested caller, fixed observers, and Node 22.22.1/Bun 1.3.14. Both used runtime
image `sha256:63921b7d4b5e7dc850059d77a7edbb8422ce3e7c15a756e3cae85cff1d4afd5a`
from `de93d526` and corrected matching image
`sha256:2202fe760be115df7ffc6ea4158e42e598e798053488d0e0f43411f149847365`
from `fabc556c` (separate draft PR #371). The only declared arm difference was
queue persistence. Image, fixture, protocol, and configuration hashes and raw
outputs are retained under the ignored local artifact directories
`artifacts/projection-dirty-f02-20260926/hosted-control/` and
`artifacts/projection-dirty-f02-20260926/hosted-treatment/`; each 125-file
evidence manifest passed SHA256 verification. The initial event-ID collision
attempt above is invalid and excluded.

| Same-cohort result | UNLOGGED control | LOGGED treatment |
| --- | ---: | ---: |
| Accepted/direct-acked/materialized/projected | 2,999,880 | 3,000,005 |
| Projected rate, later collection | 9,999.20/s | 9,997.40/s |
| Projector lag, later collection | 0 | 0 |
| HTTP intake p95 / p99 | 59.50 / 83.65ms | 59.25 / 83.90ms |
| Conservative source→canonical p95 / p99 | 358 / 632ms | 373 / 819ms |
| Conservative source→lifecycle p95 / p99 | 42,289 / 45,934ms | 41,871 / 62,271ms |
| Conservative source→market p95 / p99 | 44,418 / 53,006ms | 47,777 / 62,451ms |
| Projection PostgreSQL WAL | 17,641,971,924B | 19,236,983,026B |
| Projection PostgreSQL blocks read | 1,466,202 | 2,285,875 |

Stress exited 0 in both arms. Frozen checker exited 1 solely on lifecycle and
market sustained freshness; its authority, duration, cohort, and queue checks
passed. Both arms drained to exact accepted/source/projected equality, 16
contiguous unique frontiers, zero dirty queues, and rollback-only full business
reference parity (control 2,471,569 lifecycle/64 market rows; treatment
2,471,661 lifecycle/64 market rows). No projection retries or PostgreSQL
deadlocks were observed. LOGGED added 1,595,011,102B projection WAL, 9.04% or
about 532B per accepted command, while this single pair showed no sustained
projector-rate loss. The higher block-read count and longer market p99 bound
are concerning but cannot be assigned to queue logging from one sequential
pair; neither arm qualified the 5s downstream freshness gate or independent
20% drain headroom.

After evidence verification, disposable droplet `603811601` and firewall
`0e3d8cf7-d244-4546-a6db-8310a7f8b7c0` were destroyed. Both provider
lookups returned 404 and OpenTofu state was empty.

On the aged control database after writers stopped and both queues drained,
`ALTER TABLE ... SET LOGGED` for both tables took 0.13s wall time. Their
pre-conversion relation sizes were 127,041,536B and 335,872B. This does not
bound an active-writer lock window or a larger target; stop projection writers
and measure the target before migration. The disposable 100k-row reader-lock
probe above confirms the lock can wait.

Same-prefix analysis of the no-profiler control attributes conservative
command-weighted p95 intervals of 10,849ms from durable materializer commit
to observed lifecycle prefix and 34,027ms from prefix observation to covering
lifecycle marker. Market counterparts are 12,445ms and 34,054ms. These are
observation bounds, not actual per-order visibility times or additive p95s.
They point F04 at downstream execution/barrier/observation as well as canonical
processing; the LOGGED change itself does not solve that delay.

## Decision and remaining proof

Keep the small LOGGED migration as draft F02 candidate: crash and exact rebuild
proof pass, and the matched pair shows no throughput-rate loss despite 9.04%
extra WAL. Do not activate an UNLOGGED generation-recovery protocol on this
evidence; it would add cross-process read gating and rebuild coordination. A
repeat pair and target-specific WAL budget can revisit that choice. F02 remains
open until disposable public-read/market-metadata crash proof, an acceptable
target migration window, and freshness qualification pass. Neither queue
option explains the projection-split throughput no-go.
