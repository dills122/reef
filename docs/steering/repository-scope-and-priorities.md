# Reef Repository Scope And Priorities

## Purpose

This file captures the repo-level product and engineering posture that should shape day-to-day changes.

Reef is a local-first, simulation-driven institutional trading venue and post-trade platform. It is not a retail trading app, a toy matching demo, or an HFT benchmark harness. It is an API-first venue simulator where commands, hidden-book matching, trades, lifecycle events, audit trails, deterministic scenarios, and post-trade workflows are inspectable, replayable, and explainable.

The platform must be designed for both:

1. High correctness
   - deterministic replay
   - strict lifecycle causality
   - audit-friendly event trails
   - idempotent command handling
   - reproducible scenario outcomes
   - no silent data loss, gaps, or duplicate lifecycle effects

2. High throughput
   - low write amplification in command hot paths
   - durable asynchronous command intake where appropriate
   - partitionable processing by book, session, and instrument
   - batched canonical persistence
   - async read-model and control-room projections
   - measurable sustained throughput under realistic bot and simulator load

Performance work must never weaken correctness, determinism, auditability, or replay semantics.

## Primary Deliverables

- Astro documentation and marketing site
- Kotlin platform runtime for APIs, workflow orchestration, durable command intake, persistence, read models, projections, and admin modules
- Go matching engine for order book, hidden-book behavior, matching, partial fills, cancel/modify behavior, and execution event generation
- Simulator for deterministic scenarios, seeded participants, replay, synthetic actors, traffic generation, stress testing, and performance evidence
- Versionable contracts and shared models across runtime, engine, and simulator
- Local-first operational tooling for setup, reset, smoke, stress, replay, diagnostics, and performance comparison

## Core Priorities

Reef development should optimize for these priorities, in order:

1. Lifecycle correctness
   - Orders, cancels, modifies, fills, rejects, trades, events, and post-trade states must follow explicit, testable lifecycle rules.
   - Business rejects are acceptable when valid.
   - System failures, trace gaps, duplicate trades, out-of-order fills, and nondeterministic replay are not acceptable.

2. Deterministic simulation and replay
   - Scenario runs must be seedable, replayable, and explainable.
   - Randomness, clocks, actor identities, correlation IDs, command IDs, stream positions, and event sequence numbers must be controlled or recorded.
   - Replay must be able to prove whether outcomes match previous runs.

3. High-throughput architecture
   - Hot command paths must avoid unnecessary synchronous persistence, table scans, read-model updates, and per-command write amplification.
   - Durable command intake, matching, canonical event persistence, and read-model projection should be separated where possible.
   - Batch writes, append-only logs, partitioned processing, and async projections should be preferred over synchronous write-everything transactions.

4. Auditability and explainability
   - Every meaningful lifecycle transition should be reconstructible from canonical command and event data.
   - Projections, dashboards, and timelines should be rebuildable from canonical facts.
   - Performance optimizations must not remove evidence required for audit, trace, replay, or post-trade explanation.

5. Stable typed contracts
   - Runtime, engine, simulator, UI, and documentation should communicate through stable, versionable contracts.
   - Contract changes must preserve semantics or include explicit migrations.

6. Local-first operability
   - Developers must be able to run, reset, smoke test, stress test, replay, and inspect the system locally.
   - Local workflows should remain reliable as the architecture moves toward stream-backed and partitioned processing.

## Throughput And Correctness Standards

Reef treats throughput as a first-class design concern.

When working on command intake, matching, persistence, workers, projections, simulator load, or control-room views, evaluate changes against both correctness and throughput.

Important distinctions:

- Attempted RPS/TPS: commands sent by clients or simulator actors
- Durably accepted RPS/TPS: commands accepted into the durable ingress path
- Processed RPS/TPS: commands consumed and resolved by workers or the engine
- Canonical event RPS/TPS: lifecycle facts durably persisted
- Projected RPS/TPS: read models, dashboards, and timelines caught up

Do not claim a throughput milestone using only attempted commands or raw published messages.

A throughput claim should include, where applicable:

- attempted command rate
- durably accepted command rate
- processed command rate
- business reject rate
- system failure rate
- p50/p95/p99 latency
- stream or queue backlog
- oldest unprocessed command age
- worker lag
- DB flush latency
- projection lag
- replay result
- trace validation result
- rows written per accepted command
- commits per accepted command
- evidence artifact or benchmark report location

Performance improvements are not valid if they cause:

- nondeterministic replay
- missing lifecycle events
- duplicate trades or executions
- out-of-order events for the same book
- projection inconsistency hidden from operators
- unbounded backlog
- silent command loss
- audit or trace gaps

## Target Architecture Direction

The preferred high-throughput architecture is:

```text
Boundary/API
  -> durable command ingress
  -> partitioned command workers
  -> matching engine
  -> canonical lifecycle/event persistence
  -> async projections/read models/control-room views
```

The platform should avoid this shape for high-throughput paths:

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

That synchronous shape may remain useful for strict correctness baselines, local debugging, and selected test modes, but it should not be treated as the primary path to high sustained TPS.

## Hot Path Rules

For command intake and processing hot paths:

- keep synchronous work minimal and explicit
- do not add database writes to the command hot path without justification
- do not synchronously update read models unless the mode explicitly requires it
- do not repeatedly scan hot append tables for status, metrics, leaderboards, or control-room views
- prefer append-only canonical logs over mutable queue/status rows
- prefer worker offsets and watermarks over repeatedly updating command-log status rows
- prefer batch persistence over per-command persistence
- prefer deterministic event IDs and sequence numbers over generated side effects that are hard to replay
- prefer async projections for query/UI state
- expose projection freshness and lag instead of pretending all views are synchronously current

Any change that increases write amplification in the hot path should include a clear reason and, when relevant, benchmark evidence.

## Command Acceptance Semantics

A command is not durably accepted until the configured ingress mechanism has confirmed durable acceptance.

For stream-backed modes:

- return `202 Accepted` only after durable stream publish acknowledgment
- reject before durable acceptance if backpressure, stream health, partition lag, worker health, or DB flush lag indicates the system cannot safely drain
- do not return `202` for commands that only reached an in-memory queue or socket buffer
- keep idempotency valid across retries, crashes, duplicate submissions, and replay

For Postgres-backed captured-ack modes:

- keep the command capture path narrow
- avoid turning the command log into a high-churn mutable queue
- treat the Postgres-only path as a correctness baseline or local fallback unless performance evidence proves otherwise

## Partitioning And Ordering Rules

Commands that affect the same order book must process in a deterministic order.

The routing key for matching-sensitive commands should preserve this invariant:

```text
All submit/cancel/modify commands for the same venue session and instrument must enter the same ordered processing lane.
```

Preferred partition identity:

```text
venueSessionId + instrumentId
```

For simulator isolation, `runId` may also be included:

```text
runId + venueSessionId + instrumentId
```

Do not introduce worker concurrency that allows multiple workers to mutate the same book concurrently unless the matching engine and lifecycle model explicitly prove correctness under that design.

Cancels and modifies must carry enough routing metadata to reach the same partition as the original order. Avoid adding synchronous hot-path lookups merely to recover missing routing information.

Logical partition lanes do not need to map one-to-one to instruments or worker processes. Prefer a fixed configured lane count, group cold instruments by deterministic hash, and isolate only proven hot books through explicit routing overrides. If a hot instrument shares a lane with cold instruments, first move the cold instruments away; live migration of the hot book itself requires drain, sequence fencing, audited routing epochs, snapshot/replay handoff, and checksum verification.

## Canonical Facts And Projections

Reef distinguishes between canonical lifecycle facts and rebuildable projections.

Canonical facts include:

- accepted command records
- command results
- order lifecycle events
- trade/execution events
- deterministic event sequence numbers
- replay-relevant metadata

Rebuildable projections include:

- order current-state views
- trade search views
- execution views
- control-room metrics
- leaderboard views
- timeline views
- trace summaries
- run status counters
- audit query conveniences

Workers should commit canonical facts before acknowledging consumed commands.

Read models and projections should be asynchronously updated where possible and should expose watermarks such as:

```text
projectionName
runId
partitionId
lastCommandSeqApplied
lastEventSeqApplied
lagEvents
lagMs
updatedAt
```

The UI and API should be honest about projection freshness.

## Active Boundaries

- `apps/docs-site/` owns public/static documentation and should not contain runtime logic.
- `services/platform-runtime/` owns API boundaries, workflow orchestration, durable command intake, persistence, read models, projections, and admin behavior.
  - It owns command acceptance semantics.
  - It owns idempotency enforcement or coordination.
  - It owns backpressure behavior.
  - It must keep hot-path persistence minimal and measurable.
- `services/matching-engine/` owns matching and execution logic, not platform workflow orchestration.
  - It should remain deterministic for the same ordered command input.
  - It should not own UI, admin, persistence, or post-trade orchestration concerns.
  - It should expose matching outcomes in stable contract form.
- `services/simulator/` owns scenario control, seeded activity, replay, synthetic actors, traffic generation, and stress/load evidence.
  - It must distinguish attempted, accepted, processed, and projected throughput.
  - It should validate trace integrity, replay determinism, lifecycle correctness, and backlog behavior.
  - It should include hot-symbol and cancel/modify-heavy workloads, not only easy multi-symbol throughput cases.
- `contracts/proto/` owns versionable inter-service contract definitions.
  - Runtime-to-engine and runtime-to-simulator semantics must remain stable unless explicitly migrated.
  - Contract changes should preserve lifecycle meaning and replay compatibility.
- `packages/ui-models/` owns shared UI-facing model definitions.
  - UI models should reflect projection freshness where relevant.
- `packages/scenario-definitions/` owns reusable scenario inputs.
  - Scenario definitions must preserve deterministic seeds, clocks, actors, instruments, and correlation inputs.
- `scripts/` owns local development and operational automation.
  - Setup, reset, smoke, stress, replay, and diagnostics workflows should remain reliable.
  - Performance scripts should produce comparable artifacts across runs.
- `docs/steering/` owns normative direction for architecture, languages, APIs, repository shape, performance posture, and communication boundaries.

## Safe Refactor Boundaries

Do not refactor these without explicit instruction:

- top-level project shape:
  - `apps/`
  - `services/`
  - `contracts/`
  - `packages/`
  - `docs/`
  - `scripts/`
- public `/api/v1` boundary direction
- command acceptance semantics, including:
  - accepted vs processed distinction
  - `202` behavior
  - idempotency behavior
  - backpressure behavior
- runtime-to-engine contract semantics
- matching lifecycle semantics, including:
  - hidden-book matching behavior
  - partial fills
  - multi-match behavior
  - cancel/modify rules
  - business reject classification
- event names and lifecycle semantics used for audit, replay, read models, post-trade workflows, and deterministic scenarios
- canonical command/event sequencing rules
- stream, queue, or worker ordering semantics
- scenario determinism inputs, including seeds, clocks, actor identity, command IDs, order IDs, correlation IDs, partition keys, and event sequence IDs
- local environment commands in `Makefile` and `scripts/dev/`
- benchmark and performance evidence workflows documented in `docs/PERFORMANCE_LEARNINGS.md`

Safe default changes:

- feature-scoped improvements within one bounded context
- validation and endpoint hardening
- focused test additions
- typed contract clarification
- projection/read-model improvements that do not alter canonical semantics
- documentation updates that keep steering aligned with actual behavior
- benchmark/reporting improvements that make throughput and correctness easier to inspect
- local developer tooling improvements that preserve existing commands

## Changes That Require Extra Care

The following changes require explicit reasoning, tests, and preferably benchmark evidence:

- adding synchronous database writes to command intake
- changing idempotency behavior
- changing command acceptance behavior
- changing worker acknowledgment timing
- changing partition keys or routing behavior
- changing event names or lifecycle event ordering
- changing replay inputs or event sequencing
- changing matching-engine command/result semantics
- changing persistence strategy for canonical facts
- replacing append-only behavior with mutable status updates on hot tables
- adding indexes to high-write append tables
- making control-room or UI endpoints scan raw hot tables under load
- changing stress benchmark definitions
- claiming a performance improvement without comparable before/after evidence

## Performance Development Guidelines

When optimizing Reef, prefer these moves:

- reduce synchronous hot-path writes
- batch canonical persistence
- isolate canonical facts from rebuildable projections
- use append-only event/command logs where appropriate
- partition large append tables by run, session, day, or another explicit lifecycle boundary
- minimize indexes on high-write canonical tables
- move query-heavy views to projections
- expose lag/watermarks for async views
- add backpressure before durable acceptance
- measure backlog and drain behavior
- preserve deterministic replay

Avoid these moves as primary performance fixes:

- blindly increasing HTTP thread counts
- blindly increasing worker counts
- adding more database indexes to hot write tables
- weakening durability without clearly marking data as rebuildable
- hiding projection lag
- counting raw intake as full venue throughput
- skipping audit/event writes to inflate TPS
- bypassing idempotency
- bypassing lifecycle validation
- making matching state concurrently mutable without deterministic ordering guarantees

## Benchmark And Evidence Expectations

A performance-related change should ideally answer:

```text
What workload was used?
What command mix was used?
Was there a single hot instrument test?
Was there a cancel/modify-heavy test?
What was attempted RPS?
What was accepted RPS?
What was processed RPS?
What was projected RPS?
What were p50/p95/p99 latencies?
What was system failure rate?
What was business reject rate?
Did replay remain deterministic?
Did trace validation pass?
Did backlog remain bounded?
Did the system drain after load stopped?
What rows/command or writes/command changed?
What DB flush or persistence metric changed?
Where is the evidence artifact?
```

Throughput without correctness is not success. Correctness without a credible path to sustained load is not enough for Reef's target venue simulator.

## Current Performance Posture

Treat the existing synchronous/Postgres-heavy path as a correctness baseline and local fallback unless updated benchmark evidence says otherwise.

The project direction should move toward:

```text
durable command ingress
partitioned workers
deterministic matching
batched canonical persistence
async projections
measured backpressure
deterministic replay
```

Near-term performance goals should be validated in stages:

1. durable command acceptance under load
2. worker processing under load
3. canonical event persistence under load
4. projection catch-up under load
5. full replay and trace validation after stress

Do not treat raw message intake as equivalent to full venue capacity.

## Normative References

- `REEF_PROJECT_OVERVIEW.md`
- `REEF_TECHNICAL_DESIGN.md`
- `docs/steering/architecture.md`
- `docs/steering/repository.md`
- `docs/steering/inter-service-communication.md`
- `docs/steering/external-api-boundary.md`
- `docs/ENGINEERING_DELIVERY_POLICY.md`
- `docs/PERFORMANCE_LEARNINGS.md`
