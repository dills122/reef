# Simulator Persona + Session Config Plan

## Goal

Move from percentage-only load profiles to explicit, configurable simulation sessions that define:

1. market universe and baseline conditions
2. personas/actors and strategy behavior
3. execution schedule and run controls
4. deterministic seeds for replayability

## Current State

`services/simulator/cmd/load-tester` currently supports:

- profile mix percentages (`market-maker`, `institutional`, `retail`, `noise`)
- global quantity/price ranges
- mode-based behavior (`chaos`, `strict-lifecycle`, `capacity-baseline`)

It does not yet support:

- session config files
- named personas with distinct strategy parameters
- per-symbol market conditions
- multi-instrument universes

## Target Model

Introduce a `--session-config` input (YAML or JSON) that drives the run.

Top-level sections:

1. `session`: run metadata and deterministic seed
2. `runtime`: base-url, duration, workers, request-rate, timeout
3. `market`: tradable equities + baseline price/liquidity context
4. `actors`: persona definitions and strategy parameters
5. `mix`: actor allocation and behavior weights
6. `faults` (optional): deterministic fault injection rules

## Actor Classes (v1)

1. `market_maker`
- posts two-sided flow around a reference price
- configurable quote width, requote frequency, max inventory bias

2. `algo`
- simple starter strategies:
  - `momentum_follow`
  - `undercut_spread`
- configurable lookback window, aggression, max participation rate

3. `institutional`
- personas:
  - `mutual_fund`
  - `hedge_fund`
  - `day_trader`
- configurable slice size, urgency, modify/cancel cadence

4. `retail`
- personas:
  - `dip_buyer`
  - `breakout_chaser`
  - `passive_limit`
- configurable order-size distribution and side bias

## Market Universe Model (v1)

For each symbol:

1. `symbol`
2. `instrumentId`
3. `startingPriceNanos`
4. `avgDailyVolume`
5. `sharesOutstanding`
6. `marketCap`
7. `volatilityBps` (session-level baseline)
8. `spreadBps`

Notes:
- real-market seeded snapshots can be ingested later; v1 should work from static fixtures checked into repo
- deterministic baseline fixtures are preferred for reproducible test results

## Phased Delivery

1. Phase 1: Config ingestion + validation
- add `--session-config`
- support config + existing flags (flags override config)
- validate totals, ranges, and required references

2. Phase 2: Persona runner
- instantiate actor workers from config
- map each actor to strategy module and state
- preserve current summary/report format

3. Phase 3: Multi-instrument + market conditions
- randomize/route order flow across symbol universe
- apply per-symbol spread/volatility parameters

4. Phase 4: Fault scenarios + replay packs
- scripted fault injection per `scenarioRunId`
- catalog session files in `packages/scenario-definitions`

## Implementation Notes

1. Keep load-tester as the orchestrator; do not split into separate binaries yet.
2. Keep strategy modules package-local (`services/simulator/internal/strategy/*`) first.
3. Ensure every emitted command includes:
- `scenarioRunId`
- `seed`
- `actorId`
- `actorType`
- `strategyId`
4. Add config schema tests before strategy complexity.

## Exit Criteria for This Workstream

1. A session file can fully define a run without long CLI flags.
2. At least 6 named personas are supported across market-maker/algo/institutional/retail.
3. At least 5 equities can be configured in one session.
4. Run outputs include actor-level attribution in summaries.

## Backlog and Execution Policy

- [`docs/SIMULATOR_UPGRADE_BACKLOG.md`](./SIMULATOR_UPGRADE_BACKLOG.md)
