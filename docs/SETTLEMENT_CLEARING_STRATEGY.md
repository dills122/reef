# Settlement And Clearing Strategy

## Purpose

Define the target settlement/clearing direction for Reef, including a realistic industry baseline and a fast `instant-post-trade` operating profile for simulation-game use.

This document is planning guidance. It does not replace [`POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md) or the minimal P2 fact slice in [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md). It explains how those pieces should grow.

## Core Decision

Reef should model one post-trade domain with multiple timing/policy profiles, not separate business models.

- `ops-realistic`: production-shaped U.S. equities default, with `T+1`, trade-date allocation/confirmation/affirmation pressure, clearing, netting, DvP-style settlement attempts, fails, and repair.
- `instant-post-trade`: game/simulation profile that compresses timing to near-instant clearing and settlement while preserving the same lifecycle states, audit events, netting/ledger semantics, and failure injection points.

`clear-lite` may remain informal shorthand in design discussion, but public docs/API names should use `instant-post-trade`.

Instant-post-trade is an accelerated profile. It must not become a shortcut that writes final balances directly or skips post-trade causality.

## Industry Baseline

Default baseline should follow current U.S. institutional equity shape:

- `T+1` standard settlement cycle for applicable securities transactions.
- allocation, confirmation, and affirmation targeted on trade date.
- central counterparty-style clearing acceptance and novation before settlement.
- deterministic netting by participant, instrument, currency, and settlement date at minimum.
- settlement as exchange of cash and securities legs, with finality and DvP-style principal-risk control.
- securities held and transferred by book entry in the simulation model.
- append-only ledger entries and compensating corrections.
- standard financial messaging semantics inspired by ISO 20022/FIX/SWIFT domains, while Reef contracts remain explicit and versioned.

Reference anchors:

- SEC T+1 final rule: <https://www.sec.gov/files/rules/final/2023/34-96930.pdf>
- DTCC CNS overview: <https://www.dtcc.com/clearing-and-settlement-services/equities-clearing-services/cns>
- CPMI-IOSCO PFMI: <https://www.bis.org/cpmi/publ/d101a.pdf>
- ISO 20022 overview: <https://www.iso20022.org/about-iso-20022>

## Non-Negotiables

- simulation actors use the same public/admin command paths as manual users.
- every major state transition emits an event with command, trace, correlation, causation, actor, and scenario metadata.
- matching history remains immutable; settlement consumes canonical trade facts and creates post-trade facts.
- gross trade detail remains available even when netted obligations are used for settlement.
- ledger entries are append-only; corrections are compensating entries.
- instant-post-trade preserves lifecycle state names and event causality.
- calendar, cutoff, netting, affirmation, and settlement timing are policy-versioned and recorded on scenario runs.
- settlement/account finality stays downstream from matching; pre-trade risk checks remain bounded hot-path guards only.

## Target Lifecycle

```text
TradeCreated
  -> allocation proposed
  -> confirmation generated
  -> affirmation accepted or timed out
  -> clearing submitted
  -> clearing accepted or rejected
  -> novation recorded
  -> netting batch created
  -> settlement obligation created
  -> settlement instruction created
  -> settlement attempt started
  -> cash leg and security leg evaluated
  -> settled, partially settled, failed, or aged fail
  -> exception opened when needed
  -> repair posted
  -> retry or resolution recorded
```

Early implementation can combine steps in one application service transaction for simplicity, but it should still emit separate domain events and persist enough state to inspect the chain.

## Instant-Post-Trade Profile

Instant-post-trade should be a policy profile, for example:

```yaml
postTradeProfileId: instant-post-trade-v1
settlementCycle: T+0_SIMULATED
allocationMode: auto
confirmationMode: auto
affirmationMode: auto
clearingMode: auto_accept
novationMode: immediate
nettingMode: gross_v1_then_micro_batch
nettingWindow: per_tick
settlementMode: auto_attempt
settlementAttemptDelayMs: 0
cashLegFailureRate: scenario_configured
securityLegFailureRate: scenario_configured
agedFailClock: simulated
ledgerPostingMode: immediate_append_only
```

Rules:

- Auto-affirm does not mean no affirmation. It means `AffirmationAccepted` is emitted by a configured simulator/system actor.
- Auto-clear does not mean no clearing. It means `ClearingAccepted` and `NovationRecorded` happen immediately under a named clearing policy.
- Instant settlement does not mean direct balance mutation. It means settlement instructions, attempt events, leg outcomes, and ledger postings occur in one fast workflow.
- Failure injection remains explicit and seeded. Instant-post-trade defaults to no failures for normal game runs, but scenario fault profiles can create cash-leg failure, security-leg failure, clearing reject, affirmation timeout, and repair paths.
- Normal instant-post-trade runs settle matched trades immediately after the per-tick post-trade workflow completes. Trades do not settle only when a seeded failure, risk hold, reference-data problem, or explicit scenario rule blocks settlement.
- Netting cadence is per simulator tick. First implementation may settle gross obligations, then add deterministic micro-batch netting under the same policy-version model.

## Game Use

Instant-post-trade exists because the simulation game needs fast feedback:

- bots and players should see positions/cash/equity update quickly.
- leaderboard/scoring should not wait for wall-clock `T+1`.
- runs should remain deterministic under seeded fault policies.
- post-trade failures should still be gameplay-relevant when enabled.

Recommended game accounting:

- headline equity uses settled plus haircut-adjusted pending value; in instant-post-trade happy paths, most matched trades should become settled in the same tick, so pending value is mainly for in-flight, failed, or held obligations.
- scoring haircut defaults: `100%` for same-tick pending, `50%` for held or repair-pending obligations, `0%` for rejected or voided obligations.
- unsettled obligations should be visible separately.
- fails should carry score penalties and blocked unsettled value first.
- clearing rejects and aged fails should affect bot health/admin controls.
- final run reports should show gross trades, netted obligations, settlement outcomes, repairs, penalties, and replay evidence.

## Deployment And Profile Modes

Settlement and clearing behavior must be configurable independently from Bot Arena. A user should be able to run Reef as a realistic venue/post-trade platform without enabling simulation-game services.

Required profile resolution order:

1. scenario/run override, when a simulator or arena run explicitly selects a post-trade profile.
2. venue/session override, when an operator configures one venue session differently from the platform default.
3. platform default, from environment/config/admin policy.
4. hard default: `ops-realistic-v1`.

Recommended platform config:

```text
POST_TRADE_PROFILE=ops-realistic-v1
```

Recommended profile modes:

| Mode | Post-trade profile | Purpose | Bot Arena required |
| --- | --- | --- | --- |
| `ops-realistic-platform` | `ops-realistic-v1` | Standalone real-like platform and post-trade demo | no |
| `simulation-local` | scenario-selected | Deterministic local scenario testing | no |
| `bot-arena` | `instant-post-trade-v1` by default | Fast game feedback, leaderboards, bot scoring | yes |
| `venue-core-only` | none or deferred | Matching/ingress throughput tests before post-trade expansion | no |

Rules:

- Bot Arena is one consumer of the platform, not a required dependency for settlement.
- `instant-post-trade-v1` is a normal policy profile that can be selected by simulator/game runs, but it must not become a simulator-only shortcut.
- `ops-realistic-v1` should be the default for standalone platform operation.
- Every trade, obligation, ledger posting, and scenario/run report must record the effective post-trade profile and policy version.
- Runtime profile validation should fail closed when a non-local deployment leaves post-trade profile selection implicit.
- Current implementation has partial calendar/settlement-cycle admin configuration, seeded durable post-trade profile controls, durable scenario/run and venue/session profile overrides, non-local `POST_TRADE_PROFILE` boot validation, a profile resolver with scenario/run, venue/session, platform, environment, and hard-default precedence, the P2 settlement fact slice with profile evidence fields, and a replayable trade-to-settlement obligation materializer.
- The current materializer creates deterministic settlement instructions and attempts for `instant-post-trade` obligations. If no opening resource facts are seeded for a scenario, instant mode remains unconstrained for fast simulation. If resource facts are seeded, it checks buyer cash and seller securities before finality, emits failed leg outcomes plus a break on insufficiency, and only writes append-only ledger proof entries plus `SETTLED` facts when both legs pass. A posted repair unlocks the next deterministic attempt number; a successful retry writes ledger proof, `SETTLED`, and `RESOLVED` facts. It leaves `ops-realistic` obligations waiting for explicit future lifecycle steps.
- The first instant-post-trade finality implementation is gross-per-trade only. It proves both legs and four ledger entries per settled trade, derives replayable account/asset balance and settlement-proof views from those entries, and now emits a minimal auto allocation, confirmation, and affirmation fact chain before the settlement instruction. It does not yet apply micro-batch netting or create clearing/novation records.
- Near-term adjustment from standards review: keep `SettlementInstructionCreated` before `SettlementAttemptStarted`, use `SETTLED` only for financial finality after leg/ledger proof, and leave `RESOLVED` for exception/case closure.
- The next bounded post-trade sprint is [`POST_TRADE_LIFECYCLE_SPRINT.md`](./POST_TRADE_LIFECYCLE_SPRINT.md). It should add clearing/novation facts, an exception queue v1, operator-readable lifecycle state, and scenario evidence for instant happy path, cash fail/repair, security fail/repair, and ops-realistic pending behavior. It must not expand into full CCP/CNS clearing, production custody, or complete netted obligation settlement.

Policy storage:

- environment/config default: `POST_TRADE_PROFILE`
- admin policy table: `admin.post_trade_profiles` for seeded/operator-managed profile definitions and active platform default
- scenario/run override: `runtime.reference_scenario_runs.post_trade_profile_id`
- venue/session override: `runtime.reference_venue_sessions.post_trade_profile_id`
- explicit scenario/request override: scenario definition or settlement fact field `postTradeProfileId`
- evidence field on obligations/ledger entries: `postTradeProfileId` and `postTradePolicyVersion`

Obligation materialization:

- gateway fact append command: `POST /admin/v1/settlement/facts`
- gateway materialization command: `POST /admin/v1/settlement/obligations/materialize`
- gateway cash repair command: `POST /admin/v1/settlement/repairs/cash`
- gateway security repair command: `POST /admin/v1/settlement/repairs/security`
- gateway force-settle command: `POST /admin/v1/settlement/force-settle`
- gateway reverse-ledger command: `POST /admin/v1/settlement/reverse-ledger-entry`
- local-only aliases remain available under the same `/internal/admin/settlement/...` suffixes when `PLATFORM_INTERNAL_HTTP_MODE` allows them
- input: `scenarioRunId`/`runId`, optional `venueSessionId`
- source: persisted runtime trades plus accepted buy/sell orders
- deterministic obligation id: `settlement-obligation-{tradeId}`
- deterministic instant instruction id: `settlement-instruction-settlement-obligation-{tradeId}-1`
- deterministic instant attempt id: `settlement-attempt-settlement-obligation-{tradeId}-1`
- repaired instant retry id shape: `settlement-attempt-settlement-obligation-{tradeId}-{attemptNumber}`
- deterministic instant settlement id: `settlement-final-settlement-obligation-{tradeId}`
- deterministic instant allocation id: `settlement-allocation-settlement-obligation-{tradeId}`
- deterministic instant confirmation id: `settlement-confirmation-settlement-obligation-{tradeId}`
- deterministic instant affirmation id: `settlement-affirmation-settlement-obligation-{tradeId}`
- minimal post-trade chain: allocation references the settlement obligation, canonical trade id, canonical buy/sell order ids, and buyer/seller accounts; confirmation references the allocation and obligation; affirmation references the confirmation, allocation, and obligation; the first settlement instruction is caused by the affirmation
- ledger proof: buyer cash debit, seller cash credit, seller security debit, buyer security credit
- cash amount: venue fixed-point price nanos multiplied by quantity units
- idempotency: settlement fact store primary keys and merge validation make repeat materialization safe
- query surface: `GET /api/v1/settlement/obligations/{scenarioRunId}` returns current obligation state projected from facts
- ledger query surface: `GET /api/v1/settlement/ledger/{scenarioRunId}` returns replayable participant/account/asset balances plus per-settlement proof totals derived from append-only ledger facts
- proof query surface: `GET /api/v1/settlement/proof/{scenarioRunId}` returns one replay proof with trade/obligation/attempt/ledger identifiers, final balances, settlement proof rows, profile/policy evidence, `CLEAN`/`GAPPED` proof status, causation-gap checks, fact counts, and a deterministic checksum
- settlement facts/proof now expose allocation, confirmation, and affirmation counts and identifiers for the minimal instant-post-trade chain
- score query surface: `GET /api/v1/settlement/score/{scenarioRunId}` returns participant scoring inputs from the same facts: settled balances, pending value, haircut-adjusted pending value, blocked unsettled value, fail counts, aged-fail counts, repair-pending counts, and penalty points; optional `asOf` and `agedFailAfterSeconds` query parameters let scenario-clock checks age open fails deterministically
- resource seeding: `resourcePositions` in the settlement facts endpoint establish opening participant/account/asset availability for realistic instant-mode checks
- constrained instant failures: insufficient buyer cash emits a `CASH` leg outcome with `LEG_FAILED` and a `CASH_LEG_FAILED` break; insufficient seller securities emits a `SECURITY` leg outcome with `LEG_FAILED` and a `SECURITY_LEG_FAILED` break; failed attempts do not emit settlement ledger entries or `SETTLED` facts
- repair reattempts: a `SettlementRepairPosted` fact against the open break lets the next materialization create attempt `N+1`; successful repaired attempts emit the normal four ledger entries, one `SETTLED` fact, and a `SettlementResolved` fact for the repaired break
- repair command behavior: the first command paths post either buyer cash or seller security `resourcePosition` facts plus `SettlementRepairPosted` against an existing break; they require `scenarioRunId`, `settlementBreakId`, `accountId`, `actorId`, and deterministic `occurredAt`, and can default participant, asset, quantity, profile, and policy version from the broken obligation
- repair actions: cash breaks use `POST_CASH_LEG_REPAIR`; security breaks use `POST_SECURITY_LEG_REPAIR`
- operator actions: force-settle and reverse-ledger-entry write `SettlementOperatorAction` audit facts with required `reasonNote`, `actorId`, and deterministic `occurredAt`
- force-settle behavior: posts an operator action plus the needed resource/repair facts, then re-runs materialization so finality still goes through normal instruction, attempt, leg outcome, ledger proof, `SETTLED`, and `RESOLVED` facts
- reverse-ledger-entry behavior: posts an operator action plus one opposite-direction ledger entry on a new operator attempt, preserving the original settlement proof row while adjusting replayable balances through append-only compensation

## Data Model Direction

Likely settlement/clearing tables:

- `settlement.allocations`
- `settlement.confirmations`
- `settlement.affirmations`
- `settlement.clearing_records`
- `settlement.novations`
- `settlement.netting_batches`
- `settlement.netted_obligations`
- `settlement.obligations`
- `settlement.obligation_legs`
- `settlement.instructions`
- `settlement.attempts`
- `settlement.breaks`
- `settlement.repairs`
- `settlement.exception_cases`
- `admin.post_trade_profiles`
- `runtime.reference_scenario_runs`
- `account.ledger_entries`

Keep current-state tables separate from append-only events/facts. Read models for UI/game scoreboards should be rebuildable.

## Event Model Direction

Initial events to model:

- `TradeAllocated`
- `ConfirmationGenerated`
- `AffirmationAccepted`
- `AffirmationTimedOut`
- `ClearingSubmitted`
- `ClearingAccepted`
- `ClearingRejected`
- `NovationRecorded`
- `NettingBatchCreated`
- `NettedObligationCreated`
- `SettlementObligationCreated`
- `SettlementInstructionCreated`
- `SettlementAttemptStarted`
- `SettlementCashLegSettled`
- `SettlementSecurityLegSettled`
- `SettlementFailed`
- `SettlementPartiallySettled`
- `SettlementAgedFailRecorded`
- `SettlementRepairPosted`
- `SettlementResolved`
- `LedgerEntryPosted`

Current instant-post-trade settlement facts map into this direction:

```text
SettlementObligationCreated
  -> SettlementAllocationProposed
  -> SettlementConfirmationGenerated
  -> SettlementAffirmationAccepted
  -> SettlementInstructionCreated
  -> SettlementAttemptStarted
  -> SettlementFailed(reason=CASH_LEG_FAILED)
  -> SettlementRepairPosted(action=POST_CASH_LEG_REPAIR or POST_SECURITY_LEG_REPAIR)
  -> SettlementResolved
```

## Implementation Slices

1. Document and implement post-trade policy profiles.
   - `ops-realistic-v1`
   - `instant-post-trade-v1`
   - platform default/venue override/scenario override resolution
   - persisted policy version on every run and obligation

2. Replace the P2-only fact chain with a small settlement domain service.
   - consume canonical trade facts
   - create obligation, failure, repair, resolution events
   - keep current P2 assertion compatibility

3. Add instant clearing and gross settlement before full settlement expansion.
   - settlement instruction facts before settlement attempts
   - auto clearing submission/acceptance facts
   - novation facts
   - gross obligation settlement first
   - cash/security leg outcomes and append-only ledger before `SETTLED`
   - deterministic per-tick micro-batch netting as follow-up
   - netting policy version on obligations once netting is enabled

4. Add exception queue v1.
   - project `CASH_LEG_FAILED`, `SECURITY_LEG_FAILED`, `AFFIRMATION_TIMEOUT`, and `CLEARING_REJECT`
   - include severity, owner role, SLA state, actor, correlation id, and repair action
   - close queue items only through append-only repair/resolution facts

5. Add account ledger postings.
   - one typed append-only ledger for cash and securities
   - synchronous ledger posting inside the instant-post-trade tick
   - settled/pending/blocked balances as projections
   - compensating repair entries

6. Add full failure matrix.
   - affirmation timeout
   - clearing reject
   - cash leg fail
   - security leg fail
   - partial settlement
   - aged fail

7. Add game/report projections.
   - settled cash/securities
   - unsettled obligations
   - fail penalties
   - netting compression
   - settlement replay proof
   - one JSON scenario proof report with trade IDs, obligation IDs, ledger entry IDs, final balances, causation chain, and checksum
   - current implementation has proof and score read projections for gross settlement, including derived aged-fail penalties from scenario clock; netting compression remains follow-up work

## Discussion Points

Resolved choices:

1. use `instant-post-trade-v1` in public docs/API; keep `clear-lite` only as informal shorthand.
2. leaderboard equity uses settled plus haircut-adjusted pending value; happy-path instant settlement leaves little pending exposure.
3. clearing/netting cadence is per simulator tick.
4. instant-post-trade failures are opt-in per scenario/fault profile.
5. cash-leg failure is the first failure path; security-leg failure, clearing reject, and affirmation timeout follow.
6. v1 may settle gross obligations first; micro-batch netting follows.
7. broker-level accounts come first; customer subaccounts are deferred.
8. one typed `account.ledger_entries` table holds cash and securities.
9. fail penalties start with score penalty plus blocked unsettled value.
10. force-settle and reverse-ledger actions require reason note in v1; dual control can follow.
11. bots can see own settled cash, own positions, own pending obligations, and own fails, without counterparty detail.
12. first settlement slice can stay Kotlin-internal; protobuf contracts follow after shape stabilizes.
13. use ISO 20022-inspired names only; do not import heavy external schemas.
14. settlement deadlines use scenario clock; runtime operational metrics use wall clock.
15. scenario lock proof requires trade-to-ledger replay evidence with no causation gaps.
16. pending-value haircut defaults are `100%` same-tick pending, `50%` held or repair-pending, and `0%` rejected or voided.
17. same-tick ordering is `match -> persist trade -> post-trade -> ledger -> score -> projections`.
18. blocked unsettled value reduces buying power but does not fully disable trading by default.
19. fault profiles start as scenario-level defaults; participant/instrument overrides can follow.
20. first admin/operator repair commands are `post-cash-repair`, `force-settle`, and `reverse-ledger-entry`, all with required reason note.
21. settlement state naming uses `SETTLED` for financial finality and `RESOLVED` for exception/case closure; P2 `RESOLVED` maps to exception closure after the settlement ledger state is proved.
22. instant-post-trade writes ledger postings synchronously in the scenario tick; UI/read projections can lag with visible freshness metadata.
23. non-local standalone platform boot should require explicit `POST_TRADE_PROFILE`.
24. first admin policy commands are `post-trade-profile-upsert`, `post-trade-profile-list`, and `post-trade-profile-activate`.
25. scenario proof artifact is one JSON report with trade IDs, obligation IDs, ledger entry IDs, final balances, causation chain, and checksum.

## Recommended Next Step

Start with `instant-post-trade-v1` plus P2 compatibility:

```text
canonical trade
  -> auto allocation/confirmation/affirmation
  -> auto clearing acceptance/novation
  -> gross or micro-batch netting
  -> settlement obligation
  -> seeded cash-leg fail
  -> repair
  -> synchronous ledger postings
  -> settled ledger state and resolved exception state
  -> score calculation
  -> async read projections
```

This gives the game near-instant results while preserving the architecture needed for realistic settlement later.
