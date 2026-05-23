# Reef Roadmap

## Purpose

This roadmap turns the product overview and technical design into an execution sequence for the next stages of the project.

It is intentionally biased toward:

- production-shaped module boundaries
- local-first development
- deterministic simulation
- inspectable workflows and event trails

## Phase 0: Foundation Reset

Goal: prepare the repository for the actual architecture described in the project docs.

Deliverables:

- establish repository structure for apps, services, shared contracts, and docs
- replace prototype-era README messaging with current project framing
- define architecture and language steering
- choose initial build and workspace conventions
- decide how Kotlin and Go services will be started locally

Exit criteria:

- repo structure matches the intended platform shape
- contributors can tell where new code belongs
- design constraints are documented well enough to avoid ad hoc implementation

## Phase 1: Core Venue Slice

Goal: implement the smallest end-to-end flow that proves the platform shape.

Scope:

- reference data for instruments, participants, and accounts
- order submission API
- Go matching engine with hidden order book behavior
- execution and trade persistence
- append-only domain event log
- basic Angular UI for reference data, order entry, and trade/event views

Suggested vertical slice:

1. Create instrument and participant reference data.
2. Submit buy and sell orders.
3. Validate and route orders from Kotlin runtime to Go engine.
4. Match orders and return executions.
5. Persist order state, executions, trades, and events.
6. Expose a UI read model for blotter and audit timeline.

Exit criteria:

- one user can drive the complete trade lifecycle from UI to engine and back
- every significant state change is queryable through the platform
- simulation is not required yet, but the command path is simulation-ready

## Phase 2: Post-Trade Slice

Goal: extend execution into realistic operational workflows.

Scope:

- trade allocation records
- booking/enrichment workflow
- confirmation generation
- affirmation or mismatch state tracking
- settlement obligation creation
- exception queue for broken or failed downstream steps

Suggested vertical slice:

1. Matched trade creates a post-trade workflow record.
2. Trade is allocated to one or more accounts.
3. Confirmation is generated.
4. Settlement obligation is opened.
5. A forced mismatch or failure creates an exception case.
6. UI exposes operational queues and lifecycle status.

Exit criteria:

- one completed execution can be traced through downstream workflow states
- at least one failure path is modeled and visible in the UI
- event history explains both happy-path and exception-path outcomes

Implementation posture:
- use one in-process lifecycle runner with bounded modules, not separate deployable engines per stage
- deliver exactly two scenario paths first: `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`
- expand through scenario waves only after deterministic replay and timeline assertions are stable

## Phase 3: Simulation Control Plane

Goal: add deterministic scenario execution without bypassing platform APIs.

Scope:

- scenario definitions
- seeded run configuration
- participant bot behaviors
- simulated market clock
- replay/reset workflow
- fault injection hooks

Rules:

- simulation actors must use the same commands as manual users
- scenarios may accelerate time, but should not mutate state directly
- replay should rely on stored commands and events, not hidden in-memory shortcuts

Exit criteria:

- a scenario can be run deterministically from a seed
- a run can be paused, resumed, and replayed
- the same execution and post-trade read models work for manual and simulated activity

Scenario expansion order:
1. Wave 1: `P1`, `P2`
2. Wave 2: `P3`, `P4`, `P5`
3. Wave 3: `P6`, `P7`, `P8`
4. Wave 4: `P9`, `P10`

## Phase 4: Asynchronous Expansion

Goal: introduce messaging where it meaningfully improves separation and observability.

Scope:

- NATS (JetStream)-backed event publication where async boundaries help
- background workers for read-model building or workflow processing
- explicit protobuf contracts between Kotlin runtime and Go engine
- clearer service startup and health management
- outbox relay pattern for reliable event publication

Constraints:

- do not introduce distributed complexity before local workflows are stable
- synchronous HTTP or gRPC boundaries are acceptable in earlier phases
- async messaging should be additive, not a rewrite of the domain model

Exit criteria:

- service boundaries are explicit
- contracts are versioned
- async flows remain understandable and testable locally
- runtime write path persists domain state + event log + outbox atomically
- outbox relay drains backlog with retry/backoff and measurable lag

## Phase 5: Data Lifecycle and Scheduled Operations

Goal: harden retention, analytics usability, and EOD operations without overbuilding platform complexity.

Scope:

- EOD archive export job with integrity manifest and checksums
- analytics refresh pipeline from the same market-day cutoff snapshot
- scheduler/job-runner service for recurring jobs (EOD export, analytics refresh, maintenance)
- archival layout and retention tiering strategy

Rules:

- EOD export is rollup/archive only, never first-time persistence
- intraday lifecycle events and state must persist in real time
- scheduler jobs must be idempotent, resumable, and auditable

Exit criteria:

- market-date EOD job can be run safely once with duplicate prevention
- archive artifacts include manifest, checksums, and reconciliation counts
- analytics views/tables are refreshed from reconciled EOD data
- run history and failure states are queryable for operators

## Cross-Cutting Workstreams

These should advance incrementally across phases:

- authentication and role-aware UI surfaces
- inter-service gRPC/protobuf contract hardening
- external API boundary and client integration standards
- admin command surface (CLI first, API later)
- audit/event explorer
- testing strategy and fixtures
- developer bootstrap and local orchestration
- schema migration discipline
- observability and structured logging

## Recommended Initial Repo Shape

This is the target structure to grow into during Phase 0:

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
```

Early simplification is acceptable:

- the simulator may begin as a module inside the Kotlin runtime
- protobuf may follow HTTP JSON contracts first, then harden later
- a single Angular app can host multiple operational surfaces before splitting

Implementation detail reference:
- [`docs/EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)

## Near-Term Priority Order

If starting fresh from the current repository state, the recommended order is:

1. establish repo structure and steering
2. replace the old prototype README
3. stand up the Kotlin runtime shell
4. stand up the Go engine shell
5. define the first order/execution contract
6. implement the v1 end-to-end venue slice
7. layer in post-trade workflows
8. add simulation control
