# Reef Architecture Steering

## Mission

Reef is a simulation-first institutional trading venue and post-trade platform.

The architecture must support:

- realistic domain boundaries
- local-first development
- deterministic simulation
- inspectable workflows
- replayable event history

The platform should be production-shaped, but not prematurely production-optimized.

## System Shape

Reef is expected to grow into these primary surfaces:

- Angular platform UI for operations, simulation control, admin, and audit views
- Astro documentation/marketing site
- Kotlin platform runtime for APIs, workflow orchestration, persistence, and read models
- Go matching engine for order book and matching behavior
- Postgres as canonical state storage
- NATS as the preferred async backbone once service boundaries justify it
- Protobuf as the preferred inter-service contract format once contracts stabilize

## Architectural Rules

### 1. Simulation uses real platform paths

Simulation actors must submit the same commands manual users submit.
They must not write directly into domain tables or bypass workflow boundaries.

### 2. Core domain logic stays framework-light

Business rules, workflow state transitions, and domain events should live outside HTTP adapters, UI code, and persistence details.

### 3. Bounded contexts come before service extraction

Maintain clean module boundaries from the start, but avoid splitting into separate deployables until there is a concrete reason.

### 4. Every major transition is evented

Meaningful state changes must emit explicit events suitable for audit trails, timeline views, and replay support.

### 5. Hybrid persistence is the default

Prefer current-state relational tables plus an append-only event log.
Do not adopt full event sourcing unless the project proves it is necessary.

### 6. Determinism matters

Simulation paths should be seedable, replayable, and explainable.
Avoid hidden time dependencies or untracked randomness in scenario execution.

## Bounded Contexts

The initial domain breakdown should follow these contexts:

- reference-data
- orders-and-execution
- trade-processing
- post-trade-workflow
- clearing-and-netting
- settlement
- exceptions-and-operations
- simulation-control
- audit-and-analytics

These may begin as modules in a single service, but code should still reflect the boundary names and ownership.

## Initial Deployment Shape

Phase 1 should bias toward a modular monolith plus separate engine:

- one Kotlin runtime/API service
- one Go matching engine service
- one Postgres database
- one Angular application
- optional in-process event dispatcher inside the Kotlin runtime

This is the default unless there is a strong reason to distribute more aggressively.

## Repository Shape

Use this target layout as the default:

```text
apps/
  platform-ui/
  docs-site/
services/
  platform-runtime/
  matching-engine/
  simulator/
contracts/
  proto/
packages/
  ui-models/
  scenario-definitions/
docs/
  steering/
```

Additional folders are acceptable when justified, but avoid mixing runtime, UI, and contract code in the same top-level area.

## Contract Guidance

- model state changes as commands and events
- keep contracts explicit and versionable
- prefer protobuf for Kotlin-Go contracts once shapes settle
- prefer stable identifiers over positional coupling
- treat timestamps, seeds, correlation IDs, and actor identity as first-class metadata

Examples of early commands:

- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `AllocateTrade`
- `GenerateConfirmation`
- `AdvanceSettlement`
- `StartScenarioRun`
- `PauseScenarioRun`

Examples of early events:

- `OrderSubmitted`
- `OrderAccepted`
- `OrderRejected`
- `ExecutionCreated`
- `TradeCreated`
- `TradeAllocated`
- `ConfirmationGenerated`
- `SettlementObligationCreated`
- `ExceptionRaised`
- `ScenarioRunStarted`

## Persistence Guidance

- Postgres is the source of truth for current state
- maintain append-only event storage alongside current-state tables
- read models may be rebuilt from persisted events and state transitions where useful
- do not let UI-specific projections leak back into core write-model logic

At minimum, plan for:

- reference data tables
- order and execution tables
- trade and allocation tables
- workflow state tables
- settlement obligation tables
- exception case tables
- event log tables

## Integration Guidance

- start with the simplest interface that preserves service boundaries
- synchronous HTTP or gRPC between runtime and engine is acceptable early on
- introduce NATS when async workflows materially improve the design
- never let transport concerns define the domain model

For communication and API specifics, treat these as normative companions:

- [`inter-service-communication.md`](./inter-service-communication.md)
- [`external-api-boundary.md`](./external-api-boundary.md)

## Tooling and Automation

- development and operational automation should default to `bun`-executed scripts under `scripts/`
- `make` targets should delegate to those scripts and remain lightweight wrappers
- deterministic environment setup/reset/smoke flows are required for local-first development

## Observability and Audit

Every service should make it easy to answer:

- who issued the command
- what changed
- when it changed
- why it changed
- which downstream actions it triggered

Use:

- structured logs
- correlation IDs
- durable event records
- traceable workflow identifiers

## Testing Strategy

Favor a layered approach:

- domain unit tests for workflow and state transitions
- contract tests for runtime-engine integration
- repository tests for persistence mappings
- UI tests for workflow-critical surfaces
- scenario tests for deterministic replay behavior

Simulation tests must verify determinism, not just activity generation.

## Non-Goals For Early Phases

Avoid early investment in:

- low-latency optimization
- real market connectivity
- production-scale infrastructure
- excessive service fragmentation
- event-sourcing purity
