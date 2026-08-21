# Projection Maintainer Cardinality Local Validation

Date: 2026-08-20 (America/Toronto; artifact timestamps cross 2026-08-21 UTC)

## Question

Does running order-lifecycle and market-data maintenance loops in every
canonical projector process help throughput, or does it create avoidable
projection-Postgres contention?

## Isolated Shape

- Disposable Compose project `reef-projection-worker-ab`; normal Reef
  containers and volumes were not used.
- Current working-tree runtime image.
- `100,002` canonical outcomes created at `4,996.78/s` over `20s` with all
  projectors stopped.
- Four canonical projectors, partitions `0-3`, `4-7`, `8-11`, and `12-15`.
- Full projection, fills enabled, batch `250`.
- Only projector 0 ran order-lifecycle and market-data maintenance. Projectors
  1-3 ran only their partitioned canonical loops.
- Intake, matching, and all materializers were stopped before projection.
- Projection Postgres used `pg_stat_statements.track=all`,
  `track_io_timing=on`, and `track_wal_io_timing=on`.

The source ceiling stayed fixed at `100,002`; final projection counts were:

| Relation | Rows |
| --- | ---: |
| `submit_results` | 100,002 |
| `runtime_events` | 89,927 |
| `orders` | 82,697 |
| `executions` | 86,922 |
| `trades` | 43,461 |
| `order_lifecycle_state` | 82,697 |
| `market_data_snapshots` | 64 |
| `order_lifecycle_dirty` | 0 |
| `market_data_snapshot_dirty` | 0 |

There were no projector failures, retries, retry exhaustion, or projection DB
deadlocks.

## Timing Result

The backlog completed before the fixed-backlog harness obtained its first
sample, so the harness correctly rejected an invented exact rate. Docker
container start timestamps and each projector's monotonic completion metric
provide a conservative bound:

- earliest projector container start: `01:13:46.107926426Z`;
- last projector completion: `01:13:56.491Z`;
- container-start-to-completion bound: at most `10.383s`;
- conservative drain lower bound: at least `9,631.32 outcomes/s`.

This bound includes JVM/container startup before the canonical loop could do
work, so actual steady drain capacity was higher. It exceeds the current
`6k/s` minimum local headroom gate, but remains local evidence rather than a
DigitalOcean promotion result.

The earlier all-four-maintainer local validation observed `52,250` items drain
at `3,929.46/s`. The two windows use the same machine, code, four canonical
partition writers, full stage, and batch size, but they are not a perfect
benchmark pair: the earlier database included setup outcomes and did not enable
statement tracking, while this run completed before normal harness sampling.
The direction and magnitude justify promoting single-maintainer cardinality to
the next named remote A/B; they do not by themselves prove remote `5k/5m`.

## Statement Attribution

For the clean canonical projection work, `pg_stat_statements` recorded:

| Statement | Calls | Total execution | Shared blocks read | Temp blocks written | WAL bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| full `runtime_persist_submit_outcomes` | 408 | 22.66s | 29,402 | 0 | 504,289,511 |
| nested status stage | 408 | 11.72s | 19,678 | 0 | 286,794,987 |
| timeline wrapper | 408 | 6.34s | 9,716 | 0 | 217,494,524 |
| nested timeline body | 408 | 6.17s | 9,715 | 0 | 217,494,524 |
| submit-result replay-conflict check | 408 | 2.57s | 0 | 0 | 0 |

Nested rows overlap their parent function totals and must not be summed. The
status stage is the larger of the two write stages in this shape; the
timeline stage remains material. No temporary blocks were written locally,
which means the remote `~16GB` temporary-data behavior still needs the bounded
memory/batch and pre-aged-data experiments.

## Excluded Attempt And Scan Finding

One setup attempt omitted `EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack`.
The projectors were healthy, but their canonical loops correctly did not start;
the drain harness timed out with the source ceiling unchanged and zero work.
That attempt is excluded from throughput evidence.

The pre-statement snapshot preceded that failed attempt, so lifecycle,
market-data, and diagnostic query deltas include its `120s` idle window and
must not be compared as drain costs. That contamination nevertheless exposed a
real query shape: `SELECT COUNT(*) FROM runtime.submit_results` ran `2,591`
times and read `119,557` shared blocks. `projectionStatus` executes this exact
count, and market-data maintenance calls `projectionStatus` every cycle. This
provides a concrete explanation for much of the previously observed
`submit_results` sequential-scan pressure. Replace or amortize the exact count
only with a design that preserves final freshness/gap correctness.

## Decision

- Advance one designated lifecycle/market-data maintainer to the named remote
  projection gate; keep all four canonical partition writers.
- Require the remote artifacts to report the effective maintainer flags from
  all four projectors and fail unless exactly one lifecycle and one market-data
  maintainer are observed.
- Preserve the previous all-maintainer behavior unless per-projector overrides
  are explicitly set, so unrelated profiles do not change silently.
- Keep statement/I/O evidence mandatory in the remote gate.
- Investigate watermark/counter-based or safely cached projected-count status
  before treating `submit_results` scan pressure as solved.
- Do not run another unchanged paid `5k/5m` gate. The next paid run must be an
  explicit single-maintainer A/B with the new statement artifacts.

## Local Artifacts

- Preload:
  `/tmp/reef-projection-worker-ab-preload/materializer-stress-rate-5000-workers-384.json`
- Correct single-maintainer status snapshot:
  `/tmp/reef-projection-worker-ab-drain/projection-drain-report-single-maintainer.json`
- Statement/settings/table snapshots:
  `/tmp/reef-projection-worker-ab-drain/db/`
- Excluded fail-closed setup attempt:
  `/tmp/reef-projection-worker-ab-drain/projection-drain-report.json`
