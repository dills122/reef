# Reef Architecture Decisions

## Purpose

Track major architectural directions in one place so implementation changes can be traced back to explicit decisions.

## Active Decisions

### D-001: Inter-Service Communication Direction

Status: accepted, amended by D-040

Summary:
- Protobuf is the canonical contract schema for inter-service boundaries.
- gRPC is the preferred runtime-to-engine synchronous transport for compatibility, admin/control, health, and direct benchmark paths.
- per-command unary gRPC is not the target high-throughput matching hot path.
- HTTP/JSON remains as migration fallback until parity is validated.

Primary references:
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-002: External API Boundary Direction

Status: accepted

Summary:
- User-facing APIs are versioned under `/api/v1`.
- Boundary concerns (auth hook, idempotency, rate limits, validation, account/bot risk pre-check, error envelope) are explicit architecture requirements.
- Account/bot risk pre-checks run before durable command acceptance. Non-allow decisions must not append command-log rows, reserve stream intake rows, or publish command messages; supported implementations include allow-all, static env controls, and cached Postgres operator controls with non-allow decision audit rows. These checks must not perform projection reads or heavy synchronous exposure scans on the hot path.
- Command circuit breakers for global, venue-session, and instrument scopes are hard pre-acceptance gates. Tripped breakers reject before command-log rows, stream intake rows, or command messages are created.
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
- original planning anchor target was 5,000 accepted req/s per runtime + engine instance, with staged near-term and stretch targets documented in SLO baselines.
- horizontal scaling should multiply this strong per-instance unit rather than compensate for unresolved single-node bottlenecks.
- superseded for the bot-arena scaling track by D-035, which raises the active target to `7500-10000` completed commands/sec with no accepted-command accounting gaps.

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
- the then-active target was `5k` accepted requests per second per runtime + engine instance before relying on horizontal scale-out.
- superseded for the bot-arena scaling track by D-035, which uses completed command lifecycle throughput rather than accepted-request intake.
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
- the next high-value sprint should build a local simulator testing/admin UI before deeper throughput architecture changes.
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

### D-033: Command Log Lifecycle Controls

Status: accepted

Summary:
- `command_log.commands` and `command_log.command_results` are canonical command intake/audit history, but unbounded local stress history is not a useful default for iterative throughput work.
- command-log pruning must be explicit and dry-run-first in local tooling.
- pruning may delete terminal command history only when no active `command_work_queue` row exists for the command.
- named replay/audit runs can be protected with `command_log.retention_pins` before broad arena-run pruning.
- retention pins support exact command IDs and session-friendly selectors such as idempotency-key prefixes.
- physical partitioning remains a follow-up after prune/retest evidence shows how much table lifecycle alone recovers.

Primary references:
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/DEV_ENV.md`](./DEV_ENV.md)

### D-034: Command Log Partitioning Direction

Status: accepted

Summary:
- Do not range-partition the current `command_log.commands` table in place.
- `commands` remains the hot command-ID/idempotency lookup table until a v2 schema deliberately changes the key shape.
- partitioning should first target terminal history/archive tables where `completed_at`, `received_at`, or run/session metadata are natural lifecycle keys.
- command payloads should be split from the hot command index before partitioning bulky command history.
- active queue rows should stay small through active-only semantics, pruning, and worker drain improvements rather than time partitioning.
- the next implementation slice is run/session attribution so arena and simulator workloads can be retained, pruned, and diagnosed by run.

Primary references:
- [`docs/COMMAND_LOG_PARTITIONING_PLAN.md`](./COMMAND_LOG_PARTITIONING_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)

### D-035: Completed Throughput And No-Loss Scaling Target

Status: accepted

Summary:
- the active bot-arena scaling target is completed command lifecycle throughput, not raw accepted-request intake.
- the minimum target is `7500` completed commands per second per runtime + engine instance.
- the preferred target is `10000` completed commands per second per runtime + engine instance.
- if the runtime returns an accepted response, the command must be durably captured and must either reach terminal `COMPLETED`/`FAILED` state or remain visible as active leased/retryable work.
- overload must reject or throttle before durable acceptance when the system cannot safely drain more work.
- `stream-ack` is the target high-throughput mode for this track.
- Postgres `captured-ack` remains a local fallback and A/B baseline, not the final bot-arena throughput architecture.
- `sync-result` remains the deterministic correctness and compatibility mode.
- raw intake and capacity-baseline profiles remain diagnostics only; they do not prove release readiness without completed-throughput accounting and queue drain.
- Kubernetes scale-out must not be used to hide per-instance write amplification, queue backlog, or accepted-command accounting gaps.
- bot traffic must use the same public command/API path as other simulator traffic, with run/session/bot attribution and no direct state mutation.

Primary references:
- [`docs/THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)

### D-036: Stream-Ack Ingress And Partitioned Processing Direction

Status: accepted

Summary:
- JetStream should be introduced early for the bot-arena high-throughput track as the durable accepted-command ingress log.
- JetStream is not the canonical venue outcome store; Postgres remains authoritative for command results, lifecycle events, orders, executions, and trades.
- The runtime may return `202 Accepted` in `stream-ack` mode only after a durable JetStream publish acknowledgment.
- publish failure, publish timeout, or unsafe stream/worker/DB health must return explicit `429` or `503` before durable acceptance.
- accepted-command streams use retained log semantics with file storage and explicit replay windows, not JetStream WorkQueue retention.
- command subjects include a deterministic partition token, with submit/cancel/modify for the same book routed to the same partition lane.
- cancel/modify commands must carry enough routing metadata to avoid synchronous DB lookup on the throughput hot path.
- JetStream message dedupe is not enough for business idempotency; Reef must keep a scoped idempotency guard with payload hashes and command/stream references.
- partition workers ack JetStream only after canonical command result and event-log writes commit.
- projection workers, leaderboard updates, and analytics are downstream rebuildable consumers with watermarks and lag metrics.
- failure tests must cover publish retry, redelivery before DB commit, redelivery after DB commit before ack, deterministic replay, and projection rebuild.

Primary references:
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)

### D-037: Stream-Ack Canonical Append And Projection Split

Status: accepted

Summary:
- Reef's high-throughput path is a deterministic simulated market venue architecture, not a generic request/response CRUD path and not a real-money production exchange.
- the market-simulation hot path is accepted command -> ordered partition lane -> matching/lifecycle decision -> canonical append-only facts -> stream ack.
- D-037 refines D-036 for phase order, metric definitions, canonical persistence, and projection ownership.
- the implementation order is role split and explicit partition ownership first, canonical append store second, async projection system third, engine shards fourth, and DigitalOcean benchmark harness fifth.
- `accepted/sec` means the API returned `202` only after durable JetStream publish acknowledgement.
- `completed/sec` means a worker consumed the command, made the matching/lifecycle decision, committed canonical command result and venue events, and acknowledged the JetStream message.
- `projected/sec` means downstream read models, timelines, counters, leaderboards, reports, or UI state caught up from canonical facts.
- `visible/sec` means projected state is available to UI/control-room users; it is not part of the command completion definition.
- normalized orders, executions, trades, status rows, trace timelines, leaderboard state, and run summaries are not stream-ack completion requirements; they are async projections with watermarks and lag metrics.
- canonical append tables must be sufficient to prove and replay what happened to every accepted command.
- redelivery after worker crash is a normal design case; deterministic IDs and uniqueness constraints must prevent duplicate trades, executions, lifecycle events, and terminal command results.
- submit, cancel, and modify commands affecting the same run/session/instrument must use the same deterministic partition lane; cancel/modify must carry routing metadata rather than requiring hot-path lookup.
- backpressure must be based on drain health and completed throughput, not durable acceptance rate alone.
- drain backpressure policy must be explicit: `control-room-fresh` can reject on worker and projection lag, while `venue-core` reports projection lag without letting read-model freshness define the canonical venue throughput ceiling.
- no `7500-10000` completed/sec claim is valid without accepted/completed/projected accounting, bounded lag, p95/p99 evidence, zero accepted-command gaps, and replay or checksum evidence.

Primary references:
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [NATS JetStream Streams](https://docs.nats.io/nats-concepts/jetstream/streams)
- [NATS JetStream Consumers](https://docs.nats.io/nats-concepts/jetstream/consumers)
- [NATS Subject Mapping and Partitioning](https://docs.nats.io/nats-concepts/subject_mapping)
- [PostgreSQL Populating a Database](https://www.postgresql.org/docs/current/populate.html)
- [PostgreSQL Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html)

### D-038: Stream-Ack Post-Soak Optimization Direction

Status: accepted

Summary:
- the July 3, 2026 DO soak does not invalidate the stream-ack macro architecture; it shows that the current hot path still writes and projects too much per completed command for the target.
- the next optimization target is `completed/sec`, not accepted/sec, with accepted throughput close to worker-completed throughput and a clean post-load drain.
- the promotion ladder is `2000 completed/sec` sustained for at least `5m`, then `5000/sec`, then `7500/sec` and larger ceiling probes only after the lower tier is stable.
- before scaling workers/projectors broadly, Reef must measure rows/command, WAL bytes/command, commits/command, projection work items/command, and partition skew.
- practical `2-3x` subsystem headroom over the active tier is acceptable when cost and complexity are reasonable; avoid expensive brute-force capacity that hides inefficient writes or hot-partition behavior.
- a barely stable `2000/sec` soak with saturated resources, growing lag, slow drain, or no credible path to `5000/sec` is not a successful milestone for this track.
- implementation work should prefer bottleneck-class fixes and practical headroom over micro-tuning a narrow pass at the current tier.
- canonical Postgres remains authoritative under D-036/D-037, but the canonical write shape may be collapsed from per-event rows into compact command/event batch records when replay, idempotency, ordering, checksums, and audit semantics remain intact.
- projection writes should be optimized as a separate streaming system: batch, coalesce repeated aggregate updates, reduce hot indexes, use staging/merge paths where useful, and allow unlogged/disposable storage only for rebuildable projection caches.
- hot partitions should be treated as either routing bugs or legitimate hot-book market behavior; even-distribution and hot-book benchmarks must be labeled separately.
- the preferred infrastructure split order is load generator first, canonical Postgres second, projection Postgres third, and NATS only after JetStream metrics show pressure.
- using JetStream as the canonical venue event log with Postgres as projection/query storage is a reserved hard-pivot option only if a compact canonical Postgres append path still caps completed throughput; adopting it would require a new decision that supersedes the D-036/D-037 completion boundary.

Primary references:
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/DIGITALOCEAN_STRESS_TEST_PLAN.md`](./DIGITALOCEAN_STRESS_TEST_PLAN.md)
- [`docs/PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)

### D-039: Redpanda Stream-Ack Provider Comparison

Status: provisional

Summary:
- D-036 remains the accepted high-throughput direction: JetStream is the default durable accepted-command ingress log, and Postgres remains the canonical venue outcome store.
- Redpanda/Kafka-compatible ingress is added as an opt-in comparison provider behind `STREAM_ACK_LOG_PROVIDER=redpanda`.
- The public command contract, partition key, scoped idempotency guard, and worker completion boundary are unchanged.
- In Redpanda mode, the command stream name maps to a Kafka topic, the runtime publishes with explicit partition routing and `acks=all`, and workers manually commit offsets only after canonical submit outcomes are durable.
- Redpanda should not be promoted over JetStream without comparable 5m soak evidence for accepted/sec, completed/sec, bounded lag, p95/p99, CPU/memory, restart/redelivery behavior, and replay/audit accounting.
- This comparison is meant to test durable-log mechanics, not to move canonical venue facts out of Postgres.

Primary references:
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/DEV_ENV.md`](./DEV_ENV.md)

### D-040: JetStream Command Stream And Engine Ingestion Direction

Status: superseded by D-041 for the hot ingress producer

Summary:
- JetStream remains the preferred durable command stream for the next venue-core throughput phase.
- Redpanda/Kafka provider work remains useful for comparison, but current evidence does not show the broker as the active blocker.
- the July 2026 90-second stream-ack A/B showed JetStream clean at about `2488` accepted and worker-completed commands/sec, while the 5k target exposed worker-to-engine failures:
  - `engine gRPC SubmitOrder failed with UNAVAILABLE: io exception`
- stress evidence must treat async worker failure, ack failure, unresolved redelivery, and accepted/completed drain gaps as failed runs even when API responses are all `202`.
- generic workers calling unary engine `SubmitOrder` per command are transitional scaffolding, not the final high-throughput matching architecture.
- the target venue-core path is:
  - boundary/API durably publishes command to JetStream
  - matching-engine shard consumes assigned command partitions
  - engine processes ordered command batches
  - engine publishes canonical venue event batches
  - engine acks command messages only after durable event publication
- until direct engine consumption exists, the interim path should prefer batch RPC or bidirectional streaming per deterministic partition lane, stable channels, bounded in-flight work, explicit deadlines, idempotent retries, and engine-health backpressure.

Primary references:
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)
- [`docs/PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)

### D-041: Kafka-Compatible Durable Producer For Hot Command Ingress

Status: accepted

Summary:
- The July 4 direct no-DB publish-pipeline telemetry showed the matching engine and direct engine consumer are not the current limiter; the API path is dominated by durable publish ack time.
- The hot-ingress implementation target is a Kafka-compatible command-log producer and matching-engine direct consumer, first tested against Redpanda, while keeping JetStream available as fallback and comparison.
- The runtime must still return `202 Accepted` only after the configured durable command-log producer acknowledges the command.
- The Kafka-compatible producer uses explicit command partitions, `acks=all`, idempotent producer mode, bounded application in-flight work, async send callbacks, batching, and compression.
- The matching engine consumes assigned Kafka topic partitions directly in the no-DB path, publishes venue event batches to a Kafka-compatible event topic, and commits command offsets only after durable event-batch publication succeeds.
- Redpanda/Kafka adoption is limited to the durable ingress/log mechanics at this stage. It does not move canonical venue facts out of the matching/post-match completion boundary.
- The next promotion gate is evidence from no-DB and stream-ack stress runs showing materially lower durable publish ack time, bounded queue/in-flight depth, no accepted/acked gap, and clean replay/audit metadata.

Primary references:
- [`docs/PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- [`docs/DEV_ENV.md`](./DEV_ENV.md)
- [`docs/DEV_ENV.md`](./DEV_ENV.md)

### D-042: Shard-Local In-Memory Hot Book

Status: accepted

Summary:
- matching hot-book state remains in Go memory inside the matching-engine shard that owns the relevant command partition.
- book ownership follows the durable command partition key: `runId + venueSessionId + instrumentId`, with submit/cancel/modify for the same book routed to the same ordered lane.
- engine sharding scales across owned partition ranges; it does not split one mutable book across multiple concurrent writers.
- logical lanes do not need to map one-to-one to ticker symbols, worker processes, or engine deployables; cold instruments may share deterministic lanes.
- hot books should be isolated with explicit static routing overrides first; runtime routing changes and live book migration are deferred until routing epochs, drain/fencing, snapshot/replay handoff, and checksum verification are implemented.
- the matching engine should use a Reef-owned book implementation with ordered price levels, FIFO queues per price, and an order-id index for direct cancel/modify unlinking.
- `github.com/tidwall/btree` is acceptable as a narrow ordered price-level index dependency; matching semantics, replay, event generation, and checksums remain Reef-owned.
- snapshots are recovery accelerators for shard-local book state, not the source of truth. Recovery must remain snapshot plus durable command/event replay plus checksum verification.
- Redis, Postgres, RocksDB/Pebble, and embedded C++ engines are not accepted hot-book stores for this phase.

Primary references:
- [`docs/HOT_BOOK_SHARDING_PLAN.md`](./HOT_BOOK_SHARDING_PLAN.md)
- [`docs/steering/go.md`](./steering/go.md)
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)

### D-044: Projection-Backed Market Data Snapshot Boundary

Status: accepted

Summary:
- market-data reads must remain separate from order-entry writes and must not query matching-engine mutable book internals for normal bot/user traffic.
- the first implemented market-data surface is projection-backed and conservative: `runtime.order_lifecycle_state` rebuilds open/filled/cancelled order state from projected runtime facts, `runtime.market_data_snapshots` stores top-of-book snapshots, and bounded depth reads aggregate remaining open lifecycle quantity by price.
- `POST /api/v1/market-data/snapshots` rebuilds lifecycle state before refreshing top-of-book snapshots. `GET /api/v1/market-data/snapshots/{instrumentId}` and `GET /api/v1/market-data/depth/{instrumentId}` expose source projection and lag/freshness metadata.
- `MARKET_DATA_PROJECTOR_ENABLED=true` enables an opt-in background loop for top-of-book refreshes on background-capable runtime roles. It is disabled by default until workload evidence justifies always-on refresh behavior.
- bounded depth remains read-time aggregation over lifecycle state for now. A fully incremental market-data projector and venue-session-specific depth are follow-on work.

Primary references:
- [`docs/TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md)
- [`docs/CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- [`docs/DEV_ENV.md`](./DEV_ENV.md)

### D-043: Venue Event Batch Materialization Boundary

Status: accepted

Summary:
- the next persistence slice is venue event batch materialization, not more generic runtime workers calling the engine and writing normalized rows.
- matching-engine shards may commit command offsets after they consume ordered command partitions, mutate shard-local books, and durably publish `VenueEventBatch` records.
- Postgres materialization is asynchronous from the durable venue event stream/topic. The materializer commits its own consumed event-batch offset only after compact canonical Postgres rows commit.
- durable venue event batches are the matching-engine recovery handoff and canonical matching ledger for engine completion. Postgres remains the compact materialized canonical/query store for command outcome lookup, replay, audit, and downstream projection.
- initial materialization tables should include `runtime.canonical_venue_event_batches` and `runtime.canonical_command_outcomes`.
- canonical batch rows must preserve batch id, shard id, partition id, command stream/topic, event stream/topic, first/last command sequence or offset, command count, payload checksum, payload format/version, creation time, and original batch payload or equivalent replay-safe fact payload.
- command outcome lookup rows must support status lookup, idempotent materializer replay, command-to-batch linkage, command type, result status, reject code, instrument/order identifiers, stream sequence/offset, and payload hash.
- the first runtime source is Kafka-compatible Redpanda consumption of the configured venue event topic through `PLATFORM_RUNTIME_ROLE=materializer`; JetStream event-batch materialization can be added behind the same `VenueEventBatchSource` contract later.
- normalized `orders`, `executions`, `trades`, `runtime_events`, UI tables, metrics, and leaderboards remain downstream projections unless a future decision deliberately promotes a field into the materializer's compact canonical commit.
- the first downstream projection from event-batch materialization is compact lifecycle visibility: `runtime.canonical_command_outcomes` can project submit outcomes into `submit_results` and lifecycle `runtime_events` without placing Postgres back in the matching hot path.
- full `orders` projection from the event-batch path uses the durable command-payload join first: accepted `SubmitOrder` outcomes can reconstruct order metadata from `command_log.command_payloads`, while `VenueEventBatch` remains compact and focused on canonical engine outcomes.
- replay/checksum evidence from durable event batch to Postgres rows is required before throughput claims for this slice; the local `dev-venue-event-replay-check` path must show idempotent stored batch replay, clean command counts, payload checksums, command outcome payload hashes, stream sequence continuity, and projection watermarks where a projection name is supplied.

Lifecycle boundary:
```text
API 202:
  after durable command-log ack

engine processed:
  after command consumed, book mutated, and venue event batch durably published

command offset commit:
  after durable venue event-batch publication

Postgres canonical materialized:
  after async materializer reads event batches and commits compact canonical rows

Postgres materializer offset commit:
  after compact canonical Postgres batch commit

projection visible:
  after async projections catch up from canonical rows/events
```

Primary references:
- [`docs/WORK_PLAN.md`](./WORK_PLAN.md)
- [`docs/CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- [`docs/PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/HOT_BOOK_SHARDING_PLAN.md`](./HOT_BOOK_SHARDING_PLAN.md)

### D-032: Command Log Queue And Result Split

Status: accepted

Summary:
- `command_log.commands` remains the durable inbound command intake record and idempotency anchor.
- mutable worker state belongs in `command_log.command_work_queue`, not on the command intake row.
- terminal response payloads belong in `command_log.command_results`, not on the command intake row.
- command status APIs compose command metadata, queue state, and result rows so public status behavior remains stable.
- this split is intended to reduce indexed status updates and dead tuples on the command intake table before deeper async/batched runtime persistence work.
- first benchmark evidence showed this split alone regresses accepted ingress because it adds active-queue/result writes; follow-up work must reduce write amplification before treating the split as a throughput win.
- command intake can use the opt-in `command_log.command_append(...)` database routine so command insert, active-queue enqueue, and duplicate replay stay in one database call.
- benchmark evidence did not justify making that routine the default: `EXTERNAL_API_COMMAND_LOG_APPEND_MODE=inline` remains the default until a function or batched intake path beats it.
- durable request payloads can live in `command_log.command_payloads` while new `command_log.commands` rows keep a slim compatibility payload. `EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE=side-table` is the default because it narrows the hot command metadata row without losing worker replay data; `inline` remains available for A/B testing.
- batched terminal result and queue-completion writes are now the default async completion path, but 2026-07-02 stress evidence did not recover the `7500` completed/sec target.
- captured-ack submit workers batch runtime outcome persistence by preparing submit outcomes for the claimed batch, persisting them with one schema-owned `runtime.runtime_persist_submit_outcomes(jsonb)` call, and only then marking command-log terminal rows. The current bulk routine performs set-based submit-result, order, execution, trade, event, and trace-sequence writes, preserving no-complete-before-persist ordering while reducing app-to-DB round trips.
- the next measured bottlenecks are command-log reserve/write amplification and per-command runtime persistence, not async queue claim mechanics.
- `command_log.command_work_queue` is recoverable active scheduling state and can be unlogged to reduce WAL churn; no-loss accounting still depends on logged `command_log.commands` for accepted commands and logged `command_log.command_results` for terminal outcomes.
- bootstrap must continue reconstructing active queue rows from durable commands without terminal results after restart.
- first quick loaded-stack evidence for the unlogged active queue recovered a `3945.78 accepted rps` point with eventual `59395/59395` terminal commands and `0` accounting gap, but it is still below the `7500` completed/sec target.
- captured-ack dev/stress profiles should disable legacy boundary command capture by default because durable command capture is already provided by `command_log.commands`; keeping both writes on the hot path reduced the quick loaded-stack point to about `1646 accepted rps`.
- first quick loaded-stack evidence after disabling duplicate legacy capture reached `4757.19 accepted rps` with eventual `71559/71559` terminal commands and `0` accounting gap.
- new captured-ack commands should not perform the synchronous idempotency-store lookup after command-log reservation; command-log idempotency already guards duplicate replay in this mode.
- first quick loaded-stack evidence after skipping that redundant lookup reached `4851.68 accepted rps` with eventual `73005/73005` terminal commands and `0` accounting gap.
- drain-accounted worker sweeps show that raising async worker count alone does not reach the completed-throughput target. The best corrected loaded-stack point accepted `4801.95 rps`, but completed only `532.38/sec` during load and drained the remaining backlog afterward at `3743.31/sec`.
- first bulk runtime procedure evidence stayed lossless (`0` final active and `0` accounting gap), improved the `8k/8s` post-load drain point to `4417.50/sec`, and reached `2135.68 completed/sec` during a `15k/15s` pressure run, but still left command-log append and runtime batch persistence as the measured blockers.
- command-log child tables (`command_payloads`, `command_work_queue`, and `command_results`) no longer keep same-schema foreign keys to `command_log.commands` on the hot write path. Accepted-command durability remains anchored by logged `command_log.commands` plus logged `command_payloads`; terminal durability remains in logged `command_results`; active queue state remains derived/reconstructable. Prune deletes child rows explicitly before command rows instead of relying on cascade.
- first evidence after set-based runtime persistence and command-log FK removal stayed lossless, reached `4014.61 accepted rps`, `2404.21 completed/sec` during load, and `5606.43/sec` post-load drain with `8` async workers. A `16` worker comparison lowered accepted throughput and did not improve completed throughput enough, so `8` workers remains the better current local profile.
- superseded by D-036 for the bot-arena high-throughput path: the next major throughput slice should build stream-ack ingress and partitioned processing rather than continue small command-log write-amplification tuning.

Primary references:
- [`docs/ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`docs/BOT_ARENA_STRESS_BASELINE_2026-07-01.md`](./BOT_ARENA_STRESS_BASELINE_2026-07-01.md)
