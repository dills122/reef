# Abuse Breaker Overhead Comparison (2026-05-27)

## Purpose

Validate boundary breaker runtime overhead in a non-tripping configuration before enabling broader tuning work.

## Setup

- stack: `docker compose` local dev
- runtime image: branch `codex/abuse-control-circuit-breakers`
- simulator command (both runs):
  - `node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --workers 128 --rate 1200 --pretty-summary --report-out <path>`
- run A (off): `EXTERNAL_API_ABUSE_BREAKER_MODE=off`
- run B (on, non-tripping):
  - `EXTERNAL_API_ABUSE_BREAKER_MODE=reject-rate`
  - `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS=100000`
  - `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS=30`
  - `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS=60`

Artifacts:

- off: `/private/tmp/reef-sim-breaker-off-60s.json`
- on: `/private/tmp/reef-sim-breaker-on-high-threshold-60s.json`

Breaker stats snapshot after run B:

- endpoint: `GET /internal/boundary/abuse/stats`
- `trips=0`, `blocks=0`, `releases=0`, `activeBlockedClients=0`

## Results

| Metric | Off | On (high threshold) | Delta |
|---|---:|---:|---:|
| throughput rps | 1072.83 | 1071.53 | -0.12% |
| accepted business rps | 1056.41 | 1056.78 | +0.03% |
| success rate | 98.47% | 98.62% | +0.16% |
| latency p95 ms | 46.7148 | 46.7160 | +0.00% |
| latency p99 ms | 90.92 | 103.77 | +14.14% |

## Interpretation

- Throughput and p95 are effectively unchanged in non-tripping mode.
- Accepted throughput and success-rate differences are within normal run variance for short 60s windows.
- p99 widened in run B; because other primary metrics remained stable and no breaker trips occurred, this looks like short-window tail variance, not deterministic overhead.

## Decision

- keep breaker hooks enabled as feature-complete baseline
- do not claim meaningful overhead until we repeat with longer windows (5-10m) and 2+ paired samples per lane

## Next

1. Add this A/B pair to throughput campaign lanes (breaker-off and breaker-on-high-threshold).
2. Add an intentional trip lane to validate reject behavior and recovery envelope.
3. Track breaker counters per lane artifact for regression checks.
