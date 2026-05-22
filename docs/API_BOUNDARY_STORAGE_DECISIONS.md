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

## Configuration Matrix

- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory|postgres`
- `EXTERNAL_API_AUTH_MODE=allow-all|static-token`
- `EXTERNAL_API_RATE_LIMIT_MODE=allow-all|fixed-window`

## Next Implementation Steps

1. Add `PostgresAuthCredentialStore` and rotate `AuthHook` to store-backed token resolution.
2. Add `RedisRateLimitStore` and wire it to `FixedWindowRateLimitHook`.
3. Add idempotency TTL policy classes (`short|standard|long`) and scheduled cleanup for Postgres records.
