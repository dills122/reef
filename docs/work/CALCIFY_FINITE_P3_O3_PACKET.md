# Calcify finite P3 — O3 financial admission packet (DRAFT)

Date: October 11, 2026. Status: **draft for independent review; nothing here is accepted, implemented or
qualified**. Baseline: `master` at `85115ac80` (O1 PR485 and O2 PR488 merged). Authority boundary: no
production financial authority, no capacity claim, no legacy removal. Governing sources:
[source contract](CALCIFY_FINITE_P3_SOURCE_CONTRACT.md) (O3 row, numeric budgets), the
[overnight plan](CALCIFY_OVERNIGHT_SESSION_PLAN.md) O3 row, the [system RFC](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md),
and the [O2 verification record](../evidence/calcify-finite-p3/2026-10-11-o2-verification.md) for what is and is not proven.

## 1. Objective and acceptance (from the contract, restated)

Turn each captured `READY_EXECUTION` trade into exactly one balanced, once-only financial obligation in an isolated
Calcify namespace, settled by unreserved gross delivery-versus-payment (DvP), with funding and repair as
authenticated controls in the **same durable input order** as source-derived inputs.

Observable acceptance (each becomes a named test in section 8):

- Opening: buyer quote 0, seller security 6; the three fixture trades are captured **pending with zero ledger legs**.
- Funding buyer 600,000,000,000 quote nanos plus **three distinct repair attempts** settles all three trades **once**.
- Identical retry of any action returns the original disposition and creates no new attempt; a changed retry with
  the same action key is a conflict that changes nothing.
- A `REPLAY` capture member or an identical duplicate business execution never creates a second obligation.
- Every decision has balanced integer legs per asset (sum 0) and no balance below zero except opening accounts.
- Restore from checkpoint plus suffix, and full replay from history, produce identical state.

## 2. Decisions

| ID | Decision | Status |
| --- | --- | --- |
| D1 | **Generalize accounts/assets.** The module is typed over account and asset identifiers taken from the registered opening (`parties[].accountId`, `quoteCurrency`, `baseAssetId`), not the reference kernel's literals `buyer`/`seller`/`USD_NANO`/`ACME_SHARE`. The fixture is one 2-account x 2-asset instance. Negative tests must fail any hidden fixed-name assumption (swapped roles, renamed assets, a third account that must be refused). | Accepted by owner, Oct 11 |
| D2 | **Unreserved gross DvP only.** No reservations, netting, partial settlement, or external effects. | Accepted by owner, Oct 11 |
| D3 | **Durable credit redesign out of scope.** | Accepted by owner, Oct 11 |
| D4 | **Funding/repair are control actions** issued by an authenticated operator principal (the reference kernel's `opening-resource-owner` authority), never by trading principals; permitted while the run is `ACTIVE` **or** `STOPPED` (closure of already-admitted work needs them after stop); charged to the reserved control capacity (8 controls) and to the 160-attempt financial budget. | **Proposed default; needs explicit owner confirmation** |
| D5 | Budgets as in the contract: 128 source executions (16 publications x 8) plus 8 controls; <=160 admitted financial attempts; <=320 result/history records; 4 positions (2 accounts x 2 assets); <=256 obligations before retirement. | Accepted by owner, Oct 11 |
| D6 | **Two delivery units, declared now:** O3a pure typed module (no I/O) and O3b durable adapter and order integration. Each has its own cap of 3 independent reviews; O3b starts only after O3a is accepted. Declaring the split up front is not review-cap evasion. | Accepted by owner, Oct 11 (as recommended) |
| D7 | **Common durable order mechanism and topic registration** (section 5). | **OPEN; blocks O3b; needs owner decision** |

## 3. What O3 receives from O1/O2

- Capture envelopes (`FiniteLifecycleCaptureV1`, CAPTURE topic): each `LifecycleMemberV1` with disposition
  `READY_EXECUTION` carries the trade fact (`executionId`, buy/sell order ids, quantity units, price nanos, currency),
  its two `ExecutionCreated` rows, and both order dependencies (immutable acceptance with `participantId` and `accountId`).
  Accounts for a trade are therefore derived from the two acceptances and checked against the opening's `parties`.
- `REPLAY` members reference the first capture and must be ignored for financial purposes (contract: O3 owns the
  disposition of an identical duplicate business execution: no second obligation).
- Business execution identity is `(runId, venueSessionId, instrumentId, executionId)`; physical source provenance
  `(generation, topic UUID, partition, offset, outcome ordinal)` is audit data, not identity.
- Capture is not settlement: O1/O2 prove source capture only.

## 4. Reference kernel map (test-only `financial/FinancialKernel.kt`, 412 lines, plus the 265-line independent oracle)

| Reference behaviour | O3 disposition |
| --- | --- |
| Action key `(namespace, domain, actionId)` and normalized-input digest; same key and digest returns `PRIOR_RESULT` with no new attempt; same key with a different digest is `ACTION_CONFLICT` | **Carry**, as typed values with explicit canonical encoding (no ad hoc JSON strings) |
| Decision = atomic set of before/after changes with hash-chained history, validate-then-apply, replay and checkpoint-plus-suffix restore | **Carry the semantics**; redesign the representation (section 6) |
| `CAPTURE` creates a pending obligation, due-queue entry, workflow/instruction rows | **Carry the obligation; drop** workflow/instruction/due-queue/clock/continuation (no scheduling in unreserved gross DvP for the finite fixture) |
| `SETTLE` attempt number keyed; `INSUFFICIENT_*` becomes an exception; `ALREADY_SETTLED`; `PRIOR_ATTEMPT`; overflow checked | **Carry** |
| `FUND` with authority `opening-resource-owner`, balanced against an `opening` account | **Carry**, authority per D4 |
| `ACTIVATE_POLICY`, `gross-p1/p2`, staged-input queue, `CLOCK`/`CONTINUE` | **Drop** for O3 (D2); revisit only if staging is needed for D7 |
| Hard-coded buyer/seller/asset literals, `require(buyerAccount=="buyer")` | **Replace** per D1 |
| JSON tree state (string-typed numbers, path-based deltas) | **Replace** with typed Kotlin/protobuf values; signed64 checked arithmetic |
| Whole state as one object | **Do not repeat** (see C1) |

## 5. Open design question D7: where is the single durable order?

The contract requires one durable order shared by source-derived inputs, opening, funding and repair. Three shapes:

- **A. New versioned binding adding a `FINANCIAL_INPUT` (and changelog) topic role.** One transactional writer appends
  both capture-derived executions and control inputs, giving a literal total order on a single partition. Cost: the
  finite binding v1 digest and the five registered topic roles are frozen; this needs a binding **v2**, an additive CHECK
  change like migration `0076`, new golden bytes, and Go/Kotlin parser changes. Heavy, but the cleanest.
- **B. Order established inside the financial processor's own transactional history.** The processor reads CAPTURE and a
  CONTROL topic; each accepted input is assigned the next history position when it is recorded (the reference kernel's
  staging/`position` idea). Replay then uses the recorded history, not re-consumption, so cross-topic arrival order cannot
  change results. Still needs a registered CONTROL topic and a financial changelog role, so it also touches the binding,
  but not the capture path.
- **C. Controls through the O2 ingress adapter.** Reuses existing auth, budget and durability, but mixes trading and
  financial control traffic in the COMMAND stream that the matcher consumes. Likely unacceptable for isolation.

Recommendation: **B**, with the binding extension specified as a small additive v1.1 or v2 in the packet. This is a
contract change and the overnight plan treats a heavy contract pivot as a human gate; it is flagged here, not decided.

## 6. Design constraints for the pure module (O3a)

- **C1: no whole-state value.** G9 showed a single changelog value forced a 6.3 MB transport proof. Use per-entity store
  entries (`position:(account,asset)`, `obligation:executionKey`, `attempt:(executionKey,ordinal)`, `action:key`) and an
  append-only decision record per input. A decision touches a bounded number of entries (target <=16).
- **C2: typed canonical values.** `AccountId`, `AssetId`, `ExecutionKey`, `ActionKey`, `Amount` (signed64 via exact
  arithmetic), `Leg`, `Decision`, `Disposition` (closed enum). Canonical bytes are protobuf with explicit field numbers; the
  digest is SHA-256 over versioned canonical bytes, as in the O1/O2 contracts. No `Map<String,...>` identity.
- **C3: pure and deterministic.** `decide(state, input) -> Decision | Refusal` with no clock, randomness or I/O; the adapter
  applies decisions. Validate everything before any mutation.
- **C4: invariants checked on every decision.** Legs sum to zero per asset; no negative non-opening position; obligation
  residuals never increase; settled is terminal; an execution key is captured at most once.
- **C5: bounds enforced, not assumed.** Decision record, attempt counts and obligation counts are rejected beyond the pinned
  limits; pin serialized sizes by a max-field reflection test (the O1 `captureBytes` approach) before O3b.

## 7. Budgets and bounds to pin before O3b

| Item | Source | To pin in review |
| --- | --- | --- |
| Source executions / controls / admitted attempts / history records | contract: 128 / 8 / 160 / 320 | confirm D5 |
| Positions / obligations | 4 / 256 | confirm |
| Serialized decision record (max) | **not yet derived** | derive with a max-field reflection test; reference kernel used 64 KiB and 64 changes, which is not a requirement |
| Per-entity store entry bytes and total managed state | **not yet derived** | derive; target well under the default broker record ceiling without a bespoke transport proof |
| Physical broker/JVM/native bounds | contract: open | measured preflight, no capacity claim |

## 8. Acceptance tests (names are proposals)

O3a (pure module, no broker, no database): `openingPendingHasZeroLegs`; `fundingThenThreeRepairsSettlesEachTradeOnce`;
`identicalRetryReturnsOriginalDisposition`; `changedRetryIsConflictWithNoMutation`; `replayMemberNeverCreatesObligation`;
`duplicateBusinessExecutionSameEconomicsIsNoop`; `duplicateBusinessExecutionChangedEconomicsIsConflict`;
`insufficientResourcesPendingZeroPartialLegs`; `overflowRefusedAtomically`; `legsBalancePerAssetAlways`;
`noNegativeNonOpeningPosition`; `settledIsTerminal`; `restoreFromCheckpointPlusSuffixEqualsReplay`;
`genericAccountsAssetsNotFixtureNames` (swapped roles, renamed assets, third account refused);
`maxFieldDecisionRecordFitsPinnedBound`.
O3b (adapter, needs D7): crash after commit replays once; control authorization (trading principal refused); order is
identical across restart; budget refusal accounting; exact retry across restart; no hot-path scans or per-input DB round trips.
End-to-end API/SQL rows belong to O4/O5, not here.

## 9. Files and leases (proposed)

New: `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/*.kt` and matching tests;
`contracts/proto/calcify_finance.proto` (additive) with regenerated Go/Kotlin outputs only if D7 requires the Go side; docs.
Shared files only under explicit lease: `contracts/calcify/README.md`, binding contract and migration (D7), `Main.kt`.
Reference-only: `services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/**`.

## 10. Process (from the delivery policy and the O2 lessons)

Test infrastructure stays small: prefer direct tests over bespoke supervisors; cumulative blocker timebox 20 minutes then
escalate; any live broker run needs a separate one-attempt approval; replay hosted-CI steps in a clean checkout; add files
by exact path only. Independent review: fresh context, neutral bootstrap separate from the author explanation, max 3 per unit.

## 11. Exclusions

Reservations, netting, partial settlement, external effects, durable credit redesign, legacy removal, production migration,
SQL/read APIs (O4), end-to-end fixture (O5), throughput/capacity, any financial-authority or adoption claim.

## 12. Decisions needed before this packet can be reviewed

1. Confirm D4 (control principal, allowed states, budget charging).
2. Decide D7 (recommend B) and whether a binding v1.1/v2 is acceptable now.
3. Confirm the serialized-bound derivation method (C5) rather than reusing the reference kernel's 64 KiB.
