# Bot Arena Scoring And Reporting Next Steps

Status: score-v1 is ready for the current arena promotion path after local
reset-to-reset proof and hosted DigitalOcean 15 minute hardening evidence.

## Current State

`score-v0` is still a participation and policy-compliance score. It remains
available for the small sprint fixture and backward-compatible report plumbing.

`score-v1` is now available in the arena tick runner and is the default policy
for `packages/scenario-definitions/arena/equity-multi-local.v1.json`. It ranks
eligible public competitors on a simple final-equity formula:

```text
scoreV1 = startingEquity
        + pnlComponent
        - inventoryRiskPenalty
        - commandQualityPenalty
        - enforcementPenalty
```

The formula is exposed in `scoreBreakdown.componentDetails.publicScoreV1`.
`scoreBreakdown.shadowScore` remains as calibration-only diagnostics, so older
market-interaction and difficulty tuning signals are still visible without
silently driving the public leaderboard.

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

## Score V1 Promotion Baseline

Current public scoring policy:

- policy id: `score-v1`
- mode default: `packages/scenario-definitions/arena/equity-multi-local.v1.json`
- public scope: score-eligible public competitor bots only
- non-public actors: house liquidity providers and NPC flow remain diagnostic
- formula: final equity minus inventory-risk, command-quality, and enforcement
  penalties
- shadow score: still emitted for calibration, not public leaderboard ordering

Promotion evidence requires:

- hardening summary `status=pass`
- command accounting gap `0`
- no timed-out, failed, or rejected commands unless explicitly allowed by mode
- no freeze events in the positive gate
- execution readback present for filled bots
- `scoringCalibration.dataQuality.publicScoreMismatchCount=0`
- public leaderboard contains only eligible public competitors
- local reset-to-reset proof has exact deterministic score/accounting equality
- hosted 15 minute run passes arena artifact checks with health pass required

## Local Ready Evidence

Score-v1 passed local reset-to-reset proof on 2026-07-14 with projection drain
required after live command submissions.

Artifacts:

- `/tmp/reef-score-v1-drained-5m-a.json`
- `/tmp/reef-score-v1-drained-5m-a.summary.json`
- `/tmp/reef-score-v1-drained-5m-b.json`
- `/tmp/reef-score-v1-drained-5m-b.summary.json`

Both 5 minute runs completed with:

- `2321` submitted commands
- `2321` terminal `COMPLETED` commands
- `0` timed-out commands
- `0` freezes
- hardening summary `status=pass`
- market health `status=pass`
- identical execution fill totals by instrument and role
- identical public score-v1 leaderboard

The reusable proof comparison is:

```sh
node scripts/dev/compare-arena-score-v1-proof.mjs \
  /tmp/reef-score-v1-drained-5m-a.json \
  /tmp/reef-score-v1-drained-5m-b.json
```

Current proof hash for deterministic scoring/accounting fields:

```text
fcac0f8a6f0a3612cc4fd69c2c0734fee57e8b507dec7bd9b84da1b00d30cfb3
```

The comparison intentionally treats live health sampling as tolerance-based:
the two runs differed by one empty-book sample out of `1350` samples
(`95.925926%` vs `96%` top-of-book/depth availability), while both remained
above mode health targets and had zero crossed or locked book samples. Do not
use exact health-sample equality as a score-v1 promotion gate.

Hosted follow-up passed on 2026-07-14:

- artifact root: `reports/do-benchmark/do-benchmark-20260714T010045Z/`
- duration: `900s`
- hardening summary: `status=pass`
- submitted commands: `6985`
- terminal `COMPLETED` commands: `6985`
- command accounting gap: `0`
- timed-out commands: `0`
- public score-v1 mismatch count: `0`
- execution fill count: `2010`
- public score-v1 leaderboard matched the local proof ranking and scores

The 15 minute hosted run is promotion evidence for score-v1 correctness. It is
not yet evidence that c-8/source-build hosted pacing is optimal. Track pacing
cleanup separately from score-v1 correctness.

## Cleanup Story: Hosted Arena Pacing Lag

Problem: the hosted DigitalOcean `15m` arena gate passed correctness and scoring
checks, but the arena stage took `1017s` for a `900s` schedule. The final
completion lag was about `107s`; every scheduler event completed behind
schedule and total scheduler sleep was `0`.

Initial hypothesis: c-8 source-build hosted shape plus per-batch projection
drain/status polling leaves the arena loop CPU or I/O saturated enough that it
cannot recover to the nominal tick schedule, even though all commands complete
correctly and projections catch up.

Acceptance criteria for cleanup:

- rerun the same hosted arena profile with hardening summary `status=pass`
- keep score-v1 leaderboard and execution summary stable
- keep accounting gap, timeouts, rejects, failed ticks, and freezes at `0`
- keep projection freshness caught up with lag `0`
- reduce `finalCompletionLagMs` below `30000`
- show non-zero scheduler sleep time, or document why the configured tick
  cadence intentionally saturates the worker
- capture stage timing and artifact paths in this doc and the soak checklist

Candidate fixes to test one at a time:

- use Docker Hub image mode for the arena profile once images include the arena
  runtime dependencies
- increase worker size from `c-8` to `c-16`
- reduce per-command projection-drain frequency by draining at deterministic
  tick/session boundaries instead of after every live command batch
- tune command status polling and projection drain polling intervals for hosted
  runs
- profile the remote arena stage to separate bot runtime, HTTP command waits,
  projection drain, and persistence/readback costs

## Score V1 Policy Direction

`score-v1` should remain simple enough to explain from one report artifact. The
first version ranks only score-eligible public competitor bots.

Proposed headline:

```text
scoreV1 = startingEquity
        + pnlComponent
        - inventoryRiskPenalty
        - commandQualityPenalty
        - enforcementPenalty
```

Implemented initial components:

- **P&L component**: zero-fee cash plus marked inventory from
  `tradingMetrics.pnl.finalEquityDiagnostic` when available, otherwise
  baseline plus diagnostic P&L, with legacy score fallback for dry-run reports.
- **Inventory risk penalty**: gross terminal marked inventory, exposure ratio,
  and terminal concentration penalties reused from the existing score-breakdown
  risk component.
- **Command quality penalty**: timeout rate, invalid intent rate, and excessive
  cancel/replace pressure reused from the conduct component.
- **Enforcement penalty**: run freezes and operational pauses reduce score;
  disqualified bots are still excluded from the public leaderboard.
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
- Use the default `equity-multi-local.v1` mode for `score-v1`, or pass
  `--scoring-policy-version=score-v1` to override older mode files.
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
- score-v1 proof comparison `status=pass` when comparing two clean reset runs

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
- `scripts/dev/compare-arena-score-v1-proof.test.mjs`
