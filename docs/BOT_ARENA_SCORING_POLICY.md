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
