# Calcify — post-match redesign discovery

Status: discovery in progress; Phase 1 outline and match-commitment meaning agreed, implementation details open.
Recorded: 2026-09-28 America/Toronto (2026-09-29 UTC).
Base: `origin/master` at `368a9247b31c8997c5dd9f51aeb88a911adebfe7` (rollback PR #428 merged).
Branch: `codex/calcify-discovery`.

## Purpose and working assumptions

Calcify names Reef's fresh, full post-matching design effort. This is a working assumption to confirm. Goal: specify complete post-match product semantics, authority, data flow, failure behavior, read contracts, restore, and capacity proof before choosing implementation. This document records questions and a proposed logical flow; it is not an instruction to resume the retired implementation.

Existing Reef invariants remain in force unless a later explicit decision amends them: deterministic execution and replay; durable acceptance before `202`; same-lane matching order; immutable matching facts separate from rebuildable reads; auditability and idempotency; simulator use of normal command paths. Earlier redesign scope held Go matching, ingress, pretrade, and matching-outcome format fixed. Confirm those boundaries during Calcify discovery instead of silently expanding them.

## Starting state and evidence

- Rollback PR #428 removed the redesign merged through #406. Active baseline again uses committed venue outcomes, existing Kotlin runtime/lifecycle/market projections, and the trade-to-settlement materializer with normalized lifecycle and ledger facts. `docs/WORK_PLAN.md` is a dated execution snapshot, not Calcify approval.
- C5 qualified venue-core accepted/direct-acked/materialized throughput near 10,000 commands/s in two 300-second samples with projections off. C2 failed sustained 5,000/s full projection, while historical 2,500/s full projection closed. These are different stages and versions; see [`docs/THROUGHPUT_BASELINES.md`](../THROUGHPUT_BASELINES.md).
- PM-S3 treatment accepted/direct-acked 2,776,548 commands in 300 seconds (9,254.07/s) but post-match completion lagged; control/treatment were not a matched comparison. Approximate stage samples do not establish exact final correctness or a single root cause.
- Retired journal candidate had no measured hosted 10,000/s run. One local 50/s, 60-second run accepted/materialized 2,999/2,999 and matched 1,044 first trades only after drain. An instrumented repeat spent 42.66/59.83 loaded writer seconds in synchronous broker-gap checks and 8.66 seconds in repeated full-prefix controls; source-to-journal lag grew in load. Market API's growing replay/proof failed read latency/availability. These observations identify that implementation's hot spots, not a general limit on journals or PostgreSQL.
- Prior synthetic 640-result append timing tested only a write shape. Neither it nor stopped-source drain proves complete, concurrent capacity. Preserve failed-run records and compare matched code/config/workload/host/observer in future tests.

Historical evidence: [projection review](https://github.com/dills122/reef/blob/26efef7bf8c2ad30908fff76dc07b30e0e757924/docs/research/POST_MATCH_PROJECTION_ARCHITECTURE_REVIEW_2026-09-26.md), [institutional research](https://github.com/dills122/reef/blob/26efef7bf8c2ad30908fff76dc07b30e0e757924/docs/research/POST_MATCH_INSTITUTIONAL_SETTLEMENT_DESIGN_RESEARCH_2026-09-28.md), [account arbitration spike](https://github.com/dills122/reef/blob/26efef7bf8c2ad30908fff76dc07b30e0e757924/docs/research/POST_MATCH_ACCOUNT_ARBITRATION_SPIKE_2026-09-27.md), [journal trial postmortem](https://github.com/dills122/reef/blob/26efef7bf8c2ad30908fff76dc07b30e0e757924/docs/research/evidence/post-match-ledger-trial-postmortem-2026-09-29.md), [retirement handoff](https://github.com/dills122/reef/blob/26efef7bf8c2ad30908fff76dc07b30e0e757924/docs/work/handoffs/2026-09-29-post-match-redesign-retirement.md). These are evidence archives, not accepted Calcify design.

## Proposed logical flow for review

```mermaid
flowchart LR
    M[Committed matching outcomes] --> V[Incremental source coverage and identity]
    V --> I[Verified post-match input]
    I --> K[Market and tape projection]
    I --> P[Private execution projection]
    I --> W[Trade workflow]
    C[Versioned calendar policy opening funding and repair inputs] --> W
    W --> O[Gross detail and eligible obligations]
    O --> A[Durable account arbitration]
    A --> S[Settlement decision]
    C --> S
    S --> F[Durable financial authority]
    F --> R[Balance status and history projections]
    I --> X[Reconciliation]
    F --> X
    K --> X
    P --> X
    R --> X
```

Proposal, not decision: source reader proves contiguous partition coverage and exact bytes once, then independently checkpointed consumers process verified input. Routine API reads use indexed projections, never full-prefix replay. Market tape follows committed execution even while settlement is pending or failed. Workflow records each visible allocation/confirmation/affirmation/clearing/novation/obligation transition under versioned policy. Gross execution detail survives any netting. Settlement fixes scarce-account order durably, evaluates both DvP sides against authoritative account state, and atomically records settled effects or typed pending/failure result. Financial commit defines finality; projection arrival does not. Combined reads expose distinct matching and financial as-of positions. Repair/retry adds facts; it does not rewrite execution or prior failure.

## Phase 1 — match commitment

Agreed high-level path: matching engine publishes `VenueEventBatch` to its durable venue-event log; a commitment extractor reads those batches and publishes one `MatchCommitment` per `TradeCreated` to a new durable commitment log; a verifier publishes verified commitments to a durable inbox; a temporary settlement worker consumes inbox records and writes idempotent PostgreSQL receipts. Exact contracts, verification rules, inbox form, and worker failure behavior remain to be designed. Receipt is not financial settlement.

Agreed meaning: `MatchCommitment` states that, according to the matching engine's current facts, a match was made and durably committed. It does not assert verification, participant acceptance, or settlement. Matching output remains `VenueEventBatch`; the extracted commitment is a post-match representation of one trade fact. The source event retains authority for what matching decided.

Agreed extractor handoff: read a committed `VenueEventBatch`, emit one `MatchCommitment` per `TradeCreated` in source order, and publish those records plus the extractor's consumed source offset in one Redpanda transaction. A batch with no trades advances the source offset without a commitment. This follows the existing matching-engine pattern of atomically publishing `VenueEventBatch` and committing its command offset. The transaction covers this broker handoff only; commitment identity and downstream idempotency still need design.

Next design point: specify commitment identity, source provenance, and ordering contract. Then define verification and inbox semantics.

Logical authority and physical storage are separate decisions. Evaluate compact relational transactions, durable ordered log/state-machine designs, and a specialist ledger plus explicit cross-store protocol. PostgreSQL journal is neither presumed nor excluded. A single shared account may impose a real ordered decision floor; batch preparation and durable output must be measured without changing scarce-resource winners.

## Decision register — all open

| ID | Decision to make | Acceptance question |
| --- | --- | --- |
| CAL-01 | Product profiles and obligation semantics | Gross instant DvP, scheduled/netted obligations, partial settlement, pending/fails, repair, and actor permissions: which are required now? |
| CAL-02 | Scope at matching boundary | Can source outcome/coverage contract change, or must Calcify add a sidecar without touching matching output? |
| CAL-03 | Canonical source and control inputs | How are empty ranges, source generations, policy/calendar versions, opening balances, funding, and repair durably ordered and authenticated? |
| CAL-04 | Scarce-account winner and replay | Must matching source alone reproduce cross-partition winners, or is retained post-match admission order canonical? |
| CAL-05 | Financial finality and idempotency | Which commit acknowledges settlement; what exact identity makes ambiguous retry safe; how are four effects or typed breaks atomic? |
| CAL-06 | Read contracts | Required market, participant, financial, and audit fields, access controls, as-of meaning, freshness, response latency, and cross-store presentation? |
| CAL-07 | Recovery and trust | How do crash, stale owner, tamper, missing source, source-only restore, target-only restore, and retention changes fail or recover? |
| CAL-08 | Physical authority | Which storage/runtime topology meets correctness and capacity at fixed resource cost? What falsifies each candidate? |
| CAL-09 | Capacity envelope | Required commands/s, trades/s, hot-account skew, instrument mix, run age, read traffic, failure mix, duration, and headroom? |
| CAL-10 | Cutover and compatibility | What API/storage compatibility, shadow parity, rollback, and migration evidence must precede traffic switch? |

Decision status changes only through reviewed Calcify specification and relevant Reef decisions/contracts. Record alternatives, rationale, and tests with each choice.

## Discovery and delivery phases

1. **Product model:** write state machine for both profiles, obligations, netting, partial/fail/repair, and finality. Gate: signed examples and counterexamples for executed-but-unsettled trade.
2. **Authority and order:** specify source/control provenance, exact coverage, cross-partition arbitration, idempotency, and retention. Gate: deterministic scarce-winner and changed-input replay fixtures.
3. **Read and recovery:** define as-of APIs, private/market/audit views, rebuild, fencing, tamper, and independent restore policy. Gate: complete failure matrix and bounded-read design.
4. **Candidate selection:** compare storage/runtime options with same logical contract and fixed workload. Gate: choose small, falsifiable vertical proofs; record rejected alternatives.
5. **First vertical slice:** one source cohort through authority, DvP or typed failure, one market view, one financial view, and reconciliation. Gate: exact values and fault/replay parity before rate claims.
6. **Incremental scale ladder:** local hot/diverse cohorts; aged history; sustained load; mixed reads; fault/restart under load; then matched disposable hosted run. Gate at each step: accepted, materialized, source-covered, workflow-admitted, financially final, and projected rates/frontiers plus in-load backlog slope, API freshness/latency, WAL/rows/CPU/I/O, and complete closed-cohort parity.
7. **Cutover design:** contract migration, shadow comparisons, operator runbook, rollback, and qualification. No production switch implied by discovery or diagnostic pass.

Every executed run gets code/config/workload/host/observer/artifact identity, success and failure record, and explicit correction history per throughput ledger. Numeric targets and exact commands remain open until CAL-09 and candidate topology are agreed. No benchmark has been run for Calcify.

## Next discussion

Use presentation to resolve CAL-01 through CAL-04 first. Then work through remaining decisions and examples before implementation planning. This document should evolve as decisions are reviewed; current flow remains a hypothesis.
