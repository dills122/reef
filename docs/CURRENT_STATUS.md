# Reef Current Status

## Purpose

This is the short operational snapshot for Reef. Use it to orient current work before reading deeper planning, benchmark, or sprint documents.

Last aligned: 2026-07-07.

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
- The active high-throughput venue-core path is moving toward a Kafka-compatible durable command log, explicit command partitions, matching-engine direct consumption, durable venue event batches, command offset commit after durable event-batch publication, and asynchronous Postgres materialization from those event batches.
- `202 Accepted` still means the configured durable ingress/log producer has acknowledged the command. Do not weaken that contract.
- The current command intake contract is captured in [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md): submit and cancel are the first hot-path scope, modify is deferred, hot cancel requires routing metadata, and accepted-but-not-completed means durable pending work, never a possible drop.
- Order-entry APIs, market-data/history APIs, account/bot ledgers, settlement, and analytics are separate planes. See [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md).
- Product-facing surfaces are limited to venue intake/trading information and admin/data. Raw `/internal/*` HTTP routes are local/migration tooling only and must not be exposed as public, partner, bot, SDK, or stable operator contracts. See [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md) and D-048.
- Current boundary hardening checkpoint: hosted public admin/data uses `/admin/v1/...` gateway routes, raw `/internal/*` HTTP is controlled by `PLATFORM_INTERNAL_HTTP_MODE`, non-local boundary defaults fail closed unless auth/rate-limit/durable-idempotency/internal-exposure modes are explicit, admin HTTP actor identity is principal/header-bound rather than body/query-bound, command/order/market-data reads pass through read boundary checks, and `/healthz` plus `/readyz` are distinct runtime surfaces.

Current decision anchors:

- D-036 and D-037 define durable stream-backed acceptance and completion semantics.
- D-040 supersedes generic unary worker-to-engine calls for the hot matching path.
- D-041 makes Kafka-compatible durable producer plus matching-engine direct consumer the active hot-ingress target, with JetStream retained as fallback/comparison.
- D-043 makes venue event batch materialization the next persistence boundary: event batches are the durable matching handoff, and Postgres materializer offsets commit only after compact canonical rows commit.
- D-047 defines the current command intake process: public `202` responses expose stable command references, provider details stay diagnostic, idempotency scopes by `clientId + route + idempotencyKey`, and canonical materialization closes accepted-command accounting after drain.
- D-048 defines the internal interface and external surface hardline: internal service/control capabilities default to gRPC/protobuf or durable messaging, while externally reachable admin/data capabilities must be gateway-backed, authenticated, authorized, audited, and versioned.
- D-049 tracks the active API/control-plane hardening backlog: finish remaining account/object authorization, migrate remaining hosted/CI/operator callers from [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md) off raw `/internal/*`, add internal gRPC service identity, expand readiness, and make stream lane identity deterministic by run/session/instrument when command models support it.
- Local 2026-07-04 evidence shows the venue event batch materializer can keep compact canonical Postgres storage correct under mixed submit/modify/cancel direct-stream load at `5k rps` and `10k rps` for `3m`; see [`PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md`](./PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md).
- The first persistence-layer test gate after materialization projects `SubmitOrder`, `ModifyOrder`, and `CancelOrder` lifecycle outcomes from `runtime.canonical_command_outcomes` into `submit_results`, `runtime_events`, and accepted submit `orders`. No-DB direct-consume batches carry the compact `acceptedOrder` projection fact; `command_log.command_payloads` is a fallback for older or command-log-backed batches.
- Local replay/check tooling now verifies stored venue event batch payload replay is idempotent and compares command counts, payload checksums, command outcome payload hashes, stream gaps/overlaps, and optional projection watermarks with `make dev-venue-event-replay-check`.
- `/api/v1/commands/{commandId}` now exposes the provider-neutral public status vocabulary from `COMMAND_INTAKE_PROCESS.md`: stream/captured pending commands report `ACCEPTED`, worker-draining commands report `IN_FLIGHT`, canonical accepted outcomes report `COMPLETED`, canonical business rejects report `REJECTED`, and failure outcomes report `FAILED`. The legacy queue state remains available as diagnostic `internalStatus`.
- Account/bot risk pre-checks now have a boundary contract and allow-all/static implementation that can reject, backpressure, or disable bots before command-log append, stream intake reservation, or durable publish.
- Market-data reads have a first conservative projection-backed slice: `runtime.order_lifecycle_state` tracks open/partially-filled/filled/cancelled/rejected order state, `runtime.market_data_snapshots` reads remaining open `LIMIT` quantity for `/api/v1/market-data/snapshots/{instrumentId}`, and `/api/v1/market-data/depth/{instrumentId}` exposes bounded lifecycle-backed depth. The opt-in `MARKET_DATA_PROJECTOR_ENABLED=true` loop keeps top-of-book snapshots current and reports status at `/internal/market-data/projector/status`. Maintenance is incremental, mirroring the order-lifecycle projector: it first advances `runtime.order_lifecycle_state` from `runtime.order_lifecycle_dirty`, which marks each touched order's instrument in `runtime.market_data_snapshot_dirty`, then `runtime.runtime_project_market_data_snapshots(...)` recomputes top-of-book for only those instruments, bounded by `MARKET_DATA_PROJECTOR_BATCH_SIZE`; instruments whose last open order leaves the book get their snapshot row removed instead of left stale.
- `/api/v1/market-data/trades/{instrumentId}` exposes a public trade tape (price, quantity, currency, occurredAt, tradeId, and a monotonic `sequence` cursor), most-recent-first, bounded by `limit` (max 500) with `before=<sequence>` pagination. Deliberately excludes counterparty/order/participant identity, matching `docs/BOT_ARENA_PLAN.md`'s visible-data policy ("public top-of-book, limited depth, or last-trade data"). Reads `runtime.trades` directly — no projector, no lag, since trade facts are already durable and idempotently written by the existing submit-outcome persist functions. Trade tape, intraday bars, and own-order reads now include a small `meta` block declaring their source and freshness model.
- `runtime.order_lifecycle_state` is kept live by its own opt-in `ORDER_LIFECYCLE_PROJECTOR_ENABLED=true` background loop (status at `/internal/order-lifecycle/projector/status`), rather than only through the manual/admin rebuild endpoint. Maintenance is incremental: every write that touches `orders`/`executions`/`trades`/`runtime_events` marks affected order_ids in `runtime.order_lifecycle_dirty`, and each cycle recomputes only those, bounded by `ORDER_LIFECYCLE_PROJECTOR_BATCH_SIZE`. The old full-table rebuild stays available as a manual/admin repair tool. Genuine engine-level `SubmitOrder` rejects (not boundary rejects like `AUTHORIZATION_ERROR`/`REFERENCE_DATA_ERROR`) now get an `orders` row and a `REJECTED` lifecycle status instead of being visible only through `submit_results`.
- `/api/v1/market-data/bars/{instrumentId}` exposes intraday OHLCV bars (`interval` one of `1m`/`5m`/`15m`/`1h`, `start`/`end` bounds) aggregated from `runtime.trades` via Postgres `date_bin`, matching the Bot SDK's `BotHistoricalBarsRequestV1`/`HistoricalBarV1` contract exactly. `/api/v1/orders/current` and `/api/v1/orders/history` expose participant-scoped own-order reads (joining `runtime.orders` and `runtime.order_lifecycle_state`), closing the previous "no scoped orders query" gap — the only prior order read was an unscoped all-participants dump. Own-order reads now accept optional `instrumentId` and `limit` query parameters so bot/user history reads can stay bounded. `/api/v1/data/availability` now inventories these bot/user data surfaces with endpoint, source, freshness model, visibility scope, required/optional filters, and projection lag/watermark where available, so tests can state whether they prove durable facts, projection freshness, or only command completion.
- `packages/bot-sdk/src/live-client.ts` adds live-read wiring in the Bot SDK: `createLiveMarketDataClientV1`, `createLiveHistoricalDataClientV1`, `createLiveOwnOrdersReadClientV1`, and `createLiveBotContextV1` call the endpoints above over real HTTP. `runner.ts`, `strategy-runner.ts`, and `hosted-runner.ts` now accept opt-in `readClients`, so fixture mode remains the default while live smoke and hosted artifact runs can read market data, bars, and own orders from platform projections. Hosted reports include `readMode` and, in live read mode, `dataAvailability`. Prices are stored venue-wide as fixed-point nanos (`contracts/proto/order_execution.proto`'s `Price.nanos`, `price_nanos = price_dollars * 1e9`); live reads divide by that scale before returning `number` fields to bot code, and the venue adapter converts bot `limitPrice` values back to nanos before submitting commands.

## Current Forward Path

Work should follow this order unless a new decision supersedes it. The active checkpoint is durable direct path hardening: prove the path from API durable publish ack through matching-engine direct consumption, durable venue event batch, canonical materialization, and projection/replay verification before longer remote soaks.

1. Keep the current durable-acceptance contracts stable while promoting the D-041 Redpanda/Kafka-compatible hot-ingress path from local proof to longer remote evidence.
2. Complete the API/control-plane hardening backlog in [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md#api-and-control-plane-hardening-backlog): remaining account/object authorization, `/internal/*` caller migration from [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md), internal gRPC service identity, richer readiness, deterministic stream lane keys, and fail-closed non-local profiles.
3. Implement the command intake contract in [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md): submit/cancel first, hot cancel metadata enforcement, stable `202` response, provider-neutral status, duplicate idempotency tests, and accepted/materialized drain accounting.
4. Preserve the proven direct engine ingestion shape: command log/topic -> engine shard -> durable venue event batch -> command offset commit.
5. Harden canonical persistence through venue event batch materialization with crash/restart tests, deterministic command ordering, compact canonical facts, idempotent replay, and checksum evidence.
6. Prove compact persistence projection end to end under the same gate: durable event batch, canonical Postgres rows, projected submit result/runtime event, and idempotent projector replay.
7. Order-lifecycle-state and market-data top-of-book snapshot maintenance are both now incremental (dirty-tracked, not full-table rebuild). Public trade tape and intraday bars are now live (`/api/v1/market-data/trades/{instrumentId}`, `/api/v1/market-data/bars/{instrumentId}`), and the Bot SDK live-read clients can be injected into `runner.ts`/`strategy-runner.ts`/`hosted-runner.ts` through `readClients`, plus participant-scoped own-order reads (`/api/v1/orders/current`, `/api/v1/orders/history`). Depth reads (`/api/v1/market-data/depth/{instrumentId}`) still aggregate remaining open lifecycle quantity at request time rather than from a maintained projection; venue-session-specific depth needs a projected session key on order lifecycle facts before it can be truthfully exposed.
8. Lock the first deterministic lifecycle scenarios against [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md) and [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md): `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`.
9. Expand post-trade modules only after timeline and replay assertions prove causation end to end.

## Documentation Map

Use these docs for active work:

- Project framing: [`REEF_PROJECT_OVERVIEW.md`](../REEF_PROJECT_OVERVIEW.md)
- Technical design: [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
- System overview: [`SYSTEM_OVERVIEW.md`](./SYSTEM_OVERVIEW.md)
- Current and target architecture diagrams: [`ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md`](./ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md)
- Infrastructure backbone server: [`SYSTEM_INFRASTRUCTURE_BACKBONE.md`](./SYSTEM_INFRASTRUCTURE_BACKBONE.md)
- Venue runtime backbone services: [`SYSTEM_BACKBONE_SERVICES.md`](./SYSTEM_BACKBONE_SERVICES.md)
- Simulator environment shape: [`SYSTEM_SIMULATOR_ENVIRONMENT.md`](./SYSTEM_SIMULATOR_ENVIRONMENT.md)
- Combined backbone/simulator topology: [`SYSTEM_BACKBONE_SIMULATOR_TOPOLOGY.md`](./SYSTEM_BACKBONE_SIMULATOR_TOPOLOGY.md)
- Trading, market-data, account, settlement, and analytics boundaries: [`TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md)
- API surface policy: [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md)
- Scenario contracts: [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md)
- Scenario live assertion plan: [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md)
- Minimal settlement exception facts: [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md)
- Command intake process: [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- Current plan: [`WORK_PLAN.md`](./WORK_PLAN.md)
- Roadmap: [`ROADMAP.md`](./archive/ROADMAP.md)
- Decisions: [`DECISIONS.md`](./DECISIONS.md)
- Performance evidence and next implications: [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)
- Persistence materializer evidence: [`PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md`](./PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md)
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
