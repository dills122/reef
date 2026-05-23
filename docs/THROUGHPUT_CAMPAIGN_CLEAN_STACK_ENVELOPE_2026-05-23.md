# Throughput Campaign Clean-Stack Envelope (2026-05-23)

## Purpose

Capture a clean-stack throughput envelope after simulator lane improvements and validate how close we can get to sprint targets under deterministic persona traffic.

## Key Commands

### 1) Clean reset + smoke

```bash
make dev-reset JS_RUNTIME=node
```

Equivalent single-step campaign entrypoint:

```bash
DEV_CAMPAIGN_RESET_STACK=1 make dev-throughput-campaign JS_RUNTIME=node
```

### 2) Clean baseline check (`1100 rps`, `workers=64`)

```bash
DEV_CAMPAIGN_DURATION=30s \
DEV_CAMPAIGN_RATES=1100 \
DEV_CAMPAIGN_WORKERS=64 \
DEV_CAMPAIGN_TRACE_CHECK_LIMIT=100 \
DEV_CAMPAIGN_ARTIFACT_DIR=/tmp/reef-throughput-campaign-clean-post-reset \
make dev-throughput-campaign JS_RUNTIME=node
```

### 3) High-envelope sweep (`1300,1500,1700` x `64,96`)

```bash
DEV_CAMPAIGN_DURATION=30s \
DEV_CAMPAIGN_RATES=1300,1500,1700 \
DEV_CAMPAIGN_WORKERS=64,96 \
DEV_CAMPAIGN_TRACE_CHECK_LIMIT=100 \
DEV_CAMPAIGN_ARTIFACT_DIR=/tmp/reef-throughput-campaign-clean-high-envelope \
make dev-throughput-campaign JS_RUNTIME=node
```

## Clean Baseline Snapshot (`1100/64`)

Artifacts:
- `/tmp/reef-throughput-campaign-clean-post-reset/throughput-campaign-summary.json`
- `/tmp/reef-throughput-campaign-clean-post-reset/throughput-campaign-summary.md`

Results:
- quality lane (`strict-lifecycle` + `strict-clean`):
  - throughput `1063.06 rps`
  - accepted `976.11 rps`
  - success-rate `91.82%`
- capacity lane (`capacity-baseline` + `capacity-heavy`):
  - throughput `1058.01 rps`
  - accepted `958.92 rps`
  - success-rate `90.63%`

## High-Envelope Snapshot (Clean Stack)

Artifacts:
- `/tmp/reef-throughput-campaign-clean-high-envelope/throughput-campaign-summary.json`
- `/tmp/reef-throughput-campaign-clean-high-envelope/throughput-campaign-summary.md`

### Quality Lane (`strict-lifecycle`, `strict-clean`)

- peak throughput: `1292.08 rps` at `rate=1500`, `workers=64`
- peak accepted: `1183.94 rps`
- success-rate at peak: `91.63%`
- `>=95%` quality cap: not reached

### Capacity Lane (`capacity-baseline`, `capacity-heavy`)

- peak throughput: `960.97 rps` at `rate=1300`, `workers=64`
- peak accepted: `871.85 rps`
- success-rate at peak: `90.73%`
- `>=95%` quality cap: not reached

## Findings

1. Clean stack state is a first-order factor. Without reset, extended campaigns drifted into lower throughput/latency instability; after `dev-reset`, throughput returned to >`1k rps` immediately.
2. Quality lane now reaches the minimum throughput target band (`1250-1500`) on raw throughput (`1292 rps`) while staying above `90%` success.
3. Capacity-heavy lane still has an earlier saturation point (best near `1300/64`) and drops sharply with higher rates/workers.
4. `workers=64` is the strongest operating point in both lanes; `workers=96` generally increases p95/p99 and lowers effective throughput.
5. Reject taxonomy remains overwhelmingly business-state (`INVALID_STATE`) rather than transport errors on clean runs.

## Operating Guidance (Current)

- Quality-focused benchmark target point: `rate=1500`, `workers=64`
- Capacity-heavy benchmark target point: `rate=1300`, `workers=64`
- Run `make dev-reset` before long/high-rate comparison campaigns to avoid stale-state drift between sweeps.

## Implementation Update (2026-05-23)

Completed:
1. Added pre-campaign reset control to throughput campaign tooling:
   - `DEV_CAMPAIGN_RESET_STACK=1`
2. Tuned `capacity-heavy` action mix from `60/30/10` to `68/24/8` (`submit/modify/cancel`).

Verification run:

```bash
DEV_CAMPAIGN_RESET_STACK=1 \
DEV_CAMPAIGN_DURATION=30s \
DEV_CAMPAIGN_RATES=1300,1500 \
DEV_CAMPAIGN_WORKERS=64 \
DEV_CAMPAIGN_TRACE_CHECK_LIMIT=100 \
DEV_CAMPAIGN_ARTIFACT_DIR=/tmp/reef-throughput-campaign-capmix-tuned \
make dev-throughput-campaign JS_RUNTIME=node
```

Results:
- quality lane peak: `1320.57 rps`, accepted `1210.46 rps`, success `91.66%`
- capacity lane peak: `1370.17 rps`, accepted `1243.12 rps`, success `90.73%`

## Next Actions

1. Add side-by-side campaign comparator output (old vs new artifact directories) so tuning deltas are explicit in one report.
2. Split campaign goal reporting into:
   - raw throughput goal
   - accepted throughput goal
   to avoid ambiguity when success-rate and accept-reject mix shift.
3. Add optional lane-level action mix overrides (`DEV_CAMPAIGN_CAPACITY_SUBMIT_PCT`, etc.) to support controlled experiments without code edits.
