# Reef

## Technical Design Overview

## 1. Purpose

This document describes the initial technical design for Reef, a simulation-first institutional trading venue and post-trade platform.

Companion standards:
- [`docs/POST_MATCH_STANDARDS.md`](./docs/POST_MATCH_STANDARDS.md) defines normative post-match implementation standards, role model, calendar configuration rules, observability requirements, and exception taxonomy.

The design target is:

- realistic architecture and service boundaries
- strong inspectability and auditability
- local-first development ergonomics
- deterministic simulation capabilities
- clear seams for future extraction into more distributed deployment shapes

The system is intentionally **production-shaped but not prematurely production-optimized**.

## 2. Architectural Summary

Reef will be built as a multi-app, multi-language platform with a modular-first design.

### Chosen direction

- **Astro** for the marketing/documentation site
- **Kotlin** for the platform API/runtime and workflow orchestration
- **Go** for the matching/execution engine
- **Go** currently powers the simulator/load-testing CLI
- **Postgres** for canonical state
- **NATS** as the preferred messaging backbone once async service boundaries are introduced
- **Protobuf** as the preferred shared contract format between Kotlin and Go services

## 3. System Shape

At a high level, Reef consists of five major parts.

### 3.2 Platform runtime/API
A Kotlin service that acts as the central platform runtime.

Responsibilities:
- expose HTTP and WebSocket APIs
- handle commands from clients and simulation services
- orchestrate domain workflows
- persist canonical state
- publish domain events
- build read models for client consumption
- coordinate with the engine

### 3.2.1 External API boundary (public/client-facing)

The runtime must expose a stable user/consumer-facing boundary under versioned routes (for example `/api/v1`), even when implemented in-process initially.

Responsibilities:
- validate client requests and identity context
- enforce idempotency for mutating commands
- enforce per-client rate limits
- map public contracts to internal command models
- provide a stable integration surface decoupled from internal service transport

### 3.3 Matching engine
A Go service responsible for order book and matching behavior.

Responsibilities:
- maintain hidden order books
- receive submit/cancel/modify requests
- apply matching rules
- emit executions and order updates
- manage trading session state relevant to matching

### 3.4 Simulation harness
A simulator harness responsible for running deterministic scenarios. The original target was a Kotlin module inside the runtime; the current implementation uses a separate Go CLI/load-tester under `services/simulator` and should continue to drive the runtime through public command paths.

Responsibilities:
- load scenario definitions
- create participant bots and behavior profiles
- drive the simulated market clock
- submit commands into the platform
- inject faults and downstream failures
- coordinate replay and reset behavior

Design rule:
- simulator implementation language is less important than command-path parity, deterministic seeds, traceable run artifacts, and replay assertions.

Exploratory extension:
- [`docs/BOT_ARENA_PLAN.md`](./docs/BOT_ARENA_PLAN.md) proposes a future tournament-style bot arena on top of the simulation control plane. The arena would use sandboxed bot execution, tested operator-controlled liquidity and background-flow bots, modular game modes, replayable runs, separate arena storage for competition metadata, and leaderboard analytics while preserving venue command-path parity.
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./docs/STREAM_ACK_ARCHITECTURE_PLAN.md) defines the target high-throughput venue-ingress path for bot-arena scale: JetStream as the durable accepted-command log, deterministic partition workers, and Postgres as the canonical venue outcome/event store.

### 3.5 Admin operations surface (CLI first)

Admin controls should be implemented as application-layer use cases first and exposed via a CLI adapter in early phases.

Responsibilities:
- reference data administration
- simulation run/operator controls
- trace/event inspection and maintenance operations
- admin audit action logging

Design rule:
- admin HTTP APIs can be added later by reusing the same application-layer admin modules used by the CLI

### 3.6 Marketing/documentation site
An Astro site for public-facing docs, architecture pages, screenshots, and writeups.

## 4. Recommended Deployment Evolution

### Phase 1: modular monolith plus engine
Start with:
- one Kotlin runtime/API service
- one Go engine service
- one Postgres instance
- optional in-process event bus inside Kotlin runtime

This keeps development manageable while still preserving realistic boundaries.

### Phase 2: asynchronous backbone
Add:
- NATS for command/event messaging where useful
- separate simulator process if not already split
- more explicit read-model builders and background workers

### Phase 3: expanded platform
Add:
- more advanced simulation modes
- richer analytics and replay features
- possible extraction of selected workflows into separate workers/services

## 5. Core Architectural Principles

### 5.1 Simulation drives the real platform path
Simulation actors must use the same commands and APIs as users. They should not mutate platform state directly.

### 5.2 Core domain logic remains framework-light
Business rules, state machines, and event definitions should not be tightly coupled to Ktor or any framework adapter.

### 5.3 Hybrid persistence model
Prefer relational current-state tables plus an append-only event log, rather than full event sourcing on day one.

### 5.4 Every major state change is evented
Meaningful transitions should emit versioned events for audit, replay, and UI timelines.

### 5.5 Bounded contexts first, microservices later
Strong modular boundaries should exist from the beginning, but deployment should remain simple until complexity justifies extraction.

## 6. Bounded Contexts

Reef should be organized around explicit business domains.

### 6.1 Reference data
Owns:
- instruments
- participants
- legal entities
- accounts
- books
- calendars
- venue rules
- settlement instruction profiles

### 6.2 Orders and execution
Owns:
- order intake
- order validation
- order lifecycle state
- engine integration
- execution intake
- trade creation

### 6.3 Trade processing
Owns:
- allocations
- booking logic
- trade enrichment
- commission and fee calculation
- account splits

### 6.4 Post-trade workflow
Owns:
- confirmations
- affirmations
- mismatch detection
- workflow deadlines

### 6.5 Settlement
Owns:
- settlement obligations
- cash and security movement modeling
- settlement state transitions
- fail records
- settlement completion/failure outcomes

### 6.6 Clearing and netting
Owns:
- clearing submission and acceptance workflow
- CCP-style novation lifecycle
- participant risk-check outcomes for clearing eligibility
- netting of trade obligations by participant, instrument, and settlement date
- netted obligation snapshots and adjustments

### 6.7 Exceptions and operations
Owns:
- break queues
- exception cases
- repair actions
- escalation state

### 6.8 Simulation control
Owns:
- scenarios
- participant bots
- behavior profiles
- market clock
- replay runs
- fault injection

### 6.9 Audit and analytics
Owns:
- event timeline views
- derived metrics
- venue analytics
- run summaries
- traceability across trade lifecycles

## 7. Core Domain Flow

The foundational end-to-end flow for v1 is:

1. Reference data exists for participants, accounts, and instruments.
2. A manual user or simulator submits an order command.
3. The platform validates and forwards the order to the Go engine.
4. The engine updates the hidden order book and returns execution results or order status updates.
5. The platform persists trades/executions and emits resulting domain events.
6. Trade processing creates allocation and booking records.
7. Post-trade workflows generate confirmations and track mismatches.
8. Settlement creates obligations and progresses them through a configurable lifecycle.
9. Exceptions are raised when downstream steps fail or mismatch.
10. The UI presents both current-state views and event timelines.

### 7.1 Expanded post-match flow (target operating model)

To simulate a production-shaped U.S. equities post-trade lifecycle, Reef should model explicit stages after match:

1. execution capture records fills from the engine with normalized economics and identifiers.
2. trade processing applies enrichment, allocation, booking, and fee placeholders.
3. confirmation and affirmation handles compare states, mismatch detection, and workflow deadlines.
4. clearing receives affirmed trades, performs acceptance or rejection, and records novation transitions.
5. netting consolidates obligations by participant and instrument into settlement-ready net positions.
6. settlement orchestrates instruction, settlement attempts, partial completion, fail aging, and repair paths.
7. exceptions and operations provides actionable queues, ownership, and repair commands across all stages.
8. audit and analytics preserves immutable traceability from order to final settlement outcome.

Design rule:
- each stage must remain independently observable and replayable; no hidden "black-box" transition from trade to settled.

### 7.2 V1 vertical slice (locked scope)

The first milestone is one deterministic lifecycle scenario, not broad feature coverage.

V1 baseline:
- one instrument profile
- hidden midpoint cross behavior
- one complete happy path
- one deterministic broken path with operator repair

Command-level target flow:
1. `SubmitOrder`
2. `MatchOrders` (engine output)
3. `CreateTrade`
4. `AllocateTrade`
5. `GenerateConfirmation`
6. `AffirmTrade` or `MismatchTrade`
7. `CreateSettlementObligation`
8. `CompleteSettlement` or `FailSettlement`
9. `OpenException` and `ResolveException` when required

V1 acceptance criteria:
- same seed produces the same event sequence
- each command/event carries `scenarioRunId`, `correlationId`, and `causationId`
- broken path always opens an exception and supports repair-driven settlement completion
- full lifecycle is visible in timeline and current-state read models

## 8. Suggested Event Model

The system should use explicit commands and events.

### 8.1 Commands
Examples:
- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `AllocateTrade`
- `GenerateConfirmation`
- `AffirmTrade`
- `SubmitForClearing`
- `AcceptClearing`
- `RejectClearing`
- `NetSettlementObligations`
- `ScheduleSettlement`
- `InstructSettlement`
- `AdvanceSettlement`
- `ResolveException`
- `StartScenarioRun`
- `PauseMarketClock`
- `InjectFault`

### 8.2 Events
Examples:
- `OrderSubmitted`
- `OrderAccepted`
- `OrderRejected`
- `OrderRested`
- `TradeMatched`
- `ExecutionCreated`
- `TradeAllocated`
- `TradeBooked`
- `ConfirmationGenerated`
- `TradeCompared`
- `TradeAffirmed`
- `TradeMismatched`
- `ClearingSubmitted`
- `ClearingAccepted`
- `ClearingRejected`
- `TradeNovated`
- `ObligationsNetted`
- `AffirmationFailed`
- `SettlementObligationCreated`
- `SettlementScheduled`
- `SettlementInstructionCreated`
- `SettlementPartiallyCompleted`
- `SettlementCompleted`
- `SettlementFailed`
- `SettlementFailAged`
- `ExceptionOpened`
- `ExceptionResolved`
- `ScenarioRunStarted`
- `ScenarioRunCompleted`

### 8.3 Event design rules
- events must be immutable
- events must have versioned schemas
- events must include trace/correlation identifiers where possible
- events must be suitable for append-only storage
- event handlers should be idempotent

## 8.4 Event envelope standards

All meaningful domain events must carry:
- `eventId`
- `eventType`
- `traceId`
- `causationId`
- `correlationId`
- `scenarioRunId` (required for scenario-driven activity)
- `occurredAt`
- producer/service identity
- schema version

### 8.5 Canonical identifier strategy

Use stable business identifiers from the beginning to avoid replay and traceability churn.

Required identifiers:
- `orderId`
- `clientOrderId`
- `executionId`
- `tradeId`
- `allocationId`
- `confirmationId`
- `settlementObligationId`
- `exceptionId`
- `scenarioRunId`
- `correlationId`
- `causationId`

## 9. State Machines

Key workflows should be implemented as explicit state machines rather than loosely managed status fields.

### 9.1 Order lifecycle
Potential states:
- pending_validation
- accepted
- rejected
- resting
- partially_filled
- filled
- cancelled
- expired

### 9.2 Trade lifecycle
Potential states:
- created
- allocated
- booked
- compared
- confirmed
- affirmed
- clearing_submitted
- clearing_accepted
- novated
- ready_for_settlement
- settled
- failed
- broken

### 9.3 Settlement lifecycle
Potential states:
- obligation_created
- pending_instruction
- scheduled
- in_settlement
- partially_settled
- settled
- failed
- aged_fail
- repaired

### 9.4 Exception lifecycle
Potential states:
- opened
- assigned
- under_review
- repaired
- resolved
- closed

## 10. Data Design Strategy

### 10.1 Canonical relational state
Use Postgres for current-state records.

Expected core tables:
- `participants`
- `accounts`
- `instruments`
- `orders`
- `executions`
- `trades`
- `allocations`
- `confirmations`
- `settlement_obligations`
- `exceptions`
- `scenario_runs`
- `audit_entries` or projections for UI support

## 11. Communication and API Standards

### 11.1 Inter-service communication

Direction:
- Protobuf contracts in `contracts/proto/`
- gRPC as the preferred runtime-to-engine transport
- temporary HTTP/JSON compatibility path during migration via feature flags

Rules:
- contracts are versioned and additive-first
- command IDs are treated as idempotency keys
- retries must be bounded and idempotency-safe
- trace/correlation metadata must propagate across service calls

### 11.2 Public API boundary

Near-term architecture:
- implement the public boundary in Kotlin runtime
- keep API routes versioned under `/api/v1`
- preserve clean module seams to allow later gateway extraction
- expose only explicit product-facing API families:
  - venue intake and trading information for order entry, command status, participant order state, executions, trade tape, and current market data
  - admin/data for operator-approved administration plus intraday and historical data access
- never expose raw internal HTTP routes as product APIs
- canonical surface policy: [`docs/API_SURFACE_POLICY.md`](./docs/API_SURFACE_POLICY.md)

Boundary requirements:
- authentication hook (token/API key)
- idempotency key requirement for writes
- rate limiting at client scope
- structured error envelope with correlation IDs
- audit-safe structured logging

### 11.3 Admin layer

Direction:
- application-layer admin use cases first
- CLI adapter first
- internal admin/control interfaces use gRPC/protobuf by default
- admin/data HTTP APIs may be added later only as gateway-backed, versioned, authenticated, audited adapters reusing the same modules
- raw `/internal/*` HTTP access is local/migration tooling, not a deployable user surface

Admin command requirements:
- actor context
- authorization checks
- audit event emission
- idempotency for mutating operations

Early admin priorities for post-match realism:
- calendar profile management (country-agnostic with U.S. defaults)
- role and permission administration for core operational personas
- policy version management (netting and settlement profiles)

## 12. Scalability Posture (Early)

To avoid design lock-in:
- keep boundary components stateless where possible
- maintain write/read model separation
- preserve transport abstraction between runtime and engine
- maintain append-only event trail for replay and diagnostics
- avoid coupling public API shapes to internal engine transport contracts

### 12.1 Event log
Maintain an append-only `event_log` table with at least:
- event id
- event type
- event version
- aggregate type
- aggregate id
- correlation id
- causation id
- occurred at timestamp
- actor/source
- payload

This supports:
- replay
- audit trails
- timeline UIs
- debugging
- scenario analysis

### 12.2 Read models
Maintain denormalized read models tailored for UI workflows, such as:
- order blotter views
- trade timeline views
- settlement queue summaries
- exception workbench summaries
- scenario run dashboards

## 13. Platform Runtime Design (Kotlin)

The Kotlin runtime is the central orchestrator of business workflows.

### Responsibilities
- API exposure
- authentication/session plumbing for local environments
- command handling
- orchestration across bounded contexts
- persistence and transaction coordination
- read model updates
- event publication
- engine communication
- websocket push updates for UIs

### Framework direction
Prefer a lightweight stack such as **Ktor** to keep architecture explicit and reduce framework magic.

### Internal module layering
Suggested structure inside Kotlin services:
- `domain` — entities, value objects, invariants, state machines
- `application` — command handlers, use cases, orchestration
- `infrastructure` — database adapters, messaging, engine clients, framework adapters

## 14. Matching Engine Design (Go)

The Go engine should be narrowly focused.

### Responsibilities
- receive order actions
- maintain hidden order books
- apply matching rules
- emit execution results and order state changes
- manage engine-local market session behavior

### Non-responsibilities
The engine should not own:
- allocations
- settlement workflows
- exception handling
- broader platform orchestration
- UI-facing read models

### Engine contract examples
Commands into engine:
- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `OpenSession`
- `CloseSession`

Events/results from engine:
- `OrderAccepted`
- `OrderRejected`
- `OrderRested`
- `ExecutionCreated`
- `OrderCancelled`

### Communication options
Initial acceptable options:
- HTTP/JSON
- gRPC using protobuf

Likely preferred medium-term option:
- protobuf contracts with gRPC or NATS-backed messaging

## 14.1 Runner-first post-match architecture (Kotlin runtime modules first)

Do not create deployable engines for every stage in v1. Use one orchestrator runner with extraction-ready module seams.

### A. Lifecycle runner
Responsibilities:
- own end-to-end workflow progression
- invoke bounded modules in-process using command interfaces
- enforce deterministic sequencing and idempotent command handling

Suggested names:
- `LifecycleRunner`
- `PostTradeRunner`
- `WorkflowRunner`

### B. Bounded modules (initial in-process seams)
Required modules:
- `OrderExecutionModule`
- `TradeProcessingModule`
- `PostTradeModule` (confirmation and affirmation)
- `SettlementModule`
- `ExceptionModule`
- `SimulationControlModule`

Module rules:
- module interaction is command/event based, not direct table mutation
- each module owns invariants and state transitions
- write paths are idempotency-safe by command ID
- contracts are transport-neutral and versioned for later extraction

### C. Service extraction criteria (future)
Split a module into its own service only when one or more are true:
- scale profile diverges from runtime core
- failure isolation is required
- team ownership requires independent deploy cadence
- async transport provides measurable operational value

## 14.2 Environment model: production-shaped and simulation modes

Reef should support two modes without changing core domain behavior:

### Production-shaped mode
- realistic workflow timing windows and operational deadlines
- stricter validation and failure handling
- deterministic but externally paced clock behavior
- integration-ready adapter boundaries for external systems

### Simulation mode
- controllable market and settlement clocks
- seeded determinism for replay
- scenario-driven fault injection at each post-match stage
- accelerated lifecycle progression for rapid experimentation

Mode rule:
- both modes must execute the same application commands and state machines; mode only adjusts timing, adapters, and failure injection profiles.

Policy rule:
- deadlines and settlement-cycle behavior (for example affirmation cutoffs and `T+1`) must be configuration-driven, not hardcoded.

## 14.3 Settlement ledger adapter strategy

Settlement state should be modeled behind an explicit adapter interface:

- `RelationalLedgerAdapter` for default Postgres-backed lifecycle state and history.
- `EventLogLedgerAdapter` for append-only event-first settlement audit paths.
- `BlockchainLedgerAdapter` for optional scenario experiments with tokenized obligations, atomic delivery-versus-payment simulation, or external finality proofs.

Adapter design rules:
- domain-level settlement semantics remain constant across adapters.
- adapter selection is environment-configurable, not hardcoded in domain modules.
- blockchain-backed modes are optional experiments and must not block baseline delivery.
- default v1 implementation is a relational account-style ledger with append-only postings plus compensating corrections.

## 15. Simulation Harness Design

The simulator harness should act like a specialized control-plane client.

Current implementation note:
- `services/simulator` is a Go CLI/load-testing harness.
- future simulator/control-room work may add a local control API and UI, but must preserve the same platform command paths and report artifacts.

### Responsibilities
- parse and execute scenario definitions
- create synthetic participant behavior
- manage seeded randomness
- control a simulated market clock
- invoke platform commands
- record scenario-level metadata
- inject faults and failures intentionally

### Scenario requirements
Scenarios should be:
- deterministic when given a seed
- versionable in source control
- composable and data-driven
- inspectable after a run

### Potential scenario inputs
- instrument universe
- participant definitions
- behavior profiles
- liquidity patterns
- order waves
- timing windows
- downstream failures
- settlement rule overrides

## 17. Messaging Strategy

### Early phase
Use an in-process event bus inside the Kotlin runtime where appropriate. Keep complexity low.

### Expansion phase
Introduce **NATS** as the lightweight async backbone.

Potential uses:
- durable accepted-command ingress for high-throughput `stream-ack` mode
- publishing domain events
- decoupling read-model updaters
- simulator notifications
- engine integration if desired
- background workers

For the bot-arena scaling track, NATS/JetStream is not only a fanout bus. It is the target retained command-ingress log for accepted commands, while Postgres remains authoritative for command results and venue lifecycle events.

## 18. Shared Contracts

Cross-language contracts should be explicit and versioned.

### Preferred direction
Use **Protobuf** for:
- engine commands and responses
- shared event definitions
- selected API DTOs if helpful

### Benefits
- Kotlin and Go interoperability
- versioning discipline
- future gRPC support
- reduced contract drift

## 19. Repository Structure

A pragmatic monorepo is recommended.

```text
reef/
  apps/
    docs-site/            # Astro

  services/
    platform-runtime/     # Kotlin platform runtime
    simulator/            # Go simulator/load-testing harness
    matching-engine/      # Go matching engine

  contracts/
    proto/                # protobuf schemas / shared contracts

  packages/
    scenario-definitions/ # scenario definitions and fixtures

  scripts/
    dev/
    ci/

  docs/
    steering/
```

## 20. Development Workflow Goals

Local development should remain simple.

### Desired local workflow
- start Postgres
- start the Kotlin platform runtime
- start the Go engine
- seed data and run a scenario
- inspect orders, trades, settlements, and events live

### Ideal commands later
- `make dev`
- `make seed`
- `make scenario-demo`
- `make reset`

## 21. MVP Scope Recommendation

The first thin slice should prove the architecture, not complete every market workflow.

### MVP thin slice
- define basic reference data
- submit hidden buy/sell orders
- match in engine
- create execution and trade records
- allocate a trade
- generate a confirmation
- create settlement obligations
- complete some settlements and fail others
- display current-state views and event timelines in the UI

This slice is enough to validate:
- core domain boundaries
- engine/runtime integration
- event logging
- read-model approach
- UI workflows
- simulation viability

## 22. Future Expansion Areas

Once the thin slice is working, likely next areas are:
- richer scenario DSL
- participant bot strategies
- conditional indications of interest
- market sessions and trading calendars
- fault libraries
- analytics and venue KPIs
- replay controls and time-travel inspection
- more advanced post-trade workflows

## 23. Risks and Tradeoffs

### 23.1 Multi-language complexity
Kotlin + Go adds tooling complexity. This is acceptable if contract boundaries are explicit and the repo stays organized.

### 23.2 Framework drift
If the Kotlin layer becomes overly framework-dependent, future adaptability drops. Keep domain and application code clean.

### 23.3 Premature distribution
Too many deployables too early will slow progress. Keep the runtime consolidated until real need appears.

### 23.4 Over-modeling post-trade too early
The project can become huge quickly. Favor a thin but coherent vertical slice first.

## 24. Immediate Next Steps

1. Normalize the repository structure around apps, services, libs, infra, and docs.
2. Define shared contracts for the initial order and execution flow.
3. Create the Kotlin runtime skeleton with basic command handling and persistence.
4. Create the Go engine skeleton with a minimal hidden order book.
5. Build the first deterministic scenario that produces a full lifecycle from order submission to settlement outcome.

## 25. One-Sentence Architecture Statement

**Reef should be built as a simulation-first institutional trading platform with a Kotlin platform runtime, a Go matching engine, relational current-state persistence plus an append-only event log, and a separate control plane for deterministic scenario execution and replay.**
