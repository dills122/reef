# Bot Arena Scoring Policy

Status: draft discussion, 2026-07-12.

This document captures the first scoring direction for Reef Bot Arena. It is a
companion to `docs/BOT_ARENA_MONETARY_POLICY.md`; the economic policy defines
the game economy, while the scoring policy defines eligibility, ranking,
penalties, NPC difficulty handling, and season aggregation.

## Goals

Scoring should optimize for:

- simple headline ranking that users can understand
- enough risk and conduct metrics to discourage reckless or abusive strategies
- mode-specific scoring for different game types
- replayable, auditable score calculation under a locked economic policy
- resistance to leaderboard overfitting and seed memorization
- clear separation between market diagnostics and score-affecting facts

## Scoring Layers

Use layered scoring instead of one opaque formula.

### 1. Eligibility Gates

Before ranking, decide whether a bot result counts.

A result is ineligible when any of these occur:

- sandbox violation
- unapproved bot version
- disabled, banned, quarantined, or non-active bot version
- risk limit breach that policy marks terminal
- abuse threshold breach
- replay mismatch
- accepted-command accounting gap
- scoring run calculated under an economic policy different from the run's
  locked policy
- missing required run, bot, policy, or profile hashes

Ineligible results remain stored for audit, but do not appear on public ranked
leaderboards except in disqualification lists.

### 2. Headline Score

Initial headline score:

- final equity

Final equity should be calculated from:

- starting cash
- realized PnL
- fees, rebates, penalties, and carry from locked `EconomicPolicy`
- mark-to-market value of open positions under the mode's valuation rule

Open position valuation must be declared in `ScoringPolicy`. Candidate rules:

- last midpoint
- last trade
- liquidation value with spread penalty
- conservative side of book

Default recommendation: use liquidation value with spread penalty for ranked
runs. It discourages bots from ending with large risky inventory marked at a
friendly midpoint.

### 3. Risk Metrics

Risk metrics should appear from day one, but not all need to drive the first
headline score.

Core metrics:

- max drawdown
- downside volatility
- loss streak
- terminal inventory concentration
- gross notional exposure
- turnover
- realized vs unrealized PnL split
- slippage vs arrival midpoint
- implementation shortfall where relevant

Initial recommendation:

- use final equity as headline
- use max drawdown and conduct penalties as tie-breakers or secondary columns
- add risk-adjusted season boards after several runs prove metric stability
- during calibration, expose `scoreBreakdown.shadowScore` with component details
  for PnL, fill efficiency, inventory pressure, and conduct penalties while
  leaving the public score unchanged

Sharpe-like ratios are useful for longer windows, but noisy for short single
runs. Treat them as season metrics or research metrics until run counts are
large enough.

### 4. Conduct Metrics

Conduct scoring should discourage strategies that win by stressing the venue
rather than trading well.

Track:

- invalid order rate
- cancel/replace ratio
- quote stuffing proxy
- self-trade pattern
- repeated risk-gate rejects
- timeout/freeze count
- command burstiness
- boundary abuse decisions

Recommended behavior:

- small conduct issues: point penalty or tie-break disadvantage
- repeated or severe issues: disqualification
- abuse-control breaches: disqualification and operator review

Conduct penalties must be deterministic and policy-versioned.

### 5. Mode-Specific Metrics

Each game mode owns extra score components.

Market-making challenge:

- quote continuity
- spread quality
- top-of-book contribution
- inventory control
- adverse-selection loss
- realized spread
- cancel discipline

Directional strategy challenge:

- final equity
- max drawdown
- turnover efficiency
- slippage
- exposure discipline

Survival league:

- no ruin
- max drawdown
- recovery after shock
- liquidity under stress
- terminal equity

Execution-quality challenge:

- implementation shortfall
- completion ratio
- participation-rate control
- market impact
- timing risk

## NPC Difficulty

NPC profiles affect scoring context because they define scenario difficulty.

Initial recommendation: use leaderboard partitions plus a small multiplier on
non-baseline score components. Keep this multiplier in `shadowScore` until
enough run artifacts prove the relative difficulty calibration is stable.

Examples:

- `benign-noise`
- `balanced-flow`
- `toxic-momentum`
- `liquidity-shock`
- `stress-mixed`

Leaderboards should compare only runs with the same:

- game mode
- economic policy version
- scoring policy version
- NPC difficulty bucket
- visible-data policy version
- seed set or season set

Initial shadow multipliers:

- `benign-noise`: `1.00`
- `ranked-standard`: `1.00`
- `balanced-flow`: `1.05`
- `toxic-momentum`: `1.10`
- `stress-liquidity`: `1.15`
- `event-shock`: `1.20`

The multiplier applies only to variable components such as equity,
participation, risk, and conduct. It must not multiply the baseline score.

Current report-only formula:

- `formulaVersion`: `shadow-score-v1`
- `equity`: capped zero-fee marked PnL from participant-scoped fills
- `risk`: penalties for terminal inventory size, inventory exposure ratio,
  terminal inventory concentration, excessive turnover, freezes, failed ticks,
  and operational pauses
- `marketInteraction`: bounded credit for completed commands, fills, fill
  quantity, fill ratio, and completion rate
- `conduct`: penalties for timeout rate, invalid intent rate, cancel/replace
  pressure, and freezes
- `difficulty`: small multiplier applied only after summing non-baseline
  variable components

`shadow-score-v1` remains report-only until enough live and replay artifacts
show that the component weights rank strategies in a useful, stable way.

Each arena report should also include `scoringCalibration`, a scenario-level
summary of the report-only scoring data:

- eligible and non-scoring actor counts by actor class and score effect
- NPC difficulty buckets and effective difficulty multiplier
- min/max/average public score, shadow score, component scores, and core
  diagnostics across eligible competitors
- data-quality flags such as `low-eligible-competitor-count`,
  `no-eligible-fills`, `no-pnl-attribution`, `partial-pnl-attribution`, and
  `public-score-mismatch`

Calibration summaries are intended for comparing run artifacts and tuning the
formula. They must not affect public leaderboard scoring while shadow scoring
is in report-only mode.

Use `scripts/dev/compare-arena-scoring-calibration.mjs` to compare two arena
report artifacts. The comparison reports formula/policy drift, eligible actor
count changes, shadow-score and component average deltas, the component with
the largest average movement, and data-quality flags added or removed between
runs.

Use `scripts/dev/run-arena-scoring-calibration-matrix.mjs` to generate a small
calibration artifact set. By default it runs the compact dry-run matrix for:

- `equity-sprint-noise`: `equity-sprint.v1`
- `equity-multi-toxic`: `equity-multi-local.v1`

The matrix writes each compact report, pairwise comparison files from the first
entry as the baseline, and `manifest.json` with report summaries and comparison
pointers. Live runs can use the same script with `--submit-mode=live`,
`--venue-url`, `--arena-admin-url`, `--seed-reference`, and projection-drain
flags once the local stack is healthy.

Use `scripts/dev/analyze-arena-actor-diagnostics.mjs` after a matrix run to
record what each actor/persona knob appears to influence. It emits:

- profile-level metrics grouped by `actorProfile.profileId`
- knob-level metric groups for aggression, order rate, spread crossing,
  cancel discipline, quote size, quote spread, inventory skew, panic threshold,
  latency jitter, and risk discipline
- per-profile knob groups so shared knob names such as `aggression` can be
  read for a specific persona without mixing NPC, MM, and competitor effects
- provider-owned liquidity attribution fields, including submitted-order share,
  fill share, and current provider quote spread when participant readback has
  enough bid/ask order data
- provider adverse-selection markout when participant fills can be matched to a
  post-fill health-sample mid inside the diagnostic window
- instrumentation gaps such as `no-fills-observed`, `no-pnl-observed`, and
  `no-pnl-per-executed-notional`
- caveats when run count or parameter variation is too low for confident
  tuning

The actor diagnostics report is observational. Treat it as a map of which
knobs are worth varying in matched scenario matrices, not as causal proof from
a single run.

Use `scripts/dev/run-arena-actor-calibration-matrix.mjs` when a knob needs a
matched run set. It generates temporary mode/catalog overlays, varies one
actor-profile knob at a time, runs compact arena reports, and writes
`actor-diagnostics.json` plus `actor-influence-summary.json` beside the matrix
manifest. The influence summary records one row per configured knob value plus
metric deltas such as commands, fills, provider spread, PnL, and markout so the
next tuning decision does not require manual `jq` extraction. Initial
behavior-backed groups are:

- `mm-quote-spread`
- `mm-quote-size`
- `npc-aggression`
- `npc-spread-cross`
- `npc-order-rate`

Use `--environment=thin-wide-liquidity` when default house liquidity saturates
NPC taker behavior. This environment keeps the same source mode but writes
temporary overlays with wider, thinner market maker quotes
(`mm-tight-bluechip.quoteSpreadBps=100`, `quoteSize=2`) plus relaxed spread
health thresholds. The environment is recorded in the matrix manifest, applies
to the baseline and every group entry, and group-specific knob overrides still
win for the target profile being tested.

Example live slice:

```sh
bun scripts/dev/run-arena-actor-calibration-matrix.mjs \
  --out-dir=/tmp/reef-arena-actor-calibration-live \
  --submit-mode=live \
  --venue-url=http://127.0.0.1:8080 \
  --arena-admin-url=http://127.0.0.1:8080 \
  --seed-reference \
  --duration-seconds=30 \
  --environment=thin-wide-liquidity \
  --group=npc-aggression
```

Initial local live calibration notes from 15-second matched slices:

- `npc-bad-aggressive-retail.aggression` at `0.35`, `0.65`, and `0.95`
  produced the same default-mode result: average `10` submitted commands,
  `10` fills, `1.0` fill ratio, `$2400` executed notional, `-10` PnL bps,
  and `-2.4` total PnL. A follow-up 10-second live slice under
  `thin-wide-liquidity` still produced no movement: average `5` commands,
  `5` fills, `1.0` fill ratio, about `$1199.76` executed notional, and
  `-1.2` total PnL for every value. Do not use this knob for difficulty
  scoring until the taker strategy can choose non-crossing or less-crossing
  prices.
- `npc-bad-aggressive-retail.maxSpreadCrossBps` at `50`, `150`, and `250`
  also produced the same default-mode result: average `10` fills and `1.0`
  fill ratio. Under `thin-wide-liquidity`, it still produced average `5`
  fills, `1.0` fill ratio, about `$1199.76` executed notional, and about
  `-10.002` PnL bps for every value. Current taker logic references best
  ask/bid and then adds at least a positive cross offset, so these values
  change limit-price distance beyond top of book, not whether the order crosses
  resting liquidity.
- `npc-bad-aggressive-retail.orderRate` is behavior-backed: `low` produced
  average `3` submitted commands and fills, `medium` produced `5`, and `high`
  produced `10`, with executed notional scaling from about `$719` to `$1199`
  to `$2400`. The generated influence summary surfaces this automatically as a
  `+7` fill-count delta and about `+233%` movement from `low` to `high`.
- `mm-tight-bluechip.quoteSpreadBps` has useful liquidity signal: `10` bps
  produced market maker fills and positive average MM PnL, while `20` and `40`
  bps produced no MM fills in the slice. Quote-quality spread metrics are still
  split between market-wide coverage and provider-owned current-order spread
  attribution.
- Adverse-selection diagnostics are signed from the provider's perspective:
  negative markout means the post-fill mid moved against the provider, positive
  markout means favorable post-fill movement. These diagnostics remain
  score-neutral until the mark window and sampling cadence are calibrated.
  In the first 15-second local slice with `mm-tight-bluechip.quoteSpreadBps=10`,
  filled MM quotes showed about `+5` bps average markout and `0%` adverse fills.
- `mm-tight-bluechip.quoteSize` is behavior-backed for displayed depth:
  average submitted quantity scaled roughly `23.3`, `46.7`, `116.7` for
  `5`, `10`, `25` quote size values. It did not produce fills in that slice.

Why partition plus small multiplier:

- simple to audit
- hard to game
- supports separate "easy", "standard", and "hard" boards
- avoids mixing incomparable runs on public boards
- still records a calibrated scoring candidate for later promotion

## Liquidity Provider Neutrality

House liquidity providers should be score-neutral.

They can affect market conditions:

- spread
- depth
- fill probability
- adverse selection
- price path
- volatility

They must not directly add or subtract leaderboard points. Report their
diagnostics in public or operator reports:

- PnL
- inventory
- quote uptime
- spread/depth contribution
- adverse-selection loss
- kill-switch activation

Arena reports should expose liquidity diagnostics in two places:

- `botResults[].liquidityDiagnostics` for each house market maker
- `liquiditySummary` for scenario-level liquidity context

Both surfaces must set `scoreNeutral: true` and `pointsEffect: 0`. They are
inputs for interpreting scenario quality and score calibration, not direct
score inputs.

Initial liquidity diagnostics:

- touched instruments and venue quote coverage
- average top-of-book uptime and depth percentage
- median and p95 quoted spread bps
- submit/modify/cancel activity and cancel/replace pressure
- fill participation and terminal inventory pressure
- adverse-selection placeholder until post-fill price path attribution exists

Useful liquidity flags:

- `missing-liquidity-provider`
- `no-active-liquidity-provider`
- `no-liquidity-fills`
- `low-quote-uptime`
- `thin-depth`
- `wide-median-spread`
- `crossed-book`

## Ranked, Asymmetric, And Training Modes

Ranked standard mode:

- all competitors share the same world
- all competitors share the same economic, scoring, visibility, and credit
  policies
- hidden per-competitor handicaps are forbidden

Ranked asymmetric mode:

- asymmetry is declared in the game mode
- bots are ranked within role/class, or the score formula explicitly accounts
  for role assignment
- role assignment is stored in the replay envelope

Training and chaos modes:

- any actor can be tuned freely
- results are not mixed with ranked leaderboards
- useful for local testing, resilience, conduct gates, and product demos

This preserves the ability to run a "bad aggressive trader" or other extreme
actors while keeping ranked boards comparable.

## Public And Final Leaderboards

Public provisional boards are useful for engagement but create overfitting risk.

Recommended structure:

- provisional board: public seed set, lower precision, diagnostic-heavy
- final board: hidden seed set or sealed season seed set, full precision,
  published after run completion
- season board: aggregate across multiple final seed sets

Avoid exposing enough score detail during a season to let participants tune
against one exact seed set.

## Tie-Breakers

Initial tie-break order:

1. final equity
2. lower max drawdown
3. lower conduct penalty
4. lower slippage
5. lower terminal gross exposure
6. fewer accepted commands for same equity and risk profile

Tie-breakers are policy-versioned and must be visible in score reports.

## Season Aggregation

Season score should reduce luck from one seed.

Candidate aggregation:

- rank points per final run
- median rank across seed sets
- average normalized score across compatible runs
- worst-run floor to punish fragile strategies

Initial recommendation:

- use rank points per run for public simplicity
- publish final equity and risk metrics as supporting columns
- add a "consistency" board using median rank once enough runs exist

## Research Follow-Up

Scoring needs a focused research pass before implementation. Topics:

- risk-adjusted return metrics for short simulated trading runs
- drawdown metrics and statistical significance
- market-making score formulas: realized spread, inventory cost, adverse
  selection, quote continuity
- execution-quality metrics: implementation shortfall and market impact
- leaderboard overfitting defenses from ML competitions
- season aggregation methods that reduce seed luck

Candidate sources to evaluate:

- Sharpe ratio and later risk-adjusted performance literature
- drawdown duration/depth literature
- market microstructure and market-making performance papers
- competition leaderboard papers such as Ladder and bootstrap leaderboard
  methods

## Open Questions

- Should v0 ranked scoring use liquidation value or conservative side-of-book
  value for terminal open positions?
- Should conduct penalties subtract points, affect tie-breakers, or only
  disqualify beyond thresholds?
- Should NPC difficulty remain bucket-only through v1, or should hard modes get
  separate season medals but no multiplier?
- How many hidden final seeds are enough for a weekly board?
- Should market-making challenge score final equity first, or quote quality
  first with PnL as a constraint?
- Should season score use rank points, normalized score, or median rank?
