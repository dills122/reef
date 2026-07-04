# Reef Current Status

## Purpose

This is the short operational snapshot for Reef. Use it to orient current work before reading deeper planning, benchmark, or sprint documents.

Last aligned: 2026-07-04.

## Current Project State

Reef has moved beyond a repository skeleton. The current implementation includes:

- Kotlin platform runtime with `/api/v1` submit, cancel, and modify command paths
- boundary idempotency, auth/rate-limit hooks, abuse protection, command capture, and command status lookup
- explicit runtime, boundary, auth, admin, orchestration, analytics, and command-log schemas applied through local migrations
- runtime query surfaces for orders, trades, events, and trace timelines
- admin CLI scaffolding for reference data, roles, calendars, simulation controls, and trace inspection
- Go matching engine with hidden-book matching, partial fills, multi-match, cancel, modify, HTTP, gRPC, and direct stream-consumer paths
- protobuf contracts for order execution commands and results
- Go simulator/load tester with persona/session support, deterministic replay checks, stress reports, and intake benchmarks
- Docker-first local setup, reset, smoke, stress, replay, and DigitalOcean benchmark automation

The platform UI and post-trade lifecycle are still early. The current strongest verification surface is the local/remote simulator and benchmark harness, not the UI.

## Active Architecture Direction

Keep these distinctions explicit:

- `sync-result` remains the deterministic correctness and compatibility mode.
- Postgres `captured-ack` remains a local fallback and A/B baseline.
- JetStream `stream-ack` proved the durable-acceptance shape, but July 2026 evidence showed the generic worker-to-engine path and write-heavy projection path are not the final high-throughput base.
- The active high-throughput venue-core path is moving toward a Kafka-compatible durable command log, explicit command partitions, matching-engine direct consumption, durable venue event batches, and offset commit only after durable event publication.
- `202 Accepted` still means the configured durable ingress/log producer has acknowledged the command. Do not weaken that contract.

Current decision anchors:

- D-036 and D-037 define durable stream-backed acceptance and completion semantics.
- D-040 supersedes generic unary worker-to-engine calls for the hot matching path.
- D-041 makes Kafka-compatible durable producer plus matching-engine direct consumer the active hot-ingress target, with JetStream retained as fallback/comparison.

## Current Forward Path

Work should follow this order unless a new decision supersedes it:

1. Keep the current durable-acceptance contracts stable while validating the D-041 Redpanda/Kafka-compatible hot-ingress path.
2. Prove no-DB direct engine ingestion at materially better durable publish-ack latency, bounded queue/in-flight depth, clean accepted/acked accounting, and replay/audit metadata.
3. Reintroduce canonical persistence deliberately after the direct-ingestion ceiling is understood, preserving deterministic command ordering and canonical event facts.
4. Complete persisted venue lifecycle projections for submit/cancel/modify so query APIs match engine lifecycle state.
5. Build the simulator control-room MVP on top of existing scripts and artifacts.
6. Lock the first deterministic lifecycle scenarios: `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`.
7. Expand post-trade modules only after timeline and replay assertions prove causation end to end.

## Documentation Map

Use these docs for active work:

- Project framing: [`REEF_PROJECT_OVERVIEW.md`](../REEF_PROJECT_OVERVIEW.md)
- Technical design: [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
- Current plan: [`WORK_PLAN.md`](./WORK_PLAN.md)
- Roadmap: [`ROADMAP.md`](./ROADMAP.md)
- Decisions: [`DECISIONS.md`](./DECISIONS.md)
- Performance evidence and next implications: [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- Stream/direct throughput context: [`ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- Local setup and commands: [`ONBOARDING.md`](./ONBOARDING.md), [`DEV_ENV.md`](./DEV_ENV.md)
- Steering index: [`steering/README.md`](./steering/README.md)

Dated benchmark reports, sprint plans, and baseline documents are evidence/history unless this file or `WORK_PLAN.md` links them as active inputs.

## Cleanup Policy

Do not delete benchmark reports or decision records just because they are superseded. They explain why the current path changed.

Slim planning docs by:

- keeping one current execution ladder
- marking historical sprint plans as historical when they are superseded
- moving repeated benchmark interpretation into `PERFORMANCE_LEARNINGS.md`
- linking evidence instead of duplicating long run summaries in multiple plans
