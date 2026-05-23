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

- `runtime`: command handling write models, lifecycle events, outbox
- `boundary`: API idempotency and boundary concerns
- `auth`: roles and actor-role bindings
- `admin`: policy/config/audit operations
- `orchestration` (planned): scheduler definitions and run-state
- `analytics` (planned): transformed reporting/query surfaces

## Runtime Schema (Hot Path)

### Core command and order lifecycle

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
