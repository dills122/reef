Absolutely — here’s a clean handoff overview for Codex.

## Codex Handoff: Simulator Persona + Session Config Design

Reef’s simulator should evolve from a percentage-based load tester into a **config-driven market simulation runner**. The goal is to define full simulation sessions through YAML/JSON files, including market universe, actor personas, trading strategies, actor groups, runtime controls, deterministic seeds, and optional fault injection.

This should remain part of the existing simulator/load-tester flow for now, not a separate binary. The design should preserve Reef’s broader rule that simulations drive the platform through the same commands/APIs as real users.

````md
# Simulator Persona + Session Config Design

## Goal

Introduce `--session-config` support so a simulation run can be fully defined by a YAML or JSON config file.

The config should define:

- session metadata and deterministic seed
- runtime/load settings
- market universe and per-symbol conditions
- named actors/personas
- reusable trading strategy profiles
- generated actor groups
- optional deterministic faults
- actor-level attribution in run output

## Core Concepts

Each simulated actor should be modeled using four concepts:

Actor Type → Persona → Strategy Profile → Universe Eligibility

Example:

```yaml
id: mm-tech-1
type: market_maker
persona: electronic_liquidity_provider
universe:
  symbols: [AAPL, MSFT, NVDA]
strategyProfile: tight_two_sided_quotes
```
````

This answers:

1. Who is the actor?
2. What can they trade?
3. How do they behave?
4. How much activity do they generate?

## Top-Level Config Shape

```yaml
session:
  id: multi-actor-hidden-liquidity-demo
  seed: 12345
  mode: scenario
  description: Multi-actor hidden liquidity simulation

runtime:
  baseUrl: http://localhost:8080
  durationSeconds: 300
  workers: 8
  requestRatePerSecond: 25
  timeoutMs: 5000

market:
  clock:
    start: "2026-05-22T09:30:00-04:00"
    speed: 10x
  instruments:
    - symbol: AAPL
      instrumentId: inst-aapl
      startingPriceNanos: 190000000000
      spreadBps: 8
      volatilityBps: 120
      avgDailyVolume: 50000000
      sharesOutstanding: 15500000000
      marketCap: 2950000000000
      sector: technology

strategyProfiles:
  tight_two_sided_quotes:
    strategy: two_sided_quote
    params:
      quoteWidthBps: 10
      requoteEveryMs: 1000
      maxInventory: 10000
      inventorySkew: medium

  patient_accumulator:
    strategy: sliced_parent_order
    params:
      urgency: low
      sliceSize: 500
      maxParticipationRateBps: 50

  aggressive_momentum:
    strategy: momentum_follow
    params:
      lookbackTicks: 20
      aggression: high
      cancelReplaceCadenceMs: 750

actors:
  - id: mm-tech-1
    type: market_maker
    persona: electronic_liquidity_provider
    universe:
      symbols: [AAPL, MSFT, NVDA]
    strategyProfile: tight_two_sided_quotes

  - id: fund-alpha
    type: institutional
    persona: mutual_fund
    universe:
      symbols: [AAPL]
    strategyProfile: patient_accumulator

actorGroups:
  - id: retail-crowd
    type: retail
    count: 30
    universe:
      symbols: [AAPL, MSFT, NVDA]
    personaDistribution:
      dip_buyer: 0.35
      breakout_chaser: 0.30
      passive_limit: 0.25
      noise_trader: 0.10
    strategyProfileDistribution:
      random_small_orders: 0.50
      passive_limit_entry: 0.30
      momentum_follow_light: 0.20

faults:
  - id: fail-settlement-aapl
    atClockTime: "2026-05-22T15:45:00-04:00"
    type: settlement_fail
    probability: 1.0
    appliesTo:
      symbol: AAPL
```

## Actor Types and Personas

Support these over time:

### Market Maker

Market makers should only trade configured equities, not the full universe by default.

Personas:

- `electronic_liquidity_provider`
- `specialist_market_maker`
- `opportunistic_liquidity_provider`

### Institutional

Personas:

- `mutual_fund`
- `hedge_fund`
- `pension_fund`
- `asset_manager`
- `index_fund`
- `quant_fund`

### Retail

Personas:

- `dip_buyer`
- `breakout_chaser`
- `passive_limit`
- `noise_trader`
- `panic_seller`

### System / Ops

Useful later for post-trade and fault simulation:

- `fault_injector`
- `settlement_ops`
- `affirmations_ops`
- `compliance_reviewer`

## Strategy Profile Design

Trading behavior should be abstracted into reusable `strategyProfiles`.

Actors should reference a profile by name.

This allows predefined strategies while still allowing custom/detailed ones later.

Examples:

```yaml
strategyProfiles:
  tight_two_sided_quotes:
    strategy: two_sided_quote
    params:
      quoteWidthBps: 10
      requoteEveryMs: 1000
      maxInventory: 10000

  passive_limit_entry:
    strategy: passive_limit
    params:
      priceOffsetBps: 15
      cancelAfterMs: 10000

  random_small_orders:
    strategy: random_order_flow
    params:
      minQty: 1
      maxQty: 100
      sideBias: neutral
```

Actors may optionally support overrides later:

```yaml
strategyOverrides:
  params:
    aggression: medium
```

## Actor Groups

Use `actorGroups` to generate many actors of a given type.

Example:

```yaml
actorGroups:
  - id: retail-crowd
    type: retail
    count: 30
    universe:
      symbols: [AAPL, MSFT, NVDA]
    personaDistribution:
      dip_buyer: 0.35
      breakout_chaser: 0.30
      passive_limit: 0.25
      noise_trader: 0.10
    strategyProfileDistribution:
      random_small_orders: 0.50
      passive_limit_entry: 0.30
      momentum_follow_light: 0.20
```

Generated actors should become deterministic runtime actors:

```text
retail-crowd-001
retail-crowd-002
retail-crowd-003
```

Each generated actor should receive a deterministic derived seed from:

```text
session.seed + actorGroup.id + generatedActorId
```

## Universe Eligibility

Every actor or actor group should define what it can trade.

Minimum v1 support:

```yaml
universe:
  symbols: [AAPL, MSFT, NVDA]
```

Later support:

```yaml
universe:
  sectors: [technology]
  minAvgDailyVolume: 10000000
  maxSpreadBps: 20
```

## Required Command Metadata

Every emitted platform command from the simulator should include:

- `scenarioRunId`
- `seed`
- `actorId`
- `actorType`
- `persona`
- `strategyId`
- `correlationId`
- `causationId`

This is important for replay, event timelines, debugging, and actor-level attribution.

## Implementation Phases

### Phase 1: Config Ingestion + Validation

- Add `--session-config`
- Support YAML or JSON
- Allow existing CLI flags to override config values
- Echo resolved config in run summary
- Validate required fields and references

Validation rules:

- `session.seed` is required
- actor IDs are unique
- actor group IDs are unique
- strategy profile names are unique
- actors reference existing strategy profiles
- actors only reference known symbols
- persona distributions total to `1.0`
- strategy distributions total to `1.0`
- prices, spreads, volatility, and quantities are positive
- runtime settings are sane

### Phase 2: Persona Runner

- Instantiate named actors from `actors`
- Generate actors from `actorGroups`
- Assign deterministic seeds
- Map actors to strategy modules
- Preserve existing summary/report output
- Add actor-level attribution

### Phase 3: Multi-Instrument Market Conditions

- Route order flow across configured symbol universes
- Apply per-symbol spread and volatility parameters
- Support market-maker symbol coverage
- Ensure actors cannot trade symbols outside their configured universe

### Phase 4: Faults + Replay Packs

- Add deterministic fault injection by `scenarioRunId`
- Support scenario fixture files under something like:

```text
packages/scenario-definitions
```

or:

```text
libs/reef-scenarios
```

- Add golden scenario regression tests

## Suggested Internal Package Shape

```text
services/simulator/
  cmd/load-tester/
    main.go

  internal/config/
    session_config.go
    validation.go
    defaults.go

  internal/actor/
    actor.go
    factory.go
    generated_actor.go

  internal/strategy/
    strategy.go
    two_sided_quote.go
    sliced_parent_order.go
    momentum_follow.go
    passive_limit.go
    random_order_flow.go

  internal/market/
    universe.go
    instrument.go
    conditions.go

  internal/runtime/
    runner.go
    scheduler.go
    seed.go
    attribution.go

  internal/faults/
    fault.go
    injector.go
```

## Exit Criteria

This workstream is complete when:

1. A session file can define a full run without long CLI flags.
2. Existing flags can override config values.
3. At least six named personas are supported.
4. At least five equities can be configured in one session.
5. Market makers can be limited to specific symbols.
6. Actor groups can generate many deterministic actors.
7. Strategies are reusable via named strategy profiles.
8. Every emitted command includes actor/session attribution.
9. Run summaries include actor-level and strategy-level attribution.
10. Config schema/validation tests exist before complex strategy expansion.

```

The most important coding direction for Codex: **do config/schema/validation first, then actor generation, then strategy complexity.**
```
