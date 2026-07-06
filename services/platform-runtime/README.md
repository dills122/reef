# Platform Runtime

This service will be the Kotlin-based central platform runtime for Reef.

Responsibilities:

- expose HTTP and WebSocket APIs
- handle platform commands
- orchestrate domain workflows
- persist canonical state
- publish domain events
- build read models for UI consumption
- integrate with the matching engine
- host simulation control initially, unless extracted later

Current state:

- Kotlin source and Gradle build scaffold
- `GET /health`
- `POST /orders/submit` (legacy/internal; requires `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true` and `X-Reef-Internal-Route: true`)
- `POST /orders/cancel` (legacy/internal; requires `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true` and `X-Reef-Internal-Route: true`)
- `POST /orders/modify` (legacy/internal; requires `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true` and `X-Reef-Internal-Route: true`)
- `POST /api/v1/orders/submit` (requires `X-Client-Id` + `Idempotency-Key`)
- `POST /api/v1/orders/cancel` (requires `X-Client-Id` + `Idempotency-Key`)
- `POST /api/v1/orders/modify` (requires `X-Client-Id` + `Idempotency-Key`)
- reference data endpoints for instruments, participants, and accounts
- query endpoints for orders, trades, events, and trace timelines
- transport path to the Go matching engine over HTTP or gRPC
- unit-testable API and application layers
- runtime-to-engine transport selection (`ENGINE_TRANSPORT=http|grpc|grpc-stream`)

Current persistence caveat:

- durable mode uses explicit domain-schema table names instead of relying on JDBC `currentSchema`
- migration-owned runtime/auth/boundary table definitions are applied automatically by local dev startup/reset
- Docker/local runtime defaults to `RUNTIME_DB_BOOTSTRAP_MODE=validate`; `compat` remains available only as a local repair fallback
- narrowing/removing service-side compatibility bootstrap code is still pending
- the intended direction is split-ready domain schemas (`runtime`, `boundary`, `auth`, `admin`) with migration-owned tables and procedure-first critical write paths

Run locally when Gradle is available:

```bash
cd services/platform-runtime
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew run
```

Transport config:

- `PLATFORM_HTTP_SERVER=jdk|netty` selects the runtime HTTP adapter (default `jdk`). The `netty` adapter is currently a measured hot-path adapter for submit/status/internal stats during no-DB ceiling tests.
- `PLATFORM_NETTY_BOSS_THREADS=1`, `PLATFORM_NETTY_WORKER_THREADS=0`, `PLATFORM_NETTY_APPLICATION_THREADS`, and `PLATFORM_NETTY_APPLICATION_MAX_PENDING_TASKS=2048` tune the Netty adapter. `0` worker threads uses Netty's default event-loop sizing; blank application threads use a CPU-aware runtime default.
- `ENGINE_TRANSPORT=grpc` (Docker dev default; real gRPC client path to matching-engine)
- `ENGINE_TRANSPORT=grpc-stream` (experimental submit-order lane transport using persistent bidirectional gRPC streams; cancel/modify fall back to unary gRPC)
- `ENGINE_TRANSPORT=http` (legacy HTTP client path, useful for A/B comparisons)
- `ENGINE_GRPC_STREAM_LANES=16` and `ENGINE_GRPC_STREAM_QUEUE_CAPACITY=100000` tune the experimental stream transport lanes and per-lane in-flight capacity.
- `MATCHING_ENGINE_BASE_URL` for HTTP transport (default `http://localhost:8081`)
- `MATCHING_ENGINE_GRPC_TARGET` for gRPC transport target (default `localhost:9081`)
- `ENGINE_GRPC_DEADLINE_MS` for runtime-to-engine gRPC command deadlines (default `2000`)

External boundary config:

- `EXTERNAL_API_AUTH_MODE=allow-all|static-token` (default `allow-all`)
- `EXTERNAL_API_TOKENS=client-1:token-1,client-2:token-2` (used by `static-token`)
- `EXTERNAL_API_RATE_LIMIT_MODE=allow-all|fixed-window` (default `allow-all`)
- `EXTERNAL_API_RATE_LIMIT_MAX` (default `120`)
- `EXTERNAL_API_RATE_LIMIT_WINDOW_SECONDS` (default `60`)
- `EXTERNAL_API_ABUSE_BREAKER_MODE=off|reject-rate` (default `off`)
- `EXTERNAL_API_ABUSE_BREAKER_ENABLED=true|false` (default `true` when mode is `reject-rate`)
- `EXTERNAL_API_ABUSE_BREAKER_REJECT_RATE_ENABLED=true|false` (default `true`)
- `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS` (default `50`)
- `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS` (default `30`)
- `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS` (default `60`)
- `EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES` comma list (default `INVALID_STATE,NOT_FOUND,REFERENCE_DATA_ERROR,VALIDATION_ERROR`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTES` comma list (default `/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTE_POLICIES` optional comma list of `route:maxRejects/windowSeconds/blockSeconds` (example: `/api/v1/orders/modify:10/30/120`)
- `EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY=true|false` (default `false`)
- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory|postgres` (default `inmemory`)
- `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres|inmemory|disabled` (default `postgres`)
- `EXTERNAL_API_COMMAND_LOG_MODE=disabled|postgres|inmemory` (default `disabled`; `postgres` appends inbound `/api/v1` commands to `command_log.commands`)
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack|stream-ack|accepted-async` (default `sync-result`; captured modes require command-log capture, `stream-ack` requires JetStream, and `accepted-async` is an in-memory no-DB isolation mode)
- `EXTERNAL_API_ACCEPTED_ASYNC_LANES`, `EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY`, `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE`, and `EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS` tune the no-DB accepted-async submit intake. The default in-flight window is `32` per lane to avoid flooding the engine stream.
- `RUNTIME_PERSISTENCE=inmemory|postgres|noop` (`noop` is benchmark-only: keeps reference/auth setup data but drops command outcomes, orders, trades, events, canonical facts, and projections)
- `STREAM_ACK_INTAKE_STORE=postgres|inmemory` selects stream-ack idempotency/intake reservation storage. For no-DB long soaks with `inmemory`, keep `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES` positive to bound replay-window memory and use `STREAM_ACK_INMEMORY_INTAKE_SHARDS` to reduce monitor contention.
- `STREAM_ACK_PUBLISHER=stream|log|noop` selects the stream-ack publisher override. `noop` is benchmark-only and must not be used for durable acceptance claims because the response no longer proves broker append.
- `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true|false` (code default `false`; local compose default `true`; legacy mutation and reference-data POST routes also require `X-Reef-Internal-Route: true`)
- `RUNTIME_DB_BOOTSTRAP_MODE=compat|validate` (Docker/local default `validate`; use `compat` only for local repair/debug)

Command status lookup:
- `GET /api/v1/commands/{commandId}` returns command status when command-log capture is enabled.

Operational stats endpoint:
- `GET /internal/boundary/abuse/stats` (returns breaker mode/config and counters: `trips`, `blocks`, `releases`, `activeBlockedClients`)

When `EXTERNAL_API_IDEMPOTENCY_STORE=postgres`, the runtime reuses:

- `RUNTIME_DB_URL`
- `RUNTIME_DB_USER`
- `RUNTIME_DB_PASSWORD`

The Postgres boundary stores create/read `boundary.api_idempotency_records` and `boundary.api_command_captures` by default.

Current limitation:

- local verification in the Codex sandbox may still need elevated execution because Gradle opens local coordination sockets
- CI is configured to run the Kotlin test suite through the checked-in wrapper

Build guidance:

- follow [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
- follow [`docs/steering/kotlin.md`](../../docs/steering/kotlin.md)
- start as a modular monolith with clear bounded contexts

Admin CLI:

- entrypoint: `com.reef.platform.admin.AdminMainKt`
- commands include:
  - reference upserts (`instrument-upsert`, `participant-upsert`, `account-upsert`)
  - role/permission admin (`role-upsert`, `role-assign`, `roles-list`, `actor-roles`)
  - calendar and override management (`calendar-upsert`, `calendar-list`, `override-upsert`, `override-list`)
  - simulation controls (`sim-start`, `sim-pause`, `sim-stop`, `sim-state`)
  - trace inspection (`events-recent`, `trace-events`)

Order command authorization:

- submit/cancel/modify require actor role permissions (`order.submit`, `order.cancel`, `order.modify`)
- local seed scripts can upsert `order_trader` through internal `/auth/roles` and `/auth/actor-roles` endpoints
- those HTTP seed endpoints are guarded by `X-Reef-Internal-Route: true` and the same legacy mutation route flag as reference-data seed endpoints
