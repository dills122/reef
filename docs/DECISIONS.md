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

### D-022: Baseline SLO Target Document

Status: accepted

Summary:
- baseline SLO targets must be explicitly documented and versioned.
- cross-stage objectives (order path, boundary, and post-match stages) should be tracked and refined as architecture matures.

Primary references:
- [`docs/SLO_BASELINES.md`](./SLO_BASELINES.md)
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)

### D-023: Single-Instance Capacity Objective

Status: accepted

Summary:
- Reef should track a concrete single-instance throughput objective before horizontal scale-out.
- planning anchor target is 5,000 accepted req/s per runtime + engine instance, with staged near-term and stretch targets documented in SLO baselines.
- horizontal scaling should multiply this strong per-instance unit rather than compensate for unresolved single-node bottlenecks.

Primary references:
- [`docs/SLO_BASELINES.md`](./SLO_BASELINES.md)
- [`docs/SPRINT_DEV_ENV.md`](./SPRINT_DEV_ENV.md)

### D-024: Event Durability and Distribution Pattern

Status: accepted

Summary:
- Postgres append-only event persistence is the canonical event history.
- NATS (JetStream) is the asynchronous distribution backbone, not the sole source of truth.
- domain state changes and corresponding outbox records must be committed atomically to avoid state/event divergence.
- event delivery semantics are at-least-once; consumers must be idempotent by `eventId`.

Primary references:
- [`docs/ROADMAP.md`](./ROADMAP.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-025: EOD Data Lifecycle Policy

Status: accepted

Summary:
- EOD workflows produce archive and analytics derivatives from already-persisted intraday data.
- EOD is not permitted to be first-time persistence for trading lifecycle events.
- three-tier data posture is adopted:
  - hot operational store (real-time canonical writes)
  - analytics projections (transformed query-optimized views)
  - immutable archive artifacts (compressed file-based partitions with manifest/checksums)

Primary references:
- [`docs/ROADMAP.md`](./ROADMAP.md)
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)

### D-026: Minimal Scheduler/Orchestration Policy

Status: accepted

Summary:
- recurring operational jobs should run through a lightweight job-runner with DB-backed run state.
- scheduler jobs must be idempotent, resumable, and auditable, keyed by business identifiers (for example `market_date`).
- initial implementation should avoid heavyweight orchestration frameworks unless proven necessary by scale/operational complexity.
- orchestration persistence belongs to an isolated `orchestration` domain schema.

Primary references:
- [`docs/DB_SPLIT_READINESS.md`](./DB_SPLIT_READINESS.md)
- [`docs/ROADMAP.md`](./ROADMAP.md)

### D-027: Postgres Procedure-First Persistence Policy

Status: accepted

Summary:
- Reef should prefer Postgres stored procedures/functions (PL/pgSQL) for write-path mutations and operational job state transitions.
- service code should call stable database routines for critical write workflows rather than assembling ad hoc multi-statement SQL sequences.
- read-only query surfaces may continue to use parameterized SQL, but cross-table mutation orchestration belongs in database routines.
- procedure contracts are versioned and forward-additive; breaking changes require versioned routine names and migration notes.

Primary references:
- [`docs/DB_SPLIT_READINESS.md`](./DB_SPLIT_READINESS.md)
- [`docs/EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)

### D-028: Throughput Architecture Progression

Status: accepted

Summary:
- after reaching the tuned local `~3k rps` single-instance capacity profile, further throughput work should prioritize architecture changes over more thread/pool tuning.
- the active target is `5k` accepted requests per second per runtime + engine instance before relying on horizontal scale-out.
- Reef should separate durable command intake from downstream runtime persistence through an append-only `command_log` slice.
- synchronous command-result behavior remains available for compatibility and deterministic tests.
- async/batched runtime persistence and read-model projection isolation are the next major scaling levers.
- physical database splitting is deferred until schema-level diagnostics prove contention that cannot be solved with logical isolation, partitioning, and batching.

Primary references:
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/DB_SPLIT_READINESS.md`](./DB_SPLIT_READINESS.md)

### D-029: Simulator Control Room Before Next Throughput Architecture Sprint

Status: accepted

Summary:
- the next high-value sprint should build a local simulator testing/admin UI before deeper `5k` architecture changes.
- the UI should orchestrate existing simulator/dev scripts through a local-only control API rather than replacing the CLI.
- UI-launched runs must produce the same report artifacts and reproduction commands as CLI-launched runs.
- the control API must use allowlisted commands and artifact path guardrails; no arbitrary shell execution from the browser.
- GitHub Pages is reserved for project overview/docs; running simulations requires a local control API or a future fully hosted Reef environment.
- the first UI slice should prioritize developer testing views: control-room overview, run builder, active run, run results, compare runs, and trace explorer.
- accepted rps, submitted/request rps, backpressure, trace status, runtime instance count, and per-instance vs cluster-wide scope must be visible in the relevant views.
- this sprint supports the throughput track by making stress runs, comparisons, diagnostics, and scenario execution easier to repeat.

Primary references:
- [`docs/PROJECT_PITCH.md`](./PROJECT_PITCH.md)
- [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)
- [`docs/SIMULATOR_UPGRADE_BACKLOG.md`](./SIMULATOR_UPGRADE_BACKLOG.md)
- [`apps/platform-ui/README.md`](../apps/platform-ui/README.md)

### D-030: Runtime Event Schema Baseline And Outbox Timing

Status: accepted

Summary:
- Current live `runtime.runtime_events` keeps `event_id` and `occurred_at` as `TEXT` because engine event IDs are semantic strings and simulation command timestamps are command-sourced strings.
- `actor_id TEXT NOT NULL DEFAULT ''` and `payload_json JSONB NOT NULL DEFAULT '{}'::jsonb` are required on runtime events so write models do not drop attribution or event context.
- Schema validation must guard critical runtime event column types until a deliberate typed-ID/timestamp migration is planned.
- `runtime.event_outbox` remains a follow-up migration/workflow. It should be introduced with explicit transaction timing and publisher semantics rather than being mixed into this drift-fix slice.

Primary references:
- [`docs/SPRINT_CRITICAL_QUALITY_HARDENING.md`](./SPRINT_CRITICAL_QUALITY_HARDENING.md)
- [`docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md`](./DATA_DOMAIN_SCHEMA_BLUEPRINT.md)

### D-031: Captured-Ack Async Command Worker

Status: accepted

Summary:
- `captured-ack` remains opt-in and is not the default external API behavior.
- When `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=true`, the runtime starts one or more background workers that atomically claim `RECEIVED` command-log records, execute the existing platform API operation, and mark each command `COMPLETED` or `FAILED`.
- The command log is the write-ahead intake record and idempotency anchor for this mode.
- Captured-ack `202` responses replay from the command log instead of writing a second boundary idempotency row on the intake path.
- `/internal/commands/async/stats` reports worker settings, command-log status counts, and async claim/complete/fail counters for drain testing.
- This is the accepted-command write-ahead slice. It decouples request acknowledgment from full runtime persistence, but it does not yet implement batched persistence, projection isolation, partitioned event storage, or multi-instance worker coordination.
- Synchronous `sync-result` remains available for compatibility and deterministic tests.

Primary references:
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/BOT_ARENA_STRESS_BASELINE_2026-07-01.md`](./BOT_ARENA_STRESS_BASELINE_2026-07-01.md)

### D-032: Command Log Queue And Result Split

Status: accepted

Summary:
- `command_log.commands` remains the durable inbound command intake record and idempotency anchor.
- mutable worker state belongs in `command_log.command_work_queue`, not on the command intake row.
- terminal response payloads belong in `command_log.command_results`, not on the command intake row.
- command status APIs compose command metadata, queue state, and result rows so public status behavior remains stable.
- this split is intended to reduce indexed status updates and dead tuples on the command intake table before deeper async/batched runtime persistence work.
- first benchmark evidence showed this split alone regresses accepted ingress because it adds active-queue/result writes; follow-up work must reduce write amplification before treating the split as a throughput win.

Primary references:
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/BOT_ARENA_STRESS_BASELINE_2026-07-01.md`](./BOT_ARENA_STRESS_BASELINE_2026-07-01.md)
