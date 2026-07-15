# Reef Work Plan

## Purpose

This is the active execution plan. It deliberately stays short and points to detailed evidence instead of repeating every sprint plan and benchmark note.

For the current snapshot, read [`CURRENT_STATUS.md`](./CURRENT_STATUS.md) first.

## Source Of Truth

Active execution planning starts from:

- this file
- [`CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md)
- [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md)
- [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md)
- [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md)
- [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md)
- [`BOT_ARENA_AUTH_AND_PROVISIONING.md`](./BOT_ARENA_AUTH_AND_PROVISIONING.md)
- [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md)
- [`DIGITALOCEAN_STRESS_TEST_PLAN.md`](./DIGITALOCEAN_STRESS_TEST_PLAN.md)
- [`HOT_BOOK_SHARDING_PLAN.md`](./HOT_BOOK_SHARDING_PLAN.md)

Topic-specific architecture and steering docs remain active inputs when linked from this set. Older sprint, benchmark, and research documents remain evidence or design context unless one of the active docs explicitly reactivates them.

## Planning Posture

Reef remains a simulation-first institutional trading venue and post-trade platform. The near-term work is not broad feature expansion. It is proving a durable, replayable, high-throughput venue core while preserving the API, audit, and simulation contracts that later post-trade modules need.

Current deployment assumptions:

- one Kotlin platform runtime
- one Go matching engine, with direct stream-consumer work in progress
- Postgres as canonical state for durable runtime modes
- JetStream and Redpanda/Kafka-compatible providers available for durable command-log experiments
- simulator/load tools driving the same public command paths as manual users
- post-trade modules added only after command/event causation is stable enough to inspect
- command intake work follows [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md) for the current submit/cancel hot-path contract, accepted-but-not-completed semantics, and readiness gates
- API/control-plane hardening follows [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md#api-and-control-plane-hardening-backlog): raw `/internal/*` remains local/migration only, external admin/data must use versioned gateway contracts, and non-local runtime profiles must fail closed on unsafe boundary defaults
- raw internal HTTP caller status is inventoried in [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md)
- Bot Arena Phase 1 local evidence now has two named gates: `make dev-smoke-bot-arena-local-persist` for the positive live `/api/v1` path with persisted run results, and `make dev-smoke-bot-arena-local-negative` for deterministic freeze/disqualification evidence. Both persist via loopback-only admin calls from inside `platform-api`, keeping raw `/internal/*` off the host/public surface. Static operator evidence is generated with `make dev-render-bot-arena-report` and compared across run artifacts with `make dev-render-bot-arena-report-index`.

## Current Execution Checkpoint

Active checkpoint: projection/read-model promotion on top of the proven durable direct materializer path.

Target path:

```text
API publish ack
  -> matching-engine direct consume
  -> durable VenueEventBatch
  -> command offset commit
  -> materializer canonical Postgres commit
  -> event-batch offset commit
  -> projection/replay verification
```

The direct materializer portion of this checkpoint now has local crash/replay proof and DigitalOcean `10k` `soak-5m` promotion evidence. The checkpoint is not fully done until Reef can also prove, with named local gates and then remote evidence, that projected read models remain fresh or drain predictably under the same durable venue-event load.

### Active Now

1. Keep the API/control-plane hardening backlog moving alongside durable-path work.
   - finish remaining account/client/object-id authorization after command, participant order, and market-data reads now pass boundary checks
   - migrate hosted/CI/operator callers listed in [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md) off raw `/internal/*` and onto `/admin/v1/...`, gRPC, or CLI adapters
   - add service identity for internal gRPC beyond single-host plaintext/private-network posture
   - expand `/readyz` from lightweight runtime status to enabled-dependency readiness
   - make submit stream lanes use `runId + venueSessionId + instrumentId` once command models carry those fields
   - keep non-local boot fail-closed for auth, rate limit, durable idempotency, and internal HTTP exposure mode

2. Lock profile validation before any throughput claim.
   - no-op publisher, stream-direct no-DB, JetStream stream-ack, Redpanda/Kafka-compatible stream-ack, and materializer profiles must reject unsafe settings before runs.
   - bounded in-memory intake retention is required for no-DB ceiling profiles.

3. Maintain and extend named crash/restart tests for the direct durable path.
   - API publishes then exits before boundary published-marker update.
   - matching engine publishes `VenueEventBatch` then exits before command offset commit.
   - matching engine fails before event-batch publish, leaving command offset uncommitted.
   - materializer commits compact canonical rows then exits before event-batch offset commit.
   - projector exits mid-batch and replays idempotently.
   - local gate target: `make dev-smoke-venue-event-crash-gate` starts the Redpanda direct-stream materializer profile, selects commands that cover all four direct-stream partitions, stops/restarts engine/materializer/projector roles around accepted commands, injects one engine command-ack failure after durable event-batch publish, injects one materializer ack/offset failure after canonical commit, injects one projector failure after read-model rows commit but before watermark commit, waits for canonical/projection drain, then runs `scripts/dev/venue-event-replay-check.mjs`.
   - 2026-07-08 local evidence: this gate passed with 12 accepted commands across partitions 0, 1, 2, and 3, final engine ack lag 0, final materializer lag 0, replay/checksum mismatches 0, duplicate replay inserts 0; see [`DURABLE_DIRECT_CRASH_GATE_RESULTS_2026-07-08.md`](./DURABLE_DIRECT_CRASH_GATE_RESULTS_2026-07-08.md).
   - API publish-marker recovery is covered by HTTP-path tests that retry after publish ack before marker update, republish, converge the marker, and stop republishing once the marker is durable.
   - remaining promotion gap: projection/read-model freshness under the same durable event-batch load.

4. Run short local durable gates before any long soak.
   - durable publish acknowledgement succeeds before `202`.
   - direct engine consumer fetches/processes/publishes/acks all accepted commands.
   - direct engine coverage spans every configured partition in the crash gate.
   - engine command ack/offset failure after event-batch publish redelivers and replays with no duplicate canonical inserts.
   - materializer writes canonical outcomes for all accepted commands after drain.
   - materializer ack/offset failure after canonical commit redelivers and replays with no duplicate canonical inserts.
   - projection watermarks do not advance over missing encoded stream sequences when canonical rows arrive out of order.
   - projector failure after read-model rows but before watermark commit replays idempotently with no duplicate read-model rows.
   - replay/checksum reports `0` gaps, duplicate inserts, payload mismatches, stream gaps, or overlaps.
   - projection replay/idempotency applies no duplicate read-model rows.
   - bot/user read-surface claims match `/api/v1/data/availability` and the read-surface inventory in [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md).

5. Promote only clean local gates to the DigitalOcean/OpenTofu harness.
   - the bridge harness exists under `infra/do-benchmark/` with `scripts/dev/do-benchmark-host.sh`, `make do-benchmark`, and local report checks; the remaining requirement is clean promotion evidence, not initial scaffold creation.
   - for the generic stream-worker path, first remote tier remains `2000` completed/sec for at least `5m`, followed by `5000`, then `7500`.
   - for the active Redpanda direct-stream plus venue-event-materializer path, use `make do-materializer-10k-gate`: short tier is `10k / 60s / 3 samples`, then `10k / 5m / 2 samples`, then `10k / 15m / 1 sample`.
   - the materializer 10k gate requires p95 <= `100ms`, p99 <= `200ms`, attempted and accepted rps >= `9900`, all `16` direct-stream partitions active, partition skew <= `4`, DB WAL/activity/settings/`pg_stat_io` diagnostics, and accepted/direct-acked/materialized gaps equal to `0`.
   - reports must include attempted, accepted, direct-acked, materialized, projected, lag, p95/p99, restart counts, and artifact paths.
   - 2026-07-12 promotion evidence: `make do-materializer-10k-gate` `soak-5m` passed on DO c-16 with run `do-benchmark-20260712T143401Z`: two `5m` samples at `10k rps` and `1024` workers, `100%` success, average accepted/materialized throughput `9999.68/sec`, average p95 `28.25ms`, average p99 `57.21ms`, zero accepted/direct-acked/materialized gaps, and zero materializer lag. Local artifacts live under `reports/do-benchmark/do-benchmark-20260712T143401Z/`.
   - This evidence intentionally has `projected=0`; do not use it as a read-model freshness or UI/control-room capacity claim.

### Implementation Slices

Plan the failure matrix as subsystem slices, not as disconnected one-off tests:

1. API intake and publish marker.
   - idempotency replay/conflict
   - publish timeout, broker unavailable, producer saturation
   - API exit after publish ack before boundary marker update
   - hot cancel metadata rejection

2. Matching-engine direct consumer.
   - engine exit before `VenueEventBatch` publish
   - engine exit after `VenueEventBatch` publish before command offset commit
   - event-batch publisher failure
   - poison command terminal `FAILED` outcome

3. Venue event materializer.
   - event-batch redelivery after Postgres commit
   - idempotent canonical inserts
   - accepted/materialized accounting closure
   - provider-neutral status authority from canonical outcomes

4. Projection and replay.
   - projector restart mid-batch
   - projection watermark replay
   - no duplicate read-model rows
   - `make dev-venue-event-replay-check` as promotion gate

5. Promotion harness.
   - profile validation
   - short local durable gate
   - pre-DO admission gate
   - artifact fetch and report validation before destroy

### Explicit Non-Goals

- no post-trade expansion until command/event causation is proven end to end
- no UI/control-room freshness as a blocker for venue-core command intake
- no more API intake tuning unless durable drain/accounting proof is already clean
- no treating no-op publisher, raw accepted/sec, or accepted-but-unmaterialized counts as release evidence
- no exposing raw `/internal/*` HTTP routes as product, bot, partner, public, or stable operator APIs
- no snapshot/recovery claim until snapshot format, routing epoch metadata, and replay checksum inputs are specified
- no live Bot Arena control-room/dashboard scope in this slice; keep that separate from the real-time stress monitoring dashboard workstream

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
- Bot Arena local positive/negative persisted gates and static operator report artifacts

## Active Gaps

The current gaps are:

- durable hot-ingress plus canonical materialization now has a clean `10k` `soak-5m` baseline; the next throughput gap is projection/read-model freshness under the same durable venue-event load
- generic stream workers calling the engine per command are transitional, not the target hot matching architecture
- direct matching-engine command consumption exists and compact persistence from durable venue event batches has local proof; local API publish-marker recovery and engine/materializer/projector crash gates passed, and the direct materializer `10k` `soak-5m` DO gate passed on 2026-07-12
- the submit/cancel intake contract needs implementation-ready proof around duplicate idempotency, accepted-but-not-completed accounting, and event-batch intermediate status authority; hot-path cancel routing metadata is now covered by stream envelope and HTTP boundary tests, and `/api/v1/commands/{commandId}` exposes the provider-neutral public status vocabulary for stream references, in-flight work, canonical completions, rejects, and failures
- API/control-plane hardening still needs the follow-up backlog in [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md#api-and-control-plane-hardening-backlog), especially account/object authorization, migration of remaining raw `/internal/*` callers from [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md), internal gRPC service identity, deeper `/readyz`, and deterministic stream lane keys
- first deterministic lifecycle scenarios have promoted local live assertion reports, but are not locked end to end; P1 now has scoped direct-stream replay evidence and native hidden-depth timeline support in the assertion harness, but still needs a promoted same-run report combining command status, replay counters, timeline proof, and projection-lag evidence; P2 still needs replay/checksum evidence and a live settled instant-post-trade chain proof when those facts are present
- post-trade workflows remain scenario-locked future work
- Bot Arena score-v1 correctness gate is locked locally and on DigitalOcean: local reset-to-reset `5m` proof passed and hosted run `do-benchmark-20260714T010045Z` passed the `15m` arena artifact gate. The active follow-up is hosted arena pacing lag cleanup, not score-v1 correctness.

## Active Execution Ladder

1. Validate D-041 hot ingress.
   - Maintain the Redpanda/Kafka-compatible durable producer plus matching-engine direct consumer as the venue-core baseline now that the direct materializer `10k` `soak-5m` gate has passed.
   - Require durable ack-before-`202`, bounded queue/in-flight depth, clean accepted/acked accounting, and replay/audit metadata.
   - Kafka-compatible publish retries may absorb retriable broker metadata/leader callback failures only inside the existing publish-ack timeout; they must not weaken `202` semantics or hide non-retriable failures.
   - Use [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md) as the implementation contract: `SubmitOrder` and `CancelOrder` first, `ModifyOrder` deferred, hot cancel without routing metadata rejected, and provider metadata kept diagnostic rather than public-contract required.
   - Keep JetStream as fallback and comparison until evidence says otherwise.

2. Complete API/control-plane hardening.
   - Treat [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md#api-and-control-plane-hardening-backlog) as the active checklist.
   - Keep `/api/v1` and `/admin/v1` as the only externally reachable HTTP product families.
   - Keep raw `/internal/*` for loopback/local migration only; every hosted caller must use a gateway, gRPC, or CLI adapter.
   - Use [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md) as the migration source of truth.
   - Require object authorization tests for each object-id read endpoint before calling that read surface public-ready.
   - Move from single-host plaintext gRPC to TLS/mTLS or service-mesh identity before multi-host non-local deployment.

3. Implement venue event batch materialization.
   - Start from the matching engine's durable `VenueEventBatch` output, not runtime workers calling the engine.
   - Commit command offsets after durable venue event-batch publication.
   - Commit materializer offsets only after compact canonical Postgres batch rows commit.
   - Compact canonical batch storage and command/outcome lookup rows now exist for status, idempotent replay, audit, and projection inputs.
   - Kafka-compatible venue event batch materializer runtime role, diagnostics, and local smoke target now exist.
   - Event-batch replay/checksum tests now gate the materializer contract before throughput claims.
   - `/api/v1/commands/{commandId}` now prefers materialized canonical command outcomes and falls back to existing status surfaces while materialization catches up.

4. Complete venue lifecycle projection.
   - This is the next promotion track after the 2026-07-12 materializer `soak-5m` pass: enable the appropriate projector/read surfaces during a durable venue-event load run, record projected throughput, projection lag/watermarks, replay idempotency, and read API freshness separately from canonical accepted/materialized throughput.
   - Remote gate command: `make do-projection-freshness-gate ARGS=plan|run-destroy`. It runs the Redpanda direct-stream plus venue-event-materializer path on DigitalOcean with venue-event-batch projection, order-lifecycle projection, and market-data projection enabled, then fails if projected work does not catch up to durable canonical materialization with zero materialized/projected gap, zero projector lag, and zero projection DB deadlocks after the configured drain window.
   - 2026-07-12 short projection evidence: after splitting four projector instances across active direct-stream partitions `0-15`, DO run `do-benchmark-20260712T172412Z` passed at `2.5k rps` on c-16 with `149,976` accepted/materialized/projected commands, projected rps `2499.56`, p95 `35.98ms`, p99 `82.06ms`, projection lag `0`, materialized/projected gap `0`, and projection-postgres deadlocks `0`.
   - Compact submit outcome projection from materialized `runtime.canonical_command_outcomes` into `submit_results` and `runtime_events` now exists as the first persistence test gate.
   - The first persistence-layer live smoke now runs the durable event-batch path end to end: durable event batch -> canonical batch/outcome rows -> projected submit result/runtime event/order/lifecycle/market-data rows -> public command status, own-order, top-of-book, depth, and data-availability reads. Its replay check rewinds the projector watermark and requires the stable DB/API snapshot to remain unchanged after replay, so duplicate read-model rows or freshness drift fail locally before a remote projection gate is promoted.
   - No-DB direct-consume `VenueEventBatch` submit outcomes now carry the compact `acceptedOrder` fact needed to reconstruct `orders` rows; the durable `command_log.command_payloads` join remains a compatibility fallback.
   - Submit/cancel/modify/fill/reject state is queryable through `runtime.order_lifecycle_state` (derived from `orders`, `executions`, and `runtime_events`), kept live by the opt-in `ORDER_LIFECYCLE_PROJECTOR_ENABLED=true` background loop (status at `/internal/order-lifecycle/projector/status`) instead of manual/admin-triggered rebuild only.
   - Genuine engine-level `SubmitOrder` rejects (not boundary rejects like `AUTHORIZATION_ERROR`/`REFERENCE_DATA_ERROR`) now get an `orders` row and a `REJECTED` `order_lifecycle_state` status instead of being visible only through `submit_results`.
   - The background loop maintains `runtime.order_lifecycle_state` incrementally: every write path that touches `orders`/`executions`/`trades`/`runtime_events` marks affected order_ids in `runtime.order_lifecycle_dirty`, and `runtime.runtime_project_order_lifecycle_state(batchSize)` only recomputes those, bounded by `ORDER_LIFECYCLE_PROJECTOR_BATCH_SIZE`. Cost scales with recent activity, not total historical order count. The old full-table rebuild (`rebuildOrderLifecycleState`) is kept as a manual/admin repair tool.
   - `runtime.market_data_snapshots` top-of-book maintenance is now incremental too: each `order_lifecycle_state` recompute marks its instrument in `runtime.market_data_snapshot_dirty`, and `runtime.runtime_project_market_data_snapshots(batchSize)` recomputes top-of-book only for those instruments, bounded by `MARKET_DATA_PROJECTOR_BATCH_SIZE`, removing an instrument's snapshot row entirely once its book empties instead of leaving it stale.
   - Public trade tape (`/api/v1/market-data/trades/{instrumentId}`) and intraday OHLCV bars (`/api/v1/market-data/bars/{instrumentId}`, matching the Bot SDK's `BotHistoricalBarsRequestV1`/`HistoricalBarV1` contract) now exist, both reading `runtime.trades` directly (no projector). Participant-scoped own-order reads (`/api/v1/orders/current`, `/api/v1/orders/history`) close the prior gap where the only order read was an unscoped all-participants dump; both support optional `instrumentId` and bounded `limit` filters to keep bot history reads small.
   - `GET /api/v1/data/availability` now inventories bot/user read surfaces with source, freshness model, visibility scope, required/optional filters, projection lag, and watermarks, so simulator reports can distinguish durable fact reads from projection-freshness claims.
   - `packages/bot-sdk/src/live-client.ts` wires `marketData`/`historical`/`orders.current()`/`history()` plus data availability to real HTTP instead of fixtures; `runner.ts`, `strategy-runner.ts`, and `hosted-runner.ts` accept those clients through opt-in `readClients` while fixture mode remains the default. The hosted artifact CLI exposes this as `--read-mode=fixture|live`; live mode records `dataAvailability` in the hosted report. The venue adapter converts bot dollar-denominated `limitPrice` values to fixed-point nanos before submit/modify commands.
   - Runtime state, engine state, events, and traces should agree under deterministic tests.

5. Clean up hosted arena pacing lag.
   - Current score-v1 correctness evidence is sufficient for promotion: local reset-to-reset proof and hosted `15m` run `do-benchmark-20260714T010045Z` both passed with zero command accounting gaps, timeouts, rejects, failed ticks, or freezes; hosted projection lag was `0` and public score-v1 mismatch count was `0`.
   - Problem statement: the hosted `900s` arena schedule took `1017s` on a `c-8` source-built DigitalOcean worker, with final completion lag near `107s` and total scheduler sleep `0`.
   - Acceptance criteria: rerun the hosted arena profile with hardening `pass`, stable score-v1 leaderboard and execution summary, projection lag `0`, zero command accounting gaps/timeouts/rejects/freezes, and `finalCompletionLagMs < 30000`.
   - Test one knob at a time: Docker Hub image mode for arena, c-16 worker, projection-drain cadence, command-status/projection-drain polling intervals, and remote arena-stage profiling.
   - The hosted arena harness now exposes `REEF_BENCHMARK_ARENA_COMMAND_POLL_MS`, `REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_TIMEOUT_MS`, `REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_POLL_MS`, and `REEF_BENCHMARK_ARENA_PROJECTION_DRAIN_CADENCE`; the default cadence is `scheduled-event` so a paced event performs one bounded projection drain instead of every bot submission polling independently.
   - For this pacing-lag cleanup, a short hosted sequence is enough before returning to a full soak: `make do-arena-pacing-lag-gate ARGS=run-destroy` defaults to `5m`, `5m`, and `7.5m` arena samples, each requiring hardening `pass`, health `pass`, projection drain, and `finalCompletionLagMs < 30000`.
   - 2026-07-14 short hosted pacing evidence passed on c-8 source-built workers with scheduled-event projection drain: `do-benchmark-20260714T021445Z` (`5m`, final completion lag `4.19ms`, `2313` completed), `do-benchmark-20260714T022948Z` (`5m`, lag `277.68ms`, `2321` completed), and `do-benchmark-20260714T024455Z` (`7.5m`, lag `7.64ms`, `3491` completed). All three had hardening `pass`, health `pass`, projection lag `0`, zero timeouts, zero failed ticks, and zero command accounting gaps. This closes the pacing-lag cleanup gate; a later full `15m` rerun can be kept as soak confidence rather than the immediate correctness blocker.

6. Lock first lifecycle scenarios.
   - `P1_GOLDEN_HIDDEN_CROSS_T1`
   - `P2_SETTLEMENT_BREAK_REPAIR`
   - Scenario contracts live in [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md). Live lock criteria and report shape live in [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md). P1/P2 fixtures encode the target scenario shape, and 2026-07-14 local live reports are promoted under `reports/scenario-assertions/`.
   - Remaining lock work: promote one same-run P1 report with run-scoped command status, exact replay counters, native historical hidden-depth visibility proof, and acceptable projection-lag evidence; attach P2 replay/checksum for the exception chain; promote a P2 live settled instant-post-trade chain report when allocation/confirmation/affirmation/instruction/attempt/settled facts are present.

7. Start post-trade expansion.
   - Re-entry criteria live in [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md#post-trade-re-entry-criteria).
   - First allowed slice is P2-only settlement exception facts from [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md): obligation, cash-leg break, manual repair, resolved exception, and transition tests.
   - Full allocation, confirmation, clearing, account-ledger mutation, and UI work stay deferred until P2-only facts prove causation.

## Active Workstreams

### A. Hot Ingress And Direct Engine Consumption

Primary references:

- [`ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md`](./ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md)
- [`DECISIONS.md`](./DECISIONS.md), especially D-036 through D-041
- [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- [`STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md)
- [`ARCHITECTURE_THROUGHPUT_TRACKER.md`](./ARCHITECTURE_THROUGHPUT_TRACKER.md)
- [`DIGITALOCEAN_STRESS_TEST_PLAN.md`](./DIGITALOCEAN_STRESS_TEST_PLAN.md)

Exit criteria:

- no accepted-command accounting gaps
- clean command ack or offset commit semantics
- no unresolved redelivery, async worker failure, or hidden accepted/completed gap
- hot-path cancel rejects missing routing metadata instead of performing synchronous lookup
- status lookup is provider-neutral and distinguishes accepted, in-flight, event-published, completed, rejected, and failed states
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
- hot-path matching does not block on Postgres materialization
- venue event batches materialize into compact, batch-oriented canonical rows with measured rows/command, WAL/command, commits/command, lag, and drain behavior
- compact command-outcome projections write downstream `submit_results` and `runtime_events` idempotently from canonical event-batch materialization
- full order/execution/trade projection from event batches uses event-batch outcome facts where available, with deliberate command-payload joins only as compatibility fallback
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

- [`SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./archive/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)
- [`SIMULATOR_PERSONA_CONFIG.md`](./SIMULATOR_PERSONA_CONFIG.md)
- [`SIMULATOR_UPGRADE_BACKLOG.md`](./SIMULATOR_UPGRADE_BACKLOG.md)
- [`packages/scenario-definitions/`](../packages/scenario-definitions/)

Exit criteria:

- UI/API orchestration uses existing scripts and artifacts rather than inventing a second simulation path
- scenario runs are seedable and replayable
- control-room views expose freshness, lag, artifact paths, and reproduction commands

## Historical Planning Documents

These documents remain useful as evidence or design context, but they are no longer the active execution ladder by themselves:

- `archive/SPRINT_COMMUNICATION_API_ADMIN.md`
- `SPRINT_CRITICAL_QUALITY_HARDENING.md`
- `archive/SPRINT_POST_MATCH_ENGINES.md`
- `BOT_ARENA_STRESS_BASELINE_2026-07-01.md`
- May 2026 throughput and abuse-breaker baseline reports
- `THROUGHPUT_SCALING_WORK_PLAN.md` — P2 (stream-ack ingress), P3 (stream idempotency/partition workers), and P5.5 (engine shards/hot book) are superseded by D-041 and the July 2026 stream-ack sunset and hot-book checkpoints (see `DECISIONS.md` and `PERFORMANCE_LEARNINGS.md`); it is still cited elsewhere (`ARCHITECTURE_THROUGHPUT_PLAN.md`, `ARCHITECTURE_THROUGHPUT_TRACKER.md`, `BOT_ARENA_PLAN.md`, `DECISIONS.md`) for the `7500`/`10000` completed-commands/sec per-instance target, which remains valid
- `ARCHITECTURE_THROUGHPUT_PLAN.md`, `ARCHITECTURE_THROUGHPUT_TRACKER.md`, `STREAM_ACK_ARCHITECTURE_PLAN.md` — narrate the pre-D-041 stream-ack-centric throughput story; superseded by the D-041 Kafka-direct-consumption direction (see `DECISIONS.md`). Retained as evidence of the evolution, not as the current design.

If one of these becomes active again, update this file and [`CURRENT_STATUS.md`](./CURRENT_STATUS.md) with the reason.
