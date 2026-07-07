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

Add a thin orchestrator around the existing Bot SDK and simulator pieces.

Candidate home:

- `scripts/dev/arena-local-run.mjs` for the first operator-facing wrapper.
- Promote shared logic later only if the script becomes too large.

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

Phase 1 should keep the orchestrator boring. It should call existing scripts and
helpers before introducing a new long-running service.

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
- role: market-maker, npc, benchmark, competitor
- default config
- expected fixture
- action/risk limits
- visible-data policy version

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

1. Add the arena mode config and built-in bot catalog shape.
2. Add local custom bot fixtures.
3. Add `arena-local-run.mjs` around existing SDK/simulator paths.
4. Add scoring v0 and report shape.
5. Wire real run-result ingestion from the arena report.
6. Add `make dev-smoke-bot-arena-local`.
7. Add drift/replay checks once the first smoke report is stable.

## Follow-Up After Phase 1

After the local smoke is reliable, plan the next slices:

- stronger bot safety/risk policy
- fairness policy and disqualification rules
- OpenBao-backed config preflight
- hosted runner service shape
- analytics API/service boundary
- replay/debug artifact storage
- public bot submission hardening
