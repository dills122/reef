# Reef

## Technical Design Overview

## 1. Purpose

This document describes the initial technical design for Reef, a simulation-first institutional trading venue and post-trade platform.

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

- **Angular** for operational and simulation UIs
- **Astro** for the marketing/documentation site
- **Kotlin** for the platform API/runtime and simulator harness
- **Go** for the matching/execution engine
- **Postgres** for canonical state
- **NATS** as the preferred messaging backbone once async service boundaries are introduced
- **Protobuf** as the preferred shared contract format between Kotlin and Go services

## 3. System Shape

At a high level, Reef consists of five major parts.

### 3.1 Platform UI layer
Angular applications for operational users and simulation control.

Planned surfaces:
- simulator/control room UI
- operations and post-trade UI
- admin/reference data UI
- audit and event explorer UI

### 3.2 Platform runtime/API
A Kotlin service that acts as the central platform runtime.

Responsibilities:
- expose HTTP and WebSocket APIs
- handle commands from UIs and simulation services
- orchestrate domain workflows
- persist canonical state
- publish domain events
- build read models for UI consumption
- coordinate with the engine

### 3.3 Matching engine
A Go service responsible for order book and matching behavior.

Responsibilities:
- maintain hidden order books
- receive submit/cancel/modify requests
- apply matching rules
- emit executions and order updates
- manage trading session state relevant to matching

### 3.4 Simulation harness
A Kotlin service or module responsible for running deterministic scenarios.

Responsibilities:
- load scenario definitions
- create participant bots and behavior profiles
- drive the simulated market clock
- submit commands into the platform
- inject faults and downstream failures
- coordinate replay and reset behavior

### 3.5 Marketing/documentation site
An Astro site for public-facing docs, architecture pages, screenshots, and writeups.

## 4. Recommended Deployment Evolution

### Phase 1: modular monolith plus engine
Start with:
- one Kotlin runtime/API service
- one Go engine service
- one Postgres instance
- one Angular UI
- optional in-process event bus inside Kotlin runtime

This keeps development manageable while still preserving realistic boundaries.

### Phase 2: asynchronous backbone
Add:
- NATS for command/event messaging where useful
- separate simulator process if not already split
- more explicit read-model builders and background workers

### Phase 3: expanded platform
Add:
- multiple Angular UIs or shells
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

### 6.6 Exceptions and operations
Owns:
- break queues
- exception cases
- repair actions
- escalation state

### 6.7 Simulation control
Owns:
- scenarios
- participant bots
- behavior profiles
- market clock
- replay runs
- fault injection

### 6.8 Audit and analytics
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
- `ScheduleSettlement`
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
- `AffirmationFailed`
- `SettlementObligationCreated`
- `SettlementScheduled`
- `SettlementCompleted`
- `SettlementFailed`
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
- confirmed
- affirmed
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

### 10.2 Event log
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

### 10.3 Read models
Maintain denormalized read models tailored for UI workflows, such as:
- order blotter views
- trade timeline views
- settlement queue summaries
- exception workbench summaries
- scenario run dashboards

## 11. Platform Runtime Design (Kotlin)

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

## 12. Matching Engine Design (Go)

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

## 13. Simulation Harness Design (Kotlin)

The simulator harness should act like a specialized control-plane client.

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

## 14. UI Design Direction (Angular)

Angular should power operational platform surfaces.

### Candidate apps or feature areas
- simulator/control room
- venue monitor
- order blotter
- trade processing console
- settlement console
- exception desk
- audit explorer
- admin/reference data management

### UI data strategy
- read models for grids and dashboards
- websocket push for live updates where useful
- route-level feature isolation
- strong typing against shared API contracts

### State management direction
- use Angular signals for local UI state where possible
- use broader store patterns only where state is genuinely cross-cutting or streaming-heavy

## 15. Messaging Strategy

### Early phase
Use an in-process event bus inside the Kotlin runtime where appropriate. Keep complexity low.

### Expansion phase
Introduce **NATS** as the lightweight async backbone.

Potential uses:
- publishing domain events
- decoupling read-model updaters
- simulator notifications
- engine integration if desired
- background workers

## 16. Shared Contracts

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

## 17. Repository Structure

A pragmatic monorepo is recommended.

```text
reef/
  apps/
    reef-sim-ui/          # Angular
    reef-ops-ui/          # Angular, if later split
    reef-site/            # Astro

  services/
    reef-api/             # Kotlin platform runtime
    reef-simulator/       # Kotlin simulation harness
    reef-engine/          # Go matching engine

  libs/
    reef-protocol/        # protobuf schemas / shared contracts
    reef-scenarios/       # scenario definitions and fixtures
    reef-docs/            # optional markdown/spec assets

  infra/
    docker/
    dev/
    db/

  docs/
    overview/
    architecture/
    decisions/
```

## 18. Development Workflow Goals

Local development should remain simple.

### Desired local workflow
- start Postgres
- start the Kotlin platform runtime
- start the Go engine
- run Angular UI locally
- seed data and run a scenario
- inspect orders, trades, settlements, and events live

### Ideal commands later
- `make dev`
- `make seed`
- `make scenario-demo`
- `make reset`

## 19. MVP Scope Recommendation

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

## 20. Future Expansion Areas

Once the thin slice is working, likely next areas are:
- richer scenario DSL
- participant bot strategies
- conditional indications of interest
- market sessions and trading calendars
- fault libraries
- analytics and venue KPIs
- replay controls and time-travel inspection
- more advanced post-trade workflows

## 21. Risks and Tradeoffs

### 21.1 Multi-language complexity
Angular + Kotlin + Go adds tooling complexity. This is acceptable if contract boundaries are explicit and the repo stays organized.

### 21.2 Framework drift
If the Kotlin layer becomes overly framework-dependent, future adaptability drops. Keep domain and application code clean.

### 21.3 Premature distribution
Too many deployables too early will slow progress. Keep the runtime consolidated until real need appears.

### 21.4 Over-modeling post-trade too early
The project can become huge quickly. Favor a thin but coherent vertical slice first.

## 22. Immediate Next Steps

1. Normalize the repository structure around apps, services, libs, infra, and docs.
2. Define shared contracts for the initial order and execution flow.
3. Create the Kotlin runtime skeleton with basic command handling and persistence.
4. Create the Go engine skeleton with a minimal hidden order book.
5. Stand up the first Angular shell with basic event and blotter views.
6. Build the first deterministic scenario that produces a full lifecycle from order submission to settlement outcome.

## 23. One-Sentence Architecture Statement

**Reef should be built as a simulation-first institutional trading platform with Angular operational UIs, a Kotlin platform runtime, a Go matching engine, relational current-state persistence plus an append-only event log, and a separate control plane for deterministic scenario execution and replay.**
