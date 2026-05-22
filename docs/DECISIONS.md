# Reef Architecture Decisions

## Purpose

Track major architectural directions in one place so implementation changes can be traced back to explicit decisions.

## Active Decisions

### D-001: Inter-Service Communication Direction

Status: accepted

Summary:
- Protobuf is the canonical contract schema for inter-service boundaries.
- gRPC is the preferred runtime-to-engine synchronous transport.
- HTTP/JSON remains as migration fallback until parity is validated.

Primary references:
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-002: External API Boundary Direction

Status: accepted

Summary:
- User-facing APIs are versioned under `/api/v1`.
- Boundary concerns (auth hook, idempotency, rate limits, validation, error envelope) are explicit architecture requirements.
- Internal runtime/engine service contracts are not treated as public client contracts.

Primary references:
- [`docs/steering/external-api-boundary.md`](./steering/external-api-boundary.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-003: Admin Surface Direction

Status: accepted

Summary:
- Admin capabilities are implemented in application-layer modules first.
- CLI is the initial adapter for admin actions.
- Future admin HTTP APIs must reuse the same admin application modules.

Primary references:
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-004: Post-Match Lifecycle Baseline

Status: accepted

Summary:
- Reef post-match behavior should follow an industry-standard first baseline:
  - compare/affirmation
  - clearing/novation
  - netting
  - settlement lifecycle
  - exception handling
- innovation paths are allowed only after baseline parity is established and test-covered.

Primary references:
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-005: Participant Roles and Permission Model

Status: accepted

Summary:
- Basic market-operating personas and permissions are required before deep engine expansion.
- Minimum role set to model:
  - venue operator
  - buy-side trader
  - sell-side executing broker
  - clearing broker
  - custodian
  - clearinghouse/CCP operator
  - settlement/depository operator
  - compliance or regulator observer
- engine and workflow command handlers must enforce actor-role authorization rules.

Primary references:
- [`docs/steering/external-api-boundary.md`](./steering/external-api-boundary.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-006: Calendar and Time Configuration Model

Status: accepted

Summary:
- Reef is country-agnostic by design.
- Calendar, holiday, and settlement-cycle settings must be admin-configurable.
- Default profile should use U.S. market and bank holiday behavior for local development.
- calendar configuration should be one of the first admin tools delivered.

Primary references:
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-007: Cross-Language Engineering Standards

Status: accepted

Summary:
- Rigid standards should be enforced consistently across Kotlin and Go services:
  - explicit domain state machines
  - idempotency-safe command handling
  - structured errors and event outcomes
  - deterministic tests for critical workflow paths
  - correlation and causation metadata propagation
- standards are non-negotiable for new workflow modules.

Primary references:
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`docs/steering/go.md`](./steering/go.md)
- [`docs/steering/kotlin.md`](./steering/kotlin.md)

### D-008: Netting Policy Baseline and Admin Evolution

Status: accepted

Summary:
- Netting behavior starts with industry-standard baseline rules.
- netting policies should be admin-configurable over time behind explicit policy versions.
- replay runs must record which policy version was applied.

Primary references:
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-009: Settlement Ledger Baseline

Status: accepted

Summary:
- Default settlement bookkeeping model is a classic account-style ledger consistent with institutional post-trade systems.
- adapter abstraction remains valid for experimentation, but baseline delivery anchors on relational account-ledger behavior.

Primary references:
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)

### D-010: Simulation-Backed External Integrations

Status: accepted

Summary:
- Integration layers should behave like real-world external dependencies from the domain perspective.
- backing implementations can be pluggable simulator adapters with synthetic personas, accounts, banks, and responses.
- simulator adapters must preserve realistic request and response semantics for training and testing value.

Primary references:
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-011: Observability as a Non-Negotiable Requirement

Status: accepted

Summary:
- end-to-end traceability is mandatory across services and engines.
- minimum required metadata on commands and events:
  - `commandId`
  - `traceId`
  - `correlationId`
  - `causationId`
  - `actorId`
  - `occurredAt`
- every major post-match stage must publish queue and lifecycle telemetry for learning and debugging.

Primary references:
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)

### D-012: Traffic Scenario Catalog Requirement

Status: accepted

Summary:
- Reef should maintain a curated scenario catalog that reflects realistic traffic, participants, and operational outcomes.
- scenario catalog must cover both happy-path and failure-path operational stories.
- scenarios should be deterministic, versioned, and replayable.

Primary references:
- [`docs/SIMULATION_TRAFFIC_PLAN.md`](./SIMULATION_TRAFFIC_PLAN.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-013: Operational UI Target Style

Status: accepted with adaptation

Summary:
- operator workflows should target Bloomberg-like density, speed, and situational awareness.
- direct Bloomberg parity is not required; the design goal is high-information operational surfaces optimized for keyboard-driven workflows.
- UI implementation should prioritize queue productivity and lifecycle trace visibility over visual mimicry.

Primary references:
- [`docs/steering/angular.md`](./steering/angular.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-014: Data Retention and Reset Policy for Simulator

Status: accepted

Summary:
- simulator environments may be reset and data may be wiped by design.
- retention defaults:
  - keep durable run metadata and event timelines where practical
  - allow environment-level reset operations for rapid iteration
- migration or archive tools are optional and should only be built when operational need emerges.

Primary references:
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)
- [`docs/SIMULATION_TRAFFIC_PLAN.md`](./SIMULATION_TRAFFIC_PLAN.md)

### D-015: Engine vs Service Extraction Criteria

Status: accepted

Summary:
- an "engine" is primarily a bounded domain module with explicit commands, events, state machine, tests, and observability.
- a separate deployable "service" is created only when justified by concrete needs such as:
  - isolation of failure blast radius
  - materially different scaling profile
  - independent runtime or operational constraints
- hobby-project default remains modular runtime modules first, service extraction later.

Primary references:
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-016: Idempotency Scope and Storage Policy

Status: accepted

Summary:
- idempotency keys for mutating APIs are scoped by `clientId + route + idempotencyKey`.
- idempotency records must persist canonical outcome payload references.
- idempotency records must support TTL by command class; operationally critical writes should use longer retention.

Primary references:
- [`docs/steering/external-api-boundary.md`](./steering/external-api-boundary.md)
- [`docs/POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md)

### D-017: Event Schema Governance Policy

Status: accepted

Summary:
- event and command schema evolution is additive-first by default.
- breaking changes require explicit version bump and deprecation window.
- schema compatibility checks are required in CI for contract artifacts.

Primary references:
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-018: Partial Settlement Modeling

Status: accepted

Summary:
- partial settlement should be represented by child obligation artifacts/events rather than opaque in-place mutation only.
- parent-child linkage must be traceable in event timelines and read models.

Primary references:
- [`docs/POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md)
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)

### D-019: Override Governance and Audit Controls

Status: accepted

Summary:
- manual override or force-advance actions require:
  - authorized operational role
  - mandatory reason code and free-text note
  - explicit override event marker
- high-impact overrides should support optional dual-control mode.

Primary references:
- [`docs/POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md)
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)

### D-020: Clock Source Determinism Policy

Status: accepted

Summary:
- domain logic must use injectable clocks; direct wall-clock calls in core workflow logic are disallowed.
- scenario runs must persist effective clock/calendar profile versions.

Primary references:
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`docs/SIMULATION_TRAFFIC_PLAN.md`](./SIMULATION_TRAFFIC_PLAN.md)

### D-021: Baseline Performance Envelope Targets

Status: accepted

Summary:
- early-stage SLO targets are required for architecture discipline, even in hobby/simulator mode.
- define per-stage baseline latency and throughput targets for:
  - order ingestion and engine round-trip
  - compare/affirmation
  - clearing decisioning
  - netting batch processing
  - settlement intake enqueue

Primary references:
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)
- [`docs/SPRINT_POST_MATCH_ENGINES.md`](./SPRINT_POST_MATCH_ENGINES.md)
