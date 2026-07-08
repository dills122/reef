# stock-data

Seed-time-only external stock reference service. Fetches one snapshot per
tracked symbol when a game/simulation is created, persists it, and never
calls the external provider again for that game seed. Full design:
[`docs/STOCK_DATA_SEEDING_PLAN.md`](../../docs/STOCK_DATA_SEEDING_PLAN.md).

Wiring this into a real game-creation flow is deferred - there is no
`GameTemplate`/game-seeding concept in the codebase yet. Callers use the HTTP
API below directly with an explicit `gameSeedId` and symbol list.

## Provider API key setup

1. Create a Tiingo account: https://www.tiingo.com.
2. Generate an API token from account settings.
3. Set `TIINGO_API_TOKEN` in the environment (or in `compose.local.yml`/
   `.env` alongside the other per-domain Postgres credentials).
4. Free tier quotas (checked 2026-07-08, confirm current terms before
   relying on them): 500 unique symbols/month, 50 requests/hour, 1,000
   requests/day, 1 GB/month. This service only calls Tiingo once per
   tracked symbol per game seed, so low-volume usage should stay well
   within free-tier limits. Confirm licensing terms before public/
   commercial launch - free/individual tiers are internal-use only until
   confirmed (see plan doc "Provider Recommendation").

## Configuration

| Var | Default | Purpose |
| --- | --- | --- |
| `STOCK_DATA_PROVIDER` | `tiingo` | `tiingo` or `fake` (deterministic, no network - tests/local only) |
| `TIINGO_API_TOKEN` | (empty) | required when `STOCK_DATA_PROVIDER=tiingo` |
| `STOCK_DATA_CURRENT_MAX_AGE_SECONDS` | `900` | max age of a current-data quote before it's treated as stale |
| `STOCK_DATA_PROVIDER_TIMEOUT_MS` | `2500` | per-request timeout to Tiingo |
| `STOCK_DATA_PROVIDER_MAX_RETRIES` | `2` | bounded retries with jitter on transient failures |
| `STOCK_DATA_ALLOW_STALE_CACHE` | `false` | allow `cached_fallback` snapshots when current + EOD both fail |
| `STOCK_DATA_PERSISTENCE` | `inmemory` | `inmemory` or `postgres` |
| `STOCK_DATA_POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5432/reef?currentSchema=stock_data` | only used when persistence is `postgres` |
| `STOCK_DATA_POSTGRES_USER` / `STOCK_DATA_POSTGRES_PASSWORD` | `reef` / `reef` | Postgres credentials |
| `STOCK_DATA_HTTP_PORT` | `8081` | HTTP listen port |

Run the schema migration before using `postgres` persistence:
`scripts/dev/db/migrations/stock_data/0001_seed_snapshots.sql` (applied via
`bun run dev:db:migrate`, domain `stock_data`).

## HTTP API

`POST /v1/seed-snapshots`

```json
{ "gameSeedId": "game-123", "symbols": ["AAPL", "MSFT"], "asOf": "2026-07-08T15:00:00Z" }
```

`asOf` is optional and defaults to request time. Returns `200` with the
persisted batch (including `batchSeedHash`) on success. Returns `422` with a
structured `{"error": {"symbol", "category", "message"}}` body on a failed
seed - see the plan doc's "Failure Behavior" section for the full list of
`category` values. Calling again with the same `gameSeedId` always replays
the persisted batch and never re-calls the provider, even if a different
symbol list is passed.

`GET /health` returns `{"status": "ok"}`.

## Running tests

```bash
./gradlew --no-daemon test
```

Uses `FakeStockDataProvider` and a scripted `TiingoHttpClient` test double -
no real network calls or Tiingo credentials required.

## What's implemented vs. deferred

Implemented: provider interface, normalized seed snapshot model, market
session classification (NYSE calendar + ET hours), deterministic fake
provider, Tiingo adapter (current IEX + EOD fallback, freshness rules,
retries/jitter, circuit breaker, short-lived cache), Postgres + in-memory
persistence, seed-once workflow, minimal HTTP entrypoint, replay/failure-path
tests.

Deferred (see plan doc "Open Questions"): wiring into a real game-creation
flow (no such flow exists yet), internal-only vs. commercial Tiingo
licensing decision, non-US exchange support, pre/post-market seed realism,
and a decision on storing full raw provider payloads (currently only a hash
is persisted).
