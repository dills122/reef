# Reef Work Plan

## Purpose

This document expands the technical design into a concrete implementation plan with workstreams, sequencing, and delivery checkpoints.

It is intended to answer:

- what should be built next
- how work should be grouped
- what dependencies exist between streams
- where to stop and reassess before adding complexity

## Planning Assumptions

- Reef remains Phase 1 deployment shape for the near term:
  - one Kotlin runtime
  - one Go matching engine
  - one Postgres database
  - one Angular application
- contracts may remain HTTP JSON initially, but should evolve toward protobuf once shapes stabilize
- simulation tooling may remain a separate Go CLI/control-plane client as long as it uses real runtime command paths and preserves deterministic run artifacts
- auditability and inspectability are treated as product requirements, not afterthoughts

## Current Status

Completed baseline as of 2026-06-04:

- repo skeleton and steering docs
- runtime and matching engine service implementation beyond initial shells
- CI/test scaffolding and local Docker automation
- initial order submission flow
- partial fills and multi-match behavior
- explicit engine-side order state
- cancel and modify paths through runtime and engine
- runtime-side persistence for accepted orders, executions, trades, and lifecycle events
- query endpoints for orders, trades, events, and trace timelines
- `/api/v1` boundary routes with idempotency, auth/rate-limit hooks, abuse protection, and command capture
- admin CLI scaffolding for reference data, roles, calendars, overrides, simulation controls, and trace inspection
- simulator/load tester with strict lifecycle, capacity baseline, persona/session config, deterministic replay, and throughput reports

Immediate implication:

- the system has a working API-first venue slice and strong simulator/stress tooling
- the next missing piece is storage/schema convergence and a complete persisted order lifecycle projection for submit/cancel/modify
- the UI should first expose local simulator/control-room workflows over existing artifacts before broad post-trade screens are added

Current planning review:
- [`docs/PROJECT_GOAL_PLAN_REVIEW.md`](./PROJECT_GOAL_PLAN_REVIEW.md)

## Delivery Strategy

The most effective sequence is:

1. complete the Phase 1 venue slice end to end
2. add inspectable query surfaces and audit/event read models
3. add reference data and operational UI basics
4. expand into post-trade lifecycle
5. add deterministic simulation and replay
6. only then introduce more distributed infrastructure

Execution rule:
- keep v1 brutally small and deterministic; add breadth via scenario packs only after the first end-to-end lifecycle is complete and replay-stable.

Current execution sprint for this sequence:
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)

Bridging sprint before post-match:
- [`docs/SPRINT_DEV_ENV.md`](./SPRINT_DEV_ENV.md)

Performance guardrails and PR checklist:
- [`docs/PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)

Engineering delivery mode and test policy:
- [`docs/ENGINEERING_DELIVERY_POLICY.md`](./ENGINEERING_DELIVERY_POLICY.md)

Next planned sprint block:
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)
- [`docs/EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)
- [`docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md`](./DATA_DOMAIN_SCHEMA_BLUEPRINT.md)

Current execution checkpoint before that block:
- complete migration ownership for durable runtime/boundary/auth/admin persistence now that compatibility bootstrap uses explicit domain schemas
- run the runtime DB/JSON/HTTP library benchmark gate before committing to hot-path library swaps
- deliver the simulator control-room MVP over existing CLI/report artifacts
- complete venue order-state projections for submit/cancel/modify

## Major Workstreams

Additional active workstream:

### Workstream K: Runtime Performance Library Investigation

Goal:
choose evidence-backed libraries and DB write strategies for the persistence sprint without speculative dependency churn.

Scope:
- runtime JSON parser/serializer benchmark
- runtime DB batching benchmark
- runtime JSONB payload construction strategy
- runtime HTTP boundary benchmark
- Go simulator JSON/client benchmark as a lower-priority follow-up

Exit criteria:
- pgjdbc prepared batch, explicit multi-row insert, and `reWriteBatchedInserts` results are compared against current write behavior
- `kotlinx.serialization` is benchmarked as the default typed JSON candidate
- DSL-JSON is evaluated only for ultra-hot DTOs if baseline JSON remains material
- Ktor Netty and Vert.x Web are benchmarked only after phase timing confirms HTTP server overhead is meaningful
- recommendation is recorded before adopting a new hot-path dependency

Primary plan:
- [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)

### Workstream H: Communication + Boundary + Admin Foundation

Goal:
establish production-shaped service communication, public API boundary foundations, and admin layer architecture without overbuilding deployment complexity.

Scope:
- transport abstraction and gRPC/protobuf migration scaffold
- `/api/v1` public boundary and middleware hooks
- CLI-first admin application layer with reusable command modules
- foundational policy codification for idempotency scope, schema governance, deterministic clocks, and override auditing

Exit criteria:
- runtime transport path supports HTTP and gRPC selection
- boundary writes enforce idempotency + auth/rate-limit hooks
- admin operations run through application layer and audit-event emission
- calendar/role admin paths and policy guardrails are implementation-ready and documented

### Workstream A: Runtime Query and Audit Surface

Goal:
make persisted runtime state queryable and inspectable.

Scope:

- query endpoints for orders, executions, and trades
- order detail and trade detail response shapes
- append-only event log model
- event emission for current venue flow
- audit timeline read model

Exit criteria:

- submit flow persists results
- persisted results can be queried directly
- each meaningful transition creates an event record
- a trade can be traced from command to current state

Status:
- mostly implemented for submit/order/trade/event/trace queryability
- remaining gap is complete current-state projection for cancel/modify and durable outbox-backed publication

### Workstream B: Reference Data Foundation

Goal:
create the minimum static domain data required to stop hardcoding identifiers.

Scope:

- instruments
- participants
- accounts
- books or venues if needed for order submission context
- basic admin APIs and validation rules

Exit criteria:

- orders reference persisted entities instead of arbitrary strings
- runtime validation depends on stored reference data
- test fixtures exist for default venue setup

### Workstream C: Venue Workflow Hardening

Goal:
finish the minimal order lifecycle around the engine.

Scope:

- cancel order path
- modify order path
- order state transitions and runtime projections
- more complete engine order status model
- resting book and blotter read models

Exit criteria:

- order lifecycle includes submit, rest, partial fill, fill, cancel, reject
- runtime and engine stay consistent across lifecycle transitions
- query APIs reflect current order state and history

Status:
- engine supports submit, rest, partial fill, fill, cancel, modify, and reject behavior
- runtime can call submit/cancel/modify and persist lifecycle events
- remaining gap is a full persisted current-state projection that reflects cancel/modify/fill state, not only accepted-order intake state

### Workstream D: Angular Operational Shell

Goal:
make the Phase 1 venue slice usable through a real operator UI.

Scope:

- app shell and routing
- reference data screens
- order entry form
- order/trade blotters
- basic audit/event view

Exit criteria:

- a user can create reference data
- a user can submit orders and view outcomes
- a user can inspect current order and trade state in the UI

Status:
- platform UI is still a placeholder
- current accepted next UI direction is a local simulator control room before broad operational order/post-trade screens
- the UI must preserve CLI/report parity and must not bypass platform command paths

### Workstream J: Simulator Control Room

Goal:
turn existing simulator, stress, replay, and throughput scripts into a local operator-style UI without changing simulator semantics.

Scope:
- local-only control API around allowlisted commands
- run builder for load/stress/spike/soak modes
- active run status/log stream
- run result summary and compare-runs views
- trace explorer over existing runtime trace/event APIs
- artifact indexing under a stable local artifact root

Exit criteria:
- UI-launched runs emit the same report artifacts and reproduction command as CLI-launched runs
- run records include seed/session config, git metadata, metric scope, runtime instance count, and artifact paths
- compare view covers accepted RPS, submitted RPS, success rate, p95/p99, reject taxonomy, and trace-check pass rate

Primary plan:
- [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)

### Workstream E: Post-Trade Core

Goal:
extend executions into realistic downstream workflows.

Scope:

- trade processing module
- allocation records
- booking workflow
- confirmation generation
- affirmation or mismatch states

Exit criteria:

- matched trades generate downstream records
- at least one post-trade happy path exists
- at least one mismatch or failure path exists

### Workstream F: Settlement and Exceptions

Goal:
build the next operational lifecycle after trade processing.

Scope:

- settlement obligation modeling
- settlement state machine
- fail handling
- exception case creation and repair workflow

Exit criteria:

- a trade can progress into settlement
- operational failures open visible exceptions
- exception state is inspectable and actionable

### Workstream G: Simulation Control

Goal:
make the platform scenario-driven without bypassing real application paths.

Scope:

- seeded scenarios
- bot participants
- market clock
- replay/reset
- fault injection

Exit criteria:

- the same command path supports manual and scenario-driven flows
- runs are deterministic from a seed
- runs are replayable and inspectable

### Workstream I: Event Backbone and Data Lifecycle Operations

Goal:
deliver reliable event distribution, durable auditability, and practical data lifecycle operations for scale growth.

Scope:

- outbox-backed event publication from runtime to NATS (JetStream)
- idempotent consumer pattern for read-model and downstream processors
- EOD archive export flow (manifest, checksum, reconciliation)
- analytics projection refresh from EOD snapshot
- scheduler/job-runner with persistent run-state machine
- retention policy knobs for hot, analytics, and archive tiers

Exit criteria:

- domain state + event log + outbox write in one transaction
- relay worker supports retry/backoff and backlog observability
- EOD export is idempotent by `market_date` and resumable after failure
- analytics refresh runs from reconciled, sealed EOD cutoff
- scheduler maintains run history, status transitions, attempts, and artifact pointers

## Work Breakdown By Phase

## Phase 1A: Close the Core Venue Loop

Priority:
highest

Deliverables:

- runtime query endpoints for orders, executions, trades
- event log append model
- runtime read models for current venue state
- cancel/modify contract drafts
- engine cancel/modify support
- stronger order lifecycle test coverage

Concrete tasks:

1. add runtime persistence-backed query APIs
2. add event persistence and event query API
3. persist order state transitions alongside executions and trades
4. add cancel order command through runtime and engine
5. add modify order command through runtime and engine
6. expose runtime read models for blotter and trade timeline

Checkpoint:

- API-only end-to-end demo exists without UI

## Cross-Phase Delivery Constraints for Event/Data Work

1. Do not defer first persistence to EOD
- intraday writes must persist in real time to the canonical hot store
- EOD jobs produce derived analytics and archive artifacts only

2. Keep NATS as distribution, not sole source of truth
- canonical audit/event history remains in Postgres append-only tables
- NATS retention can remain short-lived for replay window and fanout

3. Keep scheduler infrastructure intentionally small
- one job-runner with DB-backed state machine is preferred initially
- avoid heavyweight orchestration platforms until scale/complexity proves need

## Phase 1B: Reference Data and Operational UI

Priority:
high

Deliverables:

- instrument, participant, and account persistence
- validation against reference data
- Angular shell with order entry and blotter
- basic event explorer

Concrete tasks:

1. design reference data schema and module boundaries
2. add runtime CRUD APIs for core reference data
3. seed default development data
4. stand up Angular app shell and API client layer
5. implement order entry, order list, trade list, event view

Checkpoint:

- a user can configure data and drive trades through the UI

## Phase 2A: Post-Trade Happy Path

Priority:
medium-high

Deliverables:

- trade processing module
- allocations
- booking record creation
- confirmation generation

Concrete tasks:

1. define trade processing commands and events
2. persist post-trade workflow records
3. build downstream state machine for post-trade progression
4. expose operational read models and queues

Checkpoint:

- matched trades create downstream workflow records automatically

### Phase 2A.1: Locked V1 lifecycle scenario

Priority:
highest within post-trade work

Deliverables:

- one deterministic hidden midpoint cross scenario
- one complete happy path (`order -> execution -> trade -> allocation -> confirmation -> affirmation -> settlement complete`)
- one deterministic broken path (`settlement fail -> exception open -> repair -> settlement complete`)
- timeline/read models proving full causation chain

Concrete tasks:

1. add `ScenarioRun` aggregate metadata (`scenarioRunId`, `seed`, `clock`, `actorType`)
2. enforce canonical IDs on all lifecycle records and events
3. add deterministic fault injection point at settlement step
4. implement exception repair command path and settlement retry
5. add replay test to assert stable ordered event sequence by seed

Checkpoint:

- one command can run the scenario end to end and both paths are inspectable in UI/API

## Phase 2B: Clearing and Netting Foundations

Priority:
medium

Deliverables:

- clearing workflow module with acceptance and rejection states
- novation lifecycle tracking
- netting batches and net obligation records
- settlement input queues sourced from net obligations

Concrete tasks:

1. define clearing commands, events, and state transitions
2. add clearing eligibility checks and rejection reason model
3. implement netting rules by participant, instrument, and settlement date
4. emit netting outputs to settlement obligation intake
5. expose clearing and netting operations read models

Checkpoint:

- a matched and affirmed trade can move through clearing and become a netted settlement obligation

## Phase 2C: Failures, Exceptions, Settlement

Priority:
medium

Deliverables:

- settlement obligations
- failure scenarios
- break/exception queues
- repair actions

Concrete tasks:

1. define settlement state model
2. create exception opening rules
3. add operator repair commands
4. surface exception workbench in UI

Checkpoint:

- at least one downstream failure path is fully modeled and observable

## Phase 3: Simulation and Replay

Priority:
after Phase 1 stability

Deliverables:

- scenario definitions
- seeded run control
- replay support
- fault injection

Concrete tasks:

1. define scenario DSL or configuration shape
2. model simulation clock and run metadata
3. implement seeded actor behavior
4. persist run events and replay metadata
5. build simulation control UI

Checkpoint:

- the same venue and post-trade flows can be driven by scripted scenarios

## Backlog By Bounded Context

### Reference Data

- instrument model
- participant model
- account model
- calendars and venue rules
- settlement instruction profiles

### Orders and Execution

- cancel/modify support
- order state projection
- engine health and status
- resting book read model
- command/event versioning

### Trade Processing

- allocation model
- booking state machine
- commission and fee placeholders
- split handling

### Post-Trade Workflow

- confirmation generation
- affirmation/mismatch model
- workflow deadlines

### Settlement

- obligation model
- settlement calendar logic
- completion/failure transitions

### Exceptions and Operations

- exception case model
- repair actions
- escalation model
- operational queues

### Simulation Control

- scenario definitions
- bots and behavior profiles
- seeded randomness strategy
- replay metadata

## Scenario Path Catalog

Path definitions should be implemented as scenario packs, not as ad hoc one-off code branches.

1. `P1_GOLDEN_HIDDEN_CROSS_T1`
- hidden midpoint cross, affirmation complete, settle `T+1`
- purpose: baseline lifecycle correctness

2. `P2_SETTLEMENT_BREAK_REPAIR`
- deterministic settlement failure, exception repair, retry success
- purpose: exception lifecycle and repair workflow

3. `P3_PARTIAL_FILL_MULTI_ALLOC`
- partial fills across multiple executions and multi-account allocations
- purpose: execution-to-allocation complexity

4. `P4_AFFIRMATION_DEADLINE_BREACH`
- affirmation misses policy cutoff and opens timed exception
- purpose: policy-time realism and deadline enforcement

5. `P5_SSI_MISMATCH_DK_FLOW`
- affirmed trade fails due to SSI mismatch, then corrected and reprocessed
- purpose: realistic settlement-break handling

6. `P6_CANCEL_REPLACE_RACE`
- deterministic sequencing around cancel/replace near match boundary
- purpose: lifecycle concurrency correctness

7. `P7_DUPLICATE_COMMAND_IDEMPOTENCY`
- duplicate commands injected at critical steps
- purpose: idempotency guarantees

8. `P8_OUT_OF_ORDER_EVENT_RECOVERY`
- event delivery order perturbed and reconciled
- purpose: event robustness and recovery behavior

9. `P9_PARTIAL_SETTLEMENT_RESIDUAL`
- partial settlement success with residual failure and remediation
- purpose: obligation decomposition and residual handling

10. `P10_RISK_GATE_REJECTION`
- risk/limit policy blocks order pre-trade with audit trail
- purpose: control-plane realism and explainability

## Scenario Wave Plan

Implement in this order:

1. Wave 1: `P1`, `P2`
2. Wave 2: `P3`, `P4`, `P5`
3. Wave 3: `P6`, `P7`, `P8`
4. Wave 4: `P9`, `P10`

Wave rule:
- no new wave starts until prior wave has deterministic replay tests and timeline UI assertions passing.

## Scenario Specification Template

Each path spec should include:
- `pathId`
- `businessGoal`
- `seed`
- `preconditions`
- `faultInjection`
- `steps` (command sequence)
- `expectedEvents` (ordered)
- `expectedFinalStates`
- `invariants`
- `uiAssertions`
- `replayAssertions`
- `idempotencyAssertions`

### Audit and Analytics

- append-only event store
- timeline query API
- venue metrics
- run summaries

## Cross-Cutting Technical Tasks

- Postgres adoption plan for runtime persistence
- migration tooling
- local orchestration for runtime, engine, and database
- test fixture strategy
- API error model
- auth and persona scaffolding
- structured logs and correlation IDs
- code generation strategy for contracts

## Near-Term Sprint Recommendation

Recommended next block of work:

1. align durable persistence with split-ready domain schemas and migration-owned tables
2. complete current-state order lifecycle projections for submit/cancel/modify/fill/reject
3. build the simulator control-room MVP over existing dev/simulator scripts and artifacts
4. add deterministic scenario assertions for `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`

Reason:

- it removes the biggest architecture drift before adding post-trade storage
- it completes the inspectability promise of the current venue slice
- it turns the existing simulator strength into a repeatable operator workflow
- it keeps the platform close to the technical design's audit-first intent

After that checkpoint, recommended post-trade sprint:

1. implement `LifecycleRunner` orchestration with module seams inside runtime
2. deliver `P1_GOLDEN_HIDDEN_CROSS_T1`
3. deliver `P2_SETTLEMENT_BREAK_REPAIR`
4. add timeline + exception workbench assertions for both paths
5. only then start Wave 2 scenario paths

## Definition of Done For Early Features

For any early Reef feature to be considered complete, it should usually include:

- command/write path
- persisted state
- query/read path
- emitted events
- tests for happy path and at least one failure path
- basic documentation update where behavior changed materially
