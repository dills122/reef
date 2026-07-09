# Bot Arena Simulation Soak Checklist

## Goal

Prove Reef can run a bot arena simulation with a healthy market throughout and
a successful simulation data export.

The near-term hardening path is local-first: use local 3-5 minute live runs to
shake out arena behavior, health metrics, projection/readback gaps, and artifact
capture. Keep the 15 minute run as a later promotion gate because `15m` is the
target length for the quickest real game simulation.

The path should ramp in this order:

1. Local dry-run smoke with current fixture ticks.
2. Local live smoke against the platform stack.
3. Local 3-5 minute shakedown with test bots and persisted artifacts.
4. Local 3-5 minute multi-instrument liquidity/provider tuning run.
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
- [x] Local `3m` dry-run plan completes and writes repo-local artifacts:
  - artifact root: `artifacts/arena/local-dry-3m/`
  - `180` bot ticks
  - `146` venue command drafts
  - `0` freezes
- [x] Local `3m` live venue-path run writes repo-local artifacts:
  - artifact root: `artifacts/arena/local-live-3m/`
  - `180` bot ticks
  - `146` submitted venue commands
  - AAPL market-health summary passed
  - `1` freeze remains, caused by refreshing-bot command timeouts
- [x] Clean local `3m` live gate passes on rebuilt runtime:
  - artifact root: `artifacts/arena/local-live-3m-clean/`
  - `180` bot ticks
  - `146` submitted venue commands
  - `0` timed-out commands
  - `0` freezes
  - projection drain required and passed
  - arena persistence/readback and leaderboard entry passed
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
- [x] Local 3-5 minute live run completes with full arena health evidence.
- [ ] Local 3-5 minute shared-time multi-instrument run covers 5-10 tickers with at least
      one liquidity provider per active ticker and 10+ non-house/non-NPC bots.
  - mode and catalog coverage exist for `5` tickers and `11` non-house/non-NPC
    score-eligible bots
  - superseded sliced-time local live `3m` stream-ack run passed terminal
    command waits, multi-instrument market-health gates, arena persistence, and
    leaderboard readback
  - shared-time local live smoke passed for `3s`
  - shared-time local live `180s` simulated gate passed unpaced with full
    terminal command waits, projection drain, persistence, rendered report, and
    export
  - real paced `3-5m` wall-clock gate remains open because the first paced
    attempt showed same-offset bot/session execution and status polling still
    stretch wall time beyond the target window
- [ ] DigitalOcean 15 minute run completes with full arena health evidence.

## Local-First Hardening Plan

Use local runs to prove behavior before spending remote-cycle time:

1. `3m` live local health run using normal `/api/v1` venue command paths,
   accepted-mode command waits, projection drain, rendered report, and export.
2. `3-5m` local tuning run with explicit health scope for actively quoted
   instruments.
3. `3-5m` local multi-instrument run with `5-10` active tickers. Each ticker
   must have at least one liquidity provider; one provider may cover multiple
   tickers if quote uptime and spread/depth gates pass per ticker.
4. Add `10+` non-house/non-NPC bots after liquidity is stable. Keep intentionally
   abusive/failing bots in a separate negative-test mode so main health runs are
   not dominated by expected policy failures.
5. Promote to the `15m` gate only after local artifact bundles include command
   accounting, projection/readback health, market health, bot summaries,
   enforcement events, rendered HTML, and export JSON.

Local hardening runs require the local stack to be started with both
`ORDER_LIFECYCLE_PROJECTOR_ENABLED=true` and
`MARKET_DATA_PROJECTOR_ENABLED=true`; the runner refuses to grade a hardening
artifact without those read models enabled. Current local hardening gates also
include controlled fill pressure through `healthTargets.minTotalFills` and
`healthTargets.minFillsPerInstrument`, using venue order-fill readback as the
source of execution evidence.

The first multi-instrument mode should declare:

- active ticker set
- primary health ticker set
- liquidity-provider assignment per ticker
- minimum quote uptime per ticker
- spread/depth thresholds per ticker or liquidity tier
- bot role: house LP, NPC flow, competitor/test bot
- per-role action, cancel/replace, and open-order budgets

House liquidity providers are venue-stabilization actors, not contestant bots.
They may run on real-time or event-responsive loops instead of the contestant
tick cadence, provided they remain deterministic under replay, bounded by
explicit rate/cancel/open-order budgets, and fully visible in command,
execution, and audit artifacts.

## Latest Local Live Hardening

Run: `local-live-3m-clean`

- [x] Clean local Docker stack started from reset volumes.
- [x] Rebuilt `reef-platform-runtime` image used.
- [x] Order-lifecycle and market-data projectors enabled.
- [x] Local internal admin access enabled for host-runner persistence.
- [x] Arena report completed.
- [x] Arena export completed.
- [x] Rendered report HTML completed.
- [x] Projection drain required and passed.
- [x] Arena admin persistence, run-result readback, enforcement readback, and
      leaderboard readback passed.
- [x] Market-health summary passed for actively quoted AAPL.
- [x] Full run completed without freezes.

Evidence:

- artifact root: `artifacts/arena/local-live-3m-clean/`
- `180` ticks
- `146` submitted commands
- route mix: `110` submit, `36` cancel
- `0` timed-out commands
- `0` failed/rejected commands
- `0` freezes
- projection availability: `runtime-normalized-venue-outcomes` lag `0`,
  `market-data-top-of-book` lag `0`
- AAPL health: `30` post-warmup samples, `100%` top-of-book availability,
  `100%` depth availability, median/p95 quoted spread
  `24.906600249066003` bps, zero crossed/locked/empty-book samples
- arena persistence: `18` operations, leaderboard entry present

Current conclusion: the local single-instrument `3m` gate is now green. The next
local arena milestone can broaden to the multi-instrument liquidity/provider run
with `5-10` tickers and `10+` non-house/non-NPC bots.

## Superseded Local Multi-Instrument Plumbing Gate

Run: `arena-local-tick-1783612157169`

- [x] Stream-ack stack restored with arena admin and internal local diagnostics
      enabled.
- [x] Dedicated mode covered `5` active symbols: `AAPL`, `MSFT`, `NVDA`,
      `TSLA`, `AMZN`.
- [x] Catalog covered at least one house liquidity provider per active ticker.
- [x] Catalog covered `11` score-eligible non-house/non-NPC bots:
      `custom-technical-indicator` plus `10` configurable passive bots.
- [x] Terminal command status polling used participant read-scope headers.
- [x] Compact stream-worker canonical command results are visible through
      `/api/v1/commands/{commandId}` as terminal command status.
- [x] Full local `3m` stream-ack live run completed and persisted arena results:
      artifact root `artifacts/arena/local-live-3m-multi-terminal/`.
- [x] Multi-instrument market-health passed.

Evidence:

- `18` bots
- `180` bot ticks
- `130` submitted venue commands
- route mix: `120` submit, `10` cancel
- `130` terminal `COMPLETED` statuses
- `0` timed-out commands
- `0` failed/rejected commands
- `0` freezes
- health summary: `pass`
  - top-of-book availability: `100%`
  - depth availability: `100%`
  - median quoted spread: `20` bps
  - p95 quoted spread: `24.91` bps
  - crossed/locked/empty book samples: `0`
- run-scoped canonical command results:
  - `AAPL`: `42` accepted
  - `MSFT`: `22` accepted
  - `NVDA`: `22` accepted
  - `TSLA`: `22` accepted
  - `AMZN`: `22` accepted
- arena persistence/readback passed; leaderboard entry present.
- This run is superseded as a duration gate because the runner divided the
  configured `180s` by `18` selected bots. It remains useful plumbing evidence
  for multi-symbol command/readback/persistence behavior, but not as proof that
  all bots traded concurrently for a full `3m` market window.

Findings:

- The earlier non-AAPL gap was not matching-engine materialization failure.
  The runner seeded `participant-undefined`/`actor-undefined` before expanding
  catalog identities, causing non-AAPL commands to reject authorization.
- The terminal wait failure after that was status readback plumbing:
  stream-worker compact canonical results were persisted in
  `runtime.canonical_command_results`, but `/api/v1/commands/{commandId}` only
  checked venue-batch canonical outcomes and event-batch references.
- The final timeout was runner-side read-scope: status polls omitted
  `X-Participant-Id`, so the API correctly denied command status for
  participant-scoped commands.

## Latest Responsive-House Shared-Time Smoke

Run artifact: `/tmp/reef-arena-house-responsive-live-smoke.json`

- [x] Runner uses `shared-arena-time` scheduling.
- [x] Duration is no longer divided by selected bot count.
- [x] All `18` bots started before tick execution.
- [x] House liquidity providers used `house_responsive` scheduling with
      `250ms` deterministic wake intervals.
- [x] Contestants/NPCs used normal `1000ms` tick scheduling.
- [x] Market-health snapshots were sampled once per global arena tick, not once
      per bot tick.
- [x] Short live stream-ack smoke completed against the local stack.

Evidence:

- configured duration: `3s`
- contestant/NPC tick interval: `1000ms`
- house LP wake interval: `250ms`
- selected bots: `18`
- house LPs: `6` bots, `72` wakes, `34` submitted commands
- contestants: `11` bots, `33` ticks, `30` submitted commands
- NPCs: `1` bot, `3` ticks, `0` submitted commands
- total bot ticks/wakes: `108`
- submitted venue commands: `64`
- route mix: `52` submit, `12` cancel
- terminal statuses: `64` `COMPLETED`
- `0` timed-out commands
- `0` failed/rejected commands
- `0` house operational pauses
- `0` freezes
- health summary: `pass`
  - top-of-book availability: `100%`
  - depth availability: `100%`
  - crossed/locked/empty book samples: `0`

Next proof target:

- Re-run `artifacts/arena/local-live-3m-multi-terminal/` under
  responsive-house shared-time semantics. With `6` house LPs at `250ms`, `11`
  contestants at `1000ms`, and `1` NPC at `1000ms`, expected total work over
  `180s` is `4320` house wakes plus `2160` tick-bot ticks, or `6480` total
  bot ticks/wakes.

## Latest Responsive-House Shared-Time Gate

Run: `arena-local-tick-1783616217536`

- [x] Shared simulated market duration was `180s`.
- [x] Mode covered `5` active symbols: `AAPL`, `MSFT`, `NVDA`, `TSLA`,
      `AMZN`.
- [x] Catalog covered `6` house LPs, at least one LP per active symbol.
- [x] Catalog covered `11` score-eligible non-house/non-NPC bots.
- [x] House LPs used `250ms` responsive wakes.
- [x] Contestants/NPCs used `1000ms` ticks.
- [x] Same-offset bot sessions execute concurrently in the runner.
- [x] Terminal command status polling passed for every submitted command.
- [x] Projection drain required and passed.
- [x] Arena persistence/readback passed.
- [x] Rendered report and export JSON written.

Evidence:

- artifact root: `artifacts/arena/local-live-3m-responsive-house-unpaced/`
- wall elapsed: `106.046s`
- total ticks/wakes: `6480`
- house LPs: `6` bots, `4320` wakes, `12` submitted commands
- contestants: `11` bots, `1980` ticks, `1800` submitted commands
- NPCs: `1` bot, `180` ticks, `0` submitted commands
- total submitted venue commands: `1812`
- route mix: `1812` submit, `0` cancel
- terminal statuses: `1812` `COMPLETED`
- `0` timed-out commands
- `0` failed/rejected commands
- `0` house operational pauses
- `0` freezes
- projection drained: `true`
- health summary: `pass`
  - top-of-book availability: `100%`
  - depth availability: `100%`
  - median quoted spread: `20` bps
  - p95 quoted spread: `24.906600249066003` bps
  - crossed/locked/empty book samples: `0`
- command status timing:
  - average intake elapsed: `9.3139562075056ms`
  - average status elapsed: `293.0566982897352ms`

Findings:

- Responsive-house no-op behavior is working: LPs wake frequently but submit
  only initial quotes while healthy.
- This is useful shared-time simulation proof, but not yet real paced wall-clock
  proof. The first `--pace-ticks` attempt reached only about `40s` simulated
  after about `150s` wall time.
- Runner concurrency improved same-offset execution, but terminal status polling
  and single worker/session execution still make real paced runs slower than
  target.
- Current flow has no cancel pressure because quotes stay healthy and no fills
  deplete house orders. Next gate needs taker/fill pressure or event-responsive
  stale/fill triggers to exercise cancel/replace plumbing under load.

Next proof target:

- Add controlled taker/fill pressure and open-order/cancel-budget enforcement,
  then run a real paced `3-5m` wall-clock gate.

## Latest Fill-Pressure Local Gate

Run artifact: `/tmp/reef-arena-local-hardening-5m-fill-pressure.json`

- [x] Real local hardening duration was `300s`.
- [x] Mode covered `5` active symbols: `AAPL`, `MSFT`, `NVDA`, `TSLA`,
      `AMZN`.
- [x] Order-lifecycle and market-data projectors were enabled.
- [x] Projection drain required and passed.
- [x] Market-health summary passed.
- [x] Controlled taker pressure produced fills on every active symbol.
- [x] Cancel pressure remained present through house LP quote refresh.

Evidence:

- total submitted venue commands: `465`
- route mix: `248` submit, `217` cancel
- terminal statuses: `465` `COMPLETED`
- `0` timed-out commands
- `0` failed/rejected commands
- `0` freezes
- top-of-book availability: `100%`
- depth availability: `100%`
- median/p95 quoted spread: `20` bps
- total fills: `10`
- filled quantity: `10`
- average fill price: `239.76`
- per-instrument fills: `2` each for `AAPL`, `MSFT`, `NVDA`, `TSLA`, and
  `AMZN`

Finding: the simulator now has enough controlled fill pressure to prove the
matching/fill/readback path during short local hardening. The next quality slice
should turn these execution facts into better scoring and market-quality metrics
instead of treating all successfully active bots as roughly equal.

## Previous Local Multi-Instrument Attempt

Run: `arena-local-tick-1783610020341`

- [x] Dedicated mode added: `packages/scenario-definitions/arena/equity-multi-local.v1.json`.
- [x] Active ticker set covers `5` symbols: `AAPL`, `MSFT`, `NVDA`, `TSLA`, `AMZN`.
- [x] Catalog includes at least one house liquidity provider per active ticker.
- [x] Catalog includes `11` score-eligible non-house/non-NPC bots:
      `custom-technical-indicator` plus `10` configurable passive bots.
- [x] Runner supports multiple catalog entries sharing the same implementation
      while retaining distinct bot, actor, participant, and account identities.
- [x] Short live persistence smoke passed with duplicated implementation entries:
      artifact root `artifacts/arena/local-live-multi-persist-smoke/`.
- [x] Full local `3m` live attempt completed and persisted arena results:
      artifact root `artifacts/arena/local-live-3m-multi/`.
- [x] Rendered report HTML and export JSON were generated.
- [ ] Multi-instrument market-health passed.

Evidence:

- `18` bots
- `180` bot ticks
- `130` submitted venue commands
- route mix: `120` submit, `10` cancel
- `0` timed-out commands
- `0` failed/rejected commands at intake
- `0` freezes
- arena persistence: `57` operations, leaderboard entry present
- health summary: `warn`
  - top-of-book availability: `20%`
  - depth availability: `20%`
  - empty-book samples: `720` of `900`
  - only `AAPL` had final market-data readback; `MSFT`, `NVDA`, `TSLA`, and
    `AMZN` returned `404`

Findings:

- The multi-instrument runner/catalog shape is now present and useful for local
  testing.
- The first live multi-instrument run is not a pass. It proved intake,
  duplicated bot identity, cancel command submission, arena persistence, report
  rendering, and export generation, but not non-AAPL market materialization.
- The report status logic now treats failed health checks as
  `completed_with_warnings` instead of plain `completed`, so future attempts do
  not look green when top-of-book/depth gates fail.
- The next fix is to trace non-AAPL stream commands from worker completion into
  canonical outcomes/read-model projection, then rerun this same mode with
  terminal command waits or another run-scoped execution proof.

Run: `arena-local-tick-1783566974753`

- [x] Local stream-ack stack started with order-lifecycle and market-data
      projectors enabled.
- [x] API health passed from host with elevated local-network access.
- [x] Arena report completed.
- [x] Arena export completed.
- [x] Rendered report HTML completed.
- [x] Market-health summary passed for actively quoted AAPL.
- [ ] Full run passed without freezes.
- [ ] Projection drain passed on the existing local databases.
- [ ] Arena admin persistence worked from the host runner.

Evidence:

- artifact root: `artifacts/arena/local-live-3m/`
- `180` ticks
- `146` submitted commands
- `36` timed-out commands
- `1` freeze: `builtin-mm-refreshing`, reason `timedOutCommands 36 > 0`
- AAPL health: `30` post-warmup samples, `100%` top-of-book availability,
  `100%` depth availability, median/p95 quoted spread
  `24.906600249066003` bps, zero crossed/locked/empty-book samples
- own-order readbacks returned `200` for all five bot participants

Findings:

- The local venue path is usable for artifact-producing 3 minute arena runs.
- The first local live run used a stale platform-runtime Docker image because
  the stack was started with `DEV_COMPOSE_BUILD=0` to avoid a Docker metadata
  stall. That stale image exposed the old cancel/refresh command-status gap:
  the refreshing bot submitted `72` commands and timed out `36` cancel commands.
- The global projection-drain gate is not meaningful on the current reused local
  projection database: availability reported about `1.2M` lag from prior data.
  Use a clean local stack or run-scoped projection-drain accounting before
  treating this as a pass/fail gate.
- Host-to-container calls to `/internal/admin/arena/*` are rejected by the
  internal route guard (`internal HTTP route requires loopback access`), so local
  host-runner persistence needs a public/admin route, in-container execution, or
  a loopback-safe local operator path.

Follow-up verification after rebuilding `reef-platform-runtime` from the current
source:

- focused source tests passed:
  `StreamCommandWorkerTest.cancelWorkerPersistsCanonicalOutcomeBeforeAck` and
  `StreamCommandWorkerTest.modifyWorkerPersistsCanonicalOutcomeBeforeAck`
- artifact root: `artifacts/arena/local-live-1m-after-rebuild/`
- `60` ticks
- `50` submitted commands
- route mix: `38` submit, `12` cancel
- `0` timed-out commands
- `0` freezes
- AAPL market-health summary passed

Current conclusion from the stale-image run: cancel/modify stream-worker plumbing
is present in source and works in the rebuilt local runtime image. Clean-stack
verification above closes the projection-drain and host-runner arena persistence
gaps for local testing.

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
- [x] duration is shared simulated market time, not total bot work divided by
      selected bot count
- [x] contestant bots receive the full configured game window on the same
      timestamp schedule
- [x] house liquidity providers can be scheduled by deterministic responsive
      loops under separate rate and command budgets
- [ ] house liquidity providers can additionally wake from book/fill/spread
      events once those event feeds are available

Research anchor:

- ABIDES uses agent-based discrete-event simulation with many agents interacting
  with an exchange agent, configurable pairwise latencies, and a message-based
  design modeled after equity trading protocols:
  https://arxiv.org/abs/1904.12066
- Multi-asset agent-based market simulation research uses heterogeneous agents
  interacting with continuous double-auction matching across simultaneous
  assets, which is a closer fit than sequential per-bot time slices:
  https://arxiv.org/abs/2312.14903

### House Market Maker Behavior

House bots should start simple, then add inventory control:

- [ ] quote both sides around visible midpoint
- [ ] keep fixed minimum depth near touch
- [x] refresh stale quotes
- [ ] skew quotes when inventory grows
- [ ] widen spread during volatility or projection lag
- [ ] stop quoting only through explicit policy event
- [ ] replenish depleted quotes within a configured latency target
- [ ] satisfy quote uptime targets per assigned instrument
- [ ] emit house activity separately from score-eligible contestant results

Research anchor:

- Market-making literature frames market makers as liquidity providers earning
  spread while managing inventory and dynamically skewing quotes:
  https://arxiv.org/abs/1605.01862
- Designated market-maker behavior research argues quote obligations should
  consider liquidity replenishment speed, not only quote presence:
  https://arxiv.org/abs/1508.04348

## Implementation Checklist

### A. Shared-Time Arena Runner

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
- [x] Stop dividing configured duration by selected bot count.
- [x] Interleave bot sessions by global arena tick.
- [x] Collect market-health snapshots once per global arena tick.
- [ ] Re-run local `3m` multi-instrument gate under shared-time semantics.

### A2. House Liquidity Provider Runtime

- [x] Add mode/catalog field for `schedulingClass`: `contestant_tick`,
      `npc_tick`, or `house_responsive`.
- [x] Keep house LPs out of public scoring while preserving command evidence.
- [ ] Preserve house P&L and inventory diagnostics once those projections are
      available.
- [x] Add first per-house-LP budgets/config: wake interval, data latency,
      max commands/sec, cancel/replace/sec, open orders per side, inventory cap,
      quote TTL, and dark-space flags.
- [ ] Enforce cancel/replace/sec, open orders per side, inventory cap, quote TTL,
      and replenishment target.
- [ ] Add deterministic event-responsive triggers: book empty, quote stale,
      fill observed, spread breached, inventory threshold crossed.
- [x] Add report metrics split by scheduling class.
- [ ] Add report metrics for quote uptime, replenishment latency, cancel ratio,
      order-rate budget use, inventory range, and per-symbol coverage.
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
- [x] Add stale quote refresh interval.
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

### Local Dry Plan

- duration: `3m`
- tick interval: `1000ms`
- submit mode: `dry-run`
- purpose: prove run sizing, artifact shape, scoring, and report/export
  generation before starting the venue stack
- report artifacts: arena tick and hardening reports use a backpressure-aware
  streaming JSON writer so 3-15 minute runs do not materialize the full
  pretty-printed report string before writing it
- long-run parser/render/export path: use `--report-shape=compact` for
  15-minute report consumers. Full reports preserve per-tick detail for small
  debug runs, but 15-minute multi-instrument full JSON can exceed Node's
  single-file buffer limits.

Latest local dry plan:

```bash
bun scripts/dev/arena-local-tick-run.mjs \
  --duration-seconds=180 \
  --tick-interval-ms=1000 \
  --warmup-seconds=30 \
  --health-sample-interval-ms=1000 \
  --command-wait-mode=none \
  --submit-mode=dry-run \
  --out=artifacts/arena/local-dry-3m/arena-local-tick-run.json
```

Observed locally:

- `180` bot ticks
- `146` venue command drafts
- `0` submitted commands, as expected for dry-run mode
- `0` freezes
- report JSON, rendered HTML, and export JSON written under
  `artifacts/arena/local-dry-3m/`

### Local Live Hardening

- duration: `3-5m`
- tick interval: `1000ms`
- bots: house market makers plus one taker test bot
- purpose: command path, projection drain, readback validation, and artifact
  preservation

### Local Multi-Instrument Tuning

- duration: `3-5m`
- tick interval: `1000ms`
- warmup: `30s`
- instruments: `5-10` tickers
- liquidity: at least one provider per active ticker
- bots: house liquidity providers, NPC flow, and `10+` non-house/non-NPC bots
- purpose: prove arena realism before the 15 minute gate

### DO Shakedown

- duration: defer until local 3-5 minute gates are meaningful and stable
- purpose: remote orchestration, host sizing, and artifact transfer validation

### DO Tuning

- duration: optional after local multi-instrument tuning
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
