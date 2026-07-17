# Projection Throughput Scaling Plan

## Purpose

Record the July 2026 DigitalOcean projection bottleneck evidence and define the
fix sequence for scaling read-model freshness toward the proven venue-core
materializer baseline.

This plan is not a request to weaken command acceptance semantics. `202
Accepted` still requires durable ingress acknowledgement, direct matching still
commits command offsets only after durable `VenueEventBatch` publication, and
canonical materialization remains the source of audit/replay truth. The work
here is about making rebuildable projections keep up without putting their cost
back on the hot command path.

## Current Evidence

All runs below used the Redpanda/Kafka-compatible direct-stream plus
venue-event-materializer path on a DigitalOcean c-16 worker.

| Run | Shape | Result | Read |
| --- | --- | --- | --- |
| `do-benchmark-20260717T014839Z` | `2.5k rps`, `60s`, `256` workers, projection enabled | `149,975` attempted/accepted/direct-acked/materialized/projected, lag `0`, gaps `0`, p95 `31.31ms`, p99 `79.38ms` | Current named projection-fresh gate remains green. |
| `do-benchmark-20260717T015658Z` | `5k rps`, `60s`, `256` workers, before deterministic event insert ordering | `299,950` accepted/direct-acked/materialized, p95 `67.57ms`, p99 `138.57ms`; projection ended with `1,834` lag, `1` projector failure, `1` projection-postgres deadlock | Venue-core held; projection failed by deadlock and freshness lag. |
| `do-benchmark-20260717T020807Z` | same `5k` shape after ordering `runtime_events` inserts by `event_id` | `299,952` accepted/direct-acked/materialized/projected, materialized/projected gap `0`, deadlocks `0`, p95 `69.92ms`, p99 `136.68ms`; watermark lag `1,367` | The tactical ordering fix removed the deadlock/count-gap mode but did not make `5k` projection-fresh. |
| `do-benchmark-20260717T131344Z` | same `5k` shape with `STREAM_ACK_PROJECTION_STAGE=command-status` after Docker Compose env pass-through fix | `299,955` attempted/accepted/direct-acked/materialized/projected, lag `0`, gaps `0`, p95 `74.49ms`, p99 `112.93ms`, projector failures/retries/deadlocks `0` | Command status plus own-order lifecycle can keep up at `5k`; full event/timeline projection remains the active bottleneck. |
| `do-benchmark-20260717T134058Z` | same `5k` full-projection shape after deterministic timeline sequencing removed the trace allocator for new canonical payloads | `299,804` attempted/accepted/direct-acked/materialized/projected, lag `0`, gaps `0`, p95 `71.89ms`, p99 `112.89ms`, projector failures/retries/deadlocks `0` | Full projection is now green at `5k/60s`; remaining work is lowering WAL/temp/table pressure before longer soaks or higher gates. |
| `do-benchmark-20260717T142157Z` | same `5k` full-projection shape after making dirty queues unlogged and avoiding redundant dirty conflict updates | `299,954` attempted/accepted/direct-acked/materialized, projected `252,866`, final lag/gap `47,088`, p95 `64.41ms`, p99 `105.69ms`, projector failures/retries/deadlocks `0` | Dirty queue WAL/table pressure improved, but this A/B did not preserve `5k` freshness. Treat it as diagnostic, not promotion evidence. |

The patched `5k` run showed direct partitions balanced across all `16` active
partitions with about `1.017` skew, and the venue-event materializer matched
accepted throughput. The remaining lag was concentrated in projector-owned
partitions `8-11`.

Projection-postgres pressure in the patched `5k` run:

- `~2.01GB` WAL for one `60s` sample, about `6.71KB` per accepted command.
- `~2.05M` inserted tuples and `~47k` updated tuples.
- `~5.03GB` temp bytes.
- hottest tables by growth: `runtime.runtime_events`, `runtime.executions`,
  `runtime.trades`, `runtime.orders`, `runtime.order_lifecycle_state`,
  `runtime.submit_results`, `runtime.runtime_trace_sequences`,
  `runtime.order_lifecycle_dirty`, and market-data dirty/snapshot tables.

Projection-postgres pressure in the `5k` `command-status` run:

- `~1.22GB` WAL for one `60s` sample, about `4.06KB` per accepted command.
- `~1.48M` inserted tuples and `~47k` updated tuples.
- `~3.87GB` temp bytes.
- hottest tables by growth: `runtime.executions`, `runtime.trades`,
  `runtime.order_lifecycle_state`, `runtime.orders`, `runtime.submit_results`,
  and `runtime.order_lifecycle_dirty`.
- `runtime.runtime_events` and `runtime.runtime_trace_sequences` had no row
  growth, confirming the stage split removed the timeline/event write path from
  the command-status freshness gate.

Projection-postgres pressure in the deterministic-timeline `5k` full run:

- `~1.94GB` WAL for one `60s` sample, about `6.48KB` per accepted command.
- `~1.75M` inserted tuples and `~47k` updated tuples.
- `~5.59GB` temp bytes.
- all `16` projector partitions ended with watermark lag `0`.
- `runtime.runtime_trace_sequences` did not appear in tracked table growth;
  runtime event/typed projection pressure moved to `runtime.runtime_events`
  itself (`270,015` inserts, `~588MB` total table/index growth), plus
  executions, trades, orders, submit results, lifecycle state, and dirty queues.

Dirty-queue A/B pressure in `do-benchmark-20260717T142157Z`:

- projection WAL fell to `~1.79GB`, about `5.98KB` per accepted command.
- dirty-queue updates dropped to `0`; `order_lifecycle_dirty` table/index
  growth fell to `~13.9MB` from `~25.0MB` in the prior full pass, and
  `market_data_snapshot_dirty` growth fell to `~65KB` from `~115KB`.
- full projection did not catch up: projected `252,866` of `299,954`, with
  final lag/gap `47,088` spread across all `16` projection partitions.
- The A/B says dirty queues are worth keeping out of WAL, but this change alone
  is not a promotion fix; the dominant remaining stall is still broader
  full-projection write shape, especially `runtime_events`,
  `order_lifecycle_state`, executions/trades, and their indexes/temp work.

Canonical runtime DB pressure in the same patched `5k` run was much lower and
cleaner:

- `~729MB` WAL, about `2.43KB` per accepted command.
- `299,952` `runtime.canonical_command_outcomes` rows.
- `6,603` `runtime.canonical_venue_event_batches` rows.
- no canonical DB deadlocks.

## System Read

The current bottleneck is not intake, matching, durable event-batch publication,
or compact canonical materialization at `5k rps`. The bottleneck is asking the
read-model side to synchronously maintain every query convenience table at the
same freshness SLO.

In market-infrastructure terms:

- The order-entry lane is healthy at this level.
- The durable audit lane is healthy at this level.
- The projection lane is too write-amplified and too coupled.
- The next throughput gains come from reducing projection work per command,
  making projector writes partition-local and deterministic, and assigning
  explicit freshness SLOs to read surfaces.
- The stage split isolated the biggest class of coupling: command-status and
  lifecycle freshness are now independently green at `5k`, so the next
  bottleneck is reducing full-projection WAL/temp/table growth, not the durable
  venue-core lane.

## Non-Negotiable Constraints

- Do not move rebuildable projection writes back into the command acceptance
  path.
- Do not reduce canonical event-batch or command-outcome facts needed for audit,
  replay, idempotency, and deterministic simulation.
- Do not let projection lag redefine venue-core command acceptance unless a
  run explicitly uses a control-room freshness backpressure policy.
- Do not hide deadlocks by only increasing retry counts. Retries are acceptable
  as resilience, but gates must still report deadlocks, retries, lag, and drain
  time.
- Do not scale projector process count blindly. More writers can increase
  Postgres lock/index churn if the write shape is still wrong.

## Fix Sequence

### 0. Preserve the Current Evidence Baseline

Status: started.

- Keep the `2.5k` named projection freshness gate as the current green
  promotion point.
- Keep the `5k` pressure run as the current projection knee.
- Keep the harness token fix so DO materializer/projection runs seed reference
  data with the same admin token the remote API expects.
- Keep deterministic `runtime_events` insert ordering by `event_id`; it removed
  the observed `runtime_events` index-deadlock/count-gap failure mode in the
  A/B run.

Acceptance:

- `2.5k` short gate remains zero lag, zero gaps, zero deadlocks.
- `5k` pressure gate reports accepted/materialized/projected counts, watermark
  lag, deadlocks, retries, WAL bytes/command, temp bytes, and top table growth.

### 1. Add Projection Failure Resilience Without Hiding the Bottleneck

Make projector transaction failure behavior production-sane while keeping gates
strict.

- Add bounded retry for retryable SQL states such as `40P01` deadlock and
  serialization failures around projector batch persistence.
- Emit retry counters per projector instance, partition range, SQL state, and
  projection name.
- Keep the promotion gate strict: `deadlocks=0` and `failedDelta=0` for green
  freshness claims. A retry can keep production moving, but a benchmark with
  retries is still evidence of contention.
- Ensure watermark advancement is still after all read-model rows commit.

Acceptance:

- Injected projector failure/retry tests prove no duplicate read-model rows and
  no watermark advance over missing rows.
- DO reports distinguish clean passes from retry-assisted runs.

### 2. Split the Monolithic Projection Function

`runtime.runtime_persist_submit_outcomes(jsonb)` currently fans one command
batch into many tables: submit results, orders, executions, trades, runtime
events, trace sequence bookkeeping, dirty queues, lifecycle state, and market
data derivations. That coupling is the main write-amplification surface.

Split it into explicit projection stages with independent SLOs:

- command status projection: `submit_results` and command lookup facts.
- order fact projection: accepted/rejected order rows.
- fill/tape projection: executions and trades.
- event/timeline projection: `runtime_events` and trace timelines.
- lifecycle projection: order state and current own-order reads.
- market-data projection: top-of-book/depth/trade-derived surfaces.

Each stage should be idempotent and replayable from canonical event-batch
facts. The freshness-critical stage should not wait behind timeline or analytics
work unless the caller explicitly asks for those surfaces to be fresh.

Acceptance:

- The benchmark report can show projected work by stage.
- `5k` command status plus lifecycle freshness can be tested independently from
  full runtime event/timeline freshness.
- Replay of each stage is idempotent from canonical facts.

### 3. Reduce Rows and WAL Per Command

Target the tables that dominate the patched `5k` run.

- Replace `runtime.runtime_trace_sequences` upsert-per-command behavior with a
  deterministic sequence derived from canonical partition sequence and event
  ordinal where possible. Trace sequence allocation should not require a hot
  shared upsert table for the common single-event command case.
  - Initial implementation is in place for canonical venue-event projection
    payloads that carry `streamSequence`: the timeline stage derives
    `runtime_events.sequence_number` from `streamSequence * 100 + eventOrdinal`
    and only falls back to `runtime.runtime_trace_sequences` for legacy
    payloads without stream sequence metadata.
- Move rebuildable dirty queues to unlogged or staging tables when they can be
  reconstructed from canonical facts and watermarks. Durable truth stays in
  canonical command outcomes and event batches.
  - Initial implementation is in place: `order_lifecycle_dirty` and
    `market_data_snapshot_dirty` are unlogged rebuildable queues, and hot
    dirty-marking paths use `ON CONFLICT DO NOTHING` because an already-dirty
    id already preserves the required recompute signal.
- Collapse insert/delete dirty-table churn by batching dirty ids in memory or
  unlogged staging before merge.
- Avoid writing `runtime_events` for every freshness-critical read if the read
  surface can be served from typed order/execution/trade facts.
- Review hot projection indexes. Keep indexes required for public reads and
  replay integrity; drop or defer indexes that only serve cold inspection paths.
- Partition large append-heavy projection tables by event stream, run/session,
  projection partition, or time where that reduces B-tree contention and vacuum
  pressure without harming point lookups.

Acceptance:

- Projection-postgres WAL per accepted command falls materially below the
  current `~6.7KB`.
- Temp bytes per `5k/60s` run fall materially below the current `~5GB`.
- Top table growth per command is tracked in every pressure run.

### 4. Make Projector Ownership More Local

The current four-projector split is necessary but not sufficient. At `5k`, the
lag concentrated in partitions `8-11` even though direct-stream command
distribution was balanced.

- Keep explicit partition ownership. Do not return to generic `0-63` defaults
  for the current `0-15` active direct-stream profile.
- Add per-projector, per-partition lag, drain-rate, rows/sec, WAL estimate, and
  retry counters to the report summary.
- Test whether smaller partition ownership groups help only after reducing
  write amplification. More writers before write-shape fixes may worsen index
  contention.
- Keep stream lane identity aligned with `runId + venueSessionId +
  instrumentId` as command models support it, so matching-sensitive work and
  projection ownership remain deterministic.

Acceptance:

- `5k` pressure reports identify the slow partition group without manually
  opening raw JSON.
- Any topology change is A/B tested against the same c-16 shape and same gates.

### 5. Maintain Hot Read Models Directly

Some read surfaces should not be rebuilt by aggregating broad lifecycle tables
under load.

- Build maintained depth ladders per instrument/session instead of computing
  depth from `runtime.order_lifecycle_state` at request time.
- Maintain top-of-book with delta updates from order lifecycle changes rather
  than broad recompute.
- Keep trade tape and bars separate: trade tape can read append-only trade
  facts; bars can tolerate a looser freshness model or separate aggregation.
- Keep own-order current/history reads on lifecycle/order facts but ensure the
  projection path writes only the columns and indexes those reads need.

Acceptance:

- Public read APIs declare source and freshness model.
- Depth/top-of-book reads do not require table scans or broad aggregation on the
  hot lifecycle table.

### 6. Add Freshness Classes

Not all reads deserve the same SLO as command status.

Suggested classes:

- `venue-core`: accepted, direct-acked, materialized. Projection lag reported,
  not gating.
- `command-status-fresh`: command status and own-order state caught up.
- `market-data-fresh`: top-of-book/depth/trade surfaces caught up.
- `timeline-fresh`: runtime event/trace history caught up.
- `analytics-eventual`: leaderboard, reporting, bars, and historical analytics
  may lag behind real-time command status.

Acceptance:

- Gate names and reports state which freshness class they prove.
- UI/control-room readiness can choose a stricter class without changing the
  venue-core benchmark claim.

### 7. Expand the Remote Gate Ladder

Use gates to prevent vague throughput claims.

Current:

- `2.5k` projection freshness short: green.
- `5k` command-status freshness short: green.
- `5k` full projection short: green after deterministic timeline sequencing.

Next gates:

1. `2.5k soak-5m`: zero lag, zero gaps, zero deadlocks, zero projector DB
   retry delta, DB diagnostics present.
2. `5k soak-5m`: zero lag, zero gaps, zero deadlocks, zero projector DB retry
   delta, DB diagnostics present, and table/WAL/temp pressure compared against
   `do-benchmark-20260717T134058Z`.
3. `5k command-status-fresh`: green on `do-benchmark-20260717T131344Z` after
   fixing Docker Compose pass-through for `STREAM_ACK_PROJECTION_STAGE`.
   Re-run this after changes to submit status, order, fill, trade, or lifecycle
   projection writes.
4. `5k full-projection-fresh`: green on `do-benchmark-20260717T134058Z`.
   Re-run after changes to runtime events, executions/trades, lifecycle, dirty
   queues, or projection indexes.
5. `5k timeline-fresh`: run with `REEF_DO_PROJECTION_STAGE=timeline` when
   isolating runtime event/trace history cost from command status and lifecycle
   freshness.
6. `7.5k` and `10k` projection pressure/freshness gates only after `5k` is
   boring.

Every gate should report:

- attempted, accepted, direct-acked, materialized, projected.
- count gaps and watermark lag.
- lag area under curve, max lag, final lag, and drain time.
- projector retries/failures/deadlocks.
- rows, WAL bytes, commits, temp bytes, and table/index growth per accepted
  command.
- partition skew for direct stream and projection ownership.

### 8. Prepare for Higher Throughput

Once `5k` projection freshness is boring, future-proofing work should focus on
structural separation:

- Use projection-specific databases or schemas for independently scalable read
  surfaces.
- Consider separate storage for analytics/timeline/history rather than forcing
  OLTP projection Postgres to serve every workload.
- Add retention and archive policies for `runtime_events`, executions, trades,
  command outcomes, and projection artifacts.
- Tune Postgres only after write shape is fixed: WAL/checkpoint settings,
  autovacuum, table partitioning, fillfactor, and index layout should follow
  measured table growth and wait data.
- Revisit horizontal scale only with clear per-instance evidence. Scaling a
  write-amplified projector fleet can multiply the bottleneck.

## Current Priority Order

1. Land the benchmark harness token fix and deterministic `runtime_events`
   insert ordering. Initial patch is in place.
2. Add projector retry instrumentation and stricter deadlock/retry reporting.
   Initial bounded retry metrics, diagnostics fields, stress report deltas, and
   DO report gates are in place.
3. Split `runtime.runtime_persist_submit_outcomes` into projection stages.
   Initial SQL stage wrappers, Docker Compose env pass-through, and a remote
   `5k` `command-status` pass are in place; default `full` behavior remains
   unchanged.
4. Split or reduce the event/timeline stage so `runtime_events` and trace
   sequence writes no longer govern command-status freshness. Initial
   deterministic timeline sequencing is in place, and
   `STREAM_ACK_PROJECTION_STAGE=timeline` / `REEF_DO_PROJECTION_STAGE=timeline`
   is available for isolated timeline pressure runs.
5. Reduce remaining runtime-events, lifecycle/fill, and dirty-table write
   amplification. Deterministic timeline sequencing cleared the final `5k`
   lag, but `runtime_events` still drove `~588MB` table/index growth and temp
   bytes rose to `~5.59GB`. Dirty queues are now unlogged locally and reduced
   dirty-table pressure, but the follow-up `5k` full run regressed to `47,088`
   lag; keep this as diagnostic evidence and move to the next write-shape fix.
6. Split hot `runtime_events` facts from cold timeline payload JSON and review
   hot event indexes.
7. Add maintained depth/top-of-book projections.
8. Rerun `5k` freshness gates after each meaningful reduction in rows/WAL per
   command.
9. Promote `7.5k`/`10k` projection gates only after `5k` remains stable with
   zero final lag/deadlocks over longer soaks and materially lower write
   amplification.
