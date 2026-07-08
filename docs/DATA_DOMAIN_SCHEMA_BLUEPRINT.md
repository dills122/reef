# Reef Data Domain and Schema Blueprint

## Purpose

Provide a first-pass, implementation-oriented data blueprint for Reef domains, tables, views, and routine boundaries.

This is a design baseline, not locked DDL. Where a schema is already live, the field lists below are taken directly from `scripts/dev/db/migrations/**/*.sql` and should track those migrations; where a schema is still planned, that is called out explicitly.

## Design Goals

1. Support current single-Postgres deployment.
2. Preserve low-friction path to domain-specific DB split.
3. Keep operational write path fast and auditable.
4. Separate hot operational, analytics, and archive responsibilities.

## Schema Overview

- `runtime`: canonical venue facts, command outcomes, operational lifecycle/order-book projections, market-data snapshots, event backbone/outbox
- `boundary`: API idempotency, command capture, stream-intake reservations, account/bot risk controls, circuit breakers, price collars, rejection audit
- `command_log`: append-only durable inbound command capture, active work queue, and terminal results
- `auth`: roles and actor-role bindings
- `admin`: policy/config operations (post-trade profiles)
- `settlement`: post-trade obligation/instruction/settlement/exception facts (P2 exception handling plus instant-finality legs and resource-fail tracking)
- `arena`: bot registry, bot versions, qualification, run records, run results, and enforcement events
- `stock_data`: per-game-seed stock snapshot facts persisted once so simulation/replay never re-calls the external provider
- `orchestration`: scheduler/job-runner definitions and run state
- `analytics`: transformed reporting/query surfaces
- `account` (planned): users, bots, accounts, immutable ledger entries, holds, derived balances, risk limits

There is no separate `market_data` schema. Top-of-book snapshot data lives in `runtime.market_data_snapshots` / `runtime.market_data_snapshot_dirty` (see the Runtime Schema section below).

Reference boundary:
- [`docs/TRADING_MARKET_DATA_BOUNDARIES.md`](./TRADING_MARKET_DATA_BOUNDARIES.md)

## Runtime Schema

`runtime` has three responsibilities:

1. compact canonical venue persistence from durable `VenueEventBatch` output.
2. rebuildable operational projections for order/status/query surfaces, including order-book lifecycle state and market-data top-of-book snapshots.
3. the runtime event backbone/outbox used to publish domain events.

Do not put synchronous normalized order, trade, execution, or UI table writes back into the matching-engine hot path.

### Canonical venue facts

1. `runtime.canonical_venue_event_batches` (`runtime/0010`, `0013`, `0033`)
- compact durable materialization of matching-engine `VenueEventBatch` output.
- `batch_id text pk`, `shard_id text`, `partition_id int`, `command_stream text`, `event_stream text`, `first_sequence bigint`, `last_sequence bigint`, `command_count int`, `payload_checksum text`, `payload_format text default 'venue-event-batch-json'`, `payload_version text default 'v1'`, `payload_json jsonb`, `created_at text`, `materialized_at timestamptz default now()`, plus typed companion `created_at_ts timestamptz` (`0033`).
- unique on `(event_stream, partition_id, first_sequence, last_sequence)`.

2. `runtime.canonical_command_outcomes` (`runtime/0010`, `0033`, `0034`)
- command-level lookup and replay rows projected from canonical event batches; this is the primary canonical-outcome table consumed by the venue-event-batch projector.
- `command_id text pk`, `batch_id text`, `shard_id text`, `partition_id int`, `command_stream text`, `event_stream text`, `stream_sequence bigint`, `delivered_count bigint`, `command_type text`, `payload_hash text`, `instrument_id text`, `order_id text`, `result_status text`, `reject_code text`, `result_payload jsonb default '{}'`, `materialized_at timestamptz default now()`, plus typed companion `occurred_at_ts timestamptz` (`0033`).
- unique on `(batch_id, stream_sequence)`.

3. `runtime.canonical_command_results` / `runtime.canonical_venue_events` (`runtime/0006`, `0033`)
- an earlier canonical append-only pair (`canonical_command_results` keyed by `command_id`; `canonical_venue_events` keyed by `event_id`) used by `runtime.runtime_append_canonical_submit_outcomes` / `runtime.runtime_project_canonical_submit_outcomes`.
- per `docs/DB_SPLIT_READINESS.md`, this pair is a legacy/compat path pending consolidation onto `runtime.canonical_command_outcomes`; do not add new consumers here without checking current status first.

### Operational order lifecycle projections

These tables are query/read projections unless a later decision explicitly promotes a field into compact canonical persistence. All identifier, quantity, price, and timestamp fields are stored as `TEXT` for compatibility; numeric/time-typed *companion* columns (suffixed `_num`, `_ts`, `_uuid`) were added later (`runtime/0028`-`0032`) purely to support native ordering/index use and are populated by insert/update triggers alongside the text columns.

1. `runtime.orders` (`runtime/0003`, `0025`, `0032`)
- `order_id text pk`
- `engine_order_id text not null`
- `instrument_id text not null`
- `participant_id text not null`
- `account_id text not null`
- `side text not null`
- `order_type text not null`
- `quantity_units text not null`
- `limit_price text not null`
- `currency text not null`
- `time_in_force text not null`
- `accepted_at text not null`
- `client_order_id text not null default ''`
- `run_id text not null default ''`
- `venue_session_id text not null default ''`
- typed companions: `quantity_units_num numeric`, `limit_price_num numeric`, `accepted_at_ts timestamptz`
- partial index `runtime_orders_participant_client_order_id (participant_id, client_order_id) where client_order_id <> ''`

There is no `status` or `updated_at` column on `runtime.orders` — it is an immutable accepted-order fact row. Current order status/remaining-quantity state lives in the separate `runtime.order_lifecycle_state` table below.

2. `runtime.order_lifecycle_state` (`runtime/0016`, `0020`, `0028`)
- rebuildable lifecycle projection derived from `runtime.orders` + `runtime.executions` + `runtime.runtime_events`, refreshed incrementally via `runtime.order_lifecycle_dirty` tracking and `runtime.runtime_project_order_lifecycle_state(...)`.
- `order_id text pk`
- `engine_order_id text not null`
- `instrument_id text not null`
- `participant_id text not null`
- `account_id text not null`
- `side text not null`
- `order_type text not null`
- `original_quantity_units text not null`
- `remaining_quantity_units text not null`
- `filled_quantity_units text not null`
- `limit_price text not null`
- `currency text not null`
- `time_in_force text not null`
- `status text not null` (`OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `REJECTED`)
- `accepted_at text not null`
- `last_event_at text not null`
- `updated_at timestamptz not null default now()`
- typed companions: `original_quantity_units_num`, `remaining_quantity_units_num`, `filled_quantity_units_num`, `limit_price_num` (all `numeric`)
- index `idx_order_lifecycle_state_book (instrument_id, status, side, limit_price)`

3. `runtime.order_lifecycle_dirty` (`runtime/0020`)
- dirty-tracking queue driving incremental lifecycle projection: `order_id text pk`, `dirtied_at timestamptz not null default now()`.

4. `runtime.market_data_snapshots` (`runtime/0015`, `0028`)
- top-of-book snapshot projection (this is where "market data" actually lives — there is no `market_data` schema).
- `projection_name text not null`, `source_projection_name text not null`, `instrument_id text not null`, `best_bid_price text default ''`, `best_bid_quantity text default ''`, `best_ask_price text default ''`, `best_ask_quantity text default ''`, `currency text default ''`, `last_partition_seq bigint default 0`, `lag bigint default 0`, `updated_at timestamptz default now()`, primary key `(projection_name, instrument_id)`.
- typed companions: `best_bid_price_num`, `best_bid_quantity_num`, `best_ask_price_num`, `best_ask_quantity_num` (all `numeric`).

5. `runtime.market_data_snapshot_dirty` (`runtime/0021`)
- dirty-tracking queue for incremental top-of-book refresh: `instrument_id text pk`, `dirtied_at timestamptz not null default now()`.

6. `runtime.executions` (`runtime/0003`, `0031`)
- `event_id text pk`, `execution_id text not null`, `order_id text not null`, `instrument_id text not null`, `quantity_units text not null`, `execution_price text not null`, `currency text not null`, `occurred_at text not null`.
- typed companions: `event_id_uuid uuid`, `quantity_units_num numeric`, `execution_price_num numeric`, `occurred_at_ts timestamptz`.

7. `runtime.trades` (`runtime/0003`, `0022`, `0031`)
- `event_id text pk`, `trade_id text not null`, `execution_id text not null`, `buy_order_id text not null`, `sell_order_id text not null`, `instrument_id text not null`, `quantity_units text not null`, `price text not null`, `currency text not null`, `occurred_at text not null`, `sequence bigint generated always as identity` (monotonic trade-tape cursor, `0022`).
- typed companions: `event_id_uuid uuid`, `quantity_units_num numeric`, `price_num numeric`, `occurred_at_ts timestamptz`.
- indexes include `idx_trades_instrument_sequence (instrument_id, sequence desc)` for the public trade tape read path.

8. `runtime.submit_results` (`runtime/0003`, `0030`)
- `command_id text pk`, `result_type text not null`, `event_id text not null`, `order_id text not null`, `engine_order_id text not null`, `code text not null`, `reason text not null`, `occurred_at text not null`.
- typed companions: `event_id_uuid uuid`, `occurred_at_ts timestamptz`.

9. Reference tables (`runtime/0003`, `0034`): `runtime.reference_instruments (instrument_id text pk, symbol text)`, `runtime.reference_participants (participant_id text pk, name text)`, `runtime.reference_accounts (account_id text pk, participant_id text)`, `runtime.reference_scenario_runs (scenario_run_id text pk, post_trade_profile_id text, updated_at timestamptz)`, `runtime.reference_venue_sessions (venue_session_id text pk, post_trade_profile_id text, updated_at timestamptz)`.

10. `runtime.projection_watermarks` (`runtime/0007`)
- `projection_name text`, `partition_id int`, `last_partition_seq bigint default 0`, `last_projected_at timestamptz`, `updated_at timestamptz default now()`, `last_error text default ''`, primary key `(projection_name, partition_id)`.

### Event and outbox

11. `runtime.runtime_events` (`runtime/0002`, `0003`, `0029`)
- `event_id text pk` (originally `uuid`, widened to `text` in `0003` for compatibility)
- `event_type text not null`
- `order_id text not null`
- `trace_id text not null`
- `causation_id text not null`
- `correlation_id text not null`
- `actor_id text not null default ''`
- `producer text not null`
- `schema_version text not null`
- `sequence_number bigint not null`
- `payload_json jsonb not null default '{}'`
- `occurred_at text not null`
- `created_at timestamptz not null default now()`
- typed companions: `event_id_uuid uuid`, `occurred_at_ts timestamptz`

Indexes:
- `(occurred_at)`
- `(trace_id, sequence_number)`
- `(order_id, trace_id, sequence_number)`

12. `runtime.runtime_trace_sequences` (`runtime/0003`)
- `trace_id text pk`, `next_sequence bigint not null` — per-trace monotonic sequence allocator used when persisting event batches.

13. `runtime.event_outbox` (`runtime/0002`)
- `outbox_id bigserial pk`
- `event_id uuid unique not null`
- `stream text not null`
- `subject text not null`
- `payload_json jsonb not null`
- `status text not null check (status in ('pending','published','retry_wait','dead_letter'))`
- `attempt_count int not null default 0`
- `next_attempt_at timestamptz not null default now()`
- `last_error text not null default ''`
- `worker_id text not null default ''`
- `created_at timestamptz not null default now()`
- `published_at timestamptz`

Indexes:
- `(status, next_attempt_at, outbox_id)`

Routines: `runtime.fn_append_event_and_outbox_v1`, `runtime.fn_outbox_claim_batch_v1`, `runtime.fn_outbox_mark_published_v1`, `runtime.fn_outbox_mark_retry_v1`, `runtime.fn_outbox_mark_dead_letter_v1`.

## Boundary Schema

Full backend/decision detail for every boundary-owned store (idempotency, command capture, stream intake, account/bot risk, circuit breakers, price collars, rejection log) lives in [`docs/API_BOUNDARY_STORAGE_DECISIONS.md`](./API_BOUNDARY_STORAGE_DECISIONS.md) — this section only lists the tables and their key fields so the two docs don't drift out of sync.

1. `boundary.api_idempotency_records` (`boundary/0002`)
- `client_id text`, `route text`, `idempotency_key text`, `status text not null`, `payload text not null`, `created_at timestamptz default now()`, `expires_at timestamptz not null`
- primary key `(client_id, route, idempotency_key)`

2. `boundary.api_command_captures` (`boundary/0002`, `0003`, `0004`)
- `client_id text`, `route text`, `idempotency_key text`, `command_id text default ''`, `request_payload text not null`, `status text not null`, `response_status int default 0`, `response_payload text default ''`, `error_class text default ''`, `error_message text default ''`, `created_at text default ''`, `last_updated_at timestamptz default now()`, `correlation_id text default ''`, `first_received_at timestamptz default now()`
- primary key `(client_id, route, idempotency_key)`
- index `idx_api_command_captures_status_updated (status, last_updated_at desc)`

3. `boundary.stream_command_intake` (`boundary/0005`)
- `scope text`, `idempotency_key text`, `payload_hash text`, `command_id text unique`, `route text`, `subject text`, `stream_name text`, `partition int`, `stream_sequence bigint default 0`, `published boolean default false`, `first_seen_at timestamptz default now()`, `published_at timestamptz`
- primary key `(scope, idempotency_key)`

4. `boundary.account_risk_controls` (`boundary/0006`, `0008`)
- `scope_type text check (scope_type in ('ACCOUNT','BOT'))`, `scope_id text`, `decision text check (decision in ('ALLOW','REJECT','BACKPRESSURE','DISABLED_BOT'))`, `reason text default ''`, `max_quantity_units text default ''`, `max_notional text default ''`, `currency text default ''`, `updated_at timestamptz default now()`
- primary key `(scope_type, scope_id)`

5. `boundary.account_risk_decisions` (`boundary/0006`, `0008`)
- audit row per non-allow decision: `decision_id text pk`, `decided_at timestamptz default now()`, `decision text check (in ('REJECT','BACKPRESSURE','DISABLED_BOT'))`, `code text`, `message text`, `client_id text`, `route text`, `command_type text`, `command_id text`, `idempotency_key text`, `correlation_id text`, `actor_id text`, `participant_id text`, `account_id text`, `bot_id text`, `run_id text`, `venue_session_id text`, `instrument_id text`, `order_id text`, `payload_hash text`, `quantity_units text default ''`, `limit_price text default ''`, `currency text default ''`
- index `idx_account_risk_decisions_scope_time (account_id, bot_id, decided_at desc)`

6. `boundary.command_circuit_breakers` (`boundary/0007`)
- `scope_type text check (in ('GLOBAL','VENUE_SESSION','INSTRUMENT'))`, `scope_id text`, `tripped boolean not null`, `reason text default ''`, `updated_at timestamptz default now()`
- primary key `(scope_type, scope_id)`

7. `boundary.instrument_price_collars` (`boundary/0009`)
- `instrument_id text pk`, `min_price text default ''`, `max_price text default ''`, `currency text default ''`, `reason text default ''`, `updated_at timestamptz default now()`

8. `boundary.boundary_rejections` (`boundary/0010`)
- pre-acceptance guardrail rejection audit row: `rejection_id text pk`, `rejected_at timestamptz default now()`, `guardrail_type text`, `scope_type text default ''`, `scope_id text default ''`, `status int`, `code text`, `message text`, `client_id text`, `route text`, `command_type text`, `command_id text`, `idempotency_key text`, `correlation_id text`, `actor_id text`, `participant_id text`, `account_id text`, `bot_id text`, `run_id text`, `venue_session_id text`, `instrument_id text`, `order_id text`, `quantity_units text default ''`, `limit_price text default ''`, `currency text default ''`, `payload_hash text`
- index `idx_boundary_rejections_guardrail_time (guardrail_type, rejected_at desc)`

## Command Log Schema

Append-only durable inbound command capture, independent of `boundary` and `runtime` (see `docs/DB_SPLIT_READINESS.md` guardrail 9). Command intake, active work-queue state, and terminal results are split across three tables so the hot append/claim path avoids scanning terminal history.

1. `command_log.commands` (`command_log/0001`, `0002`, `0009`, `0010`)
- durable accepted-command ledger: `command_id text pk`, `client_id text`, `route text`, `idempotency_key text`, `trace_id text`, `correlation_id text`, `actor_id text`, `command_type text`, `run_id text default ''`, `run_kind text default ''`, `scenario_id text default ''`, `received_at timestamptz`, `payload_json jsonb` (legacy — full payload now lives in `command_payloads`), `status text default 'RECEIVED' check (in ('RECEIVED','PROCESSING','COMPLETED','FAILED'))`, `attempt_count int default 0`, `last_error text default ''`, `response_status int default 0`, `response_payload_json jsonb default '{}'`, `created_at timestamptz default now()`
- unique `(client_id, route, idempotency_key)`

2. `command_log.command_payloads` (`command_log/0012`)
- `command_id text pk references commands(command_id)`, `payload_json jsonb not null`, `created_at timestamptz default now()` — full request JSON split off the hot metadata row.

3. `command_log.command_work_queue` (`command_log/0003`, `0004`, `0011`)
- active (non-terminal) worker state only, `UNLOGGED`: `command_id text pk`, `status text check (in ('RECEIVED','PROCESSING','COMPLETED','FAILED'))`, `attempt_count int default 0`, `last_error text default ''`, `leased_by text default ''`, `leased_until timestamptz`, `updated_at timestamptz default now()`.

4. `command_log.command_results` (`command_log/0003`, `0004`, `0005`)
- terminal outcomes only: `command_id text pk`, `response_status int not null`, `response_payload_json jsonb default '{}'`, `completed_at timestamptz default now()`, `status text default 'COMPLETED' check (in ('COMPLETED','FAILED'))`, `attempt_count int default 0`, `last_error text default ''`.

5. `command_log.retention_pins` (`command_log/0007`, `0009`)
- named retention pins protecting rows from prune: `pin_id text pk`, `selector_type text check (in ('command_id','idempotency_prefix','trace_id','correlation_id','client_id','run_id'))`, `selector_value text`, `reason text default ''`, `created_at timestamptz default now()`, `updated_at timestamptz default now()`, unique `(selector_type, selector_value)`.

6. `command_log.command_integrity_violations` (view, `command_log/0014`) / `command_log.command_integrity_summary()` (function)
- read-only cross-table integrity diagnostics (orphaned payload/queue/result rows, active commands missing queue rows, terminal results still queued) that replace the visibility lost when hot-path same-schema foreign keys were dropped (`command_log/0013`).

Routine: `command_log.command_append(...)` performs append + duplicate-replay + work-queue enqueue in one call; same-schema foreign keys from the child tables back to `commands` were intentionally dropped (`command_log/0013`) to keep hot insert/delete paths cheap.

## Auth Schema

1. `auth.auth_roles` (`auth/0002`)
- `role_id text pk`
- `permissions text not null`

2. `auth.auth_actor_roles` (`auth/0002`)
- `actor_id text not null`
- `role_id text not null`
- primary key `(actor_id, role_id)`

## Admin Schema

1. `admin.post_trade_profiles` (`admin/0002`)
- `profile_id text pk`
- `mode text not null`
- `settlement_cycle text not null`
- `netting_mode text not null`
- `ledger_posting_mode text not null`
- `policy_version int not null check (policy_version > 0)`
- `active boolean not null default false`
- `updated_at timestamptz not null default now()`
- unique partial index enforcing at most one `active = true` row: `idx_post_trade_profiles_active_one`

`runtime.reference_scenario_runs` and `runtime.reference_venue_sessions` (see Runtime Schema) reference `post_trade_profile_id` values from this table by convention, not by foreign key (cross-domain FKs are disallowed — see `docs/DB_SPLIT_READINESS.md`).

## Settlement Schema

Settlement consumes canonical venue facts and records post-trade obligation/settlement/exception facts. It must not mutate matching history. Every table below carries `scenario_run_id text`, `post_trade_profile_id text default 'ops-realistic-v1'`, `post_trade_policy_version int default 1`, `correlation_id text`, `causation_id text`, `occurred_at timestamptz`, and `inserted_at timestamptz default now()` in addition to the fields listed.

1. `settlement.obligations` (`settlement/0001`)
- `settlement_obligation_id text pk`, `trade_id text`, `buyer_participant_id text`, `seller_participant_id text`, `instrument_id text`, `quantity text`, `cash_amount text`, `currency text`, `state text check (state = 'OBLIGATION_CREATED')`

2. `settlement.instructions` (`settlement/0001`)
- `settlement_instruction_id text pk`, `settlement_obligation_id text`, `instruction_type text check (= 'DVP')`, `state text check (= 'INSTRUCTION_CREATED')`

3. `settlement.attempts` (`settlement/0001`)
- `settlement_attempt_id text pk`, `settlement_obligation_id text`, `settlement_instruction_id text`, `attempt_number int check (> 0)`, `state text check (= 'ATTEMPT_STARTED')`

4. `settlement.breaks` (`settlement/0001`, `0003`)
- `settlement_break_id text pk`, `settlement_obligation_id text`, `reason text check (in ('CASH_LEG_FAILED','SECURITY_LEG_FAILED'))`, `state text check (= 'BROKEN')`

5. `settlement.repairs` (`settlement/0001`, `0004`)
- `settlement_repair_id text pk`, `settlement_break_id text`, `settlement_obligation_id text`, `repair_action text check (in ('POST_CASH_LEG_REPAIR','POST_SECURITY_LEG_REPAIR'))`, `actor_type text check (= 'USER')`, `actor_id text`

6. `settlement.resolutions` (`settlement/0001`)
- `settlement_resolution_id text pk`, `settlement_obligation_id text`, `settlement_break_id text`, `settlement_repair_id text`, `settlement_state text check (= 'RESOLVED')`, `exception_state text check (= 'RESOLVED')`

7. `settlement.leg_outcomes` (`settlement/0002`, `0003`)
- instant-finality per-leg outcome facts: `settlement_leg_outcome_id text pk`, `settlement_obligation_id text`, `settlement_instruction_id text`, `settlement_attempt_id text`, `leg_type text check (in ('CASH','SECURITY'))`, `state text check (in ('LEG_SUCCEEDED','LEG_FAILED'))`

8. `settlement.ledger_entries` (`settlement/0002`)
- `ledger_entry_id text pk`, `settlement_obligation_id text`, `settlement_instruction_id text`, `settlement_attempt_id text`, `participant_id text`, `account_id text`, `asset_type text check (in ('CASH','SECURITY'))`, `asset_id text`, `direction text check (in ('DEBIT','CREDIT'))`, `quantity text`

9. `settlement.settlements` (`settlement/0002`)
- `settlement_id text pk`, `settlement_obligation_id text`, `settlement_instruction_id text`, `settlement_attempt_id text`, `settlement_state text check (= 'SETTLED')`

10. `settlement.resource_positions` (`settlement/0003`)
- resource/position facts backing settlement fail tracking: `resource_position_id text pk`, `participant_id text`, `account_id text`, `asset_type text check (in ('CASH','SECURITY'))`, `asset_id text`, `quantity text`

## Arena Schema

Bot registry, qualification, run records/results, and enforcement events for the bot arena.

1. `arena.bots` (`arena/0001`)
- `bot_id text pk`, `file_name text unique`, `name text`, `publisher text`, `email text`, `description text default ''`, `version text default ''`, `created_at timestamptz`

2. `arena.bot_versions` (`arena/0001`)
- `bot_id text`, `version_id text`, `source_hash text`, `artifact_hash text`, `sdk_version text`, `api_version text`, `dependency_manifest_hash text`, `status text`, `created_at timestamptz`
- primary key `(bot_id, version_id)`, FK to `arena.bots`

3. `arena.qualification_reports` (`arena/0001`)
- `bot_id text`, `version_id text`, `report_id text pk`, `status text`, `policy_version text`, `created_at timestamptz`, FK to `arena.bot_versions`

4. `arena.qualification_report_issues` (`arena/0001`)
- `report_id text`, `issue_order int`, `issue text`, primary key `(report_id, issue_order)`

5. `arena.operator_decisions` (`arena/0001`)
- `bot_id text`, `version_id text`, `decision_order bigserial pk`, `from_status text`, `to_status text`, `actor_id text`, `reason text`, `correlation_id text`, `occurred_at timestamptz`

6. `arena.run_records` (`arena/0001`)
- `run_id text pk`, `mode_id text`, `scenario_id text`, `seed bigint`, `policy_version text`, `status text`, `created_at timestamptz`, `completed_at timestamptz`

7. `arena.run_bot_versions` (`arena/0001`)
- `run_id text`, `bot_order int`, `bot_id text`, `version_id text`, primary key `(run_id, bot_order)`

8. `arena.runtime_config_descriptors` (`arena/0001`)
- `bot_id text`, `version_id text`, `config_key text`, `provider text`, `secret_path text`, `required boolean`, `description text default ''`, primary key `(bot_id, version_id, config_key)`

9. `arena.run_bot_results` (`arena/0002`, `0003`)
- per-run/bot scoring row: `run_id text`, `bot_id text`, `version_id text`, `scoring_policy_version text`, `final_equity bigint`, `realized_pnl bigint`, `max_drawdown bigint`, `actions_proposed int`, `order_actions_proposed int`, `data_calls int`, `signals_generated int`, `disqualified boolean default false`, `score_eligible boolean default true`, `public_leaderboard boolean default true`, `created_at timestamptz`
- primary key `(run_id, bot_id, version_id, scoring_policy_version)`
- leaderboard index on `(scoring_policy_version, score_eligible, public_leaderboard, disqualified, final_equity desc, realized_pnl desc, max_drawdown asc)`

10. `arena.run_enforcement_events` (`arena/0004`)
- `run_id text`, `bot_id text`, `version_id text`, `decision text`, `reason_code text`, `reason text`, `policy_version text`, `counters_json text`, `occurred_at timestamptz`
- primary key `(run_id, bot_id, version_id, decision, reason_code)`

## Stock Data Schema

Seeded stock snapshot facts, persisted once per game seed so simulation/replay/audit never re-call the external stock-data provider (see `docs/STOCK_DATA_SEEDING_PLAN.md`).

1. `stock_data.seed_snapshot_batches` (`stock_data/0001`)
- `game_seed_id text pk`, `as_of timestamptz`, `batch_seed_hash text`, `inserted_at timestamptz default now()`

2. `stock_data.seed_snapshots` (`stock_data/0001`)
- `game_seed_id text references seed_snapshot_batches`, `symbol text`, `provider text`, `source_type text check (in ('intraday_current','historical_eod','cached_fallback'))`, `as_of timestamptz`, `source_timestamp timestamptz`, `retrieved_at timestamptz`, `currency text`, `price numeric`, `open numeric`, `high numeric`, `low numeric`, `previous_close numeric`, `volume bigint`, `raw_provider_payload_hash text`, `selection_reason text`
- primary key `(game_seed_id, symbol)`

## Orchestration Schema

Scheduler/job-runner state machine for scheduled background jobs (e.g. simulation-run export). This schema is live, not planned.

1. `orchestration.scheduled_jobs`
- `job_name text pk`, `cron_expr text`, `enabled boolean default true`, `max_attempts int default 10`, `retry_backoff_seconds int default 30`, `timeout_seconds int default 1800`, `created_at timestamptz default now()`, `updated_at timestamptz default now()`

2. `orchestration.job_runs`
- `run_id uuid pk default gen_random_uuid()`, `job_name text references scheduled_jobs`, `business_key text`, `status text check (in ('queued','running','retry_wait','succeeded','failed','cancelled'))`, `attempt_count int default 0`, `not_before timestamptz default now()`, `started_at timestamptz`, `finished_at timestamptz`, `worker_id text default ''`, `last_error text default ''`, `created_at timestamptz default now()`, `updated_at timestamptz default now()`
- unique `(job_name, business_key)`; index `job_runs_status_due_idx (status, not_before, created_at)`

3. `orchestration.job_artifacts`
- `run_id uuid references job_runs`, `artifact_type text`, `artifact_uri text`, `sha256 text`, `row_count bigint default 0`, `metadata_json jsonb default '{}'`, `created_at timestamptz default now()`
- primary key `(run_id, artifact_type, artifact_uri)`

Routines: `orchestration.fn_enqueue_job_run_v1`, `orchestration.fn_claim_due_run_v1`, `orchestration.fn_mark_run_success_v1`, `orchestration.fn_mark_run_retry_v1`, `orchestration.fn_mark_run_failed_v1`, `orchestration.fn_heartbeat_run_v1`.

Reference contract:
- [`docs/EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md`](./EVENT_DATA_LIFECYCLE_IMPLEMENTATION_SPEC.md)

## Account Schema (Planned)

The account domain owns users, bots, accounts, credits, holds, and risk controls. Current balances should be derived from immutable ledger entries plus open holds/reservations. No `account` schema or migrations exist yet.

Candidate tables:

1. `account.accounts`
2. `account.bots`
3. `account.bot_configs`
4. `account.ledger_entries`
5. `account.balance_snapshots`
6. `account.order_holds`
7. `account.risk_limits`
8. `account.bot_state_changes`

Intake risk checks should use a hot, bounded account/risk view before durable order acceptance. Settlement performs final enforcement from matched facts and can block fulfillment, create exceptions, or disable bots. Note that boundary-layer pre-trade risk controls (`boundary.account_risk_controls`, `boundary.account_risk_decisions`) already exist and cover the intake-time gate described here; this planned schema is for the account/ledger domain proper.

## Analytics Schema

`analytics.simulation_run_exports` (`analytics/0001`) is live today; the daily-fact/reporting tables below remain planned/future work.

### Current

1. `analytics.simulation_run_exports`
- `run_id text pk`, `scenario_id text default ''`, `run_kind text default ''`, `source text default ''`, `git_sha text default ''`, `profile text default ''`, `started_at timestamptz`, `completed_at timestamptz`, `exported_at timestamptz default now()`, `status text default ''`, `attempted_count bigint default 0`, `accepted_count bigint default 0`, `completed_count bigint default 0`, `materialized_count bigint default 0`, `projected_count bigint default 0`, `failed_count bigint default 0`, `p50_latency_ms double precision`, `p95_latency_ms double precision`, `p99_latency_ms double precision`, `artifact_manifest jsonb default '[]'`, `summary jsonb default '{}'`, `created_at timestamptz default now()`, `updated_at timestamptz default now()`
- indexes: `(completed_at desc, exported_at desc)`, `(scenario_id, completed_at desc)`

### Planned / future daily derived tables

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

### Suggested views (planned)

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
- append event + outbox (`runtime.fn_append_event_and_outbox_v1`)
- outbox claim/publish/retry/dead-letter
- lifecycle mutation commits

`orchestration` routines:
- enqueue run
- claim due run
- mark success/retry/fail
- heartbeat

`command_log` routines:
- append + duplicate-replay (`command_log.command_append`)
- integrity audit summary (`command_log.command_integrity_summary`)

`analytics` routines (planned):
- refresh daily facts
- validation row-count checks

## Open Questions

1. Numeric precision defaults for price/quantity per instrument class, and the pace of the text-to-typed-column migration already underway (`runtime/0028`-`0032`).
2. When to introduce partitioning for `runtime_events` and `event_outbox` (volume trigger thresholds).
3. Analytics schema in same instance vs separate Postgres instance trigger point.
4. Whether to consolidate `runtime.canonical_command_results`/`runtime.canonical_venue_events` onto `runtime.canonical_command_outcomes`, or formally mark the former legacy/compat (tracked in `docs/DB_SPLIT_READINESS.md`).
5. UUIDv7 migration trigger and rollout plan after Postgres/runtime upgrade.

## Identifier Baseline (Current)

- Most live tables use `TEXT` identifiers today (order/execution/trade/event ids, command ids), not native `UUID` columns; several tables have gained typed `_uuid`/`_num`/`_ts` companion columns for native ordering without changing the compatibility surface.
- Newer schemas that generate identifiers in Postgres (`orchestration.job_runs.run_id`) use `UUID` with `gen_random_uuid()`.
- Treat UUIDv7 as a future optimization path, not a current requirement.
