# Bot Arena Monetary Policy

Status: draft direction, 2026-07-12.

This document proposes the first monetary policy and economic-control model for
Reef Bot Arena games. It extends `docs/BOT_ARENA_PLAN.md` without changing the
accepted D-045 constraints: bots use the normal venue command boundary, the
matching engine remains unaware of game mechanics, arena facts stay outside the
trading hot-path database, and runs remain deterministic and replayable.

Scoring-specific direction lives in
[`BOT_ARENA_SCORING_POLICY.md`](./BOT_ARENA_SCORING_POLICY.md). This document
only defines the economic and actor-policy inputs that scoring consumes.

## Goals

Bot Arena monetary policy should optimize for:

- fair competition across submitted bots
- realistic venue conditions: spread, depth, volatility, fills, adverse
  selection, fees, latency, and inventory pressure
- deterministic replay from seed, bot version, scenario version, policy version,
  and input event log
- stable game quality across runs as the bot population changes
- clear operator controls for liquidity, credit, fees, and emergency shutdown

The primary target is not a real macroeconomy. Reef controls a tournament
economy where the operator sets initial endowments, risk budgets, market-maker
behavior, background order flow, fees, and scoring. That makes policy closer to
an exchange plus central-bank-like scenario controller than to a retail game
currency system.

## External Research Summary

- The Federal Reserve frames monetary policy around explicit objectives,
  transparent governance, and instruments that tighten or ease financial
  conditions. For Reef, copy the control-loop discipline: define target metrics,
  measure drift, then adjust known instruments rather than hand-tuning bots
  invisibly. Source:
  https://www.federalreserve.gov/monetarypolicy/monetary-policy-what-are-its-goals-how-does-it-work.htm
- Federal Reserve policy tools include open-market operations, administered
  rates, reserve tools, and liquidity facilities. Reef analogues are house
  liquidity budget, maker rebates, taker fees, bot credit limits, margin/leverage
  gates, and emergency liquidity injection/removal. Source:
  https://www.federalreserve.gov/monetarypolicy/policytools.htm
- NYSE market-maker rules emphasize registered actors, continuous two-sided
  displayed quotes, minimum capital, and performance obligations. Reef house
  market makers should have explicit quote obligations, capital budgets, uptime
  targets, and failure metrics instead of being generic load-test actors. Source:
  https://www.nyse.com/trade/membership
- Modern market-making literature treats bid/ask placement as a spread-capture
  problem constrained by inventory risk. Gueant summarizes dynamic quote skew
  and multi-asset inventory management; Gueant, Lehalle, and Fernandez-Tapia
  formalize inventory-constrained quoting. Reef should encode inventory-aware
  house market-maker profiles rather than static midpoint quoting. Sources:
  https://arxiv.org/abs/1605.01862 and https://arxiv.org/abs/1105.3115
- ABIDES demonstrates a market simulator with many interacting agents, an
  exchange agent, configurable network latencies, and message-based protocols
  modeled after real equity market protocols. Reef should keep actor classes and
  latency models explicit in scenario configuration. Source:
  https://arxiv.org/abs/1904.12066
- Virtual-economy research shows developer interventions such as transaction
  taxes and item sinks can affect prices and incentives, sometimes in
  unintended ways. Reef should version and publish fee/sink policies, measure
  before/after run quality, and avoid hidden mid-season parameter changes.
  Source: https://arxiv.org/abs/2210.07970
- EVE Online's Monthly Economic Report pattern is useful as an operating model:
  publish recurring economic telemetry so players and operators can reason about
  money supply, trade, production, destruction, and regional concentration. Reef
  should publish per-run and per-season economic reports for Bot Arena. Source:
  https://www.eveonline.com/news/view/monthly-economic-report-april-2024

## Policy Objects

Version each object. Store resolved policy versions on every arena run record.

- `EconomicPolicy`: starting cash, starting inventory, allowed assets, fees,
  rebates, interest/carry, margin rules, and settlement rules.
- `LiquidityPolicy`: house market-maker count, quote obligations, inventory
  budget, replenishment rules, spread bands, quote size bands, latency model,
  and kill-switch thresholds.
- `BackgroundFlowPolicy`: NPC investor/trader mix, order arrival process,
  market regime, noise floor, directional pressure, and event shocks.
- `CreditPolicy`: max gross notional, max net position, per-instrument limits,
  leverage, max loss, and liquidation behavior.
- `ScoringPolicy`: final equity headline metric plus risk, conduct, consistency,
  market-quality, NPC-context multipliers, and disqualification metrics.
- `InterventionPolicy`: deterministic operator actions allowed during scenario
  setup or scripted events, with no discretionary hidden changes mid-run.
- `ActorProfile`: reusable per-actor behavior parameters for house market
  makers, NPC flow, benchmarks, and local/test competitors.

## Active Scoring Lock

When a game mode starts an active scoring run with a resolved economic policy,
that economic policy is locked until final score calculation completes.

Locked means:

- no fee, rebate, cash, margin, carry, liquidation, or source/sink parameter
  changes while scoring is active
- no leaderboard calculation against a different economic policy version
- no partial rerun that keeps fills from one policy and scoring from another
- no operator override except a declared emergency stop that marks the run
  invalid or explicitly non-ranked

This lock applies from run acceptance through terminal score publication. New
policy tuning creates a new policy version and a new run.

## Actor Profiles

Run policy creates the world. `ActorProfile` creates actor behavior inside that
world.

Each scenario composes actors from versioned profiles:

- `actorId`
- `actorClass`: `competitor`, `house_market_maker`, `npc_flow`, or `benchmark`
- `profileId`
- `profileVersion`
- `params`
- `riskProfileId`
- `latencyBucket`
- `seedSalt`

Profile parameters should be strict: unknown keys reject the scenario before the
run starts. The resolved profile hash belongs in the replay envelope.

Candidate shared parameters:

- `aggression`
- `orderRate`
- `maxSpreadCrossBps`
- `cancelDiscipline`
- `riskDiscipline`
- `inventorySkew`
- `quoteSpreadBps`
- `quoteSize`
- `latencyJitter`
- `panicThreshold`

Ranked competitor bots should normally share the same economic world and policy
limits. Per-competitor profile tuning is acceptable for built-in bots, local
tests, training modes, eligibility restrictions, explicit penalties, or
operator-owned benchmark actors. Hidden handicaps should not affect ranked
leaderboards.

## Economic Targets

Each run should report these target bands:

- `spreadBps`: time-weighted best bid/ask spread by instrument and venue session
- `topOfBookDepth`: visible depth near midpoint
- `fillRate`: competitor order fill ratio by order type and side
- `slippageBps`: execution price vs arrival midpoint
- `volatility`: realized midprice volatility per instrument
- `inventorySkew`: house market-maker net position vs budget
- `cashSupply`: total player plus house cash by actor class
- `wealthConcentration`: final equity concentration among competitor bots
- `turnover`: traded notional divided by starting cash
- `rejectRate`: boundary/risk/abuse rejects by reason
- `quoteContinuity`: share of ticks where each required house market maker has
  valid two-sided quotes inside its allowed band
- `marketMakerPnl`: realized/unrealized PnL and drawdown for each house market
  maker, excluded from public leaderboards but included in operator reports
- `npcDifficulty`: scoring context derived from NPC profile mix and regime
  intensity

Initial local target bands should be deliberately broad:

- median spread: `5-80` bps by liquidity tier
- quote continuity: `>= 95%` for mandatory house market makers
- competitor fill rate: `10-70%`, mode-specific
- house market-maker inventory: within `80%` of per-instrument limit for at
  least `99%` of ticks
- terminal accepted-command accounting gap: `0`

## Money Supply Model

Use two separate ledgers:

- competition ledger: competitor starting cash, positions, fees, realized PnL,
  mark-to-market equity, penalties, and disqualification facts
- house ledger: market-maker funding, NPC flow funding, rebates, fee receipts,
  scripted liquidity injections, and emergency withdrawals

Do not mingle the ledgers for scoring. House actors can win or lose money, but
their PnL is market-quality telemetry, not a leaderboard result.

Liquidity providers should be score-neutral. They shape spread, depth, fill
probability, and adverse selection, but they should not directly add or subtract
leaderboard points. Their purpose is to provide comparable market conditions.

NPC profiles can affect scoring because they define scenario difficulty. A run
with `npc-benign-noise` is not equivalent to a run with `npc-toxic-momentum` or
`npc-liquidity-shock`. Scoring policy should record an NPC difficulty multiplier
or bucket so leaderboards can compare only compatible runs or explicitly adjust
scores by declared NPC context.

### Faucets

Allowed money creation:

- competitor starting cash at run start
- scripted dividends or corporate-action-like payouts if the game mode includes
  them
- house market-maker funding at run start
- NPC actor funding at run start
- deterministic emergency liquidity facility only if declared by
  `InterventionPolicy`

### Sinks

Allowed money removal:

- taker fees and venue fees
- borrow fees, carry costs, and margin interest
- liquidation penalties
- conduct penalties: quote stuffing, self-trade pattern, order spam,
  repeated invalid orders, timeout abuse
- disqualification: bot removed from leaderboard, with final facts retained

### Neutral Transfers

These should conserve cash between actor ledgers:

- trade consideration
- maker rebates funded from taker fees or explicit house subsidy
- realized PnL from matched trades
- settlement movements

## Market Maker Structure

House market makers are controlled operators, not contestants.

Required fields per profile:

- `profileId`
- `policyVersion`
- `instruments`
- `capitalBudget`
- `inventoryLimit`
- `baseSpreadBps`
- `minQuoteSize`
- `maxQuoteSize`
- `quoteRefreshInterval`
- `quoteUptimeTarget`
- `latencyBucket`
- `riskAversion`
- `skewFactor`
- `volatilitySensitivity`
- `killSwitch`

Behavior:

- Quote both sides during active market phases unless inventory, risk, or
  kill-switch rules allow withdrawal.
- Skew bid/ask around fair value as inventory approaches limits.
- Widen spreads as volatility, inventory pressure, or adverse selection rises.
- Replenish depth after fills within policy-defined delay.
- Never use private competitor state. Inputs are only scenario state, public
  market data, own orders, own fills, and declared fair-value process.
- Use deterministic randomness from run seed plus profile ID.

Initial profiles:

- `mm-tight-bluechip`: liquid instruments, tight spread, high quote continuity,
  large inventory budget.
- `mm-wide-smallcap`: wider spread, lower depth, higher volatility sensitivity.
- `mm-stressed`: quote withdrawal allowed under scripted shock conditions.
- `mm-benchmark-passive`: simple symmetric quotes for regression comparisons.
- `mm-inventory-skew`: Avellaneda-Stoikov-style skew away from inventory limits.
- `mm-last-resort`: wide emergency liquidity that activates only when depth
  collapses below policy threshold.
- `mm-toxic-aware`: widens aggressively after adverse-selection streaks.
- `mm-fading-depth`: starts deep, then reduces displayed size across the run to
  test contestant adaptation.

## NPC Bot Structure

NPC bots provide background order flow and market regimes. They should be
classified separately from house market makers.

Initial NPC classes:

- `noise`: small random orders, low information content, stable arrival rate.
- `retail`: smaller marketable orders, occasional chasing, low modify rate.
- `institutional`: larger parent-order slicing, slower cadence, higher modify
  and cancel activity.
- `momentum`: follows recent price moves with bounded aggressiveness.
- `meanReversion`: fades price moves with bounded inventory.
- `liquidityTakerShock`: scripted temporary demand/supply impulse.
- `benchmarkCompetitor`: built-in public strategy used to calibrate scoring.
- `badAggressiveRetail`: crosses wide spreads, overtrades, ignores inventory
  pressure, and creates adverse selection for disciplined bots.
- `panicSeller`: dumps inventory after drawdown or volatility threshold.
- `spoofLikeCanceller`: high cancel/replace intent used only in conduct and
  abuse-control tests, never in ranked production runs unless declared.
- `quoteStuffingTest`: extreme message-pressure actor for boundary/rate-limit
  validation, excluded from normal scoring modes.
- `newsReactive`: trades deterministically around scripted public news events.
- `liquiditySniper`: waits for stale quotes and aggressively takes them.
- `contrarianWhale`: large mean-reversion actor with slow cooldowns and high
  visible impact.
- `settlementBreaker`: creates trades likely to stress post-trade exception
  paths in non-ranked operational modes.

NPC bots:

- use the same venue command boundary as competitors
- have explicit starting cash and risk limits
- have private policy code only when they are operator-controlled, not
  leaderboard-eligible
- must record actor class in command metadata
- must be replay-stable
- may affect scoring only through declared NPC difficulty buckets or
  multipliers, never through hidden per-bot adjustments

## Fees, Rebates, And Penalties

Default v0 fee model:

- maker fee: `0` bps or small rebate funded by taker fees
- taker fee: `1-5` bps by mode
- cancel fee: none initially; use conduct penalties for abusive cancel ratios
- borrow/carry: disabled until shorting or leverage are explicit game features
- liquidation penalty: disabled until margin is explicit

Fee changes are policy-version changes and should not happen inside a season
unless the season rules explicitly allow scheduled regime shifts.

## Game Modes

### Market-Making Challenge

Competitor objective: provide liquidity while controlling inventory.

Policy:

- stricter quote obligations for contestants
- score final equity, spread quality, quote continuity, adverse-selection
  losses, inventory drawdown, and order conduct
- house market makers reduced or widened so contestants can supply meaningful
  liquidity

### Directional Strategy Challenge

Competitor objective: trade signals without destabilizing market.

Policy:

- house market makers provide baseline liquidity
- NPC flow creates regimes and noise
- score final equity, drawdown, turnover efficiency, slippage, and conduct

### Survival League

Competitor objective: avoid ruin across shocks.

Policy:

- scripted liquidity/volatility shocks
- tighter loss limits
- score survival, drawdown, final equity, and recovery behavior

### Execution-Quality Challenge

Competitor objective: execute parent orders.

Policy:

- hidden target schedule or public benchmark schedule
- score implementation shortfall, participation rate control, market impact,
  and completion ratio

## Intervention Rules

Allowed:

- pre-run policy selection
- pre-run house liquidity budget selection
- scripted event schedule declared in scenario definition
- deterministic emergency facility declared in policy and triggered by measured
  thresholds

Forbidden:

- operator changing spreads, fees, or NPC behavior mid-run outside declared
  event schedule
- operator changing economic policy while active scoring is not terminal
- house actors receiving competitor private state
- leaderboard recalculation with a different policy version without publishing
  a corrected result
- changing bot-visible data policy during a run

## Reports

Produce two report levels.

Public run report:

- mode ID, scenario version, economic policy version, liquidity policy version,
  scoring policy version, seed, run window
- leaderboard
- aggregate spread, depth, volatility, fill rate, slippage, and reject rate
- disqualification list with reason codes

Operator economic report:

- all public fields
- house market-maker PnL and inventory
- NPC class flow contribution
- fee/rebate totals
- cash source/sink reconciliation
- intervention triggers
- replay hash and accounting-gap checks

## Data And Architecture Fit

Ownership:

- `packages/scenario-definitions/`: policy definitions and game mode fixtures
- `services/simulator/`: actor scheduling, seeded NPC and market-maker runners,
  replay comparison, and reports
- `packages/bot-sdk/`: visible snapshots, action types, and local harness support
- `services/platform-runtime/`: arena registry, run records, result ingestion,
  public leaderboard reads, and admin APIs
- `services/matching-engine/`: unchanged venue matching behavior

Persistence:

- Store policy versions, resolved parameters, and report facts in arena-owned
  data, not trading hot-path tables.
- Venue trades and command outcomes remain canonical venue facts.
- Arena scoring and reports are rebuildable projections from run record,
  policy versions, bot versions, command/event facts, and market-data snapshots.

Replay envelope additions:

- `economicPolicyVersion`
- `liquidityPolicyVersion`
- `backgroundFlowPolicyVersion`
- `creditPolicyVersion`
- `interventionPolicyVersion`
- house actor profile versions
- NPC profile versions
- resolved actor profile hashes
- NPC difficulty bucket or multiplier
- source/sink reconciliation hash

## First Implementation Slice

Current checkpoint (2026-07-20): items 1-2 are implemented with strict
canonical actor/economic resolution and all three invite-preview economic
fixtures. Runner reports retain resolved catalog, profile, economic, scoring,
and composition artifacts and hashes; roster lock verifies resolved
actor/economic/scoring content before persisting those references. Run records
now persist the accepted envelope, scoring, economic, roster, seed-set,
actor-profile, and risk-policy locks; result ingestion must match the scoring
lock, and terminal score publication is immutable. The zero-fee baseline now
emits deterministic competition/house cash, inventory, source/sink, and PnL
reconciliation evidence and can be required as a run gate. Non-zero fees and
rebates now reconcile against canonical maker/taker attribution, including
venue fee receipts, maker rebate payments, and explicit house subsidy limits.
Missing role coverage, role notional imbalance, and underfunded facilities fail
closed. The 2026-07-21 live local rehearsal exercised that guard: real fills
reached projection readback with `UNSPECIFIED` roles, so the economic run failed
instead of silently applying non-zero terms. End-to-end role propagation,
remaining quote-quality fields in item 3, full actor-profile coverage in 6, and
recorded fixed-seed proof for all three policies in 8 remain active; item 7 and
roster-to-run binding are complete.

1. Add static policy fixtures under `packages/scenario-definitions/arena/`.
2. Add `ActorProfile` fixtures with strict parameter validation and resolved
   profile hashes.
3. Extend local arena tick runner reports with cash source/sink reconciliation,
   house actor PnL, quote continuity, spread, depth, fill rate, and slippage.
4. Split local actors into explicit classes: competitor, house market maker,
   NPC flow, and benchmark.
5. Implement `mm-benchmark-passive`, `mm-tight-bluechip`,
   `badAggressiveRetail`, `noise`, and `institutional` as deterministic
   built-in profiles.
6. Add policy version and actor profile hash fields to run records and
   run-result ingestion payloads.
7. Enforce active scoring lock: economic policy cannot change for a non-terminal
   scoring run.
8. Add local smoke that runs one fixed seed twice and asserts identical
   economic report hashes.

## Open Questions

- Should maker rebates be paid in v0, or should v0 use zero maker fees until
  conduct controls mature?
- Should house market-maker losses be capped by hard withdrawal or by wider
  spread/inventory skew first?
- Should leaderboard final equity mark open positions at last midpoint, last
  trade, or liquidation value with spread penalty?
- Should public reports reveal NPC regime labels before a run, after a run, or
  never?
- Should game modes allow shorting and leverage before settlement/margin
  controls are fully represented?
