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

Current limitation:

- local verification in the Codex sandbox may still need elevated execution because Gradle opens local coordination sockets
- CI is configured to run the Kotlin test suite through the checked-in wrapper

Build guidance:

- follow [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
- follow [`docs/steering/kotlin.md`](../../docs/steering/kotlin.md)
- start as a modular monolith with clear bounded contexts
