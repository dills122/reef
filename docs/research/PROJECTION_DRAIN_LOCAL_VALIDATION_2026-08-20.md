# Projection Drain Local Validation — 2026-08-20

## Purpose

Validate the Gate 0 fixed-backlog drain workflow and instrumentation on a live
stack before using it in a paid DigitalOcean experiment. This is an operational
smoke and bottleneck-attribution result, not portable capacity evidence.

## Isolation And Shape

- Compose project: `reef-projection-drain-smoke`.
- Project-scoped Postgres, projection-Postgres, boundary-Postgres, NATS, and
  Redpanda volumes; alternate host ports; existing Reef containers untouched.
- Canonical source: `REEF_MATERIALIZER_STRESS_VENUE_EVENTS`, 16 partitions.
- Four projectors owned partitions `0-3`, `4-7`, `8-11`, and `12-15`.
- Full projection, batch `250`, fills enabled, lifecycle and market-data
  workers enabled.
- API, matching engine, and all four materializers were stopped before the
  measured drain.

The final preload added exactly `100,000` accepted/direct-acked/materialized
outcomes over `20s` at `4,998.34/s`, with no load, direct-stream, or
materializer failures. The isolated canonical store contained `124,980`
outcomes across all 16 partitions after including two smaller harness setup
runs.

## Drain Result

Artifact:
`/tmp/reef-projection-drain-smoke-artifacts-3/projection-drain-report.json`

- Observed initial lag: `52,250`.
- Final lag: `0`.
- Observed drain duration: `13,297ms`.
- Observed drain rate: `3,929.46 projected work items/s`.
- Maximum observed lag: `52,250`.
- Lag area: `268,851.92 work-item-seconds`.
- Samples: `39`.
- Final projected count: `124,980`.
- Projector failures/retries/retry exhaustion: `0/0/0`.
- Per-partition canonical ceilings were byte-for-byte stable throughout the
  measured window.
- Projector and projection-Postgres logs contained no application errors or
  deadlock reports.

The projectors processed `47,750` of the new outcomes during container startup
before the first successful status sample. The result therefore measures only
the remaining `52,250`; it must not be presented as the full `100,000`-item
startup-to-drain rate. Two earlier `4,990` and `19,990` backlog attempts drained
before sampling, and the harness correctly rejected both as empty-initial-lag
runs.

## Phase Attribution

Across the four projectors during the measured portion:

| Phase | Summed projector time | Per-call range |
| --- | ---: | ---: |
| Projection SQL | `38.62s` | `91.13-125.27ms` average |
| Transform/serialization | `2.15s` | `4.62-6.73ms` average |
| Canonical read | `2.05s` | `3.43-4.16ms` average |
| Commit | `1.59s` | `3.50-5.08ms` average |
| Watermark update | `0.20s` | `0.51-0.61ms` average |

These are parallel per-process timer sums, not wall-clock shares. They are
still decisive for attribution: the projection SQL function dominates the
instrumented canonical-loop work by a large margin. Kotlin transformation,
canonical polling, commit, and watermark updates are not the primary limiter
in this local shape.

Lifecycle and market-data loops also consumed material time concurrently with
the canonical projectors. That reinforces the planned worker-cardinality
ablation, but does not overturn projection SQL as the dominant measured
canonical-loop phase.

## Pool And Correctness Checks

- Maximum threads awaiting a DB connection across all sampled projector pools:
  `0`.
- Maximum active `reef-runtime-projection` connections per projector: `3` of
  `16`.
- Maximum active canonical `reef-runtime` connections per projector: `2` of
  `16`.
- Final `submit_results`: `124,980`.
- Final `order_lifecycle_dirty`: `0`.
- Final `market_data_snapshot_dirty`: `0`.
- Final `order_lifecycle_state`: `103,400`.
- Final `market_data_snapshots`: `144`.

Connection-pool saturation is therefore not supported as the current local
bottleneck. Pool topology remains a higher-tier question, not the next lever.

## Decision

Gate 0 is operationally validated. The next bounded experiment should focus on
projection SQL/write shape and lifecycle/market-data worker cardinality. The
DigitalOcean batch/memory/fillfactor/autovacuum matrix should retain the new
phase and pool telemetry so local attribution can be compared with the
production-like benchmark host.

Do not use the local `3,929.46/s` number as a DigitalOcean promotion result.
Hardware, startup loss before sampling, database age, and the short backlog all
differ from the remote gate.
