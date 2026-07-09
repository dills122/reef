# Bot Arena Scoring And Reporting Next Steps

Status: draft for local hardening after the first 5 minute fill-pressure gate.

## Current State

`score-v0` is still a participation and policy-compliance score. It is useful
for proving run ingestion, deterministic leaderboard ordering, freeze exclusion,
and report plumbing, but it is not yet a competitive trading-performance score.

Local arena reports now include enough execution diagnostics to start designing
the next policy:

- participant-scoped fills from `/api/v1/orders/fills`
- per-bot filled quantity, gross filled notional, and average fill price
- per-bot cash delta from buy/sell fills
- per-bot net inventory by instrument
- zero-fee marked inventory value
- diagnostic total P&L and diagnostic final equity
- per-instrument market quality from health samples
- command pressure, cancel pressure, and house LP activity gates

House liquidity providers and NPCs remain diagnostics-only unless a future mode
explicitly opts them into public scoring. They can and should report P&L,
inventory, fills, and quote behavior for operator tuning, but their goal is
market health, not public competition.

## Score V1 Policy Direction

`score-v1` should remain simple enough to explain from one report artifact. The
first version should rank only score-eligible public competitor bots.

Proposed headline:

```text
scoreV1 = startingEquity
        + pnlComponent
        - inventoryRiskPenalty
        - commandQualityPenalty
        - enforcementPenalty
```

Recommended initial components:

- **P&L component**: zero-fee cash plus marked inventory. Start with the
  diagnostic total already in `tradingMetrics.pnl.total`.
- **Inventory risk penalty**: mild configurable penalty on gross marked
  inventory relative to starting equity or mode limit. The first goal is to
  discourage one-way hoarding without punishing normal liquidity provision.
- **Command quality penalty**: rejects, timeouts, and extreme cancel/fill ratios.
  Keep self-trade-prevention rejects configurable because early house/NPC flow
  may intentionally collide while we tune scenarios.
- **Enforcement penalty**: disqualification remains a hard public leaderboard
  exclusion. Non-disqualifying warnings can become small score penalties later.
- **Liquidity/impact components**: descriptive first. Do not rank on quote
  quality or price impact until attribution can distinguish useful price
  discovery from destabilizing behavior.

Keep out of `score-v1` until the facts are stronger:

- lot-matched realized P&L
- maker/taker fee tiers
- adverse selection and toxicity attribution
- quote uptime rewards
- price movement rewards/penalties
- cross-run season normalization

## Report Shape Contract

The local arena report has two supported shapes:

- **Full report**: includes `sessionReports` and per-tick detail. Use for short
  debugging and fixture tests.
- **Compact report**: omits large per-tick payloads and keeps aggregate evidence.
  Use for multi-minute hardening and remote artifacts.

Stable top-level fields for current gates:

- `schemaVersion`
- `reportShape`
- `runId`
- `mode`
- `runPlan`
- `status`
- `totals`
- `commandAccounting`
- `commandStatusSummary`
- `latencySummary`
- `activityBySchedulingClass`
- `healthSummary`
- `marketQualitySummary`
- `executionSummary`
- `venueReadback`
- `enforcementEvents`
- `botResults`
- `leaderboard`
- `diagnosticLeaderboard`
- `persistence`

`executionSummary` is run-level execution evidence from venue fill readback:

- `fillCount`
- `filledQuantity`
- `filledNotional`
- `avgFillPrice`
- `byInstrument`
- `byBotId`
- `byRole`

Per-bot `tradingMetrics` carries score inputs and diagnostics:

- `commands`: proposed/submitted/completed/rejected/timed-out commands
- `orderFlow`: submitted/cancel/modify counts, sides, quantities, and submitted
  notional
- `executions`: fills, filled quantity, gross filled notional, average fill
  price, and per-instrument execution rows
- `inventory`: net quantity by instrument, mark price by instrument, gross marked
  notional, and mark source
- `pnl`: zero-fee cash, inventory value, diagnostic total P&L, and diagnostic
  final equity
- `fees`: zero-fee placeholder until a mode-level maker/taker policy is accepted
- `marketQuality`: currently placeholder; quote and impact attribution are later

Compatibility rule: compact report consumers should tolerate missing full
per-tick `sessionReports`; full report consumers should prefer aggregate fields
when present.

## Fresh Local Run Checklist

Use this for the next 5-7 minute tuning run after the slimmer image/runtime PR
lands.

Before reset/start:

- Use the rebased `codex/bot-arena-5m-tuning` branch.
- Start from a clean local stack if comparing market data or projection drain.
- Enable both projector loops at stack startup:
  - `ORDER_LIFECYCLE_PROJECTOR_ENABLED=true`
  - `MARKET_DATA_PROJECTOR_ENABLED=true`
- Keep projector poll intervals at `100ms` for local hardening unless measuring
  projector cost directly.

Recommended artifact names:

```text
/tmp/reef-arena-local-hardening-5m-exec-diagnostics.json
/tmp/reef-arena-local-hardening-5m-exec-diagnostics.summary.json
/tmp/reef-arena-local-hardening-5m-exec-diagnostics.html
```

Minimum pass checks:

- `status=completed`
- hardening summary `status=pass`
- command accounting gap `0`
- timed-out commands `0`
- freeze events `0`
- projection drained `true`
- top-of-book and depth availability at or above mode targets
- median/p95 spread at or below mode targets
- cancel command pressure above configured target
- house command pressure above configured target
- total fills at or above `healthTargets.minTotalFills`
- every primary instrument at or above `healthTargets.minFillsPerInstrument`
- bot-level `tradingMetrics.executions`, `inventory`, and `pnl` present for bots
  with fills

Useful inspection fields:

- `executionSummary.byInstrument`
- `executionSummary.byRole`
- `botResults[*].tradingMetrics.executions`
- `botResults[*].tradingMetrics.inventory`
- `botResults[*].tradingMetrics.pnl`
- `marketQualitySummary.instruments`
- `commandPressure.totals`
- `latency.overall`

## Next Local Tuning Matrix

Run these as separate 5-7 minute local artifacts. Do not change multiple knobs at
once unless a prior single-knob run identifies an obvious interaction.

| Scenario | Change | Purpose | Expected Signal |
| --- | --- | --- | --- |
| Baseline | Current 5-symbol mode | Confirm slim image/runtime branch did not change behavior | Same or better boot/runtime, fills on every symbol, no health regression |
| Higher taker pressure | Increase NPC taker frequency or reduce warmup | More fill/P&L signal | Higher fills, house replenishment/cancel pressure stays healthy |
| Wider LP spreads | Raise house target spreads modestly | Check spread gates and bot P&L sensitivity | Spread metrics move predictably; fill count may fall |
| Narrower LP spreads | Lower house target spreads modestly | Check tighter market feasibility | More fills or tighter spreads without crossed books |
| More NPC flow | Add more non-house/non-competitor flow | Exercise throughput without public scoring noise | More fills and command volume, stable latency |
| Competitor-only ranking | Keep house/NPC diagnostic-only and compare public leaderboard | Prove score scope | Public leaderboard excludes house/NPC even with fills |
| Negative policy | Add abusive/failing bot in separate mode | Keep freeze/disqualification evidence fresh | Main market stays healthy; bad bot excluded |

Promotion guidance:

- Use local 5-7 minute runs for tuning.
- Keep the 15 minute DigitalOcean run as a promotion gate, not an iteration loop.
- Do not promote a tuning profile unless local artifacts show both system health
  and market/economic diagnostics.

## Static Test Plan

No-stack tests should protect the report math before every long run:

- execution price normalization: nanos and decimal inputs
- mark price precedence: final venue snapshot, latest health sample, fixture
  fallback
- cash/inventory/P&L math for mixed buy/sell fills
- compact hardening summary fill thresholds
- rendered report execution rows, fills, net inventory, and P&L
- dry-run report behavior: no venue readback means no fake execution diagnostics

Current static coverage:

- `scripts/dev/arena-execution-diagnostics.test.mjs`
- `scripts/dev/arena-render-report.test.mjs`
- `scripts/dev/arena-local-hardening-summary.test.mjs`
- `scripts/dev/arena-local-tick-report-writer.test.mjs`
