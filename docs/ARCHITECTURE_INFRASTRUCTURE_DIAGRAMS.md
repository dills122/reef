# Reef Architecture Infrastructure Diagrams

Last aligned: 2026-07-04.

This document shows the current venue-core infrastructure shape and the target direction for the next persistence, projection, and evidence slices.

## Current Branch State

The current branch has moved the hot matching path away from runtime workers calling the engine and toward:

- durable command-log acceptance before `202`
- matching-engine direct command consumption
- in-memory hot book mutation
- durable `VenueEventBatch` publication
- asynchronous compact Postgres materialization

```mermaid
flowchart LR
  subgraph Clients["Clients And Simulation Actors"]
    UI["Platform UI / Ops"]
    Sim["Simulator / Load Tester"]
    Manual["Manual API Users"]
  end

  subgraph RuntimeAPI["Platform Runtime API"]
    API["platform-api\n/api/v1 commands"]
    Boundary["External API Boundary\nAuth, idempotency, validation"]
    Intake["Durable command intake\nstream-ack publisher"]
  end

  subgraph CommandLog["Durable Command Log"]
    KafkaCmd["Redpanda / Kafka command topic\nactive hot-ingress path"]
    JetCmd["JetStream command stream\nfallback and comparison"]
  end

  subgraph Matching["Matching Engine"]
    DirectConsumer["Direct stream consumer\npartition-owned lanes"]
    HotBook["In-memory hot book\nsession / instrument shard"]
    EventPublisher["VenueEventBatch publisher"]
  end

  subgraph EventLog["Durable Venue Event Log"]
    KafkaEvents["Redpanda / Kafka event topic\nVenueEventBatch records"]
    JetEvents["JetStream event stream\nfuture compatible source"]
  end

  subgraph Materializer["Platform Materializer"]
    MatRole["platform-materializer\nPLATFORM_RUNTIME_ROLE=materializer"]
    MatSource["KafkaVenueEventBatchSource"]
    MatLoop["VenueEventBatchMaterializer\nDB commit before event offset ack"]
  end

  subgraph Postgres["Postgres Persistence"]
    RuntimeDB["runtime Postgres"]
    CanonBatches["runtime.canonical_venue_event_batches"]
    CanonOutcomes["runtime.canonical_command_outcomes"]
    BoundaryDB["boundary Postgres\nidempotency and intake metadata"]
    ProjectionDB["projection Postgres\nnormalized read tables"]
  end

  subgraph Diagnostics["Diagnostics And Gates"]
    MatStats["/internal/venue-event-materializer/stats"]
    ReplayGate["Replay / checksum tests"]
    Smoke["make dev-smoke-venue-event-materializer"]
  end

  UI --> API
  Sim --> API
  Manual --> API

  API --> Boundary
  Boundary --> Intake
  Intake --> KafkaCmd
  Intake -. comparison .-> JetCmd
  Boundary --> BoundaryDB

  KafkaCmd --> DirectConsumer
  JetCmd -. fallback .-> DirectConsumer

  DirectConsumer --> HotBook
  HotBook --> EventPublisher
  EventPublisher --> KafkaEvents
  EventPublisher -. future compatible path .-> JetEvents

  KafkaEvents --> MatSource
  MatSource --> MatLoop
  MatLoop --> CanonBatches
  MatLoop --> CanonOutcomes
  MatLoop --> MatSource

  CanonBatches --> RuntimeDB
  CanonOutcomes --> RuntimeDB
  ProjectionDB -. existing downstream projections .-> UI

  MatLoop --> MatStats
  ReplayGate --> MatLoop
  Smoke --> API
  Smoke --> MatStats
  Smoke --> CanonOutcomes
```

Current completion boundary:

```text
API 202:
  after durable command-log ack

engine processed:
  after command consumed, hot book mutated, and VenueEventBatch durably published

command offset committed:
  after durable VenueEventBatch publication

Postgres canonical materialized:
  after async materializer reads event batches and commits compact canonical rows

materializer event offset committed:
  after compact canonical Postgres commit

projection visible:
  after downstream projections catch up from canonical rows/events
```

Current important implementation pieces:

- `platform-api` owns public command intake and returns `202` only after the configured durable ingress mechanism acknowledges.
- Redpanda/Kafka-compatible command topics are the active hot-ingress promotion path; JetStream remains a comparison/fallback provider.
- Matching-engine direct consumers own ordered command partitions and mutate in-memory books.
- The matching engine publishes durable `VenueEventBatch` facts before committing command offsets.
- `platform-materializer` consumes event batches and writes compact canonical Postgres rows.
- `runtime.canonical_venue_event_batches` preserves replay-safe batch facts and checksums.
- `runtime.canonical_command_outcomes` gives command-to-batch linkage and compact engine outcome lookup.
- Normalized orders, executions, trades, runtime events, UI views, metrics, and leaderboards remain downstream projections.

## Target Direction

The next target is to make canonical materialization visible through command status and then project lifecycle state from compact canonical rows.

```mermaid
flowchart LR
  subgraph Actors["Clients, Control Room, And Simulators"]
    UI["Control Room UI"]
    Sim["Scenario Simulator"]
    Bots["Bot / Actor Traffic"]
    Admin["Admin / Ops"]
  end

  subgraph APIPlane["API And Ingress Plane"]
    API["platform-api"]
    Boundary["Boundary contracts\nAuth, idempotency, abuse gates"]
    Backpressure["Drain-aware backpressure\nstream, engine, materializer lag"]
    DurableCommands["Durable command log\nKafka-compatible primary"]
  end

  subgraph EnginePlane["Matching Engine Plane"]
    Router["Deterministic partition routing"]
    Shards["Engine shards\nin-memory hot books"]
    BatchPub["Durable VenueEventBatch publication"]
  end

  subgraph LedgerPlane["Durable Matching Ledger"]
    EventLedger["Retained venue event batch log\ncanonical engine handoff"]
    Replay["Replay and checksum verifier"]
  end

  subgraph CanonicalPersistence["Compact Canonical Persistence"]
    Materializers["Batch materializer workers\nidempotent replay"]
    CanonBatchStore["canonical_venue_event_batches"]
    CanonOutcomeStore["canonical_command_outcomes"]
    CanonStatus["Canonical command status lookup"]
  end

  subgraph ProjectionPlane["Async Projection Plane"]
    LifecycleProj["Venue lifecycle projector\nsubmit, cancel, modify, fill, reject"]
    Orders["orders read model"]
    Executions["executions / trades read model"]
    RuntimeEvents["runtime_events / audit timeline"]
    Analytics["metrics / leaderboards / reports"]
  end

  subgraph Evidence["Evidence And Operations"]
    Smoke["E2E materializer smoke"]
    Stress["Remote / DO throughput proof"]
    Accounting["accepted / processed / materialized / projected accounting"]
    Artifacts["Replay packs / benchmark artifacts"]
  end

  UI --> API
  Sim --> API
  Bots --> API
  Admin --> API

  API --> Boundary
  Boundary --> Backpressure
  Backpressure --> DurableCommands

  DurableCommands --> Router
  Router --> Shards
  Shards --> BatchPub
  BatchPub --> EventLedger

  EventLedger --> Materializers
  Replay --> EventLedger
  Replay --> Materializers

  Materializers --> CanonBatchStore
  Materializers --> CanonOutcomeStore
  CanonOutcomeStore --> CanonStatus
  CanonStatus --> API

  CanonBatchStore --> LifecycleProj
  CanonOutcomeStore --> LifecycleProj

  LifecycleProj --> Orders
  LifecycleProj --> Executions
  LifecycleProj --> RuntimeEvents
  LifecycleProj --> Analytics

  Orders --> UI
  Executions --> UI
  RuntimeEvents --> UI
  Analytics --> UI

  Smoke --> Accounting
  Stress --> Accounting
  Accounting --> Artifacts
  Replay --> Artifacts
```

Target operating principles:

- The hot path should stay narrow: API durable command ack, engine in-memory mutation, durable event-batch publication.
- Postgres should stay out of the matching-engine hot path.
- Canonical Postgres writes should be compact, batch-oriented, idempotent, and replayable.
- Public command status should prefer canonical command outcomes when materialized and fall back to ingress/status surfaces while materialization catches up.
- Normalized read tables should be rebuildable projections, not canonical hot-path dependencies.
- Throughput claims require accounting across accepted, engine-published, materialized, projected, and replay-clean counts.

## Near-Term Slice Map

1. Canonical command status lookup.
   - Make `/api/v1/commands/{commandId}` prefer `runtime.canonical_command_outcomes` when present.
   - Preserve fallback to command-log/status surfaces while materialization catches up.
   - Make response language distinguish durable acceptance, engine processing, canonical materialization, and projection visibility.

2. Venue lifecycle projection.
   - Project submit/cancel/modify/fill/reject outcomes from canonical rows into normalized read tables.
   - Keep projections downstream and rebuildable.
   - Add deterministic tests that event batch, canonical rows, projection rows, and query APIs agree.

3. Evidence promotion.
   - Run `make dev-smoke-venue-event-materializer` against Docker as the local end-to-end gate.
   - Promote the Redpanda/Kafka-compatible path to longer remote evidence only after replay/checksum and materialization smoke are clean.
   - Measure accepted, engine-published, materialized, projected, drain lag, and replay/checksum results together.

## Source Anchors

- Current status: [`CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- Active work plan: [`WORK_PLAN.md`](./WORK_PLAN.md)
- Decisions: [`DECISIONS.md`](./DECISIONS.md), especially D-036 through D-043
- Local commands: [`DEV_ENV.md`](./DEV_ENV.md)
- Throughput evidence: [`ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
