# Bot Arena Plan

## Purpose

This document captures the current direction for adding a tournament-style trading bot arena to Reef.

The goal is to let users build strategy bots that compete in deterministic simulated markets while preserving Reef's core architecture:

- simulation actors use the same public command paths as manual users
- the matching engine remains venue-focused and unaware of game mechanics
- bot code is treated as untrusted and runs behind a narrow sandbox boundary
- tournament runs are replayable, inspectable, and auditable
- game modes are modular configurations rather than hard-coded venue behavior

This is an active early-build planning document, not a final architecture decision. Stable shipped parts should move into `docs/DECISIONS.md`, steering docs, and versioned contracts when they become durable platform commitments.

## Current Review Status

Status: accepted as an active early-build workstream for local proof; hosted/public submission design remains pre-production.

Current checkpoint: the Bot SDK runtime bridge, arena control-plane source facts, operator approval controls, local positive/negative persisted gates, static operator reports, and shared-time multi-instrument simulation proof exist. Bot-originated order commands use the normal venue command boundary with bot client identity, run metadata, stream-ack intake, command-log capture, canonical outcome persistence, projection, and replay-idempotency coverage. Local hardening now has an unpaced `5m` compact gate over `5` symbols and `23` bots with projector drain, per-instrument market-quality summaries, controlled house liquidity, passive contestant order hygiene, and cancel/replace coverage. The next local proof is a paced `3-5m` wall-clock gate with meaningful fill/price-movement pressure before the `15m` DigitalOcean promotion run.

Local hardening caveat: until market-data snapshot/depth reads are venue-session-scoped, repeated hardening runs over the same instruments can inherit previous local order-book state. Use a clean `make dev-reset` stack with `ORDER_LIFECYCLE_PROJECTOR_ENABLED=true` and `MARKET_DATA_PROJECTOR_ENABLED=true` before treating market-quality results as comparable.

This section captures follow-up review material from handwritten planning notes. It is not an implementation commitment. Before this area moves into accepted architecture, review it against the current runtime, simulator, API-boundary, and data-platform work so the arena design does not bypass Reef's deterministic command, replay, audit, and storage rules.

Second-review scope:

- onboarding, intake, and permission flow for human users, bots, first-level funding checks, KYC-style gates where applicable, and feature access
- bot creation flow, including fork/create, SDK or tool configuration, owner permissions, secret partition activation, analyzer/simulation validation, and approval before live arena participation
- bot runtime configuration, including private per-bot config, allowed runtime changes between runs, dependency policy, and isolation from other bots
- external market-data access, including OpenBB or other providers behind an adapter boundary rather than direct domain coupling
- order and user API behavior, including how orders are placed, validated, backed up, routed to matching, and exposed through order history
- matching internals, including queue feed, per-engine consumer ownership, deterministic lane assignment, settlement after fulfilled matches, and cleanup after settlement
- simulation setup, including startup snapshots, liquidity providers, built-in bots, admin or monitor actors, and controlled bot shutdown
- bot API shape, including whether it is TypeScript-based only, command-based, state/test analyzed before merge, and lifecycle-managed through base classes or pluggable methods
- game execution model, including daily, weekly, and all-time boards; scheduled runners; persistent result storage; and approved-player result aggregation
- leaderboard construction, including ending assets, number of trades, best upside per trade, final equity, realized/unrealized PnL, drawdown, consistency, tie-breakers, and disqualification conditions

Specific items to resolve during the second review:

- define the canonical event model for bot registration, bot configuration, bot approval, simulation start, market snapshot capture, order acceptance/rejection, execution, settlement, game completion, and leaderboard publication
- define the run reproducibility envelope: seed, market snapshot ID, bot version, commit or artifact hash, config hash, scenario definition version, input event log, and output events
- define bot safety limits: max order size, max position, max notional exposure, order rate, cancel/replace rate, allowed instruments, allowed order types, loss limits, timeout behavior, and kill switch
- define fairness rules for competitions: same starting cash, same market snapshot, same data access, same instruments, same duration, same latency model where applicable, same fee/slippage model, and same margin/leverage rules
- define the bot approval lifecycle: draft, submitted, static checks passed, simulation checks passed, approved, active, suspended, and archived
- define secret handling rules: who can read/write secrets, how secrets rotate, whether runners can access secrets directly, isolation by bot/user/game, and audit logging for secret access
- define the data-provider abstraction for historical candles, live quotes, corporate actions, instrument metadata, provider health, and snapshot generation
- define order lifecycle states: received, accepted, rejected, queued, partially filled, filled, cancelled, expired, and failed
- define failure handling for bot crashes, runner timeout, market-data outage, matching-engine outage, duplicate submission, replay mismatch, settlement failure, and leaderboard calculation failure

The intended output of this review should be one or more focused specs, likely:

- bot onboarding and approval flow
- bot runtime, secret, and data-access model
- simulation and game execution model
- order, matching, and settlement lifecycle for arena-originated activity
- leaderboard, scoring, replay, and audit rules

Draft economic-control direction now lives in
[`BOT_ARENA_MONETARY_POLICY.md`](./BOT_ARENA_MONETARY_POLICY.md), covering house
market makers, NPC flow, fees, source/sink accounting, policy versioning, and
economic report requirements for arena games.

Draft scoring direction now lives in
[`BOT_ARENA_SCORING_POLICY.md`](./BOT_ARENA_SCORING_POLICY.md), covering
eligibility gates, final-equity ranking, risk and conduct metrics, NPC
difficulty buckets, leaderboard partitioning, and season aggregation questions.

## Resolved Slice: Bot Submission Workflow And OpenBao Provisioning

Status: agreed direction, 2026-07-05. This resolves the "bot creation flow" and part of "secret handling rules" items from Deferred Second Review above (line 28, line 45: secret partition activation, who provisions, isolation by bot/user). Other Deferred Second Review items (safety limits, fairness rules, order lifecycle, failure handling beyond what's listed here) remain open.

Submission and update path is PR-based:

- branch naming is a fixed enum: `bots/add/<bot-name>`, `bots/update/<bot-name>`, `bots/remove/<bot-name>`. The branch segment is documentation/routing only, not a trust boundary — CI does not trust it for authorization decisions.
- new-vs-update detection is done by diffing the arena bot registry, not by branch name: if the bot ID does not exist in the registry, this is a new-bot flow (including OpenBao user/slice provisioning); if it exists, this is an update flow (new `ArenaBotVersion` under the existing bot, no new identity provisioning).
- manifest convention: each submission adds/changes `bots/<bot-name>/bot.json` (schema in `scripts/dev/bot-submission-validate.mjs`) alongside the bot's TypeScript entry file at `bots/<bot-name>/index.ts`, in the same PR.
- implementation: `.github/workflows/bot-submission.yml` scaffolds this pipeline. Stages 1-3 are real (manifest validation, then `bot-sdk-test-bot.mjs` for the combined security-scan/sandboxed-test-run gate); they may use an ephemeral `make dev-up` stack since they need no persistent state. Stage 4's OpenBao step is now a real Admin API call through `scripts/dev/bot-submission-provision-openbao.mjs`, with explicit local dry-run mode for workflow wiring and developer checks.
- post-merge registry sync: `.github/workflows/bot-registry-sync.yml` runs on pushes to `master`/`main` that change `bots/**`. It registers merged bot metadata and version facts in the durable hosted arena registry through `/admin/v1/arena/bots` and `/admin/v1/arena/bot-versions`, using `scripts/dev/bot-submission-register-merged.mjs`. The sync builds the hosted artifact after merge to record real source/artifact hashes.
- **stage 4 must call the real, always-on hosted admin API, never an ephemeral per-run stack.** Registry-diff and OpenBao provisioning both depend on durable state (the actual bot registry, the actual OpenBao instance) that a fresh `make dev-up` container does not have — every bot would incorrectly look "new" against an empty throwaway registry, and any "provisioned" secret would vanish when the ephemeral stack tears down. `bot-submission-registry-diff.mjs` and `bot-submission-provision-openbao.mjs` take `ARENA_ADMIN_API_URL` (a GitHub Actions secret) for this reason, not a derived localhost URL. Because CI only ever makes HTTP calls to this API, no JVM/Gradle runs in the bot-submission workflow at all — the Kotlin OpenBao client and the GitHub OIDC → `auth/jwt` exchange both run server-side, on the always-on host, never in a CI runner.
- raw `/internal/*` routes stay private. GitHub Actions and the admin UI reach only narrowly-scoped, authenticated admin gateway routes through Caddy: `/admin/v1/arena/bots`, `/admin/v1/arena/bots/openbao-provision`, `/admin/v1/arena/bots/config`, `/admin/v1/arena/bot-versions`, `/admin/v1/arena/runs`, `/admin/v1/arena/run-bot-results`, `/admin/v1/arena/run-enforcement-events`, and `/admin/v1/arena/leaderboard`. Runtime binds the admin actor from request headers/token context, not body/query fields.
- outstanding manual step: GitHub branch protection on `master`/`main` must require the `validate-manifest`, `scan-and-sandbox-test`, and `registry-diff-and-provision` status checks plus at least one human reviewer approval before merge (see "Merge gate" below) — this is a repo-settings change, not something a commit can express.

PR pipeline, in order, each stage blocking the next:

1. bot validity check (schema, manifest, required metadata fields including `email`)
2. security/static analysis (approved-dependency scan, per `docs/BOT_SDK_APPROVED_PACKAGES.md`)
3. sandboxed test run against the fixture market (reuses `bot-sdk-test-bot.mjs`'s existing SES-based gate; exits nonzero and marks `do_not_merge` on failure, per `docs/BOT_SDK_AUTHOR_GUIDE.md:156`)
4. OpenBao provisioning check/step, only reached if 1-3 pass and only for `bots/add` and `bots/update` (not `bots/remove` — see removal below):
   - new submitter (per authenticated PR-actor identity, not the self-reported `email` metadata field — email stays a contact field only and is never used as an identity/authorization key): provision a new OpenBao user identity, then a new bot secret slice for that user
   - existing submitter, new bot: provision only a new bot secret slice under their existing identity
   - existing submitter, bot update: no new provisioning; the existing slice is reused, since only one bot version trades at a time and secrets are not version-scoped

Identity mapping mechanism: the real OpenBao infra (`infra/hetzner-core/server/`, merged to master 2026-07-05 via PR #45/`aa70ed4`; see "Infrastructure Architecture" below) bootstraps `secret/` as a KV v2 mount with **AppRole** auth (`configure-openbao.sh`), used by `reef-platform-runtime` and `reef-simulator` to read `secret/data/bots/*` and their own service secrets at runtime. The Admin API's participant-facing bot config writer uses a separate `reef-platform-admin-bot-config` AppRole with create/update/read/delete-metadata access under `secret/*/bots/*`; the browser never receives this credential or direct OpenBao access. The submission pipeline adds a **second, separate auth backend**: `auth/jwt`, configured to validate short-lived GitHub Actions OIDC tokens and map `actor`/`repository` claims to a narrow, provisioning-only OpenBao policy (create/update/delete under `secret/data/bots/*` only — no read access to other services' secrets, no access to the AppRole auth backend itself). No long-lived OpenBao token or AppRole secret is stored in GitHub Actions secrets for this flow. This `auth/jwt` backend and its policy, the `OpenBaoProvisioningService.kt` client, the `POST /internal/admin/arena/bots/openbao-provision` route, and the gateway-backed `/admin/v1/arena/bots/openbao-provision` call path now form the submission gate.

Secret slice path: `secret/bots/<submitter-identity>/<bot-id>` — nested under the existing `secret/data/bots/*` / `secret/metadata/bots/*` policy wildcard already granted to `reef-platform-runtime`/`reef-simulator` (no policy rewrite needed for runtime reads, since the wildcard already matches the deeper path), with a per-submitter segment added for provisioning-time isolation. No version segment: only one version of a given bot is ever active/trading at once, so slice-per-version would add path churn without an isolation benefit; version history and which version last held the slice's active secrets are tracked in registry/audit rows, not in the OpenBao path.

Removal (`bots/remove/<bot-name>`): on merge, the bot transitions to `archived` in the registry AND its OpenBao secret slice is deleted/access-revoked in the same pipeline run, rather than left provisioned. This minimizes standing secret exposure for bots no longer running; if the bot is resubmitted later it goes through the new-submitter-or-existing-submitter provisioning path again as if new.

Failure handling: any pipeline stage failing (1-4) hard-blocks the PR merge via required GitHub branch protection status checks. No registry write happens on failure — the bot/version never enters `draft`/`submitted` state, and no OpenBao identity or slice is created. This matches the existing `bot-sdk-test-bot.mjs` `do_not_merge` contract and avoids partially-provisioned state.

Merge gate: branch protection requires both all automated stages (1-4) passing AND at least one human operator approval before merge — automated checks alone do not auto-merge. This matches the operator-decision-driven pattern already used for lifecycle transitions (`ArenaOperatorDecision` records actor, reason, correlation ID for state changes elsewhere in the control plane); the PR reviewer approval is the equivalent operator checkpoint for the submission path.

Known tradeoff, accepted: running OpenBao provisioning (stage 4) inside the pre-merge PR pipeline means the PR-facing CI job holds OpenBao write credentials while also executing untrusted submitted bot code (stage 3) in the same workflow run. This is a larger blast radius than a post-merge provisioning job would be. Accepted here because the requirement is that OpenBao provisioning must block merge, not follow it. Mitigation to build alongside this: stage 3 (sandboxed test run) and stage 4 (OpenBao provisioning) should run as separate CI jobs/steps with different credential scopes, so the job holding OpenBao write credentials never executes bot code directly — it only runs after stage 3 reports success as a separate job.

## Next Control-Plane Slice

Start with durable arena source facts before UI or leaderboard work:

- bot identity and public metadata
- bot version, artifact hash, source hash, SDK/API versions, and dependency manifest reference
- qualification report status and issue codes
- approval lifecycle state: `draft`, `submitted`, `checks_passed`, `approved`, `active`, `suspended`, `quarantined`, `banned`, `archived`
- operator decisions with actor, reason, correlation ID, and timestamp
- run records that reference bot versions and policy versions

This storage boundary should remain separate from trading hot-path state. Local development may begin with an in-memory or same-database schema, but the domain model should treat arena registry and approval facts as arena-owned data.

Implementation checkpoint:

- `ArenaControlPlaneService` defines the first arena-owned registry boundary in platform runtime.
- `ArenaBotRegistryStore` and `InMemoryArenaBotRegistryStore` capture source facts for local tests without introducing a trading hot-path dependency.
- `PostgresArenaBotRegistryStore` adds the first durable arena schema for registry, qualification, operator decision, and run-record facts.
- Hosted arena admin and bot-version risk checks require the separate arena datasource (`ARENA_POSTGRES_JDBC_URL`, `ARENA_POSTGRES_USER`, `ARENA_POSTGRES_PASSWORD`), not the runtime or boundary datasource.
- Bot versions now have explicit approval, active, suspended, quarantined, banned, and archived states.
- Operator decisions record actor, reason, correlation ID, timestamp, and lifecycle transition.
- Arena run records reference approved bot versions, scenario ID, seed, and policy version before execution starts.
- Arena runtime config descriptors record OpenBao secret paths and required keys without storing secret values.
- Arena bot version risk checks can reject exact bot/version commands before venue acceptance when the version is not approved or active.
- Arena run result ingestion writes `arena.run_bot_results` idempotently by `(runId, botId, versionId, scoringPolicyVersion)`, replaces retry payloads, excludes disqualified results from leaderboards, and is covered by the local `make dev-smoke-arena-run-results` gate.

Remaining work before this becomes a production control plane:

- keep `PostgresArenaBotRegistryStore` validation coverage in the schema-placement CI job as arena schema evolves; current coverage includes the full `arena.run_bot_results` ingestion shape
- keep `make dev-smoke-arena-bot-risk` as the local stack gate for bot-version risk controls; `make test-bot-sdk` syntax-checks the smoke script
- wire the platform-owned `OpenBao` provider into runner preflight using `resolveBotRuntimeConfigV1`
- extend result ingestion smoke from the current synthetic hosted-summary fixture to a real hosted bot test summary once score calculation policy is accepted

Local arena risk smoke:

```bash
ARENA_POSTGRES_JDBC_URL=jdbc:postgresql://arena-postgres:5432/reef?currentSchema=arena \
PLATFORM_ARENA_ADMIN_ENABLED=1 \
EXTERNAL_API_ARENA_BOT_VERSION_RISK_ENABLED=1 \
make dev-smoke-arena-bot-risk
```

The smoke registers a bot and bot version, quarantines that version through the hosted internal admin route, then submits a bot-versioned order and expects a pre-acceptance `BOT_DISABLED` rejection.

## Agreed Direction

The current working direction is:

- use TypeScript as the first public bot authoring SDK — implemented as the TypeScript-only `ReefBotV1` (`packages/bot-sdk`)
- use SES compartments as the first sandbox boundary — implemented in `packages/bot-sdk/src/hosted-runner.ts` (`createSesCompartmentFactoryV1`); container/WASM boundaries remain a possible later addition for a stronger threat model, not a currently open question
- keep the durable bot-runtime contract language-neutral through protobuf-defined snapshots, actions, outcomes, and resource reports
- today's shipped runner-pool prototype (`scripts/dev/arena-runner-pool-smoke.mjs`, see `docs/BOT_ARENA_RUNNER_BENCH.md`) uses a JSON-line protocol over each worker's stdin/stdout; a dedicated gRPC/protobuf protocol between sandbox workers and the arena orchestrator remains a future intent, not yet decided or implemented — no arena proto files exist under `contracts/proto/` today
- do not allow bot code to create REST or gRPC clients to Reef services
- preserve venue command semantics, validation, idempotency, abuse controls, and audit metadata for every bot-originated action
- model arena runs as real interactive markets with controlled market makers, controlled background traffic, and user competitor bots
- start with a strict visible-data policy and expand only through versioned policies backed by replay evidence
- expose final equity as the first headline leaderboard while retaining richer risk, conduct, and consistency metrics from day one
- delay public bot submissions until built-in/local bots prove replay, scoring, sandboxing, and operator controls
- keep arena metadata, leaderboards, bot registry, run history, and replay indexes outside the trading hot-path database
- use Redis only for ephemeral coordination, rate limits, leases, and live caches, not as the sole durable store for competition records
- design the run plane for early horizontal scale by tournament run, shard, instrument group, sandbox worker, and matching-engine partition
- inherit the throughput scaling target from [`docs/THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md): at least `7500` completed commands/sec per runtime + engine instance, preferably `10000`, with zero silent accepted-command loss
- use [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md) as the target venue-ingress design for high-throughput bot traffic

This direction can change, but changing it should update this document and any accepted decision records that later depend on it.

## Product Concept

Bot Arena is a simulation-control layer where submitted or built-in trading agents compete across configurable game modes.

Each bot starts with a configured capital base, risk limits, visible data policy, and allowed action set. The simulator drives seeded market conditions and participant activity. Bots observe only an explicit public snapshot, emit order intents, and compete on mode-specific leaderboards.

Arena runs should model a real market with multiple actor classes:

- operator-controlled market maker bots with public behavior descriptions
- operator-controlled background traffic bots with a mix of public and private strategy code
- user-submitted competitor bots that trade against and affect the simulated market
- optional built-in benchmark bots used for calibration and regression checks

Competitor bots should not be passive backtest observers. Their orders should enter the same simulated venue, affect liquidity and price formation, consume liquidity from controlled participants, and influence other bots' outcomes.

Operator-controlled bots are part of the product surface, not throwaway load-test scripts. They should be tested, versioned, documented, and replay-stable because they form the initial liquidity pool and general market traffic before enough user bots exist.

The arena should support specialized competitions and aggregate seasons:

- equity winner across fixed seeded sessions
- momentum trading challenge
- market-making challenge
- low-drawdown or survival league
- execution-quality challenge
- overall ranking across multiple game modes and seed sets

Game modes should eventually classify instruments/assets with explicit arena
metadata such as liquidity tier, volatility tier, quality/risk bucket, and
allowed house-liquidity profile. For example, a mode can include "junk" tier
tickers together with liquidity providers calibrated for that same tier. These
categories belong in scenario/mode metadata and bot configuration, not in the
matching engine, so the venue stays neutral while the arena can create varied
market-quality outcomes.

The game layer must not redefine Reef as a retail trading application. It should remain a controlled simulation and benchmarking surface for strategy behavior, market microstructure, and platform throughput.

Arena readiness depends on completed-throughput capacity, not only request intake. Before attaching public bot traffic, Reef should prove that accepted bot-originated commands are durably captured, terminally accounted, and drained without unexplained gaps under the `stream-ack` stress profile. Postgres `captured-ack` remains useful for local fallback and A/B comparison.

## Architecture Fit

Target flow:

```text
bot artifact
  -> sandbox runtime
  -> bot SDK interface
  -> arena runtime protocol
  -> arena adapter/orchestrator
  -> schema validation
  -> arena risk gate
  -> venue command gateway
  -> platform runtime
  -> matching engine
  -> events, read models, analytics, leaderboard
```

The matching engine should not know whether an order came from a manual user, simulator persona, or arena bot. The arena adapter is responsible for translating bot actions into normal venue commands with explicit actor identity, command IDs, idempotency keys, trace IDs, causation IDs, run IDs, and bot version metadata.

For scale, the arena should define a dedicated bot-runtime communication layer using protobuf contracts and likely gRPC transport. That protocol should connect sandboxed bot workers to the arena orchestrator; it should not give bot code direct access to the matching engine or internal platform state.

The venue command gateway should preserve `/api/v1` boundary semantics: validation, identity context, idempotency, rate limits, abuse controls, command capture, and audit metadata. The initial implementation can call the existing HTTP boundary for simplicity, but the scalable target can use a gRPC or in-process application boundary as long as command-path parity and boundary controls are preserved.

For high-throughput arena runs, the scalable target is durable stream-backed intake: bot-originated actions pass the arena risk gate, publish to the configured retained command log with a durable ack before acceptance, route by deterministic run/session/instrument partition, and reach canonical venue facts before the stream offset/message is acknowledged. The active venue-core hot-ingress target is Kafka-compatible Redpanda with matching-engine direct consumption; JetStream remains a fallback/comparison provider.

Candidate ownership:

- `packages/bot-sdk/`: public bot authoring types, helpers, and examples
- `packages/scenario-definitions/`: arena mode and scenario definitions
- `services/simulator/`: arena run orchestration, seeded scheduling, replay checks
- `services/platform-runtime/`: arena metadata, boundary enforcement hooks, leaderboard/read-model APIs where appropriate
- `docs/`: governance, safety model, game mode specs, and operator guidance

## Bot Runtime Contract

Start with one supported bot authoring surface. A narrow interface is preferable to arbitrary service integration.

Recommended initial direction:

- make the underlying bot protocol language-neutral with protobuf-defined snapshots, actions, outcomes, and resource reports
- ship TypeScript as the first authoring SDK because it is approachable, easy to package for a web-oriented project, and compatible with SES-style capability confinement
- keep Python as a likely later SDK because quant users expect it, but only after container/WASM isolation and dependency governance are mature
- consider WASM as a later portability and sandbox target once the API has stabilized
- avoid supporting multiple public languages before the replay, sandbox, and scoring models are proven

TypeScript/JavaScript is a pragmatic first interface, not a permanent platform limitation. The durable contract should be the arena protocol and data model, not the first SDK's callback syntax.

Example shape:

```ts
export interface Bot {
  onStart(context: ArenaContext): void;
  onSnapshot(snapshot: ArenaSnapshot): ArenaAction[];
  onFill(fill: OwnExecution): ArenaAction[];
  onEnd(summary: ArenaRunSummary): void;
}
```

The exact callback model can change, but the contract should stay capability-based:

- the bot receives explicit inputs
- the bot returns proposed actions
- the bot cannot call internal services directly
- the bot cannot mutate platform state
- the arena adapter decides what becomes a platform command

Bot code should not create REST or gRPC clients to Reef services. The SDK should expose a local callback/action API; the sandbox worker and arena orchestrator own all transport.

Every bot artifact should be versioned and immutable once admitted to a run:

- bot ID
- bot version
- owner or submitting principal
- source or bundle hash
- runtime version
- dependency lock hash
- permission profile
- validation status
- approval/quarantine state

## Public Data Surface

Bots should receive a curated arena snapshot rather than internal events or database records.

Likely allowed fields:

- simulation timestamp and tick/session phase
- game mode ID and visible mode parameters
- instrument reference data, tick size, lot size, and trading status
- own cash, equity, positions, and reserved balances
- own open orders
- own executions and fills
- public top-of-book, limited depth, or last-trade data as allowed by the mode
- public regime markers if the mode intentionally exposes them
- current risk limits
- remaining session time or ticks

Likely disallowed fields:

- direct database access
- internal event logs
- future scenario schedule
- simulator random number state
- hidden matching-engine state
- privileged participant metadata
- other bots' identities unless the game mode explicitly permits it
- raw scoring internals where that would make the game trivial to exploit

The visible data policy should be versioned and recorded on each run so historical results remain explainable.

The first public data surface should be strict and intentionally small. Expansion should be driven by replay evidence and bot-author feedback:

- record when bots lack enough context to behave sensibly
- add fields only through versioned visible-data policies
- keep old policies available for replay and historical leaderboard integrity
- prefer derived public fields over leaking internal event or engine state

## Action Surface

Bots emit intents, not state changes.

Initial actions:

- place limit order
- place marketable order if the mode allows it
- amend order
- cancel order
- cancel all own open orders
- no-op

Potential later actions:

- quote both sides as a single market-maker intent
- submit parent order instruction for execution-quality modes
- request inventory flattening under specific game rules

Every action should pass through:

```text
schema validation -> permission check -> risk check -> rate limit -> public API command
```

Invalid or rejected actions should be recorded as first-class arena events and may affect scoring.

## Sandbox And Abuse Controls

Submitted code is never trusted.

Minimum controls:

- isolated worker or container per bot execution context
- no outbound network by default
- no filesystem access by default
- deterministic simulator-controlled clock
- deterministic random source only if explicitly provided
- per-decision wall-time timeout
- CPU or instruction budget where practical
- memory limit
- max output size
- max retained bot state size
- max actions per tick
- dependency allowlist or vendored dependency bundle
- immutable artifact hash recorded with every run
- kill switch for timeout, crash, invalid output, abuse, or repeated rule violations

SES is a plausible first runtime for JavaScript/TypeScript bots because it supports capability-oriented execution. Container or WASM runtimes may be useful later for stronger isolation or multiple languages, but the first version should optimize for determinism and inspectability over language breadth.

Abuse controls should include:

- per-bot order rate limits
- cancel/replace rate limits
- max open orders
- max order size
- price collars and fat-finger checks
- cash and position limits
- instrument eligibility checks
- self-trade prevention where relevant
- repeated invalid-action lockout
- resource-usage penalties or disqualification rules
- circuit breakers for bot-level, tournament-level, and venue-level stress
- ban, quarantine, watch, and throttle lists for bot versions and submitting principals
- operator controls to disable a bot version during a live or scheduled run
- progressive enforcement lists for repeated timeouts, invalid actions, or abusive order patterns

## Game Mode Module Contract

Game rules should be modular. The base arena should not hard-code one competition shape.

Each game mode should define:

- mode ID and version
- eligible instruments and sessions
- initial capital and inventory configuration
- participant, controlled-flow, and competitor bot eligibility rules
- controlled market maker and background-flow configuration
- scenario generator or scenario file references
- seed set strategy
- visible data policy
- allowed action policy
- arena risk policy
- scoring policy
- leaderboard dimensions
- replay assertions
- disqualification and penalty rules

Example modes can then be added without changing core venue behavior:

- equity sprint
- momentum challenge
- market-maker trial
- liquidity drought survival
- execution-quality challenge
- overall season across multiple mode scores

## Simulation, Replay, And Audit Data

Every arena run should persist enough metadata to reproduce and explain the result.

Required run metadata:

- run ID
- tournament or season ID
- game mode ID and version
- scenario definition ID and version
- seed set
- bot IDs, versions, artifact hashes, and runtime versions
- visible data policy version
- action policy version
- risk policy version
- scoring policy version
- controlled market maker versions and parameter hashes
- background traffic bot versions and parameter hashes
- start/end timestamps
- status and failure reason if any
- event timeline pointer
- final rankings and metric summaries

Required per-decision audit:

- bot ID/version/hash
- input snapshot hash or stored snapshot reference
- callback invoked
- output actions
- validation results
- risk results
- accepted command IDs
- rejected action reasons
- runtime duration
- resource usage
- exception/timeout details

Replay should answer:

- what did the bot see?
- what did the bot decide?
- which actions were accepted or rejected?
- what venue events followed?
- why did the score change?

Replayability must be designed for scale from the start. Large tournaments should not require storing unlimited full snapshots if they can be reconstructed reliably, but the system must know exactly which inputs produced each bot decision.

Potential storage strategy:

- persist all run, policy, seed, artifact, and actor-version metadata
- store full snapshots for failed decisions, sampled ticks, and debug-enabled runs
- store snapshot hashes and event-log references for normal high-volume decisions
- make reconstruction checks part of deterministic replay tests
- keep leaderboard calculations tied to immutable scoring-policy versions

## Storage Ownership

Arena data should have a distinct storage boundary from the trading venue's hot-path state.

Trading-side durable state:

- orders
- executions
- trades
- core venue lifecycle events
- venue command state required for correctness
- risk and idempotency state required by the venue boundary

Arena-side durable state:

- bot registry and bot version metadata
- bot artifact references and validation results
- tournament, season, and run metadata
- game mode definitions and policy versions
- controlled market maker and background-flow versions
- score results and leaderboard read models
- replay indexes and debug artifact references
- ban, quarantine, watch, and throttle lists
- operator actions and arena audit records

Recommended storage split:

- arena Postgres database as the source of truth for competition metadata and leaderboard state
- object storage for bot bundles, large replay snapshots, debug payloads, run reports, and compressed artifacts
- Redis for ephemeral run coordination, leases, short-lived live leaderboard caches, rate limits, and active enforcement counters

Cheap local development can start with one Postgres instance using separate databases or clearly separated schemas. The architecture should still treat arena storage as its own domain so it can move to a separate Postgres instance without rewriting the trading runtime.

The trading hot path should not synchronously depend on arena analytics writes. If arena scoring, replay indexing, or leaderboard updates fall behind, venue command handling should continue according to normal risk and boundary rules.

## Scoring And Analytics

Final equity should be the primary headline leaderboard at first, because it is easy to understand and fun to compete on. It should not be the only metric retained or the only ranking forever.

Baseline metrics:

- final equity
- realized and unrealized PnL
- max drawdown
- return volatility
- inventory exposure
- order-to-trade ratio
- fill rate
- average spread captured or paid
- market impact proxy
- adverse selection proxy
- invalid action count
- risk violation count
- timeout count
- completion/survival rate
- consistency across seeds

The initial product can expose one main leaderboard per game mode, backed by richer hidden or secondary analytics. As the arena matures, leaderboards should support risk-adjusted, consistency, market-making, execution-quality, and aggregate season rankings. Scoring policies must be versioned so old tournament results remain stable after future rule changes.

## UI Surface

Initial UI should be an operational arena surface, not a marketing page.

Core views:

- bot registry and version history
- bot validation/quarantine result
- tournament or run setup
- active run monitor
- leaderboard
- bot detail and score breakdown
- replay/event timeline
- order, fill, inventory, and equity charts
- violation and resource usage panel

The replay and score explanation views are product-critical. Users should be able to understand why a bot won, failed, or was disqualified.

## Infrastructure Architecture

The arena should be designed as a control plane plus a run plane.

Control plane responsibilities:

- bot registry and artifact metadata
- game mode registry
- tournament scheduling and admission
- user and operator controls
- leaderboard and analytics APIs
- audit, replay, and run history
- ban, quarantine, watch, and throttle lists

Run plane responsibilities:

- arena run orchestration
- deterministic clock and seed management
- controlled market maker and background-flow execution
- sandbox worker allocation
- bot snapshot delivery and action collection
- action validation and risk checks
- venue command submission
- run-local metrics aggregation

Suggested low-cost local/MVP topology:

```text
Kotlin platform runtime
  -> trading Postgres database/schema
  -> arena Postgres database/schema
  -> optional Redis for local active-run coordination
  -> Go matching engine
  -> arena orchestrator in services/simulator
  -> local sandbox worker pool
  -> local artifact/report storage
```

This keeps the MVP cheap and inspectable. It can run under the existing local-first stack with no permanent extra infrastructure beyond the arena orchestrator and worker process.

Suggested low-cost hosted topology:

```text
one small app host for platform runtime + control APIs
  -> trading database
  -> arena database
  -> small Redis instance for active-run coordination and live caches
  -> one run host that starts tournament workers only when needed
  -> object storage for bot bundles, run artifacts, and replay/debug payloads
```

Concrete backbone/run-plane split — finalized 2026-07-05, ratified as [D-046](DECISIONS.md). Status against the merged infra (`infra/hetzner-core/`, PR #45, commit `aa70ed4`, merged to master 2026-07-05):

**Backbone** — one always-on Hetzner droplet (`cx33`, `nbg1`, OpenTofu-provisioned, private network `10.70.0.0/16`):

- **OpenBao instance** — done. `infra/hetzner-core/server/docker-compose.yml:36-53`, AppRole bootstrapped via `configure-openbao.sh`. The `auth/jwt` backend for CI provisioning (see "Resolved Slice: Bot Submission Workflow And OpenBao Provisioning" above) shipped the same day in PR #64/`cc5fd72`, along with `OpenBaoProvisioningService.kt` and the `openbao-provision` admin route. The bot-submission script now calls the gateway-backed Admin API route in real mode and keeps dry-run mode for local workflow checks.
- **Admin API** — done, as `platform-runtime` (`docker-compose.yml:67-88`). Same instance already handling arena registry/`ArenaControlPlaneService`/bot-submission OpenBao-provisioning route; also the natural home for any other admin/clerical game operations. Not a separate process.
- **Reverse proxy** — done. Caddy (`docker-compose.yml:90-109`), gated behind `profiles: [public]`.
- **Analytics microservice (API + DB)** — not built. Only a Postgres *schema* named `analytics` exists today, inside the single shared Postgres instance (`postgres/init/01-create-dbs.sh`). Needs its own API/service, holding leaderboard data, game results, aggregate data, and everything ingested from simulation runs.
- **Admin DB** — not built as a separate DB. Currently a schema (`admin`) in the same shared Postgres instance as everything else. Finalized shape: **two separate Postgres containers on the same backbone droplet** — one for Admin DB (user accounts, game/meta config, other non-runtime/non-analytics persisted state), one for Analytics DB — not schemas in one instance, and not a separate managed DB service (stays on the one cheap droplet).

**Simulation platform (run plane)** — finalized: stays on **DigitalOcean**, not a second Hetzner droplet, reusing the already-proven DO stress-test/benchmark compute path. Accepts cross-cloud complexity in exchange for reusing what already works. Today's actual implementation (`infra/simulation-runner/README.md`, `scripts/deploy/simulation-run.mjs`) is an ad-hoc reuse of the pre-existing `scripts/dev/do-benchmark-host.sh` harness — this gets rebuilt to mirror the backbone's own deploy pattern: new `infra/simulation-runner/tofu/` (DO provider, OpenTofu) + its own `docker-compose.yml` + a deploy script shaped like `scripts/deploy/hetzner-core-tofu.mjs`, so running/deploying the simulation platform is as easy and uniform as running the backbone despite targeting a different cloud.

- **Full runtime** (matching engine, simulator, trading-side hot-path DB) — exists today bundled with the DO benchmark harness; stays ephemeral, scoped to the simulation droplet, never touches the backbone's Admin/Analytics DBs directly.
- **Export/cleanup service** — new, not yet built. Runs on the ephemeral simulation droplet as the last step before teardown: aggregates a completed run's results and pushes them to the backbone's Analytics API, replacing today's raw `rsync` file copy in `simulation-run.mjs`'s `pushArtifacts()`. Runs locally on the droplet so it doesn't depend on the operator's machine staying available.
- **Blob storage for compressed debug data** — new, not yet built. Finalized choice: **Cloudflare R2**, reusing the credential/bucket setup already established for Postgres/OpenBao backup dumps (`infra/hetzner-core/README.md`) rather than introducing a second object-storage vendor (e.g. DO Spaces was considered and dropped in favor of one less vendor to manage).

`ARENA_ADMIN_API_URL` (used by the bot-submission workflow's registry-diff and OpenBao-provisioning stages) always points at the backbone's Admin API — never at an ephemeral DO simulation droplet, since the backbone is the only durable, always-addressable host in this split.

Cost-control rules:

- prefer scheduled tournaments over always-on large worker pools
- start worker capacity only while runs are active
- keep the control plane always-on and small; make the expensive run plane ephemeral
- cap bots per run, ticks per run, actions per tick, and max open orders
- store full snapshots only for failures, sampled ticks, and debug-enabled runs
- store compressed artifacts and apply retention windows for high-volume replay data
- keep public submissions out of the hot path until operator-controlled bots prove the model
- make scenario scale explicit in game mode config so expensive tournaments are deliberate
- reserve large multi-shard tournaments for scheduled windows until demand justifies always-on capacity

Scale-out target:

```text
platform/runtime control plane
  -> trading database
  -> arena database
  -> Redis/ephemeral coordination store
  -> artifact/object storage
  -> queue or scheduler for arena runs
  -> arena orchestrator workers
  -> sandbox worker pool
  -> matching engine instances or partitions
  -> metrics/replay aggregation
```

The scale-out path should add distribution where it matters:

- sandbox workers scale horizontally by bot count and callback rate
- arena orchestrators scale by tournament/run count
- matching engines scale by run, venue partition, or instrument group
- replay and leaderboard jobs scale as asynchronous batch/read-model work
- durable stream-backed ingress is the target venue command path for high-throughput bot traffic; the active provider target is Kafka-compatible Redpanda, with JetStream retained as fallback/comparison
- NATS or another explicit queue/backbone can still be used for run scheduling and metrics fanout where those concerns justify it

Scale unit:

```text
one tournament run, shard, or instrument group
  -> one arena orchestrator lease
  -> N sandbox workers
  -> one matching-engine instance or partition
  -> async replay and leaderboard aggregation
```

This keeps the system from depending on one giant simulator process. Higher throughput should come from partitioning and worker scale-out, not only from squeezing more orders per second through a single runtime path.

Isolation model:

- public bot submissions run in the least-trusted worker pool
- built-in and operator-controlled bots can run in a trusted or semi-trusted pool during early development
- sandbox workers should have no default network egress
- workers should have CPU, memory, time, and output quotas
- artifact fetch should happen through the orchestrator or a controlled side channel, not arbitrary bot code
- operator disable controls should stop future callbacks and cancel/flatten open orders according to game mode policy

Performance posture:

- optimize callback batching, snapshot construction, and action validation before adding deployment complexity
- keep matching-engine determinism and serial book processing as the baseline
- measure accepted actions/sec, decision latency, reject rate, timeout rate, and replay reconstruction cost per run
- benchmark the REST boundary against stream-ack, gRPC, or in-process command gateways before replacing the simple path
- identify whether the remaining throughput ceiling is stream publish ack, partition worker concurrency, idempotency storage, canonical DB commits, event logging, engine matching, projection lag, or simulator client pressure before changing core architecture again
- make scale tests part of arena acceptance, including single-run throughput, multi-run throughput, worker saturation, replay reconstruction, and leaderboard aggregation lag

First local stress baseline:
- [`docs/BOT_ARENA_STRESS_BASELINE_2026-07-01.md`](./archive/BOT_ARENA_STRESS_BASELINE_2026-07-01.md)

## Rollout Plan

### Phase 0: Planning

- agree on arena boundaries and non-goals
- decide first runtime target
- define the protobuf arena runtime protocol
- define initial bot SDK types
- define game mode module schema
- define arena storage ownership and migration boundaries
- document sandbox threat model

### Phase 1: Local Built-In Bots

- add built-in sample bots only
- add operator-controlled market maker and background-flow actors
- add deterministic tests for all operator-controlled bots used as liquidity or traffic
- run them through an arena adapter using existing public command semantics
- persist run metadata and action audit
- add deterministic replay assertions
- add a small leaderboard report

Detailed execution plan and benchmark evidence for this phase live in:
- [`docs/BOT_ARENA_PHASE_1_READINESS_PLAN.md`](./BOT_ARENA_PHASE_1_READINESS_PLAN.md) — the detailed Phase 1 work order: current foundations, the concrete local arena run path, and the smoke gate that proves it uses normal Reef venue commands.
- [`docs/BOT_ARENA_RUNNER_BENCH.md`](./BOT_ARENA_RUNNER_BENCH.md) — benchmark evidence and reproducible commands for the grouped TypeScript-capable bot runner shape this phase depends on.

### Phase 2: Sandbox Execution

- execute bot bundles in the sandbox runtime
- connect sandbox workers to the arena orchestrator through the arena runtime protocol
- enforce time, memory, output, and action limits
- record runtime resource usage
- fail runs deterministically on timeout or invalid output

### Phase 3: Modular Game Modes

- load game mode definitions from versioned config/modules
- support mode-specific visible data, action policy, risk limits, and scoring
- add initial equity, momentum, and market-maker modes

### Phase 4: UI And Replay

- expose bot registry, tournament setup, live run status, leaderboard, and replay views
- make decision-level audit inspectable from the UI
- compare bots across seed sets and game modes

### Phase 5: User Submissions

- add bot upload/submission flow
- validate artifact manifest and dependency policy
- quarantine unapproved submissions
- add approval and disable workflows
- add operator controls for tournament admission

## Open Questions

- Where should the arena runtime protobuf contracts live relative to `contracts/proto/` and `packages/bot-sdk/`?
- How much of the bot runtime should live in `services/simulator` versus a separate worker process?
- What exact retention windows should apply to full snapshots, sampled snapshots, debug payloads, and replay artifacts?
- What dependency policy is acceptable for public bot submissions?
- Which metrics should be visible during a run versus revealed after completion?
- Should bots be anonymous during tournaments to reduce collusion/metagaming?
- What is the first game mode that best demonstrates Reef's market-infrastructure strengths?
- How should aggregate season scoring normalize across very different game modes?
- How much controlled background-flow code should be public versus private to keep the market understandable but not trivially exploitable?
- Which run partitioning strategy should come first: by tournament run, instrument group, or explicit shard?
- What stream-ack threshold and failure-test evidence is required before public bot submissions can use the scalable path?

## Concerns And Discussion Points

### Security isolation

SES is attractive for a JavaScript-first MVP, but a public bot platform eventually needs a stronger threat model. We should decide whether the first public submission version requires container isolation, WASM, or a separate worker host.

### Determinism versus rich bot code

The more runtime features bots get, the harder replay becomes. Wall-clock time, ambient randomness, network calls, nondeterministic package behavior, and parallel execution all weaken replayability.

### Simulation exploit risk

Bots will optimize against the simulator. That is the point, but it can become uninteresting if they exploit artifacts rather than trading well. Scenario diversity, hidden seed sets, and scoring across regimes will matter.

### Scoring design

Final equity should be one leaderboard, not the only leaderboard. Risk-adjusted performance, consistency, violations, and survivability need to be built into the base analytics model.

### Data leakage

The public snapshot schema is one of the most important contracts. Exposing future schedule hints, hidden flow identity, or internal scoring details can accidentally decide the game.

### Platform boundary discipline

The arena must not bypass `/api/v1` writes or mutate state directly. If the fast path becomes tempting for throughput, it should still preserve command-path parity and audit metadata.

### Operational cost

Public submissions introduce moderation, abuse response, dependency review, and runtime operations. The first implementation should use built-in bots and local-only bundles until the sandbox story is proven.

### Storage isolation

Arena metadata and replay artifacts can grow quickly and should not weigh down trading-side command handling. Separate arena storage adds operational complexity, but it preserves venue performance and makes retention policies easier to enforce.

### Early scale pressure

The current single-instance throughput ceiling is useful but not the long-term design target. The arena should scale by run, shard, worker pool, and matching-engine partition. The first implementation should still measure bottlenecks before replacing stable simple paths.

### Product framing

This should be positioned as a deterministic market strategy arena, not real-money trading and not investment advice. The fun layer is compatible with Reef only if the underlying venue and audit model stay realistic.
