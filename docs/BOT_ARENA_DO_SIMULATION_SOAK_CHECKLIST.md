# Bot Arena DO Simulation Soak Checklist

## Goal

Prove Reef can run a DigitalOcean-hosted bot arena simulation for 15 minutes
with a healthy market throughout and a successful simulation data export.

The path should ramp in this order:

1. Local dry-run smoke with current fixture ticks.
2. Local live smoke against the platform stack.
3. DigitalOcean 3-5 minute shakedown with test bots.
4. DigitalOcean 3-5 minute house-bot tuning run.
5. DigitalOcean 15 minute success run with export.

## Current Baseline

- [x] Clean worktree branch created from fresh `origin/master`.
- [x] Existing arena mode selects 5 bots:
  - `builtin-mm-simple`
  - `builtin-mm-lifecycle-safe`
  - `builtin-mm-refreshing`
  - `builtin-npc-momentum`
  - `custom-technical-indicator`
- [x] Local dry-run smoke completes:
  - `15` bot ticks
  - `14` venue command drafts
  - `0` freezes
- [x] Local live-path JS test covers arena result persistence.
- [x] Simulation export code can ingest arena local tick reports.
- [x] Arena runner supports duration-based runs.
- [x] Duration-based runs treat duration as total arena plan time, distributed
      across selected bots.
- [x] Arena runner is wired into the DigitalOcean simulation harness.
- [x] First-pass healthy-market gates are computed from live venue readbacks.
- [x] DigitalOcean stack setup and `make dev-smoke` pass for arena profile.
- [x] DigitalOcean arena shakedown completes with an arena report.
- [x] DigitalOcean shakedown export includes arena health evidence.
- [ ] DigitalOcean 15 minute run completes with full arena health evidence.

## Latest DigitalOcean Shakedown

Run: `arena-do-3m-20260709T002100Z`

- [x] Worker provisioned on DigitalOcean.
- [x] Source checkout synced to worker.
- [x] Bun installed on worker when absent.
- [x] `bun install` completed.
- [x] `make dev-up-stream-ack` completed.
- [x] `make dev-smoke` passed.
- [ ] Arena report completed.
- [ ] Arena export completed.
- [x] Worker destroyed after failure.

Finding: the first duration implementation applied `--duration-seconds` per bot.
With 5 selected bots, a `3m` run planned roughly 15 minutes of bot ticks before
command/status waits. The runner now distributes duration across selected bots,
and the DO harness has a timeout guard for arena runs.

Run: `arena-do-3m-20260709T005500Z`

- [x] Worker provisioned on DigitalOcean.
- [x] `make dev-up-stream-ack` completed.
- [x] `make dev-smoke` passed.
- [ ] Arena report completed.
- [ ] Arena export completed.
- [x] Worker destroyed after failure.

Finding: fixed duration math worked locally, but live DO arena still timed out
before report generation. Dry `3m` plan emits `180` bot ticks and `146` venue
commands. Serial status waits at `15s` per command can exceed the shakedown
timeout before a report is written. Runner now submits command batches
concurrently per tick, emits progress logs, supports real tick pacing, and the
DO profile uses a shorter command-status wait.

Follow-up finding: dry `3m` plan includes `110` submit commands and `36` cancel
commands. Current `StreamCommandWorker` only handles `SubmitOrder`; unsupported
cancel/modify deliveries are terminated without terminal command evidence. Also,
the arena runner was waiting for `COMPLETED`/`FAILED`, while stream-ack status
can legitimately sit at `ACCEPTED`/`EVENT_PUBLISHED` until downstream canonical
projection catches up. Arena DO now uses accepted-mode command waiting and
emits route/status timing summary so the next run can separate intake speed,
status readback lag, and unsupported command gaps.

Run: `arena-do-3m-quiet-20260709T020000Z`

- [x] Worker provisioned on DigitalOcean.
- [x] Remote stage logs written to per-stage files instead of streaming full
      Docker/Gradle output into the local terminal.
- [x] `make dev-up-stream-ack` completed.
- [x] `make dev-smoke` passed.
- [x] Arena report completed.
- [x] Arena export completed.
- [x] Worker destroyed after run.

Finding: quiet stage logging fixed the local context/model-limit flood and the
arena run finished, but health remained `warn`: `146` commands submitted,
`0` timed out, projection drained, yet market-data snapshots returned `404` and
own-order reads returned `403`. Root cause was arena profile startup not enabling
the order-lifecycle and market-data projector env vars, plus missing
`X-Participant-Id` on participant-scoped order readbacks.

Run: `arena-do-3m-health-20260709T021000Z`

- [x] Worker provisioned on DigitalOcean.
- [x] `make dev-up-stream-ack` completed with order-lifecycle and market-data
      projectors enabled.
- [x] `make dev-smoke` passed.
- [x] Arena report completed.
- [x] Arena export completed.
- [x] Health verdict passed for the actively quoted instrument.
- [x] Own-order readbacks returned `200`.
- [x] Worker destroyed after run.

Evidence: `180` ticks, `146` submitted commands, `0` failed/rejected/timed-out
commands, zero command-accounting gap, and projection drained by run end. AAPL
had `30` post-warmup samples with `100%` top-of-book and depth availability,
median/p95 quoted spread `24.906600249066003` bps, and zero crossed, locked, or
empty-book samples. Own-order readback returned `200` for current and history:
`builtin-mm-simple` had `50/50`, `builtin-mm-lifecycle-safe` had `2/2`, and
`builtin-mm-refreshing` had `0/36`.

Remaining gap before calling the arena market broadly healthy: this bot mix only
quoted AAPL, so MSFT/NVDA/TSLA snapshot readbacks still returned `404`. Next
step is to broaden the bot/instrument mix or scope health gates to the
instrument set actively quoted by the selected arena mode.

## Success Definition

A successful 15 minute run means:

- [ ] Run happens on DigitalOcean.
- [ ] Run lasts `15m` excluding setup and teardown.
- [ ] Run uses normal `/api/v1` venue command paths.
- [ ] Test bots and house bots use the same Bot SDK execution surface.
- [ ] Circuit breakers and bot freezes may trigger, but expected policy outcomes
      are recorded and do not corrupt run accounting.
- [ ] Command accounting gap is zero:
  - draft commands
  - submitted commands
  - terminal commands
  - completed, rejected, failed, and timed-out commands
- [ ] Market-data and own-order projections drain by end of run.
- [ ] Data export is written locally and optionally posted to the admin export
      endpoint.
- [ ] Export artifact includes health verdict, raw metrics, bot summaries,
      enforcement events, and command accounting.

## Healthy Market Gates

These are first-pass gates. They should become mode config, not hard-coded script
constants.

- [ ] Two-sided top of book exists for primary instruments for at least `90%`
      of post-warmup samples.
- [ ] Visible bid and ask depth exists for primary instruments for at least
      `90%` of post-warmup samples.
- [ ] Median quoted spread stays within mode threshold, initially `10-25 bps`
      for equity sprint.
- [ ] P95 quoted spread stays within a wider threshold, initially `50 bps`.
- [ ] Crossed-book observations are zero.
- [ ] Locked-book observations are either zero or explicitly allowed by mode.
- [ ] Empty-book duration stays below configured threshold, initially `< 5s`
      cumulative per primary instrument after warmup.
- [ ] At least one trade completes during each non-warmup minute.
- [ ] Fill ratio is non-zero for taker/test flow.
- [ ] House market makers remain active after warmup.
- [ ] House market makers are not frozen by normal quoting behavior.
- [ ] Quote uptime per house market maker stays above mode threshold.
- [ ] Cancel/replace ratio stays below mode threshold or is explained by
      refreshing market-maker behavior.
- [ ] Realized spread can be sampled after executions at short and medium
      horizons, even if first implementation uses coarse intervals.

## Research-Guided Metric Set

External references point to three buckets Reef should measure.

### Execution Quality

Use execution quality style metrics for bot-originated orders:

- [ ] order counts, notional, and shares/contracts
- [ ] execution speed buckets
- [ ] average quoted spread
- [ ] average effective spread
- [ ] effective spread as percent of quoted spread
- [ ] realized spread at multiple horizons
- [ ] executed at quote, better than quote, and outside quote
- [ ] fill likelihood for non-marketable limits

Primary references:

- FINRA Rule 5310 highlights market character, price, volatility, liquidity,
  transaction size/type, quote accessibility, price improvement, execution
  likelihood, execution speed, execution size, and transaction costs:
  https://www.finra.org/rules-guidance/rulebooks/finra-rules/5310
- SEC Rule 605 reporting fields include covered order counts, cancellation and
  execution speed buckets, quoted spread, effective spread, realized spread, and
  price improvement/outside-quote measures:
  https://www.law.cornell.edu/cfr/text/17/242.605

### Limit Order Book Health

Use order book health metrics for market condition:

- [ ] top-of-book availability
- [ ] quoted spread in bps
- [ ] depth by side
- [ ] empty, locked, and crossed book samples
- [ ] order intensity by side
- [ ] recovery time after aggressive/taker flow

Research anchor:

- Limit order book resiliency work measures spread, depth, and order intensity
  around liquidity shocks, and treats spread/depth recovery after effective
  market orders as a market-quality signal:
  https://arxiv.org/abs/1602.00731

### Simulation Architecture

Use message-oriented, agent-based simulation shape:

- [ ] exchange/venue stays separate from agents
- [ ] agents interact through normal messages/API commands
- [ ] latency model is explicit and eventually configurable
- [ ] run config controls agent mix, venue session, instruments, and duration
- [ ] deterministic seeds govern bot/background behavior
- [ ] reports preserve event and command evidence for replay/debug

Research anchor:

- ABIDES uses agent-based discrete-event simulation with many agents interacting
  with an exchange agent, configurable pairwise latencies, and a message-based
  design modeled after equity trading protocols:
  https://arxiv.org/abs/1904.12066

### House Market Maker Behavior

House bots should start simple, then add inventory control:

- [ ] quote both sides around visible midpoint
- [ ] keep fixed minimum depth near touch
- [ ] refresh stale quotes
- [ ] skew quotes when inventory grows
- [ ] widen spread during volatility or projection lag
- [ ] stop quoting only through explicit policy event

Research anchor:

- Market-making literature frames market makers as liquidity providers earning
  spread while managing inventory and dynamically skewing quotes:
  https://arxiv.org/abs/1605.01862

## Implementation Checklist

### A. Duration-Based Arena Runner

- [x] Add mode fields:
  - `durationSeconds`
  - `tickIntervalMs`
  - `warmupSeconds`
  - `healthSampleIntervalMs`
- [x] Preserve current `ticks` field for tiny smoke tests.
- [x] Generate deterministic tick schedule from duration.
- [x] Stop using fixture length as the run limit for soak modes.
- [x] Add optional real tick pacing for remote soak runs.
- [x] Submit per-tick venue commands concurrently.
- [x] Emit arena progress logs during long runs.
- [x] Add accepted-mode command status wait for tick runner.
- [x] Add per-command intake/status timing telemetry.
- [x] Add report-level command status summary.
- [x] Add DigitalOcean arena process timeout guard for long runs.
- [ ] Add per-bot/session timeout budget inside arena runner.
- [ ] Add stream-ack worker support for cancel/modify command completion.
- [x] Add report fields:
  - `startedAt`
  - `completedAt`
  - `durationSeconds`
  - `warmupSeconds`
  - `tickIntervalMs`

### B. Live Market Readback

- [x] Sample `/api/v1/market-data/snapshots/{instrumentId}` during run.
- [ ] Sample own current/history orders for each bot during and after run.
- [ ] Read command status for every submitted command.
- [ ] Poll data availability until projections drain.
- [ ] Capture trade/read-model endpoint once available, or derive from command
      outcomes until a trade tape endpoint exists.

### C. Health Monitor

- [x] Add health monitor reporter module.
- [x] Compute top-of-book uptime.
- [x] Compute bid/ask depth availability.
- [x] Compute quoted spread bps.
- [x] Count empty, locked, crossed book samples.
- [ ] Compute per-minute trade count.
- [ ] Compute command accounting gap.
- [ ] Compute house bot quote uptime.
- [ ] Compute freeze/disqualification summary.
- [x] Emit health summary with pass/warn reasons.

### D. Bot Mix

- [ ] Keep 3 existing house market makers.
- [ ] Add passive NPC limit bot if not already present in active mode.
- [ ] Add noise/value flow bot.
- [ ] Add aggressive/taker test bot for fill and resiliency testing.
- [ ] Keep intentionally abusive bot only for negative mode.
- [ ] Add mode-level bot roles and score/public leaderboard flags.

### E. House Bot Tuning

- [ ] Make quote spread configurable per house bot.
- [ ] Make quote size configurable per house bot.
- [ ] Add inventory bounds.
- [ ] Add inventory-skew behavior.
- [ ] Add stale quote refresh interval.
- [ ] Add minimum quote uptime target.
- [ ] Add circuit-breaker allowances for house liquidity profiles.

### F. DigitalOcean Runner

- [x] Add `arena` profile to `scripts/deploy/simulation-run.mjs`.
- [x] Add `arena` path to `scripts/dev/do-benchmark-host.sh`.
- [x] Provision/start platform stack as current DO harness does.
- [x] Run arena script remotely with `submit-mode=live`.
- [x] Fetch arena report artifacts.
- [x] Run arena health checker locally after fetch.
- [x] Destroy worker unless retained by failure/debug flag.
- [ ] Keep `stream-ack` and `materializer` profiles unchanged.

### G. Export

- [x] Exporter handles `reef.arena.localTickRun.v0`.
- [x] Export includes health verdict and reasons.
- [x] Export includes health metric summary.
- [x] Export includes artifact manifest rooted to the run directory, not `/tmp`.
- [ ] Export post path is tested against admin analytics endpoint.
- [ ] Final DO run stores:
  - arena report JSON
  - export JSON
  - rendered report HTML
  - DO logs
  - docker compose state

### H. Gates

- [x] Local dry-run test.
- [x] Local live-path mock test.
- [ ] Local stack 1 minute live smoke.
- [ ] DO 3 minute arena shakedown with test bots.
- [ ] DO 3-5 minute house-bot tuning run.
- [ ] DO 15 minute final run.
- [ ] Export post/readback succeeds.

## Suggested First Run Profiles

### Local Smoke

- duration: current `ticks: 3`
- submit mode: `dry-run`
- purpose: runner/build validation

### Local Live Smoke

- duration: `60s`
- tick interval: `1000ms`
- bots: house market makers plus one taker test bot
- purpose: command path and readback validation

### DO Shakedown

- duration: `3m`
- tick interval: `1000ms`
- warmup: `30s`
- bots: house market makers, passive NPC, taker test bot
- purpose: health monitor and export validation

### DO Tuning

- duration: `5m`
- tick interval: `1000ms`
- warmup: `30s`
- bots: full house set plus controlled test bots
- purpose: tune spread, size, refresh, and inventory skew

### DO Success

- duration: `15m`
- tick interval: `1000ms`
- warmup: `60s`
- bots: tuned house set, NPC flow, 2-3 test bots
- purpose: final success gate

## Open Design Questions

- [ ] Should health gates fail the process immediately, or mark run failed after
      export is written?
- [ ] Which endpoint is canonical for trade tape during arena health checks?
- [ ] Should market health monitor be a bot, a runner module, or both?
- [ ] What first equity spread threshold is realistic for synthetic `AAPL` with
      current fixed-price fixture?
- [ ] Should house bots be exempt from public scoring but included in P&L and
      inventory diagnostics?
