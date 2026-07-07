# Bot Arena Phase 1 Readiness Plan

## Purpose

Define the first implementation slice needed to make Bot Arena locally real:
controlled market-maker bots, NPC trader bots, local custom test bots, basic
arena scoring, and a repeatable smoke gate that proves the whole loop uses the
normal Reef venue command path.

This is not the public-submission or hosted-tournament milestone. Phase 1 is the
operator-controlled/local proof that the arena model works before exposing it to
external bot authors.

## Current Foundations

Reef already has the main pieces Phase 1 should compose:

- Bot SDK authoring, fixture, hosted-runner, hosted-worker, live-read, and venue
  adapter scripts under `packages/bot-sdk/` and `scripts/dev/`.
- Example bots including simple/lifecycle-safe/refreshing market makers,
  multi-symbol strategy, and technical-indicator strategy bots.
- Go simulator persona/session config with actor attribution, strategy IDs,
  multi-instrument routing, deterministic seeds, and JSON reports.
- Platform arena registry, bot-version lifecycle states, run records, run result
  storage, leaderboard reads, and bot-version risk checks.
- Public `/api/v1` order command paths, command status, market-data reads,
  own-order reads, and data availability reporting.
- Local smoke targets for bot SDK live runs, hosted SES/container execution,
  arena bot-version risk, and arena run-result ingestion.

## Phase 1 Goal

Ship one local arena run path that can:

1. Seed a deterministic market session.
2. Register or select a small built-in bot set.
3. Run controlled market makers and NPC/background traders.
4. Run two or three local custom test bots through the hosted Bot SDK path.
5. Submit all accepted actions through `/api/v1`, never direct table mutation.
6. Wait for command completion, materialization, projection, and read visibility
   according to existing local gates.
7. Score the run with a minimal versioned scoring policy.
8. Persist run results and expose a deterministic leaderboard.
9. Produce a JSON report that can be used as the first arena regression artifact.

## Build Soon

### 1. Local Arena Orchestrator

Add the first arena orchestration path around the existing Bot SDK and simulator
pieces.

Candidate home:

- `services/simulator/internal/arena/config`
- `services/simulator/internal/arena/catalog`
- `services/simulator/internal/arena/orchestrator`
- `services/simulator/internal/arena/enforcement`
- `services/simulator/internal/arena/scoring`
- `services/simulator/internal/arena/report`
- `services/simulator/cmd/arena-local-run`
- `scripts/dev/arena-local-run.mjs` as the repository ergonomics wrapper

Responsibilities:

- read an arena mode/session config from `packages/scenario-definitions/`
- seed reference/auth data through existing guarded setup paths
- start controlled background flow through the simulator/load-tester or a small
  SDK-hosted bot loop
- build and run local custom bots through hosted worker execution
- map approved bot actions to venue command requests with the SDK venue adapter
- submit commands to `/api/v1`
- collect command status, order reads, trades, market data, and data availability
- write one arena run report
- optionally post the score payload to the existing arena run-result ingestion
  route

Phase 1 should not become a throwaway script. Keep the first runnable surface
small, but put the behavior in simulator-owned packages so the next slices can
reuse it for hosted runs, scheduled runs, and smoke gates.

### 2. Built-In Bot Catalog

Create a small versioned catalog of operator-controlled bots.

Initial catalog:

- `builtin-mm-simple`: posts a bid and offer around the visible midpoint
- `builtin-mm-lifecycle-safe`: checks own open orders before quoting
- `builtin-mm-refreshing`: cancels/requotes stale quotes
- `builtin-npc-passive-limit`: passive participant that rests away from mid
- `builtin-npc-momentum`: simple taker/follower using public bars or snapshots
- `builtin-npc-noise`: low-size randomized background flow with fixed seed

The catalog should capture:

- bot ID
- version
- source/artifact path
- role: market-maker, npc, benchmark, competitor, or monitor
- default config
- expected fixture
- action/risk limits
- visible-data policy version
- score eligibility
- public leaderboard eligibility

House bots are infrastructure actors, not public competitors. Their trading and
P&L should be retained for diagnostics and operator tuning, but they should not
appear on public leaderboards by default.

### 3. Local Custom Test Bots

Add local-only custom bot fixtures before opening the PR submission path.

Initial set:

- conservative valid bot
- aggressive but rule-abiding bot
- intentionally failing or abusive bot used to prove rejection/quarantine paths

These bots should use the same manifest shape intended for future submissions:

```text
bots/<bot-name>/bot.json
bots/<bot-name>/index.ts
```

Phase 1 does not need public branch protection or real OpenBao provisioning for
these bots. It does need the same SDK validation and hosted execution gates.

### 4. First Arena Mode Config

Add one deterministic mode under `packages/scenario-definitions/`.

Candidate:

```text
packages/scenario-definitions/arena/equity-sprint.v1.yaml
```

Minimum fields:

- mode ID and version
- scenario/run IDs and seed
- instrument universe
- session duration or tick count
- starting cash/equity assumptions
- controlled market-maker bot refs
- NPC/background bot refs
- competitor bot refs
- visible-data policy version
- action policy version
- risk policy version
- scoring policy version
- replay/drift expectations

Keep the first mode narrow: one or two equities, fixed seed, short run, final
equity headline score.

## Enforcement Model

### Run Freeze

Run freeze is the first automatic containment layer.

Behavior:

- scoped to one arena run
- stops the bot from receiving further ticks or submitting further actions
- records `frozen=true`, `disqualified=true`, `reasonCode`, policy version, run
  ID, bot ID, version ID, artifact hash, and timestamp
- does not automatically change the persistent bot-version lifecycle state

Initial freeze triggers:

- malformed hosted output
- repeated invalid actions
- repeated command rejects attributable to bot behavior
- timeout loop
- crash loop
- action count above policy
- open-order count above policy
- cancel/replace behavior above policy
- order price outside collar
- attempted instrument or order type outside mode policy

### Persistent Ban Or Quarantine

Persistent ban/quarantine is stored with arena-owned bot metadata, at the same
storage boundary as bot identity, bot versions, qualification reports, operator
decisions, and run records.

Behavior:

- applies to the exact `botId + versionId` by default
- blocks that version before venue command acceptance
- requires an admin/operator override or a newly submitted bot version
- records operator actor, reason, correlation ID, timestamp, and policy version
- must not be silently cleared by a later run

Phase 1 should prefer version-level ban/quarantine. Principal-level bans are
deferred until there is a clearer public-submission abuse model.

Persistent ban/quarantine triggers should be stricter than run freeze:

- sandbox/source policy bypass attempt
- denied API/global usage in hosted mode
- repeated severe run freezes across runs
- operator-confirmed malicious behavior
- artifact or manifest integrity mismatch

## Initial Circuit Breakers

The first breaker set should be deterministic and tick-aware. Most breakers
should reject or freeze in Phase 1; persistent ban should remain an explicit
operator or severe-policy action.

Initial defaults to tune:

- `maxActionsPerTick`: competitors `3-5`, house market makers `6-10`
- `maxOrdersPerRun`: hard cap per bot/run
- `maxOpenOrdersPerBotInstrument`: competitors `5-10`, house market makers
  `20-50`
- `maxInvalidActionsPerRun`: freeze at `3`
- `maxConsecutiveInvalidTicks`: freeze at `2`
- `maxTickTimeoutsPerRun`: freeze at `2`
- `maxConsecutiveCrashes`: freeze at `1-2`
- `maxRejectRateWindow`: freeze when more than half of the last `10` attempted
  actions are bot-caused rejects
- `maxCancelReplaceRatio`: warn first; freeze only on extreme behavior, with an
  initial candidate threshold around `10` cancels per fill for competitors
- `priceCollarBps`: reject orders too far from the mode reference price or
  visible midpoint
- `allowedInstruments`: strict mode-level allowlist
- `allowedOrderTypes`: start with `LIMIT` only

Every breaker decision should produce an arena enforcement event with:

- `runId`
- `botId`
- `versionId`
- `policyVersion`
- `decision`: allow, reject, freeze, quarantine, ban
- `reasonCode`
- observed counters/window values
- accepted command IDs if any action reached venue intake
- timestamp

## House Bot Risk Mode

House bots may need privileged liquidity behavior so the market can function
before enough competitor flow exists. Treat that as explicit mode config, not an
implicit bypass.

Example shape:

```yaml
riskProfile:
  type: house_liquidity
  assetLedgerMode: bypass
  scoreEligible: false
  publicLeaderboard: false
  maxActionsPerTick: 10
  maxOpenOrdersPerInstrument: 50
```

House bots may bypass cash/equity checks when configured with
`assetLedgerMode: bypass`.

House bots must still obey:

- hosted sandbox restrictions
- action schema validation
- allowed instrument and order-type policy
- price collars
- max open-order limits
- max actions per tick
- kill switch and run freeze
- market-wide safety breakers

This lets a house market maker lose money without weakening venue integrity.

## Healthy Market Target

The first arena health goal:

```text
A competitor bot can reliably find visible liquidity, trade against it, and
receive stable public market feedback without the book collapsing, crossing, or
becoming stale.
```

Initial measurable targets:

- top of book exists for primary instruments for at least `80-90%` of ticks
  after warmup
- median spread remains under the mode threshold, initially likely `10-25 bps`
  for an equity sprint
- at least a configured minimum number of trades completes during the run
- visible depth exists on both sides for most ticks
- crossed-book observations are zero
- empty-book duration stays below a configured threshold
- market-data and own-order projections drain by end of run
- accepted/completed/materialized command accounting gap is zero
- house market makers remain active and are not frozen by normal behavior

The first bot mix should include:

- at least one two-sided market maker
- one refreshing/requote market maker
- one passive background trader
- one aggressive/taker flow bot
- one value/noise flow bot
- one market health monitor actor
- deterministic reference price or regime model

The market health monitor is not a trading competitor. It records health signals
and can fail the smoke when the arena produced an unusable market.

### 5. Scoring V0

Implement a minimal scoring policy that can run from arena report facts.

Headline metric:

- final equity

Retained secondary metrics:

- realized/unrealized PnL where available
- trade count
- fill count
- order count
- reject count
- invalid action count
- timeout/crash count
- order-to-trade ratio
- disqualified flag and reason

The score format must include `modeId` and `scoringPolicyVersion` so old results
stay explainable after scoring changes.

### 6. Real Run-Result Ingestion

Extend the current result ingestion smoke so it can ingest the actual hosted bot
summary/report emitted by the local arena orchestrator.

The gate should prove:

- run record exists before results are posted
- bot versions referenced by the run are approved or active for the local policy
- per-bot result rows are persisted
- leaderboard ranks deterministically by scoring policy
- rejected/disqualified bots are represented explicitly

### 7. Local Arena Smoke Gate

Add:

```bash
make dev-smoke-bot-arena-local
```

Expected proof:

- stack is reachable
- arena registry/risk controls are enabled for the local profile
- built-in market makers submit through `/api/v1`
- NPC/background flow submits through `/api/v1`
- custom bots pass or fail SDK gates as expected
- command status reaches terminal states
- data availability report is captured
- final scoring report is written
- run results are persisted
- leaderboard returns deterministic ranking for the fixed seed

## Explicit Non-Goals For Phase 1

- public bot submissions
- real GitHub branch-protection enforcement
- real GitHub OIDC to OpenBao provisioning
- full analytics microservice
- production scheduler
- control-room UI
- multi-cloud run-plane deployment polish
- full account ledger, buying power, or settlement expansion
- broad tournament/season mechanics

## Runner Isolation Direction

Phase 1 should avoid one long-lived Docker container per bot. Twenty extra local
containers would add operational noise before the arena protocol is stable, and
the per-bot memory overhead is not needed for the first local proof.

The preferred near-term direction is a small pool of TypeScript-capable runners:

1. The arena orchestrator talks to runners through a stable bot-runtime protocol.
2. Each runner hosts multiple bots using Deno workers and SES compartments where
   practical.
3. The runner receives only arena snapshots, bot config, and deterministic seed
   material.
4. The runner returns proposed actions, bot logs, resource counters, and
   heartbeat/status reports.
5. The orchestrator owns venue transport, risk/enforcement, scoring, command
   status waiting, persistence, and lifecycle decisions.
6. Hosted/public phases can put the same runner protocol behind one stronger
   outer container, gVisor sandbox, or microVM boundary per runner group without
   rewriting arena orchestration.

The worker should not receive direct network, filesystem, environment,
subprocess, credential, or venue API capability. Deno's permission model is the
preferred local runtime guardrail for TypeScript, while SES compartments harden
the JavaScript object-capability surface inside the runner. These are useful
defense layers, but not a full replacement for an outer sandbox when bots become
public or hostile.

Initial grouping target:

- local dev: 1 runner process with 2-4 Deno workers
- local smoke: 2 runner processes with 2-4 workers each
- hosted internal: fixed runner container pool, each container hosting multiple
  workers and no direct network except the parent-owned control channel
- public/custom future: same protocol behind gVisor or equivalent outer sandbox

Each worker can host more than one low-rate bot, but the scheduler should treat
work as per-bot tick jobs rather than one endless loop per bot. This lets the
runner enforce per-bot time budgets, reject late tick results, recycle workers
after a fixed number of ticks/runs, and isolate overloaded bots from the rest of
the pool.

### Runner Throughput Guardrails

The arena should assume that bot runtime capacity is finite and explicitly
metered. A target like 500 proposed actions per second is feasible only if the
bot contract stays narrow and the runner does not perform venue I/O itself.

Initial guardrails:

- `maxTickWallTimeMs`: default 25-50 ms for competitor bots
- `maxTickCpuTimeMs`: tracked when the runtime exposes it
- `maxActionsPerTick`: still enforced by arena policy even if the runner is fast
- `maxQueuedTicksPerBot`: 1, with stale ticks dropped or marked late
- `maxRunnerQueueDepth`: freeze or shed lowest-priority custom bots before house
  market makers
- `maxResultBytesPerTick`: cap action/log output to prevent memory pressure
- `workerRecycleTicks`: recycle workers after N ticks or after any policy breach
- `runnerHeartbeatMs`: missing heartbeat freezes assigned bots for that run

The scheduler should reserve capacity for house market makers and health-monitor
bots. Custom bots should consume the remaining budget. If a runner saturates, the
correct first response is not to let latency compound; the arena should mark
late ticks, drop stale work, and freeze bots that repeatedly exceed their budget.

The first implementation slice for validating these assumptions is the isolated
runner benchmark in `docs/BOT_ARENA_RUNNER_BENCH.md`. That bench intentionally
measures Deno runner processes, Deno workers, synthetic bot ticks, RSS, tick
latency, and proposed-action throughput without involving the Reef venue API.
The same doc also tracks the follow-on hosted-bot bench that uses real Bot SDK
examples and hosted artifacts while still keeping venue transport out of scope.
The first pooled runner smoke now adds a JSON-line worker protocol with
`loadBot`, `runScenario`, `freezeBot`, `heartbeat`, and `shutdown`; that protocol
is the preferred handoff boundary for the arena orchestrator.

For a rough first sizing model, a bot at 500 actions per second with 5 actions
per tick requires 100 ticks per second. Ten bots at that rate means 1,000
bot-ticks per second before validation and command submission. That is plausible
only for simple strategies, batched snapshots, bounded output, and a runner pool
with backpressure. It should not be the Phase 1 default.

Phase 1 target should be much smaller and deterministic:

- 10-20 bots total
- 1-10 ticks per second per bot
- 3-10 actions per tick depending on bot class
- runner utilization and late-tick counts recorded in every arena report

### Parked Rust/WASM SDK Direction

The preferred long-term competitive bot lane is still Rust compiled to WASM,
with TinyGo as a likely second SDK once the ABI is stable. This is parked for
Phase 1 implementation but should shape the runner protocol now.

The important contract is language-neutral:

```text
init(config) -> bot_state
on_tick(snapshot, bot_state) -> tick_result
```

Rust/WASM remains attractive because it gives the arena cheap isolated artifacts,
bounded memory, deterministic host calls, no direct network or filesystem by
default, stable artifact hashing, and a clean ban/replay/audit model. The
TypeScript runner should therefore behave like one implementation of the same
bot ABI, not like the permanent platform contract.

## Readiness Criteria

Phase 1 is ready when a clean local stack can run:

```bash
make dev-smoke-bot-arena-local
```

and produce all of:

- arena run JSON report
- command status evidence
- read-surface/data-availability evidence
- per-bot score output
- persisted run-bot results
- leaderboard response
- deterministic repeat result for the same seed

## Recommended Work Order

1. Promote the pooled runner protocol into the arena package boundary.
2. Add arena package skeleton under `services/simulator/internal/arena`.
3. Add the arena mode config and built-in bot catalog shape.
4. Add local custom bot fixtures.
5. Add `cmd/arena-local-run` plus `scripts/dev/arena-local-run.mjs`.
6. Add enforcement policy and run-freeze report fields.
7. Add scoring v0 and report shape.
8. Wire real run-result ingestion from the arena report.
9. Add `make dev-smoke-bot-arena-local`.
10. Add drift/replay checks once the first smoke report is stable.

## Follow-Up After Phase 1

After the local smoke is reliable, plan the next slices:

- stronger bot safety/risk policy
- fairness policy and disqualification rules
- OpenBao-backed config preflight
- hosted runner service shape
- analytics API/service boundary
- replay/debug artifact storage
- public bot submission hardening
