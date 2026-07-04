# Reef Data Domain and Schema Blueprint

## Purpose

Provide a first-pass, implementation-oriented data blueprint for Reef domains, tables, views, and routine boundaries.

This is a design baseline, not locked DDL.

## Design Goals

1. Support current single-Postgres deployment.
2. Preserve low-friction path to domain-specific DB split.
3. Keep operational write path fast and auditable.
4. Separate hot operational, analytics, and archive responsibilities.

## Schema Overview

- `runtime`: canonical venue facts, command outcomes, operational projections, lifecycle events, outbox
- `boundary`: API idempotency and boundary concerns
- `auth`: roles and actor-role bindings
- `account` (planned): users, bots, accounts, immutable ledger entries, holds, derived balances, risk limits
- `admin`: policy/config/audit operations
- `settlement` (planned): obligations, allocations, confirmations, fulfillment, breaks, repairs
- `market_data` (planned): top-of-book/depth snapshots, recent trades, intraday bars, historical query surfaces
- `orchestration` (planned): scheduler definitions and run-state
- `analytics` (planned): transformed reporting/query surfaces

Reference boundary:
- [`docs/TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md)

## Runtime Schema

`runtime` now has two separate responsibilities:

1. compact canonical venue persistence from durable `VenueEventBatch` output.
2. rebuildable operational projections for order/status/query surfaces.

Do not put synchronous normalized order, trade, execution, or UI table writes back into the matching-engine hot path.

### Canonical venue facts

1. `runtime.canonical_venue_event_batches`
- compact durable materialization of matching-engine `VenueEventBatch` output.
- preserves batch identity, command/event stream identity, partition/offset or sequence, payload checksum, payload format/version, command count, and replay-safe payload facts.

2. `runtime.canonical_command_outcomes`
- command-level lookup and replay rows projected from canonical event batches.
- preserves command id, command type, result status, reject code, order/instrument identifiers, stream sequence/offset, result payload, and payload hash.

### Operational order lifecycle projections

These tables are query/read projections unless a later decision explicitly promotes a field into compact canonical persistence.

1. `runtime.orders`
- `order_id uuid pk`
- `engine_order_id text unique`
- `participant_id uuid`
- `account_id uuid`
- `instrument_id uuid`
- `side text check (...)`
- `order_type text check (...)`
- `quantity numeric(20,0)`
- `limit_price numeric(20,8)`
- `currency char(3)`
- `time_in_force text`
- `status text`
- `accepted_at timestamptz`
- `updated_at timestamptz`

2. `runtime.executions`
- `execution_id uuid pk`
- `event_id uuid unique`
- `order_id uuid`
- `instrument_id uuid`
- `quantity numeric(20,0)`
- `execution_price numeric(20,8)`
- `currency char(3)`
- `occurred_at timestamptz`

3. `runtime.trades`
- `trade_id uuid pk`
- `event_id uuid unique`
- `execution_id uuid`
- `buy_order_id uuid`
- `sell_order_id uuid`
- `instrument_id uuid`
- `quantity numeric(20,0)`
- `price numeric(20,8)`
- `currency char(3)`
- `occurred_at timestamptz`

### Event and outbox

4. `runtime.runtime_events`
- `event_id uuid pk`
- `event_type text`
- `order_id uuid`
- `trace_id uuid`
- `causation_id uuid`
- `correlation_id uuid`
- `actor_id text`
- `producer text`
- `schema_version text`
- `sequence_number bigint`
- `payload_json jsonb`
- `occurred_at timestamptz`
- `created_at timestamptz`

Indexes:
- `(occurred_at)`
- `(trace_id, sequence_number)`
- `(order_id, occurred_at)`

5. `runtime.event_outbox`
- `outbox_id bigserial pk`
- `event_id uuid unique`
- `stream text`
- `subject text`
- `payload_json jsonb`
- `status text check ('pending','published','retry_wait','dead_letter')`
- `attempt_count int`
- `next_attempt_at timestamptz`
- `last_error text`
- `created_at timestamptz`
- `published_at timestamptz`

Indexes:
- `(status, next_attempt_at)`

## Boundary Schema

1. `boundary.idempotency_records`
- `client_id text`
- `route text`
- `idempotency_key text`
- `request_hash text`
- `response_status int`
- `response_body jsonb`
- `created_at timestamptz`
- `expires_at timestamptz`
- `primary key (client_id, route, idempotency_key)`

## Auth Schema

1. `auth.roles`
- `role_id text pk`
- `permissions jsonb`
- `created_at timestamptz`

2. `auth.actor_roles`
- `actor_id text`
- `role_id text`
- `created_at timestamptz`
- `primary key (actor_id, role_id)`

## Admin Schema

1. `admin.market_calendar_profiles`
- `profile_id text pk`
- `timezone text`
- `calendar_json jsonb`
- `version int`
- `active boolean`

2. `admin.settlement_cycle_profiles`
- `profile_id text pk`
- `default_cycle text` -- e.g., T+1
- `rules_json jsonb`
- `version int`
- `active boolean`

3. `admin.override_audit`
- `override_id uuid pk`
- `actor_id text`
- `reason_code text`
- `note text`
- `target_type text`
- `target_id text`
- `occurred_at timestamptz`

## Orchestration Schema (Planned)

1. `orchestration.scheduled_jobs`
2. `orchestration.job_runs`
3. `orchestration.job_artifacts`

Reference contract:
- [`docs/EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)

## Account Schema (Planned)

The account domain owns users, bots, accounts, credits, holds, and risk controls. Current balances should be derived from immutable ledger entries plus open holds/reservations.

Candidate tables:

1. `account.accounts`
2. `account.bots`
3. `account.bot_configs`
4. `account.ledger_entries`
5. `account.balance_snapshots`
6. `account.order_holds`
7. `account.risk_limits`
8. `account.bot_state_changes`

Intake risk checks should use a hot, bounded account/risk view before durable order acceptance. Settlement performs final enforcement from matched facts and can block fulfillment, create exceptions, or disable bots.

## Settlement Schema (Planned)

Settlement consumes canonical venue facts and creates post-trade facts. It must not mutate matching history.

Candidate tables:

1. `settlement.obligations`
2. `settlement.allocations`
3. `settlement.confirmations`
4. `settlement.fulfillment_steps`
5. `settlement.breaks`
6. `settlement.repairs`
7. `settlement.exception_cases`

## Market Data Schema (Planned)

Market data is a separate query/read domain from order entry.

Candidate tables:

1. `market_data.book_snapshots`
2. `market_data.depth_snapshots`
3. `market_data.recent_trades`
4. `market_data.intraday_bars`
5. `market_data.historical_bars`
6. `market_data.feed_watermarks`

The first market-data slice should favor snapshots and bounded-refresh projections over matching-engine-coupled live reads.

## Analytics Schema (Planned)

### Daily derived tables

1. `analytics.trade_fact_daily`
- partition key: `market_date`
- denormalized trade-level fields for operator/reporting queries

2. `analytics.order_lifecycle_fact_daily`
- one row per order lifecycle outcome
- includes latency buckets and terminal state

3. `analytics.event_counts_daily`
- aggregated counts by event type / hour / participant

4. `analytics.exception_fact_daily` (post-match phases)
- open/close duration, queue owner, severity

### Suggested views

1. `analytics.vw_intraday_tps`
- rolling throughput metrics by minute

2. `analytics.vw_order_to_trade_latency`
- lifecycle latency distribution

3. `analytics.vw_event_backlog_health`
- outbox pending/retry/dead-letter visibility

## Archive Layout (File Store)

Suggested partitioning:

- `archive/market_date=YYYY-MM-DD/events-*.jsonl.gz`
- `archive/market_date=YYYY-MM-DD/trades-*.jsonl.gz`
- `archive/market_date=YYYY-MM-DD/manifest.json`

Manifest minimum:

- run id
- source reconciliation counts
- file list
- per-file row counts
- SHA-256 checksums
- export start/end timestamps

## Routine Ownership Boundaries

`runtime` routines:
- append event + outbox
- outbox claim/publish/retry/dead-letter
- lifecycle mutation commits

`orchestration` routines:
- enqueue run
- claim due run
- mark success/retry/fail
- heartbeat

`analytics` routines:
- refresh daily facts
- validation row-count checks

## Open Questions

1. Numeric precision defaults for price/quantity per instrument class.
2. When to introduce partitioning for `runtime_events` and `event_outbox` (volume trigger thresholds).
3. Analytics schema in same instance vs separate Postgres instance trigger point.
4. UUIDv7 migration trigger and rollout plan after Postgres/runtime upgrade.

## Identifier Baseline (Current)

- standardize on UUIDs for primary identifiers
- generate UUIDv4 in DB via `gen_random_uuid()` while on Postgres 16
- treat UUIDv7 as a future optimization path, not a current requirement
