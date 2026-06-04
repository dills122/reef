# Reef Project Goal, Plan, and Execution Review

## Date

2026-06-04

## Purpose

This review reconciles Reef's stated vision, roadmap, steering documents, and current implementation state. It is intended to prevent planning drift as the project grows from an API-first venue slice into a simulation-first market-infrastructure platform.

## Current Goal Assessment

The strongest project goal remains:

> Build a local-first, simulation-driven institutional trading venue and post-trade platform where every meaningful lifecycle transition is inspectable, replayable, and explainable.

That goal is sound. It gives Reef a clearer identity than a generic exchange demo because it combines:

- venue behavior: order intake, hidden liquidity, matching, executions, and trades
- operational workflow: reference data, boundary controls, admin actions, post-trade states, exceptions, and settlement
- simulation and replay: deterministic actors, scenario packs, stress runs, run artifacts, and trace validation

The current risk is not a weak vision. The risk is execution breadth. Recent planning added service communication, API boundary controls, admin CLI, local dev orchestration, simulator load testing, throughput baselines, abuse protection, data lifecycle, and control-room UI planning. These are individually useful, but together they can pull attention away from the first complete lifecycle story.

## Implementation Snapshot

Current implementation has moved beyond the original "shell" state.

Implemented or materially started:

- Kotlin platform runtime with health, order submit/cancel/modify, reference data endpoints, order/trade/event/trace query endpoints, `/api/v1` boundary routes, idempotency, rate-limit/auth hooks, abuse protection, command capture, and admin CLI entry points.
- Go matching engine with hidden-book matching behavior, partial-fill/multi-match behavior, cancel/modify paths, HTTP transport, and gRPC scaffold.
- Protobuf contract for submit/cancel/modify and order execution results.
- Docker-first local dev workflows through `make dev-up`, `make dev-reset`, `make dev-smoke`, stress, replay, and throughput campaign scripts.
- Simulator/load tester with strict lifecycle, capacity baseline, persona/session configuration, deterministic replay baseline, trace checks, report artifacts, and throughput telemetry.
- Split-ready migration folders and initial domain schema direction for `runtime`, `boundary`, `auth`, `admin`, `orchestration`, and `analytics`.

Material gaps:

- The runtime still has transitional root-level table initialization in service code while migration docs and schema blueprints target domain schemas such as `runtime.*`, `boundary.*`, and `auth.*`.
- Event log persistence exists for queryability, but the outbox-backed event distribution path is only partially represented by migrations/specs and is not yet the runtime write path.
- Cancel/modify operations emit lifecycle events but do not yet keep a complete persisted order-state projection equivalent to the engine state.
- The platform UI is still a README-level placeholder; the currently planned UI is a simulator/control-room surface before full order/post-trade operator workflows.
- Post-trade lifecycle modules remain planned rather than implemented.
- Some docs still describe "early Phase 1" or "placeholder" behavior and understate current capabilities.

## Critique

### 1. Vision is aligned, but the near-term product center needs sharper wording.

The original vision emphasizes a full venue-to-settlement lifecycle. The latest work has correctly invested in repeatability, simulator tooling, and performance visibility, but the plan should say plainly that the near-term product center is now:

- a reliable local simulation and stress environment
- a complete API-inspectable venue slice
- a control room that makes runs reproducible and comparable

This does not weaken the post-trade vision. It creates the operator and replay foundation that post-trade workflows need.

### 2. The plan has too many "next" tracks.

`WORK_PLAN.md`, sprint docs, data lifecycle specs, throughput trackers, and simulator-control planning all identify important next work. Without a single current execution checkpoint, agents can choose different priorities and all be able to cite a doc.

The plan needs a current execution ladder:

1. Align persistence implementation with domain schema/migration direction.
2. Deliver simulator control-room MVP around existing CLI artifacts.
3. Close the remaining venue projection gaps.
4. Build the first two deterministic lifecycle scenarios.
5. Add post-trade modules after the timeline and replay path is stable.

### 3. The storage strategy is directionally correct but execution is split.

The accepted architecture says Postgres is canonical, domain schemas should be split-ready, and procedure-first write paths should protect critical mutations. Current code still mixes:

- service-side table bootstrap
- root-level tables
- migration-defined schema tables
- procedure-first specs that are not yet uniformly called by runtime code

This should be treated as the next architectural cleanup before deeper post-match work. Otherwise post-trade tables will inherit the split and make migration harder.

### 4. Simulation is a strength and should become the integration harness.

The simulator is no longer a future add-on. It is already the strongest system-level verification tool in the repo. The control-room sprint is justified because it converts existing scripts, reports, traces, and replay checks into an inspectable workflow.

The caution: the UI must remain an orchestration and visualization layer over the same scripts/artifacts. It must not invent a second simulation semantics path.

### 5. Post-trade scope is rich but should stay scenario-locked.

The post-match plans include compare, affirmation, clearing, netting, settlement, exceptions, ledgers, adapters, calendars, roles, and overrides. That is realistic but large. The strongest constraint in the docs is the scenario lock:

- `P1_GOLDEN_HIDDEN_CROSS_T1`
- `P2_SETTLEMENT_BREAK_REPAIR`

Those should remain the only first-wave post-trade scenarios. Additional post-trade breadth should wait for deterministic replay and timeline assertions.

## Improved Execution Plan

### Track 1: Documentation and Planning Alignment

Acceptance criteria:

- Top-level README and service READMEs describe current capabilities accurately.
- Roadmap identifies the current checkpoint and the next sprint sequence.
- Work plan separates completed work, active gaps, and next decisions.

### Track 2: Persistence and Schema Alignment

Acceptance criteria:

- Runtime persistence no longer relies on root-level table bootstrap for durable mode.
- Domain schemas match the migration blueprint: `runtime`, `boundary`, `auth`, and `admin`.
- Idempotency storage uses the documented `boundary` schema or the docs explicitly mark the current table as transitional.
- Critical write paths have a clear migration plan to schema-owned routines.

### Track 3: Simulator Control Room MVP

Acceptance criteria:

- Local control API exposes only allowlisted commands.
- UI-launched runs produce the same report artifacts and reproduction command as CLI-launched runs.
- Run records include seed/session config, command, git metadata, runtime mode, metric scope, and artifact paths.
- Compare-runs view can compare at least accepted RPS, submitted RPS, success rate, p95/p99, reject taxonomy, and trace-check pass rate.

### Track 4: Venue Projection Completion

Acceptance criteria:

- Submit/cancel/modify all update persisted order lifecycle projections.
- Runtime query APIs expose current order state, history, trades, executions, and trace timeline.
- Tests prove runtime and engine lifecycle state stay consistent for rest, partial fill, fill, cancel, modify, and reject paths.

### Track 5: First Lifecycle Scenarios

Acceptance criteria:

- `P1_GOLDEN_HIDDEN_CROSS_T1` runs deterministically and asserts ordered events.
- `P2_SETTLEMENT_BREAK_REPAIR` opens, repairs, and closes a settlement exception through real command paths.
- Scenario replay validates final state and timeline invariants by seed.

## Recommended Near-Term Priority

The next best sprint is:

1. persistence/schema alignment for runtime, boundary, auth, and admin storage
2. simulator control-room MVP over existing scripts and artifacts
3. venue projection completion for submit/cancel/modify state

Reasoning:

- It removes architectural drift before adding post-trade storage.
- It turns the existing simulator strength into a repeatable operator workflow.
- It preserves the audit/replay promise before expanding domain breadth.

Post-trade implementation should start only after these are stable enough that scenario runs can prove causation and replay instead of merely creating data.
