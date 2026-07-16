# Bot Arena Simulation Tuning Sprint

## Purpose

This sprint turns the current Bot Arena direction into implementation-ready
work for repeatable test simulations, complete run diagnostics, leaderboard and
analytics wiring, bot data interfaces, starter bot coverage, and public
submission safety.

The sprint output is planning and tasking, not a claim that public bot
submissions are ready. The near-term goal is to make Bot Arena easy to run,
inspect, compare, and tune with built-in and local test bots. Public user bot
submission remains a separate readiness gate.

## Sprint Goal

Define the next Bot Arena milestone:

```text
Bot Arena can run repeatable local and hosted test simulations, collect a
complete diagnostic bundle for each run, compare results across runs, and expose
operator/public result surfaces clearly enough to tune bots and game modes before
public submissions are enabled.
```

## Definition Of Done

The sprint is done when the repository has:

- an active Bot Arena simulation-tuning milestone in `WORK_PLAN.md`
- an implementation-ready checklist for simulation tuning readiness
- a separate checklist for public user bot submission readiness
- task groups by subsystem with acceptance criteria
- a current-state gap matrix that marks each requirement as `exists`,
  `partial`, `missing`, or `blocked`
- an execution order for the next two or three build sprints

## Current Starting Point

Existing foundations:

- Bot SDK authoring, fixture, hosted-runner, hosted-worker, live-read, venue
  adapter, sandbox policy, and preflight checks under `packages/bot-sdk/`
- example bots for market making, multi-symbol strategy, technical indicators,
  passive strategy, and aggressive taker behavior
- local positive and negative persisted arena gates:
  `make dev-smoke-bot-arena-local-persist` and
  `make dev-smoke-bot-arena-local-negative`
- static report rendering and comparison index:
  `make dev-render-bot-arena-report` and
  `make dev-render-bot-arena-report-index`
- arena run result ingestion, enforcement-event persistence, leaderboard
  evidence, and bot-version risk checks
- hosted score-v1 correctness evidence with terminal command accounting,
  projection lag, fills, and public score mismatch checks
- arena admin app with public landing/game/leaderboard surfaces and a gated
  admin area
- GitHub and OpenBao provisioning direction for the public submission path

Current split:

- controlled local/hosted test simulations are close and should be hardened next
- public user submissions are still pre-production and require separate intake,
  review, identity, provisioning, and branch-protection gates

## Sprint Plan

### Day 1: Current-State Inventory

Tasks:

- inventory Bot Arena scripts, reports, gates, docs, SDK examples, and admin
  surfaces
- map existing local and hosted gates to the requirements below
- classify each requirement as `exists`, `partial`, `missing`, or `blocked`
- identify which gaps are code, docs, GitHub repository settings, hosted infra,
  or operator process

Deliverables:

- `Bot Arena Readiness Matrix`
- short answer to: what can run today, and what blocks external users?

Acceptance criteria:

- every requirement in this document is classified
- existing evidence is linked by command, report path, or doc reference
- public-submission blockers are not mixed with local simulation tuning blockers

### Day 2: Run And Diagnostic Spec

Tasks:

- define the canonical arena run envelope
- define the required diagnostic bundle for a `runId`
- define the first export contract for pulling or dumping all run data
- choose whether the first implementation is script-only, admin API, or both
- define retention and redaction rules for bot config, secrets, and debug data

Required run envelope:

- `runId`
- seed
- game mode and game-mode version
- scenario definition version
- instrument universe
- venue session IDs
- bot IDs, version IDs, artifact hashes, source hashes, and config hashes
- scoring, risk, data-policy, enforcement, and economic-policy versions
- runtime image/version and infra profile
- start/end timestamps
- run-plane and projection settings

Required diagnostic bundle:

- run metadata
- bot tick inputs, actions, and outcomes
- command submission requests and terminal `/api/v1/commands/{commandId}`
  statuses
- canonical command outcomes, orders, executions, trades, and runtime events
- public market-data snapshots, depth, trades, bars, and own-order reads used by
  bots
- bot resource usage, timeouts, crashes, sandbox violations, and hosted worker
  lifecycle events
- arena enforcement events: reject, freeze, disqualify, quarantine, and ban
- per-bot score breakdown, PnL/equity curve, exposure, drawdown, fill ratio,
  cancel/fill ratio, reject rate, and open-order counts
- per-instrument spread, depth, volume, volatility, and market-quality summaries
- projection lag, watermarks, drain status, replay/checksum result, and
  accepted/materialized/projected accounting
- operator HTML report plus raw JSON or NDJSON suitable for later analysis

Deliverables:

- `Arena Run Diagnostic Bundle Spec`
- tasks for exporter, report extension, diagnostic tests, and retention policy

Acceptance criteria:

- the bundle is keyed by `runId`
- no secret values are exported
- exported data distinguishes durable facts from projection-backed reads
- the bundle can explain why a bot won, lost, froze, or disqualified

### Day 3: Leaderboard, Analytics, And Scoring Spec

Tasks:

- separate public leaderboard data from admin analytics data
- define the public score-v1 leaderboard contract
- define admin-only analytics and diagnostic fields
- define run, season, and game-mode aggregation expectations
- define disqualification, eligibility, tie-breaker, and policy-version behavior
- define cross-run comparison output

Public leaderboard fields:

- rank
- bot display name
- game mode
- score and final equity
- eligibility and disqualification state
- run or season identifier
- scoring policy version
- completed-at timestamp

Admin analytics fields:

- score breakdown
- fills, rejects, cancellations, and cancel/fill ratio
- realized/unrealized PnL where available
- exposure and drawdown
- open-order pressure
- latency, timeout, crash, and sandbox metrics
- enforcement history
- market-quality contribution
- diagnostic bundle link or artifact reference

Deliverables:

- `Leaderboard And Analytics Contract`
- tasks for API, persistence, admin UI, public UI, report rendering, and
  cross-run comparison

Acceptance criteria:

- public and admin surfaces have different field sets by design
- disqualified bots are excluded or marked according to the scoring policy
- scoring policy versions make historical results stable after rule changes
- analytics writes remain downstream of venue command handling

### Day 4: Bot Interface And Starter Bot Catalog

Tasks:

- lock the initial bot-visible data policy
- verify SDK live-read surfaces match that policy
- define starter bot roles and missing persona coverage
- define local test bot manifest expectations
- define fixture and hosted-runner gates for each starter category

Initial bot-visible data policy:

- public top-of-book and bounded depth
- public trade tape and intraday bars
- participant-scoped own current/history orders
- explicit data availability and freshness metadata
- no direct runtime database access
- no raw `/internal/*` access
- no hidden participant identity or hidden order-book state
- no direct bot-created REST/gRPC clients to Reef services

Starter bot catalog:

- market makers: simple, lifecycle-safe, refreshing/requote
- liquidity providers: passive limit, tiered house liquidity, controlled
  inventory provider
- retail/investor personas: momentum, mean reversion, conservative limit,
  aggressive taker, DCA/rebalancer, noise
- benchmark competitors: baseline hold/cash, low-turnover, high-turnover,
  technical-indicator
- bad bots: too many orders, timeout/crash, invalid instrument, invalid order
  type, forbidden API/global, unapproved package

Deliverables:

- `Bot Data Interface Policy`
- `Starter Bot Catalog`
- tasks for missing bots, manifests, fixtures, hosted reports, and calibration
  scenarios

Acceptance criteria:

- every starter bot has a role, default config, risk limits, data policy, and
  leaderboard eligibility flag
- house bots and liquidity providers are retained for diagnostics but excluded
  from public competitor leaderboards by default
- bad bots prove deterministic reject/freeze/disqualification behavior

### Day 5: Safety, Intake, And Public Submission Readiness

Tasks:

- split simulation-readiness tasks from public-submission-readiness tasks
- define the required public bot submission lifecycle
- identify repository setting and hosted-infra prerequisites
- define branch-protection and human-review requirements
- define run freeze, persistent quarantine, ban, and recovery evidence

Required public submission gates:

- GitHub identity keyed by immutable GitHub user ID
- Reef Admin DB ownership and trust-state checks
- bot manifest validation
- SDK contract validation
- approved dependency/static sandbox scan
- hosted fixture run
- trusted OpenBao provisioning workflow through the hosted Admin API
- human reviewer approval
- post-merge registry sync with source/artifact hash capture
- bot-version risk checks before venue acceptance
- operator-controlled quarantine, ban, and archive flows

Deliverables:

- `Public Bot Submission Readiness Checklist`
- tasks for CI, branch protection, hosted Admin API, OpenBao, registry sync,
  risk checks, audit events, and operator runbook updates

Acceptance criteria:

- pull-request workflows that execute submitted bot code do not receive hosted
  Admin API secrets or provisioning privileges
- raw `/internal/*` routes remain private and are not used by public or CI
  callers
- a disabled, quarantined, banned, or archived bot version is rejected before
  durable venue command acceptance
- no public submission gate relies on self-reported bot metadata for identity
  or ownership

## Readiness Checklists

### Simulation Tuning Readiness

This gate is for controlled built-in and local test bots.

- local fast positive gate passes
- local negative/freeze gate passes
- paced local hardening run exists and reports market pressure
- hosted short gate passes with clean accounting
- hosted `15m` confidence run passes or is scheduled as soak evidence
- score-v1 is deterministic reset-to-reset
- diagnostic bundle exists for every promoted run
- report comparison can explain score, fill, reject, freeze, and market-quality
  deltas across runs
- bot-visible data availability is recorded in the run artifact
- projection lag and replay/checksum status are explicit

### Public Submission Readiness

This gate is for external users submitting bots to real arena games.

- GitHub OAuth/user model is live for the admin app
- user ownership, roles, trust state, and bot limits are enforced
- PR submission flow validates manifest, source policy, dependencies, and hosted
  fixture behavior
- trusted provisioning flow writes OpenBao slices without exposing privileged
  secrets to PR-controlled code
- branch protection requires all checks plus human review
- post-merge registry sync records durable bot/version metadata and artifact
  hashes
- participant config writes are mediated by the Admin API
- bot-version risk checks block non-active versions before venue acceptance
- operator quarantine, ban, archive, and recovery paths are audited and tested
- public docs explain that this is a deterministic simulated strategy arena, not
  real-money trading or investment advice

## Backlog Epics

### 1. Arena Run Diagnostic Bundle

Build the `runId` export/dump tooling and standard artifact format.

Acceptance criteria:

- export includes the required diagnostic bundle fields
- export redacts secrets and raw tokens
- export can run locally against a completed arena report
- tests fail if core sections are missing

### 2. Arena Report And Comparison

Extend static reports and comparison indexes into a tuning tool.

Acceptance criteria:

- one report explains a single run end to end
- comparison explains score, fill, reject, freeze, market-quality, and lag deltas
- reports link or reference raw bundle artifacts

### 3. Leaderboard And Analytics Contract

Wire public leaderboard and admin analytics around explicit schemas.

Acceptance criteria:

- public leaderboard exposes only public fields
- admin analytics exposes diagnostic and conduct fields
- disqualified bots are handled consistently
- score policy versions are included in all persisted rows and responses

### 4. Bot Data Interface Hardening

Lock SDK-visible data policy and prove no hidden/internal leakage.

Acceptance criteria:

- SDK live reads map only to approved public or participant-scoped endpoints
- reports include `dataAvailability`
- tests cover forbidden internal reads and hidden identity leakage

### 5. Starter Bot And Persona Catalog

Add or formalize market makers, liquidity providers, investor personas,
benchmarks, and bad-bot fixtures.

Acceptance criteria:

- every catalog entry has role, config, risk limits, and eligibility
- starter bots have fixture and hosted-runner coverage
- calibration matrix can show whether a mode has enough liquidity and price
  movement to tune against

### 6. Safety And Enforcement

Complete deterministic reject, freeze, disqualify, quarantine, ban, and archive
evidence paths.

Acceptance criteria:

- run-level enforcement events are persisted and appear in reports
- persistent bot-version lifecycle controls block venue acceptance
- operator decisions record actor, reason, correlation ID, policy version, and
  timestamp

### 7. Public Submission Gate

Finish PR, GitHub identity, OpenBao, registry, branch-protection, and operator
review flow.

Acceptance criteria:

- untrusted PR code never receives privileged hosted credentials
- provisioning and registry sync use hosted Admin API gateway routes
- branch protection requires automated gates and human review
- failed submissions do not create partial durable bot/version state

### 8. Hosted Simulation Promotion Gates

Standardize local fast, local hardening, hosted short, and hosted confidence
gates.

Acceptance criteria:

- each gate has a named command, required environment, and artifact path
- promoted runs require command accounting gap `0`
- promoted runs report projection lag, replay/checksum status, and score
  mismatch counts

## Recommended Build Order

1. Arena Run Diagnostic Bundle
2. Arena Report And Comparison
3. Starter Bot And Persona Catalog
4. Leaderboard And Analytics Contract
5. Bot Data Interface Hardening
6. Safety And Enforcement
7. Hosted Simulation Promotion Gates
8. Public Submission Gate

The diagnostic bundle comes first because tuning, safety review, leaderboard
trust, and bot behavior debugging all depend on being able to inspect one run
completely.

## Non-Goals

- no public user submissions as part of this planning sprint
- no real-money trading or investment-advice posture
- no new matching-engine game mechanics
- no direct bot access to runtime databases, internal HTTP, matching-engine
  internals, or service credentials
- no UI/control-room freshness claims unless projection/read-model gates prove
  them separately from venue-core materialization
