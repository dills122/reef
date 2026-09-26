# Projection Gate 0 Task Plan

Goal: deliver the first correctness-preserving projection-throughput work unit:
unsafe stage configuration protection, attributable projector instrumentation,
and a projection-only fixed-backlog measurement path.

## Phases

- [x] Phase 1: establish Kotlin, benchmark, diagnostics, and test contracts.
- [x] Phase 2: add failing tests and implement the stage/lifecycle safety guard.
- [x] Phase 3: add failing tests and implement canonical-read, transform,
  projection-SQL/commit, and watermark/batch metrics where boundaries permit.
- [x] Phase 4: add failing tests and implement projection-only backlog drain
  automation/reporting without paid infrastructure execution.
- [x] Phase 5: run focused and module/repository verification; update canonical
  docs and record remaining Gate 0 work.

## Acceptance Criteria

- Unsafe status-only plus lifecycle configuration cannot be mistaken for an
  `own-order-fresh` production topology.
- Projector telemetry separates batch size/count and meaningful processing
  phases rather than reporting only one aggregate duration.
- Pool snapshots are collectable from every projector endpoint in the stress
  telemetry path.
- Projection-only drain uses a fixed canonical backlog, does not generate new
  intake during measurement, and reports drain rate/duration/final lag.
- Existing venue-core correctness, idempotency, replay, and watermark semantics
  remain unchanged.
- New behavior is covered by tests written red-first.

## Decisions

- Keep this work unit observational and protective; do not change projection
  SQL shape, durability, indexes, or freshness architecture yet.
- Do not run paid DigitalOcean infrastructure without a separate explicit gate.
- Preserve the current full-stage production default.

## Risks

- Existing metrics boundaries may not expose database commit separately from
  SQL function execution without invasive persistence API changes.
- A projection-only benchmark must not accidentally reset or mutate canonical
  source facts needed for replay.
- Configuration validation must preserve intentional status-only diagnostic
  ablations while preventing a false lifecycle-fresh claim.
