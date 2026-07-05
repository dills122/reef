# Reef Architecture Steering

## Mission

Reef is a simulation-first institutional trading venue and post-trade platform.

The architecture must support:

- realistic domain boundaries
- local-first development
- deterministic simulation
- inspectable workflows
- replayable event history
- high-throughput command intake and lifecycle processing under realistic simulator load
- auditability and replay evidence that remain intact when performance is optimized

The platform should be production-shaped and throughput-aware, but not an HFT benchmark harness. Correctness, determinism, and auditability stay ahead of raw speed claims.

## System Shape

Reef is expected to grow into these primary surfaces:

- Astro documentation/marketing site
- Kotlin platform runtime for APIs, workflow orchestration, persistence, and read models
- Go matching engine for order book and matching behavior
- Postgres for compact canonical materialization, operational projections, account/settlement state, and analytics stores, with domain split readiness
- Kafka-compatible durable command/event streams for high-throughput venue ingress and matching event batches, with NATS JetStream retained where it fits workflow or comparison use cases
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

### 7. Throughput is a first-class constraint

Command intake, matching, persistence, projection, and simulator load changes must account for sustained throughput, backlog, and write amplification. Performance work is invalid if it weakens lifecycle correctness, command idempotency, deterministic replay, traceability, or audit evidence.

### 8. Canonical facts stay separate from projections

Persist canonical command results and lifecycle events before acknowledging consumed work. Keep read models, dashboards, control-room counters, timelines, and search views rebuildable and preferably asynchronous, with explicit lag and watermark data where freshness matters.

### 9. Ordered partitions protect matching correctness

Submit, cancel, and modify commands that affect the same venue session and instrument must enter the same deterministic processing lane unless the lifecycle model proves a different concurrency design is correct.

## Bounded Contexts

The initial domain breakdown should follow these contexts:

- reference-data
- account-and-bot-ledger
- orders-and-execution
- market-data
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
- optional in-process event dispatcher inside the Kotlin runtime

This is the default unless there is a strong reason to distribute more aggressively.

The synchronous runtime-to-engine and Postgres-heavy path is a correctness baseline and local fallback. It should not be treated as the primary path to high sustained TPS unless comparable benchmark evidence proves that posture.

## Target High-Throughput Shape

For venue ingress and matching-heavy paths, the preferred direction is:

```text
Boundary/API
  -> durable command ingress
  -> matching-engine direct partition consumer
  -> shard-local hot book
  -> durable venue event batch
  -> compact canonical materialization
  -> async projections/read models/control-room views
```

Avoid expanding this synchronous shape for high-throughput paths:

```text
Boundary/API
  -> synchronous matching
  -> synchronous order writes
  -> synchronous event writes
  -> synchronous trade writes
  -> synchronous trace writes
  -> synchronous read-model writes
  -> response
```

That synchronous path can remain useful for strict lifecycle tests, debugging, parity comparisons, and local fallback modes.

## Repository Shape

Use this target layout as the default:

```text
apps/
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
- include routing metadata required to preserve deterministic partitioning, especially `venueSessionId`, `instrumentId`, and where applicable `scenarioRunId`
- distinguish attempted, durably accepted, processed, persisted, and projected command outcomes

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

- durable venue event batches are the canonical matching ledger for engine completion
- Postgres stores compact canonical materializations and domain state, not synchronous per-command hot-path write fan-out
- maintain append-only fact storage alongside current-state tables
- read models may be rebuilt from persisted events and state transitions where useful
- do not let UI-specific projections leak back into core write-model logic
- keep hot-path database writes minimal, explicit, and measured
- prefer batched canonical persistence over per-command persistence on throughput-sensitive paths
- prefer async projections for query-heavy UI/control-room state
- keep order-entry APIs separate from market-data/history APIs
- keep settlement and account ledgers downstream from matching facts, except for bounded intake risk pre-checks

At minimum, plan for:

- reference data tables
- canonical venue event batch and command outcome tables
- operational order, execution, and trade projections
- account/bot ledger tables
- market-data snapshot, depth, trade, and bar projections
- trade and allocation tables
- workflow state tables
- settlement obligation tables
- exception case tables
- event log tables

## Integration Guidance

- start with the simplest interface that preserves service boundaries
- synchronous HTTP or gRPC between runtime and engine is acceptable early on
- introduce NATS when async workflows materially improve the design
- use JetStream stream-backed command intake when the goal is durable accepted-command throughput under bot/simulator load
- return `202 Accepted` only after the configured durable ingress mechanism has acknowledged acceptance
- reject before durable acceptance when backpressure, stream health, partition lag, worker health, or DB flush lag indicates the platform cannot safely drain
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

Throughput and stress evidence should distinguish:

- attempted command rate
- durably accepted command rate
- processed command rate
- canonical event persistence rate
- projected/read-model catch-up rate
- business reject rate versus system failure rate
- queue or stream backlog, worker lag, and oldest unprocessed command age
- replay and trace validation result

## Testing Strategy

Favor a layered approach:

- domain unit tests for workflow and state transitions
- contract tests for runtime-engine integration
- repository tests for persistence mappings
- UI tests for workflow-critical surfaces
- scenario tests for deterministic replay behavior

Simulation tests must verify determinism, not just activity generation.

## Performance Discipline

Performance is a first-class architecture concern for Reef's lifecycle-heavy paths.

Use [`docs/PERFORMANCE_LEARNINGS.md`](../PERFORMANCE_LEARNINGS.md) as normative guidance for:

- throughput and latency budgets
- benchmark methodology
- write-path optimization defaults (pooling, batching, counter-based sequencing)
- PR-level performance evidence requirements

Also treat [`repository-scope-and-priorities.md`](./repository-scope-and-priorities.md) as normative for hot-path rules, command acceptance semantics, partitioning, canonical facts versus projections, and safe refactor boundaries.

## Non-Goals For Early Phases

Avoid early investment in:

- low-latency optimization
- real market connectivity
- production-scale infrastructure
- excessive service fragmentation
- event-sourcing purity

Do not confuse these non-goals with permission to ignore sustained throughput. Reef should be high-throughput enough to run realistic simulator and bot workloads with bounded backlog and validated replay.
