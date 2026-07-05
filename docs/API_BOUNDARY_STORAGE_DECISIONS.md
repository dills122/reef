# API Boundary Storage Decisions

## Purpose

Define swappable storage backends for boundary-layer concerns and set default/target persistence choices.

## Stores and Backends

### 1. Idempotency result store

- Interface: `IdempotencyStore`
- Current backends:
  - `InMemoryIdempotencyStore`
  - `PostgresIdempotencyStore`
- Key scope: `clientId + route + idempotencyKey`

Decision:
- default local/dev: `inmemory`
- baseline durable mode: `postgres`

Rationale:
- idempotency replay correctness requires durable records across process restarts for realistic API behavior.
- Postgres keeps operational complexity low while matching current runtime persistence direction.

### 2. Auth credential validation

- Interface: `AuthHook`
- Current backends:
  - `AllowAllAuthHook`
  - `StaticTokenAuthHook` (env-configured token map)

Decision:
- default local/dev: `allow-all`
- next persistent implementation target: Postgres-backed API credential store

Rationale:
- current static-token mode is enough for deterministic local integration.
- Postgres credential storage should be added before multi-client simulation and shared environments.

### 3. Rate limiting counters

- Interfaces:
  - `RateLimitHook`
  - `RateLimitStore`
- Current backends:
  - `AllowAllRateLimitHook`
  - `FixedWindowRateLimitHook` + `InMemoryRateLimitStore`

Decision:
- default local/dev: in-memory fixed window or allow-all
- production-shaped target: Redis-backed counter store
- fallback durable option (if Redis unavailable): Postgres-backed window counters

Rationale:
- rate limiting requires high write/update throughput and short-lived counters.
- Redis is the most suitable operational shape for this workload.

### 4. Account/risk pre-checks

- Interfaces:
  - `AccountRiskCheck`
  - `AccountRiskControlStore`
- Current backends:
  - `AllowAllAccountRiskCheck`
  - `StaticAccountRiskCheck`
  - `PostgresAccountRiskCheck`
- Target backends:
  - in-memory/cached local simulation view
  - account-ledger-backed production-shaped view

Decision:
- intake should run a bounded account/risk pre-check before durable order acceptance.
- non-allow decisions are audited to `boundary.account_risk_decisions` when the Postgres-backed mode is active.
- operator/admin moderation state is stored in `boundary.account_risk_controls` and managed through `make dev-admin CMD="account-risk-set ..."` for local workflows.
- internal write endpoint `/internal/admin/account-risk/controls` supports local/admin set operations and emits admin audit events.
- internal read endpoints expose current controls and recent non-allow decisions at `/internal/boundary/account-risk/controls` and `/internal/boundary/account-risk/decisions/recent`.
- settlement performs final enforcement after matching facts exist.
- deep debt, exceeded risk thresholds, or settlement failures can block fulfillment and disable bots through account/admin facts.

Rationale:
- obviously unsafe orders should be rejected before consuming durable command-log and matching capacity.
- the pre-check must remain bounded and measured because it becomes part of the order-entry hot path.
- final financial correctness belongs to settlement and the account ledger, not to the matching engine.

## Configuration Matrix

- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory|postgres`
- `EXTERNAL_API_AUTH_MODE=allow-all|static-token`
- `EXTERNAL_API_RATE_LIMIT_MODE=allow-all|fixed-window`
- `EXTERNAL_API_ACCOUNT_RISK_CHECK_MODE=allow-all|static|postgres`
- `EXTERNAL_API_ACCOUNT_RISK_CACHE_TTL_MS=<milliseconds>` controls the Postgres-backed control-state cache.

## Next Implementation Steps

1. Add `PostgresAuthCredentialStore` and rotate `AuthHook` to store-backed token resolution.
2. Add `RedisRateLimitStore` and wire it to `FixedWindowRateLimitHook`.
3. Add idempotency TTL policy classes (`short|standard|long`) and scheduled cleanup for Postgres records.
4. Extend the account-risk store toward ledger-backed exposure and limit snapshots without adding projection scans to the order-entry hot path.

### 5. Command circuit breakers

- Interfaces:
  - `CommandCircuitBreakerCheck`
  - `CommandCircuitBreakerStore`
- Current backends:
  - `AllowAllCommandCircuitBreakerCheck`
  - `PostgresCommandCircuitBreakerStore`
- Current scopes:
  - `GLOBAL`
  - `VENUE_SESSION`
  - `INSTRUMENT`

Decision:
- circuit breakers are hard pre-acceptance gates for operator halts and venue control.
- tripped breakers reject before command-log append, stream-intake reservation, or durable publish.
- account/bot moderation remains in account-risk controls; breaker scopes should stay focused on venue/session/instrument control unless a later design explicitly merges them.
- internal write endpoint `/internal/admin/circuit-breakers` supports local/admin trip/reset operations and emits admin audit events.
- internal read endpoint `/internal/boundary/circuit-breakers` exposes current breaker state for operator surfaces.

Configuration:
- `EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_MODE=allow-all|postgres`
- `EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_CACHE_TTL_MS=<milliseconds>`
