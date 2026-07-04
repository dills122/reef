# Reef Work Plan

## Purpose

This is the active execution plan. It deliberately stays short and points to detailed evidence instead of repeating every sprint plan and benchmark note.

For the current snapshot, read [`CURRENT_STATUS.md`](./CURRENT_STATUS.md) first.

## Planning Posture

Reef remains a simulation-first institutional trading venue and post-trade platform. The near-term work is not broad feature expansion. It is proving a durable, replayable, high-throughput venue core while preserving the API, audit, and simulation contracts that later post-trade modules need.

Current deployment assumptions:

- one Kotlin platform runtime
- one Go matching engine, with direct stream-consumer work in progress
- Postgres as canonical state for durable runtime modes
- JetStream and Redpanda/Kafka-compatible providers available for durable command-log experiments
- simulator/load tools driving the same public command paths as manual users
- Angular UI and post-trade modules added only after command/event causation is stable enough to inspect

## Completed Baseline

Implemented or materially started:

- `/api/v1` submit/cancel/modify command paths with idempotency, auth/rate-limit hooks, abuse protection, and command status lookup
- runtime persistence and migration folders for split-ready schemas
- order, trade, event, and trace query endpoints
- admin CLI scaffolding for reference data, roles, calendars, simulation controls, and trace inspection
- Go matching engine hidden-book behavior, partial fills, multi-match, cancel, modify, HTTP, gRPC, and direct stream-consumer paths
- protobuf order-execution contracts
- simulator/load tester with strict lifecycle, capacity baseline, persona/session config, deterministic replay, trace checks, and throughput reports
- local Docker setup/reset/smoke/stress/replay automation
- DigitalOcean benchmark harness and July 2026 throughput evidence

## Active Gaps

The current gaps are:

- durable hot-ingress throughput is still below the target once durable publish acknowledgements and completion semantics are enforced
- generic stream workers calling the engine per command are transitional, not the target hot matching architecture
- direct matching-engine command consumption exists but still needs promotion evidence, cancel/modify support, and persistence reintroduction
- persisted order lifecycle projections do not yet fully mirror submit/cancel/modify/fill engine state
- simulator control-room UI is still planned rather than implemented
- first deterministic lifecycle scenarios are not locked end to end
- post-trade workflows remain scenario-locked future work

## Active Execution Ladder

1. Validate D-041 hot ingress.
   - Prove Redpanda/Kafka-compatible durable producer plus matching-engine direct consumer against no-DB stress targets.
   - Require durable ack-before-`202`, bounded queue/in-flight depth, clean accepted/acked accounting, and replay/audit metadata.
   - Keep JetStream as fallback and comparison until evidence says otherwise.

2. Reintroduce persistence deliberately.
   - Preserve deterministic partition ordering and no-complete-before-durable-fact semantics.
   - Keep canonical event facts separate from rebuildable projections.
   - Measure rows/command, WAL/command, commits/command, publish ack latency, worker lag, projection lag, and drain behavior.

3. Complete venue lifecycle projection.
   - Submit/cancel/modify/fill/reject state should be queryable through persisted read APIs.
   - Runtime state, engine state, events, and traces should agree under deterministic tests.

4. Build simulator control-room MVP.
   - Wrap existing scripts and report artifacts.
   - Store run metadata, seed/session config, command, git metadata, runtime mode, metric scope, and artifact paths.
   - Compare accepted, completed, projected, success rate, p95/p99, reject taxonomy, trace checks, and replay status.

5. Lock first lifecycle scenarios.
   - `P1_GOLDEN_HIDDEN_CROSS_T1`
   - `P2_SETTLEMENT_BREAK_REPAIR`
   - Assert ordered events, final state, replay consistency, and visible timeline causation.

6. Start post-trade expansion.
   - Add allocation, confirmation, settlement, and exception behavior only after the scenario/replay path can prove causation.

## Active Workstreams

### A. Hot Ingress And Direct Engine Consumption

Primary references:

- [`DECISIONS.md`](./DECISIONS.md), especially D-036 through D-041
- [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- [`STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`DIGITALOCEAN_STRESS_TEST_PLAN.md`](./DIGITALOCEAN_STRESS_TEST_PLAN.md)

Exit criteria:

- no accepted-command accounting gaps
- clean command ack or offset commit semantics
- no unresolved redelivery, async worker failure, or hidden accepted/completed gap
- benchmark reports distinguish attempted, durably accepted, engine-acked/completed, persisted, projected, and visible throughput
- replay/audit metadata is sufficient to prove every accepted command outcome

### B. Persistence And Projection Reintroduction

Primary references:

- [`DATA_DOMAIN_SCHEMA_BLUEPRINT.md`](./DATA_DOMAIN_SCHEMA_BLUEPRINT.md)
- [`API_BOUNDARY_STORAGE_DECISIONS.md`](./API_BOUNDARY_STORAGE_DECISIONS.md)
- [`COMMAND_LOG_PARTITIONING_PLAN.md`](./COMMAND_LOG_PARTITIONING_PLAN.md)
- [`EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)

Exit criteria:

- runtime persistence uses migration-owned schema objects
- hot-path writes are compact, batched where useful, and measured
- projection writes are downstream and rebuildable
- local startup validates schema placement instead of silently bootstrapping drift

### C. Venue Lifecycle Completion

Primary references:

- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
- [`docs/steering/architecture.md`](./steering/architecture.md)
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)

Exit criteria:

- submit, cancel, modify, rest, partial fill, fill, and reject states are represented consistently
- query APIs expose current state, history, trades, executions, and trace timeline
- tests prove runtime and engine lifecycle parity for core paths

### D. Simulator Control Room And Scenarios

Primary references:

- [`SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)
- [`SIMULATOR_PERSONA_CONFIG.md`](./SIMULATOR_PERSONA_CONFIG.md)
- [`SIMULATOR_UPGRADE_BACKLOG.md`](./SIMULATOR_UPGRADE_BACKLOG.md)
- [`packages/scenario-definitions/`](../packages/scenario-definitions/)

Exit criteria:

- UI/API orchestration uses existing scripts and artifacts rather than inventing a second simulation path
- scenario runs are seedable and replayable
- control-room views expose freshness, lag, artifact paths, and reproduction commands

## Historical Planning Documents

These documents remain useful as evidence or design context, but they are no longer the active execution ladder by themselves:

- `SPRINT_COMMUNICATION_API_ADMIN.md`
- `SPRINT_DEV_ENV.md`
- `SPRINT_CRITICAL_QUALITY_HARDENING.md`
- `SPRINT_POST_MATCH_ENGINES.md`
- `BOT_ARENA_STRESS_BASELINE_2026-07-01.md`
- May 2026 throughput and abuse-breaker baseline reports

If one of these becomes active again, update this file and [`CURRENT_STATUS.md`](./CURRENT_STATUS.md) with the reason.
