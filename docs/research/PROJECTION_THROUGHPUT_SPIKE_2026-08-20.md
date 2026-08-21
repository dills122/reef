# Projection Throughput Research Spike

Status: independently reviewed; approved changes incorporated; implementation
and paid benchmark runs not started

Date: 2026-08-20

Decision owner: Reef maintainers

Independent-review outcome: **approve with changes**. Reconciled changes add
existing-run I/O/scan evidence, lifecycle-worker cardinality, qualified
fillfactor/autovacuum and pool-topology experiments, an unsafe-stage regression
guard, and a distinction between the minimum `20%` promotion margin and Reef's
`2-3x` practical headroom guidance. No uncited external microbenchmark timing
is used as Reef evidence.

Companion current-system dossier:
[`PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md`](./PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md)

Independent-review packets:

- [`PROJECTION_THROUGHPUT_INDEPENDENT_REVIEW_BOOTSTRAP_2026-08-20.md`](./PROJECTION_THROUGHPUT_INDEPENDENT_REVIEW_BOOTSTRAP_2026-08-20.md)
- [`PROJECTION_THROUGHPUT_AUTHOR_EXPLANATION_2026-08-20.md`](./PROJECTION_THROUGHPUT_AUTHOR_EXPLANATION_2026-08-20.md)

## Decision Question

What is the lowest-risk path from the proven `2.5k rps` full-projection soak to
sustained `5k`, then `7.5k` and `10k`, while preserving deterministic replay,
idempotency, audit facts, and explicit projection freshness?

## Executive Conclusion

Do not add projector processes or promote a higher gate yet. The next move is a
measured two-part spike:

1. instrument the actual SQL and separate in-load drain rate from post-load
   drain rate;
2. run a staged ablation and bounded PostgreSQL memory/batch matrix before
   changing the projection schema.

The evidence does not show an ingress or partition configuration error. It
shows a repeatable projection-work ceiling. The successful `2.5k/5m` run and
failed `5k/5m` run both caused the projection database to process about `750k`
outcomes, about `5.0-5.1M` inserted tuples, `4.33-4.39GB` of WAL, and
`15.8-16.6GB` of temporary data. The `5k` run accepted and canonically
materialized the other `~754k` outcomes, but projection did not drain them.

Configuration is a credible early lever because projection Postgres is still
using its `128MB` default shared buffer selection and the PostgreSQL default
`4MB` `work_mem`, while the workload spills about `21-22KB` of temporary data
per projected outcome. It is not a sufficient architecture plan by itself:
current full projection also creates about `6.7-6.8` inserted tuples and about
`5.8-5.9KB` of projection WAL per projected outcome.

The durable design direction is:

- a freshness-critical order-state lane with its own watermark;
- an independently drainable timeline/history lane;
- typed, set-based batch persistence that parses each projection envelope once;
- maintained market-data projections downstream of correct order state;
- evidence-backed index, retention, and partition changes after query plans are
  captured.

The current `command-status` stage must not yet be treated as an own-order-state
freshness proof. It marks lifecycle work but does not persist the runtime events
that the lifecycle projector uses for cancel, reject, and modify state. A naive
independent status/timeline deployment could therefore advance lifecycle state
before its required facts exist and never re-dirty the order when timeline
facts arrive.

## Scope And Guardrails

In scope:

- the canonical-outcome read, transport, projection SQL, lifecycle, and
  market-data projection path;
- PostgreSQL 16 query execution, memory, indexes, WAL, vacuum, and table shape;
- benchmark instrumentation and a reversible experiment ladder;
- changes required to define correct freshness dependencies.

Out of scope for this spike:

- weakening `202 Accepted` or canonical materialization semantics;
- making projection rows canonical truth;
- disabling `fsync`, `full_page_writes`, or durable projection commits as a
  benchmark shortcut;
- implementing schema changes or running additional paid infrastructure;
- adopting a new broker, CDC system, or database without comparative evidence.

## Method

Source priority:

1. current Reef code, migrations, canonical docs, and exact benchmark artifacts;
2. PostgreSQL 16 and pgJDBC primary documentation;
3. inference only where the current artifacts do not contain query-level plans
   or timings.

Evaluation criteria:

- correctness and replay safety;
- sustained outcomes per second, not short-burst completion;
- lag, lag area under curve, and drain headroom;
- WAL, temporary bytes, rows, index bytes, and CPU per projected outcome;
- reversibility and operational complexity;
- ability to assign different freshness SLOs without coupling them back into
  command acceptance.

## Evidence

### Sustained gate results

| Evidence | `2.5k/5m` full | `5k/5m` full | Interpretation |
| --- | ---: | ---: | --- |
| Run | `do-benchmark-20260820T220557Z` | `do-benchmark-20260820T222945Z` | Same c-16 full-projection gate family. |
| Accepted/materialized | `749,976` | `1,500,002` | Venue core scales to the offered `5k` load. |
| Projected | `749,976` | `746,047` | Projection performed almost the same total work in both runs. |
| Final watermark lag | `0` | `757,955` | `5k` is not sustainably projection-fresh. |
| Projection WAL | `4.334GB` | `4.394GB` | WAL tracks projected work, not accepted traffic. |
| Projection temp bytes | `15.774GB` | `16.624GB` | About `21-22KB` per projected outcome. |
| Projection inserts | `5,044,806` | `5,107,870` | About `6.7-6.8` inserted tuples per outcome. |
| Projector failures/retries/deadlocks | `0/0/0` | `0/0/0` | The limiting mode is capacity, not error recovery. |

Documented fact: `docs/PERFORMANCE_LEARNINGS.md` records the historical `10k`
result with `projected=0` intentionally. That result proves direct matching and
canonical materialization, not full read-model freshness.

Observation: during the `5k` soak, telemetry showed projection rising from
about `17k` to `536k` between `22:40:10Z` and `22:44:55Z`, or roughly
`1.8k outcomes/s` while intake and canonical materialization were active. After
intake ended, it rose from about `536k` to `714k` in roughly `63s`, or about
`2.8k outcomes/s`. Canonical-database competition is therefore part of the
combined-load limit, but projection-only drain still falls far short of `5k`.

Observation: the report's `projectedPerSecond=2486.69` is not a valid drain
rate. Its numerator includes projection completed during the configured drain
window, while its denominator is only the `300s` load duration.

Observation: the `5k` diagnostic snapshot recorded `13` active projection DB
sessions, one transaction-id lock waiter, an active autovacuum worker, and no
deadlock. The cumulative I/O delta included large client, background-worker,
and autovacuum relation scans, but I/O timing was zero because the run did not
enable timing collection.

Observation: re-mining the existing projection-Postgres CSVs shows
`4,920,420` additional client-backend bulk relation reads (`37.54GiB`) and
`4,558,589` background-worker bulk relation reads (`34.78GiB`), for about
`72.32GiB` combined. `pg_stat_io` aggregates these by backend type and context,
not relation, so the bytes cannot yet be assigned to one table or function.

Observation: table-stat deltas from the same run include `2,144` sequential
scans of `submit_results` and `4,923` sequential scans plus `6` autovacuum runs
for `order_lifecycle_dirty`. These are existing, no-cost plan targets before
another paid run. The lifecycle dirty scan count is consistent with frequent
concurrent queue polling, but only query plans/timing can establish its share
of elapsed time.

Observation: `order_lifecycle_state` recorded `71,237` updates but only `164`
HOT updates (`~0.23%`). This supports examining update/index shape. It does not
prove that table fillfactor will help: PostgreSQL HOT updates also require that
no indexed column changes, while lifecycle updates commonly change indexed
status, price, or remaining-quantity facts.

Inference: the stable per-projected-outcome WAL, temporary data, and tuple
ratios make per-outcome write/query work a stronger explanation than partition
skew. Direct partition skew was only about `1.01`, and all four projector groups
accumulated similar lag.

Unknown: which exact plan nodes generate most of the `~16GB` of temporary data.
The artifacts contain database-wide counters but not `pg_stat_statements` or
`EXPLAIN (ANALYZE, BUFFERS, WAL)` for the projection functions.

### Current execution path

Documented fact: each projector loop runs one batch at a time and sleeps only
on an empty poll. The benchmark uses four projector services, each owning four
of the active `0-15` partitions, with a default batch size of `250`.

Documented fact: for a separated projection store, every batch:

1. reads four projection watermarks from projection Postgres;
2. executes one canonical query per owned partition, including a join to
   `command_log.command_payloads` when that side table exists;
3. parses result and command JSON in Kotlin and serializes a new JSON array;
4. sends the JSON array to projection Postgres;
5. runs the full status and timeline SQL fanout;
6. updates projection watermarks in the same projection transaction.

Relevant implementation:

- `CanonicalProjectionWorker.kt`
- `PostgresRuntimePersistence.kt` lines around
  `projectCanonicalCommandOutcomesAcrossStores`,
  `canonicalCommandProjectionCandidates`, and `toPersistableSubmitOutcome`
- migrations `0040`, `0043`, `0048`, and `0049`

Documented fact: the status stage writes or checks `submit_results`, `orders`,
`executions`, `trades`, and `order_lifecycle_dirty`. The timeline stage writes
`runtime_events`, `runtime_event_payloads`, and legacy trace allocation rows.
Full mode invokes both functions before advancing one watermark.

Documented fact: status persistence repeatedly expands nested trade JSON for
the trade insert and again for buyer/seller dirty identifiers. Full mode also
expands the top-level outcome array independently in status and timeline
functions. Typed-fact triggers perform regex validation and text-to-UUID,
numeric, and timestamp casts on inserted orders, results, executions, trades,
and events.

Observation: the largest `5k` projection table growth was:

- `runtime_event_payloads`: `~774MB`;
- `runtime_events`: `~625MB`, including `~363MB` of indexes;
- `executions`: `~475MB`;
- `trades`: `~391MB`;
- `orders`: `~357MB`;
- `submit_results`: `~345MB`;
- `order_lifecycle_state`: `~271MB`.

Observation: `runtime_events`, `executions`, and `trades` each had index growth
close to or greater than heap growth. This makes index utility and write cost a
required measurement, not an assumption that every existing read index should
remain on the synchronous projection lane.

### Lifecycle dependency finding

Documented fact: `toPersistableSubmitOutcome` creates an accepted order only for
`SubmitOrder`. Cancel and modify state is represented by lifecycle events.

Documented fact: `runtime_project_order_lifecycle_state` derives cancelled,
rejected, and latest-modify state from `runtime.runtime_events`.

Documented fact: the command-status SQL marks orders dirty but does not write
`runtime_events`. The timeline SQL writes events but does not mark lifecycle
orders dirty.

Inference: running command-status ahead of timeline can produce stale lifecycle
state for cancel/modify, then clear the only dirty signal. Before stages become
independent production lanes, the design must either:

- put compact lifecycle-required facts in the order-state stage and derive
  lifecycle only from those facts; or
- make timeline persistence re-dirty affected orders and gate lifecycle on both
  prerequisite watermarks.

Unknown: whether the current command-status benchmark's functional assertions
cover final cancel and modify state for sampled orders. Its zero-lag result is
useful as a performance ablation but is not sufficient evidence of that
semantic guarantee.

### Downstream worker multiplicity

Documented fact: all four benchmark projector services inherit the enabled
order-lifecycle and market-data flags. Each process starts one lifecycle loop
and one market-data loop, and every market-data call invokes lifecycle
projection before computing snapshots. The gate can therefore issue up to
eight lifecycle projection calls per `250ms` polling interval. The lifecycle
function uses `FOR UPDATE SKIP LOCKED` to distribute dirty rows safely.

Inference: this concurrency may accelerate lifecycle drain, but it can also add
dirty-queue scans, connection demand, and lock traffic. It should be isolated in
Gate 1 rather than presumed to be either the cause or the cure.

## Primary-Source Findings

Documented fact: PostgreSQL 16 says `shared_buffers` normally needs to be
materially above its `128MB` default for good performance; `25%` of system RAM
is a starting point for a dedicated database server, not a universal target.
The c-16 benchmark host is shared by all Reef services, so it needs a smaller,
measured allocation rather than copying the dedicated-server rule.

Documented fact: PostgreSQL's default `work_mem` is `4MB`, and the limit applies
per sort or hash operation and per parallel worker. Raising it globally can
multiply memory consumption across operations and sessions. Projection-local
or transaction-local A/B settings are safer than a global first change.

Documented fact: `EXPLAIN (ANALYZE, BUFFERS, WAL)` can report shared and
temporary blocks plus WAL records/bytes. With `track_io_timing`, it can also
report data and temporary file I/O time.

Documented fact: `pg_stat_statements` aggregates planning/execution, block,
temporary-block, and WAL statistics by normalized statement. It requires
`shared_preload_libraries` and a restart.

Documented fact: PostgreSQL provides `jsonb_to_recordset` and related functions
to expand an array of objects into typed rows. A multiply referenced `WITH`
query is normally evaluated once, but materialization can itself become a
temporary-data cost. The correct SQL shape must therefore be selected from
plans rather than assumed.

Documented fact: PostgreSQL and pgJDBC provide `COPY FROM STDIN` for high-speed
bulk transfer. `COPY` is a candidate for a typed per-batch staging boundary,
not permission to bypass idempotency checks or transactional watermarking.

Documented fact: PostgreSQL warns that partitioning helps only for appropriate
large-table/query shapes and that poor partition design increases planning and
memory overhead. Reef's current global `event_id` uniqueness and order/trace
queries constrain viable partition keys, so partitioning is not the first fix.

Documented fact: unlogged tables avoid WAL but are truncated after an unclean
shutdown and are not replicated. They remain appropriate only for rebuildable
queues or staging, not durable projection facts whose configured freshness
contract expects crash recovery without a replay.

Documented fact: CQRS guidance supports separate write/read stores and
independent scaling, while requiring explicit synchronization, idempotent
duplicate handling, and acknowledgement of eventual consistency. Materialized
views remain disposable and rebuildable from source facts. This supports
Reef's current canonical/projection boundary and argues for explicit lane
watermarks rather than recoupling read models to command acceptance.

Documented fact: Apache Kafka Streams can place state stores with partitioned
stream tasks and restore them from compacted changelog topics after failure.
This is a useful comparison for future partition-local projections, but it
adds state restoration, query, and operational complexity and does not itself
eliminate Reef's public SQL read-model requirements.

Documented fact: Debezium's outbox event router captures committed outbox rows
and can route by a partition field. CDC could remove canonical polling and
Kotlin re-encoding in a future design, but it would not reduce the current
projection table/index fanout. It is therefore not the first move without phase
timing that shows canonical transport is dominant.

## Options

| Rank | Option | Throughput leverage | Correctness risk | Reversibility | Decision |
| ---: | --- | --- | --- | --- | --- |
| 1 | Query-level instrumentation plus projection-only drain benchmark | Enables every later decision | Low | High | Do first. |
| 2 | Projection-local memory and batch A/B | Potentially meaningful given temp spill; magnitude unknown | Low if bounded and monitored | High | Do immediately after instrumentation. |
| 3 | Typed, parse-once set-based staging and SQL fanout | High potential across CPU, temp, and network | Medium | Medium | Preferred implementation spike after plans identify hot nodes. |
| 4 | Correct independent order-state and timeline lanes | High SLO isolation; total work falls only if lane schemas are reduced | Medium-high due lifecycle dependencies | Medium | Target architecture; specify dependencies before implementation. |
| 5 | Index/retention cuts using read workload and `pg_stat_user_indexes` evidence | Medium and cumulative | Medium | Medium | Do table-by-table after representative read tests. |
| 6 | More projector processes | Low/negative before shared write cost falls | Medium contention risk | High | Defer. |
| 7 | Partition large projection tables | Workload-dependent | High schema/query risk | Low | Defer until plans, retention, and key constraints justify it. |
| 8 | Consume the venue-event stream or CDC directly | Reduces canonical polling/re-encoding, not projection fanout | High operational and replay-ordering risk | Low | Revisit only after projection DB work is reduced. |
| 9 | Separate database per freshness lane | Isolates resources but does not remove work | Medium-high operations cost | Low | Consider after logical lane split proves value. |
| 10 | Table-local fillfactor/autovacuum and index-shape A/B | May reduce lifecycle bloat/vacuum work; cannot create HOT updates when indexed facts change | Low when isolated and reversible | High | Measure after plans; do not assume a free win. |
| 11 | PgBouncer or revised pool topology | Can bound server sessions at higher service counts; does not remove queries or writes | Medium due transaction-pooling/session-state constraints | Medium | Defer unless projector pool telemetry shows connection pressure. |

## Recommended Experiment Ladder

### Gate 0: make the benchmark decision-capable

Add before another paid promotion run:

- re-mine and preserve a compact baseline from the existing `5k/5m` CSVs,
  including the `72.32GiB` aggregate bulk reads, per-table scan deltas,
  autovacuum counts, HOT ratio, table/index growth, and their attribution
  limits;

- `pg_stat_statements` snapshots for canonical and projection Postgres;
- `track_io_timing=on` in the benchmark profile, with its overhead recorded;
- representative `EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)` for
  status, timeline, lifecycle, and market-data batches on a cloned dataset;
- per-projector batch count, batch rows, canonical-read time, Kotlin
  parse/serialize time, projection-SQL time, commit time, and watermark time;
- per-projector-process connection-pool active/idle/max/awaiting snapshots; the
  current `runtime.dbPools` telemetry probe covers the API service rather than
  all four projector processes;
- separate `inLoadProjectedRps`, `drainProjectedRps`, drain duration, maximum
  lag, final lag, and lag area under curve;
- projection database CPU/I/O samples at a finer cadence than the current
  roughly `31s` telemetry interval.

Add a projection-only drain mode that preloads a fixed canonical backlog,
stops intake, resets measurement counters, and measures only projection drain.
This separates projection capacity from canonical-write competition.

Exit gate: the top statements and plan nodes account for most elapsed time,
temporary bytes, and WAL; projected rate no longer mixes load and drain time.

### Gate 1: isolate the expensive stages

Use fresh state and identical deterministic input for each diagnostic run:

1. status stage only; lifecycle and market data disabled;
2. timeline stage only; lifecycle and market data disabled;
3. full status plus timeline; lifecycle and market data disabled;
4. full plus lifecycle; market data disabled;
5. full plus lifecycle and market data, matching the current gate.
6. repeat the lifecycle-bearing shape with one designated lifecycle/market
   worker versus the current four-service topology; remember each market-data
   cycle also invokes lifecycle projection.

Run `60-120s` first. Promote only informative candidates to `5m`.

These are performance ablations, not freshness claims. The status-only result
must not be labeled own-order-state-fresh until lifecycle dependency tests pass.

Exit gate: one or more stages explain the sustained capacity loss, and each
stage has rows/WAL/temp/time per source outcome.

### Gate 2: bounded memory and batch matrix

Hold SQL and topology constant. Test sequentially rather than as a large
factorial sweep:

1. current `128MB` shared buffers, `4MB` work memory, batch `250` baseline;
2. projection Postgres `shared_buffers=2GB`, then `4GB`, while checking total
   host memory and canonical-lane latency;
3. projection-transaction `work_mem=16MB`, `32MB`, then at most `64MB`, not a
   global setting;
4. batch `500`, then `1000`, using the best safe memory setting.
5. on a cloned/pre-aged dataset, test table-local lifecycle fillfactor and
   autovacuum thresholds only after plans show whether unchanged indexed facts
   can produce HOT updates and whether vacuum is on the critical path;
6. hold direct Hikari pools as the baseline; vary pool limits, and test a
   pooler only if all-projector pool telemetry shows connection churn,
   exhaustion, or server-process overhead.

Reject a candidate if it only shifts pressure into OOM risk, latency spikes,
canonical contention, or larger lag. Do not change durability settings.

Exit gate for `5k`: projection-only drain at least `6k outcomes/s`, combined
`5k/5m` with zero final lag/gaps/deadlocks/retries, materially lower temporary
bytes per outcome without worse correctness or API latency, and a credible
path to the `7.5k` tier. `6k/s` is the minimum anti-backlog promotion margin,
not the capacity-planning target. D-038's preferred practical target remains
`2-3x` subsystem headroom when cost and complexity are reasonable.

### Gate 3: implement the smallest structural fix supported by Gate 0-2

Preferred order:

1. replace repeated JSON expansion with a typed parse-once batch relation;
2. avoid Kotlin parse/re-encode fields that can cross the projection boundary
   as typed values;
3. preserve conflict detection, deterministic order, and watermark advancement
   in one transaction per stage;
4. create an order-state stage containing all facts required for correct
   submit/cancel/modify lifecycle;
5. move cold timeline payload/history to an independent watermark and worker;
6. make timeline catch-up re-drive any dependent state if its facts remain a
   lifecycle input;
7. review redundant indexes with measured public-read/replay plans before
   dropping them.

Exit gate: replay produces byte/row-equivalent public projection facts, crash
injection never advances a watermark over missing rows, and the `5k` gate has
at least `20%` measured projection drain headroom plus a credible path to the
next tier. Pursue `2-3x` practical subsystem headroom where it can be achieved
without brute-force cost or hiding inefficient work.

### Gate 4: promotion ladder

- `5k/5m`: zero lag and at least `6k/s` projection-only drain.
- `7.5k/5m`: zero lag and at least `9k/s` projection-only drain.
- `10k/5m`: zero lag and at least `12k/s` projection-only drain.
- repeat the final tier after a larger pre-existing projection dataset and with
  representative public reads active.

The headroom requirement prevents a gate from passing only because the run
ended before backlog became visible. It is a minimum promotion rule, while the
repository's `2-3x` guidance remains the preferred architecture headroom target
when reasonable.

## Required Correctness Tests

Before accepting independent stages or a staging rewrite:

- submit, reject, cancel, and modify facts remain idempotent under replay;
- conflicting replay still fails rather than becoming `DO NOTHING`;
- a crash after rows but before watermark replays safely;
- a crash after status but before timeline cannot publish a false lifecycle
  freshness state;
- runtime configuration rejects or clearly isolates status-only plus lifecycle
  as a diagnostic ablation until the dependency is implemented; a regression
  test prevents it from being promoted as `own-order-fresh`;
- cancelled and modified own-order state is correct when stages run at
  different speeds;
- every stage can rebuild from canonical facts without broker-only history;
- deterministic sequence/order is unchanged;
- projection lag never changes durable command acceptance unless an explicit
  backpressure policy says so.

## Confidence, Limitations, And Open Questions

Confidence: high that full projection, not venue-core intake, is the current
limiter; high that temporary work and row/index fanout are material; medium on
the relative contribution of status, timeline, lifecycle, memory, and canonical
polling until query-level evidence is collected.

Limitations:

- only one current `2.5k/5m` and one current `5k/5m` full-projection run are
  available;
- database counters cover the whole service, not individual statements;
- the final activity/wait snapshot is not a time series;
- I/O timing was disabled;
- the benchmark uses a fresh database, so long-lived vacuum, cache, and index
  behavior are underrepresented.

Open questions to answer in Gate 0-1:

- which statements and plan nodes create the temporary files;
- whether status, timeline, lifecycle, or typed-fact triggers dominate CPU;
- how much combined-load loss comes from canonical candidate reads;
- whether batch `250` is transaction-bound or already plan/CPU-bound;
- which indexes are exercised by representative public reads and replay;
- whether status-only functional checks currently validate final cancel/modify
  state;
- how drain rate changes with a multi-million-row pre-existing projection.

## Source Index

Repository evidence:

- `reports/do-benchmark/do-benchmark-20260820T220557Z/`
- `reports/do-benchmark/do-benchmark-20260820T222945Z/`
- `docs/PERFORMANCE_LEARNINGS.md`
- `docs/PROJECTION_THROUGHPUT_SCALING_PLAN.md`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/CanonicalProjectionWorker.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`
- `scripts/dev/db/migrations/runtime/0040_split_submit_outcome_projection_stages.sql`
- `scripts/dev/db/migrations/runtime/0043_runtime_event_payload_cold_table.sql`
- `scripts/dev/db/migrations/runtime/0049_execution_replay_conflicts.sql`
- `compose.base.yml` and `compose.local.yml`

Primary external sources:

- PostgreSQL 16 resource consumption:
  <https://www.postgresql.org/docs/16/runtime-config-resource.html>
- PostgreSQL 16 `EXPLAIN`:
  <https://www.postgresql.org/docs/16/sql-explain.html>
- PostgreSQL 16 cumulative statistics and `pg_stat_io`:
  <https://www.postgresql.org/docs/16/monitoring-stats.html>
- PostgreSQL 16 `pg_stat_statements`:
  <https://www.postgresql.org/docs/16/pgstatstatements.html>
- PostgreSQL 16 JSON functions:
  <https://www.postgresql.org/docs/16/functions-json.html>
- PostgreSQL 16 `WITH` materialization behavior:
  <https://www.postgresql.org/docs/16/sql-select.html>
- PostgreSQL 16 bulk loading and `COPY`:
  <https://www.postgresql.org/docs/16/populate.html>
- pgJDBC `CopyManager`:
  <https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/copy/CopyManager.html>
- PostgreSQL 16 partitioning:
  <https://www.postgresql.org/docs/16/ddl-partitioning.html>
- PostgreSQL 16 unlogged tables:
  <https://www.postgresql.org/docs/16/sql-createtable.html>
- PostgreSQL 16 routine vacuuming:
  <https://www.postgresql.org/docs/16/routine-vacuuming.html>
- PostgreSQL 16 heap-only tuples and fillfactor constraints:
  <https://www.postgresql.org/docs/16/storage-hot.html>
- PgBouncer feature and pooling-mode compatibility matrix:
  <https://www.pgbouncer.org/features.html>
- Microsoft CQRS pattern:
  <https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs>
- Microsoft materialized-view pattern:
  <https://learn.microsoft.com/en-us/azure/architecture/patterns/materialized-view>
- Apache Kafka Streams architecture:
  <https://kafka.apache.org/40/streams/architecture/>
- Debezium outbox event router:
  <https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html>
