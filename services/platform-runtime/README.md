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
- `POST /orders/submit`
- `POST /api/v1/orders/submit` (requires `X-Client-Id` + `Idempotency-Key`)
- `POST /api/v1/orders/cancel` (requires `X-Client-Id` + `Idempotency-Key`)
- `POST /api/v1/orders/modify` (requires `X-Client-Id` + `Idempotency-Key`)
- placeholder proxy path to the Go matching engine
- unit-testable API and application layers
- runtime-to-engine transport selection (`ENGINE_TRANSPORT=http|grpc`)

Run locally when Gradle is available:

```bash
cd services/platform-runtime
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew run
```

Transport config:

- `ENGINE_TRANSPORT=http` (default)
- `ENGINE_TRANSPORT=grpc` (real gRPC client path to matching-engine)
- `MATCHING_ENGINE_BASE_URL` for HTTP transport (default `http://localhost:8081`)
- `MATCHING_ENGINE_GRPC_TARGET` for gRPC transport target (default `localhost:9081`)

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
- `EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY=true|false` (default `false`)
- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory|postgres` (default `inmemory`)

Operational stats endpoint:
- `GET /internal/boundary/abuse/stats` (returns breaker mode/config and counters: `trips`, `blocks`, `releases`, `activeBlockedClients`)

When `EXTERNAL_API_IDEMPOTENCY_STORE=postgres`, the runtime reuses:

- `RUNTIME_DB_URL`
- `RUNTIME_DB_USER`
- `RUNTIME_DB_PASSWORD`

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
