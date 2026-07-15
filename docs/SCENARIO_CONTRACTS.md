# Scenario Contracts

## Purpose

Lock the first deterministic lifecycle scenarios so implementation, replay checks, smoke artifacts, and planning all assert the same behavior.

Live lock criteria and assertion report requirements live in [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md).

These contracts are stricter than the current YAML fixtures where noted. If a YAML scenario differs from this document, update the YAML and golden artifacts deliberately rather than treating the difference as implementation freedom.

## P1_GOLDEN_HIDDEN_CROSS_T1

Status: live-asserted and replay-asserted locally, with projection-freshness policy still open. `packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml` and the checked-in dry-run smoke golden model the hidden-resting-order path. The 2026-07-14 local live assertion report at `reports/scenario-assertions/p1-golden-hidden-cross-live-20260714.json` passed with 25 assertions: command completion, lifecycle/fill reads, scenario-scoped trade tape checks from the instrument tape, public depth non-leakage, read-surface inventory, and zero projection lag. The 2026-07-15 direct-stream same-run report at `reports/scenario-assertions/p1-golden-hidden-cross-replay-live-20260714.json` passed with 44 assertions and attaches `reports/scenario-assertions/p1-golden-hidden-cross-replay-check-20260714.json`: run-scoped command status from `platform-projector-0`, exact replay counters for the same three P1 commands, native hidden-depth `visibilityTimeline`, and replay-folded historical visibility proof. It records `/api/v1/data/availability` lag rows of `4`, so P1 still needs an explicit decision or clean-stack rerun for zero-lag projection freshness if that is required for the final lock.

Purpose: prove core venue lifecycle with hidden liquidity, partial fill, full fill, public market-data visibility rules, and deterministic replay.

### Actors

- Participant A: hidden seller
- Participant B: first visible buyer
- Participant C: second visible buyer

### Command Sequence

1. Participant A submits hidden resting sell:
   - instrument: `XYZ`
   - side: `SELL`
   - quantity: `100`
   - limit price: `100.00`
   - visibility: hidden
2. Participant B submits visible buy:
   - instrument: `XYZ`
   - side: `BUY`
   - quantity: `40`
   - limit price: `101.00`
   - expected result: fills `40` against hidden resting sell at resting price `100.00`
3. Participant C submits visible buy:
   - instrument: `XYZ`
   - side: `BUY`
   - quantity: `60`
   - limit price: `100.00`
   - expected result: fills remaining `60` against hidden resting sell at resting price `100.00`
4. Scenario completes after venue facts, order lifecycle, trade tape, and replay assertions pass.

### Expected States

- hidden sell order: `OPEN -> PARTIALLY_FILLED -> FILLED`
- first visible buy: `FILLED`
- second visible buy: `FILLED`
- visible top-of-book/depth: must not expose the hidden resting sell size before execution
- owner own-order reads: hidden seller may see its own hidden order and lifecycle state
- trade tape: shows both trades without counterparty identity

### Expected Facts

- exactly two executions
- exactly two trades
- total executed quantity: `100`
- execution price for both trades: `100.00`
- all scenario-driven facts carry `scenarioRunId`, `correlationId`, and `causationId`
- replay produces identical command outcome order, execution/trade facts, order lifecycle states, and checksums

### Gate Level

P1 is a hard correctness gate. It must not rely on stubbed settlement or post-trade behavior.

## P2_SETTLEMENT_BREAK_REPAIR

Status: live-asserted and replay-asserted locally, with one direct-stream read-surface caveat. The first settlement-break fixture uses `CASH_LEG_FAILED` by decision; `SSI_MISMATCH` remains a later confirmation/standing-instruction scenario, not the first P2 contract. The 2026-07-14 local command smoke report at `reports/scenario-assertions/p2-settlement-break-repair-live-20260714-command-smoke.json` passed, and the 2026-07-14 local assertion report at `reports/scenario-assertions/p2-settlement-break-repair-live-20260714.json` passed with 18 assertions covering command completion, one obligation, one `CASH_LEG_FAILED` break, one repair, one resolution, repair-linked causation, and scenario-run scoping. The 2026-07-15 direct-stream settled-chain report at `reports/scenario-assertions/p2-settlement-break-repair-settled-live-20260715b.json` passed with 37 assertions and attaches `reports/scenario-assertions/p2-settlement-break-repair-replay-check-20260715b.json`: run-scoped command status from `platform-projector-0`, exact replay counters for the same two P2 commands, and the minimal `instant-post-trade-v1` obligation/allocation/confirmation/affirmation/instruction/attempt/leg/ledger/`SETTLED` fact chain from `reports/scenario-assertions/p2-settlement-break-repair-settled-facts-20260715b.json`. The direct-stream stack returned 404 for the public settlement facts read, so the settled-chain proof is currently attached from the accepted local settlement-facts append artifact rather than `/api/v1/settlement/facts/{scenarioRunId}`.

Purpose: prove post-match causation from canonical trade fact through settlement obligation, cash-leg break, manual repair, and resolved exception without building full post-trade/account-ledger modules yet.

### Inputs

P2 should consume or recreate one canonical trade equivalent to P1's completed trade facts. It must reference canonical venue facts rather than mutating matching history.

### Command/Event Sequence

1. Canonical trade fact exists.
2. Settlement obligation is created:
   - buyer owes cash
   - seller owes shares
3. Fault injection fails the cash leg:
   - break reason: `CASH_LEG_FAILED`
4. Settlement enters break state:
   - `OBLIGATION_CREATED -> CASH_LEG_FAILED -> BROKEN`
5. Operator/manual repair is posted:
   - `REPAIR_POSTED`
   - first version was a simulated/manual repair event with no real account ledger mutation required; `D-050` has since authorized real ledger mutation broadly, and obligation materialization now writes real append-only ledger entries for settled instant-post-trade obligations (see [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md#obligation-materialization))
6. Settlement resolves:
   - `RESOLVED`
   - use `SETTLED` only after full settlement/account-ledger modules exist

### Expected Facts

- one settlement obligation references one canonical trade
- one break is opened with reason `CASH_LEG_FAILED`
- one repair action is posted by a user/operator actor
- final exception state: closed/resolved
- final settlement state: `RESOLVED`
- no direct transition from failed cash leg to resolved without repair
- all scenario-driven facts carry `scenarioRunId`, `correlationId`, and `causationId`

### Gate Level

P2 is a scenario contract gate. The first implementation may use lightweight settlement/break/repair facts, but causation must be explicit:

```text
trade -> obligation -> break -> repair -> resolved
```

P2 did not by itself authorize broad post-trade expansion. `D-050` has since accepted that broader expansion — including real account ledger mutation via obligation materialization — for the `instant-post-trade` profile. Full allocation/confirmation workflows and operational exception workbenches remain later work unless separately planned; see [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md) for current scope.

Post-trade implementation may resume only through the P2-only settlement exception slice defined in [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md#post-trade-re-entry-criteria).

## Implementation Alignment Tasks

1. Decide whether the promoted P1 same-run report's recorded projection lag is acceptable for lock, or rerun P1 on a stack expected to be globally drained with `--require-zero-projection-lag`.
2. Decide whether P2 direct-stream lock requires the public settlement facts read to be available in that profile, or whether the accepted local settlement-facts append artifact is sufficient for the settled-chain proof.
3. Keep these scenarios on public command/API paths where platform functionality exists; any stubbed post-trade action must be clearly marked in the scenario and report.
