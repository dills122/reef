# Projection Gate 0 Findings

## Sources

- `AGENTS.md` and canonical architecture/performance documents.
- `docs/research/PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md`.
- `docs/research/PROJECTION_THROUGHPUT_SPIKE_2026-08-20.md`.
- Runtime, diagnostics, persistence, Compose, and benchmark code identified
  during Phase 1.

## Notes

- Current full projection is safe because status, timeline, and watermark
  advancement share a projection transaction.
- Status-only plus lifecycle is unsafe for cancel/reject/modify freshness.
- Current stress telemetry polls projector status endpoints but the generic DB
  pool probe targets the API process only.

## Resolutions

- Added `STREAM_ACK_PROJECTION_STATUS_ONLY_DIAGNOSTIC`, defaulting false. The DO
  wrapper sets it only for explicit command-status aliases.
- Timed existing separated-store persistence boundaries without changing the
  persistence contract or transaction ordering.
- Kept load generation out of the drain measurement by adding a standalone
  harness. It validates an immutable per-partition canonical ceiling instead
  of attempting to own or mutate the intake stack.

## Live Validation

- An isolated live stack drained an observed `52,250` items to zero in
  `13.297s` (`3,929.46/s`) with stable per-partition canonical ceilings, no
  projector failures/retries, no pool waiters, and empty lifecycle/market-data
  dirty queues.
- Projection SQL dominated measured canonical-loop phase time. This validates
  the next experiment's focus on SQL/write shape while preserving the planned
  lifecycle-worker cardinality ablation.
- The follow-up single-maintainer local A/B projected `100,002` fixed outcomes
  within `10.383s` of container start (`>=9,631.32/s` conservative bound), with
  zero failures/retries/deadlocks and empty lifecycle/market-data dirty queues.
  This promotes one designated lifecycle/market-data maintainer to the named
  remote gate while keeping four canonical partition writers.
- Nested statement tracking ranked the status stage above timeline locally and
  showed `projectionStatus`'s exact `submit_results` count as a repeated scan
  source. Downstream-worker statement totals from this run include an excluded
  `120s` idle setup attempt and are not valid drain-cost comparisons.
