# Projection Throughput Author Explanation

This packet is intentionally separate from the fresh-review bootstrap. A
reviewer should read it only after completing a preliminary repository and
artifact pass.

## Intent

Establish an evidence-backed, correctness-preserving path from Reef's proven
`2.5k/s` sustained full-projection freshness to `5k/s`, `7.5k/s`, and `10k/s`,
then implement Gate 0: protect the known stage dependency, make projector work
attributable, and add a projection-only fixed-backlog measurement path. Gate 0
does not alter projection SQL shape or authorize another paid benchmark.

## Plan Traceability

The recommendation follows existing repository policy:

- keep canonical facts separate from rebuildable projections;
- do not add synchronous read-model fanout to command acceptance;
- measure rows, WAL, commits, projection work, and skew before scaling;
- optimize projections as a streaming system using batching, coalescing,
  reduced indexes, and staging where evidence supports them;
- preserve deterministic processing, idempotency, replay, and auditability.

Detailed artifacts:

- current-system dossier:
  [`PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md`](./PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md)
- decision-ready research spike:
  [`PROJECTION_THROUGHPUT_SPIKE_2026-08-20.md`](./PROJECTION_THROUGHPUT_SPIKE_2026-08-20.md)
- historical and current scaling record:
  [`../PROJECTION_THROUGHPUT_SCALING_PLAN.md`](../PROJECTION_THROUGHPUT_SCALING_PLAN.md)

Implemented Gate 0 plan items:

- startup rejects `command-status` plus lifecycle/market-data consumers unless
  an explicit diagnostic-only acknowledgement is present;
- projector status exposes batch count, last batch size, and maximum batch
  size;
- separated-store projection work records canonical-read, transform,
  projection-SQL, watermark, and commit timers;
- stress telemetry samples hot-path and DB-pool endpoints from every projector,
  and the evidence checker requires those pool probes;
- the named remote projection gate preloads `pg_stat_statements`, tracks nested
  PL/pgSQL statements, enables I/O/WAL-I/O timing, captures pre/post statement
  and settings artifacts, and fails closed when statement evidence is absent;
- per-projector lifecycle/market-data controls let the named remote gate keep
  four canonical writers while assigning downstream maintenance only to
  projector 0;
- `make dev-projection-drain-bench` measures an existing fixed backlog and
  fails if the canonical ceiling changes.

## Technical Flow

The durable hot path is command ingress to Redpanda, deterministic Go matching,
durable `VenueEventBatch` publication, and compact canonical PostgreSQL
materialization. Kotlin projector services then poll canonical outcomes by
partition and watermark, parse and re-encode each batch, and execute status and
timeline fanout in projection PostgreSQL before advancing watermarks. Separate
lifecycle and market-data workers consume dirty queues to maintain read models.

The key distinction is that the near-`10k/s` result covers venue core with
projection disabled. Full projection is a separate asynchronous capacity and
freshness contract.

## Component Walkthrough

### Venue-event materializer

The materializer consumes committed event batches, verifies checksums and
ordering, persists compact canonical facts atomically, and acknowledges only
after database success. Current evidence shows it keeps up near `10k/s`.

### Canonical projectors

Four services each own four of the benchmark's 16 partitions. Each service has
one serial canonical loop with batch `250`. Across-store projection reads
watermarks from the projection database, queries canonical PostgreSQL, parses
JSON in Kotlin, re-serializes projection envelopes, and writes them to the
projection database.
Gate 0 times those existing boundaries without moving work between them or
changing the transaction.

### Projection SQL

Full mode executes status and timeline work under one projection transaction.
It expands JSON multiple times and writes status, order, execution, trade,
event, event-payload, dirty-queue, and watermark relations. Typed-fact triggers
perform validation and casts; indexes are maintained for public read and replay
paths.

### Lifecycle and market data

Each projector process starts lifecycle and market-data loops when enabled.
Market-data projection calls lifecycle first. `SKIP LOCKED` lets concurrent
workers divide dirty items, but the actual benefit or overhead has not been
measured independently.

### Fixed-backlog drain harness

The harness assumes canonical facts were preloaded while projector services
were stopped and that intake/materialization are stopped for the measurement.
It resets only in-process hot-path timers, samples status/hot-path/pool state
from each projector, integrates lag over time, and computes drain work from the
observed initial and final watermark lag. It rejects an empty initial backlog,
nonzero final lag, probe failures, timeout, or any change in the per-partition
canonical watermark ceilings. It does not create, delete, or mutate canonical
facts.

## Choices And Rejected Alternatives

Chosen first:

- query- and phase-level instrumentation;
- a projection-only fixed-backlog drain test;
- stage ablations;
- bounded projection-local memory and batch experiments.

Preferred structural direction, contingent on measurement:

- typed, parse-once set-based staging;
- a complete order-state freshness lane;
- an independent cold timeline/history lane;
- evidence-backed index/retention changes.

Deferred:

- more projector processes, because they share the apparent database ceiling;
- table partitioning, because key/query/uniqueness constraints are not yet
  justified by plans;
- CDC/outbox, because it removes polling but not projection fanout;
- Kafka Streams or another state engine, because it materially expands
  operations and read-model design before simpler evidence-backed changes are
  exhausted;
- durability shortcuts, because they violate repository invariants.

## Invariants

- `202 Accepted` follows configured durable ingress acknowledgement.
- Committed matching offsets are not acknowledged before durable event-batch
  publication and materialization semantics are satisfied.
- Canonical facts remain the replay source; projections remain rebuildable.
- Projection watermarks never advance over missing or failed rows.
- Replay conflicts remain errors, not silent `DO NOTHING` outcomes.
- Per-lane deterministic ordering and public audit ordering remain stable.
- Projection lag is explicit and does not silently weaken command semantics.

## Verification And Evidence

Current sustained results:

- `2.5k/5m` full projection: `749,976` accepted, materialized, and projected;
  zero final lag/gap.
- `5k/5m` full projection: `1,500,002` accepted and materialized, `746,047`
  projected, final watermark lag `757,955`; zero projector failures, retries,
  or deadlocks.
- current `10k` venue core: about `9,995-9,998/s` over three `60s` samples;
  projection disabled.

The `2.5k` and `5k` projection runs performed almost identical total projection
work: about `750k` outcomes, `5.0-5.1M` inserted tuples, `4.3-4.4GB` WAL, and
`15.8-16.6GB` temporary data. This is the primary basis for diagnosing a
projection-work ceiling.

Gate 0 verification completed locally:

- full `platform-runtime` Gradle test suite;
- red-first focused tests for the unsafe-stage startup guard and projector
  batch metrics;
- deterministic drain-report calculation tests, including a moving-ceiling
  rejection case;
- benchmark evidence-checker regression tests, including missing projector
  pool and statement telemetry;
- repository script-surface syntax checks, shell syntax, Compose rendering,
  developer-tooling tests, and whitespace validation.
- an isolated live fixed-backlog run drained an observed `52,250` items to
  zero in `13.297s` (`3,929.46/s`) with stable canonical ceilings, no
  failures/retries, and no connection waiters. Summed projector timers were
  dominated by projection SQL (`38.62s`) rather than transform (`2.15s`),
  canonical read (`2.05s`), commit (`1.59s`), or watermark (`0.20s`). See
  [`PROJECTION_DRAIN_LOCAL_VALIDATION_2026-08-20.md`](./PROJECTION_DRAIN_LOCAL_VALIDATION_2026-08-20.md).
- a disposable isolated PostgreSQL check verified that the new statement
  collector preserves signed 64-bit query IDs and separately captures a
  top-level PL/pgSQL call and its nested insert with elapsed time, rows, and WAL
  bytes. No paid DigitalOcean run has exercised this instrumentation yet.
- a fresh isolated cardinality A/B projected `100,002` fixed outcomes within
  `10.383s` of container start (`>=9,631.32/s` conservative bound) with only
  projector 0 maintaining lifecycle/market data, empty dirty queues, and zero
  failures/retries/deadlocks. This is the selected topology for the next named
  remote A/B. Projector status and stress artifacts now expose the effective
  maintainer flags, and the named gate requires exactly one lifecycle and one
  market-data maintainer; see
  [`PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md`](./PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md).

## Risks

- Existing remote database-wide counters cannot attribute cost to an exact
  function or plan node. The next run can attribute nested statements, but
  representative `EXPLAIN (ANALYZE, BUFFERS, WAL)` remains necessary for plan
  nodes.
- A single current run exists at each sustained projection tier.
- The database was fresh, underrepresenting age and vacuum behavior.
- I/O timing was disabled in the existing sustained remote artifacts; the next
  named projection gate enables it and must record the setting.
- Current lifecycle worker multiplicity could be help or overhead.
- A stage split can make lifecycle stale if cancel/modify event dependencies
  are not corrected.
- Memory changes can multiply across operations and sessions and must remain
  bounded.
- Hot-path timers add low but nonzero per-batch measurement overhead.
- The drain harness validates a stable canonical ceiling but cannot itself stop
  external writers; correct orchestration remains an operator prerequisite.
- The first status sample occurs after projector startup, so work completed
  before that sample is intentionally outside the measured backlog.

## Deviations And Gaps

The historic scaling plan described a command-status run as demonstrating
command status plus own-order lifecycle freshness. Code inspection shows that
cancel/modify lifecycle still depends on runtime events written by the timeline
stage. The scaling plan has been corrected to label that run as a performance
ablation. Any remaining older wording in `docs/PERFORMANCE_LEARNINGS.md` is
documentation drift and should not be used as semantic evidence.

No SQL-shape, memory, index, worker-cardinality, or topology optimization has
been selected yet. The local drain establishes projection SQL as the dominant
instrumented canonical-loop phase and rules out local pool starvation, but it
does not provide statement/plan-node attribution or stage-isolation evidence.
Those are the next bounded experiments before selecting an optimization.

## Challenge Points For The Reviewer

- Is the evidence strong enough to rank projection write/query work first?
- Does the proposed Gate 0 isolate database, JVM, canonical-read, commit, and
  downstream-worker costs?
- Is there a correctness-preserving way to split order state from timeline that
  is simpler than the two proposed dependency designs?
- Should connection-pool and downstream-worker concurrency be tested before
  memory/batch changes?
- Are the promotion headroom thresholds sufficient for a venue simulation
  platform, and what evidence would justify different thresholds?
- Is there any external architecture that should be evaluated earlier because
  it removes a measured rather than hypothetical bottleneck?
- Should the benchmark orchestrate stopping writers and recreating projectors,
  or is fail-closed ceiling validation plus an explicit prerequisite the safer
  repository boundary?
