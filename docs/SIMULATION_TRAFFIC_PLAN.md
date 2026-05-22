# Reef Simulation Traffic Plan

## Goal

Evolve from generic load testing into realistic participant-driven traffic that models institutional and retail-style flows while preserving deterministic replay and traceability.

## Participant Profiles

### 1) Market Maker Bots

Behavior:
- continuously place bid/ask quotes around a configurable mid-price
- replenish quotes after fills
- occasionally cancel/replace stale quotes

Key parameters:
- quote spread (ticks)
- quote size range
- refresh interval
- inventory limit
- skew sensitivity (widen spread when inventory imbalanced)

### 2) Institutional Trader Bots

Behavior:
- submit larger parent flow sliced into child orders
- mix passive and aggressive child orders
- modify/cancel based on fill progress and timeout

Key parameters:
- parent order size distribution
- slice size distribution
- urgency (aggressive vs passive ratio)
- max wait before modify/cancel

### 3) Retail Flow Bots

Behavior:
- smaller, bursty orders
- mostly marketable behavior during volatility windows
- higher cancellation noise vs institutional flow

Key parameters:
- order arrival bursts
- small size distribution
- directional bias windows (buy/sell streaks)

### 4) Noise/Probe Bots

Behavior:
- random low-size traffic to create background activity
- intentionally low sophistication

Key parameters:
- low and steady arrival rate
- limited outstanding orders

## Market Regimes

Run traffic in explicit regimes:

1. Warm-up: low volatility, balanced flow
2. Normal session: steady mixed flow
3. Stress burst: higher aggressive ratio and cancellation rate
4. Cool-down: reduced activity and inventory flattening

Each regime has:
- duration
- per-profile intensity multipliers
- spread/price volatility settings

## Scheduling Model

Use a tick-based scheduler:
- each tick evaluates profile actions
- profiles decide `submit/modify/cancel` using current local state
- deterministic randomness via global seed + profile seed offsets

Determinism rules:
- fixed event ordering per tick
- deterministic PRNG per profile
- no wall-clock-based branching in decision logic

## Metrics to Add

Beyond current load report:
- accepted ops/sec by profile
- reject code distribution by profile and action
- outstanding live orders over time
- fill ratio (submitted vs executed quantity)
- cancel-to-submit ratio
- time-to-first-fill and time-to-complete-parent-order
- quote uptime for market makers

## Validation Invariants

Per trace:
- sequence numbers strictly monotonic
- causation chain continuity

Per profile:
- no duplicate command effects
- inventory stays within configured bounds (market makers)
- parent/child consistency (institutional slicer)

## Rollout Phases

### Phase A: Profile-Aware CLI (Next)
- add `--profile` mixes to simulator (`mm`, `institutional`, `retail`, `noise`)
- add per-profile worker pools and metrics
- keep current JSON summary format with profile extensions

### Phase B: Regime Engine
- add multi-regime run config file
- support runtime transitions between regimes
- emit regime boundary markers in report

### Phase C: Scenario Files
- declarative YAML/JSON scenario definitions
- seeded replay of named scenarios
- comparative run tooling (`scenario A` vs `scenario B`)

### Phase D: Fault and Recovery
- inject runtime/engine latency spikes and partial failures
- validate idempotency and trace integrity under retry pressure

## Suggested First Profile Mix

For initial realism:
- 35% market maker actions
- 30% institutional trader actions
- 25% retail actions
- 10% noise actions

With traffic actions roughly:
- submit 55%
- modify 30%
- cancel 15%

Then tune per profile rather than globally.
