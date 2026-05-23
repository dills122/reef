# Event Backbone and Data Lifecycle Implementation Spec

## Purpose

Define the concrete implementation shape for:

- durable event logging
- outbox-based async publication
- EOD archive + analytics refresh
- scheduled job orchestration

This spec intentionally targets a pragmatic single-Postgres deployment and avoids heavyweight orchestration.

## Non-Negotiable Rules

1. Intraday persistence is real-time.
- EOD is never first-write persistence for trading lifecycle facts.

2. Postgres is canonical event history.
- NATS JetStream is distribution and replay window, not sole source of truth.

3. Procedure-first write path.
- Critical mutations and job state transitions are done through schema-owned Postgres routines.

4. Delivery semantics.
- at-least-once publication and consumption
- idempotent consumer effects keyed by `event_id`

5. Identifier baseline for current stack.
- Postgres 16 baseline uses UUIDv4 (`gen_random_uuid()`)
- UUIDv7 is a planned optimization path after version/tooling upgrade

## Domain Layout (Single Instance, Split-Ready)

- `runtime`: orders, executions, trades, runtime_events, event_outbox
- `orchestration`: scheduled jobs and run state machine
- `analytics`: transformed query-optimized projections
- `archive`: metadata for exported file artifacts (optional table ownership under `orchestration` if preferred)

## Core Tables (DDL Sketch)

```sql
-- runtime
create table if not exists runtime.runtime_events (
  event_id text primary key,
  event_type text not null,
  trace_id text not null,
  causation_id text not null,
  correlation_id text not null,
  actor_id text not null,
  order_id text not null,
  schema_version text not null default 'v1',
  payload_json jsonb not null,
  occurred_at timestamptz not null,
  created_at timestamptz not null default now()
);

create index if not exists runtime_events_occurred_idx on runtime.runtime_events (occurred_at);
create index if not exists runtime_events_trace_idx on runtime.runtime_events (trace_id);

create table if not exists runtime.event_outbox (
  outbox_id bigserial primary key,
  event_id text not null unique,
  stream text not null,
  subject text not null,
  payload_json jsonb not null,
  status text not null check (status in ('pending','published','retry_wait','dead_letter')),
  attempt_count int not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_error text not null default '',
  created_at timestamptz not null default now(),
  published_at timestamptz
);

create index if not exists event_outbox_status_next_idx
  on runtime.event_outbox (status, next_attempt_at);

-- orchestration
create table if not exists orchestration.scheduled_jobs (
  job_name text primary key,
  cron_expr text not null,
  enabled boolean not null default true,
  max_attempts int not null default 10,
  retry_backoff_seconds int not null default 30,
  timeout_seconds int not null default 1800,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists orchestration.job_runs (
  run_id uuid primary key,
  job_name text not null references orchestration.scheduled_jobs(job_name),
  business_key text not null, -- e.g. market_date=2026-05-23
  status text not null check (status in ('queued','running','retry_wait','succeeded','failed','cancelled')),
  attempt_count int not null default 0,
  not_before timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz,
  worker_id text not null default '',
  last_error text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (job_name, business_key)
);

create index if not exists job_runs_status_due_idx
  on orchestration.job_runs (status, not_before);

create table if not exists orchestration.job_artifacts (
  run_id uuid not null references orchestration.job_runs(run_id),
  artifact_type text not null, -- archive_manifest, analytics_report, etc.
  artifact_uri text not null,
  sha256 text not null,
  row_count bigint not null default 0,
  metadata_json jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  primary key (run_id, artifact_type, artifact_uri)
);
```

## Required Stored Procedures / Functions

Routine naming can use `_v1` suffix for safe evolution.

### Runtime write path routines

1. `runtime.fn_append_event_and_outbox_v1(...) returns void`
- Inserts `runtime_events` row and matching `event_outbox` row atomically.

2. `runtime.fn_submit_order_commit_v1(...) returns jsonb`
- Commits order state transition + event + outbox in one transaction boundary.
- Returns canonical response envelope for API layer.

3. `runtime.fn_outbox_claim_batch_v1(worker_id text, batch_size int) returns setof runtime.event_outbox`
- Claims due rows (`pending`,`retry_wait`) using `for update skip locked`.
- Marks rows as in-progress via attempt increment and worker marker strategy.

4. `runtime.fn_outbox_mark_published_v1(outbox_ids bigint[]) returns void`

5. `runtime.fn_outbox_mark_retry_v1(outbox_id bigint, err text, delay_seconds int) returns void`

6. `runtime.fn_outbox_mark_dead_letter_v1(outbox_id bigint, err text) returns void`

### Orchestration routines

1. `orchestration.fn_enqueue_job_run_v1(job_name text, business_key text, not_before timestamptz) returns uuid`
- Idempotent enqueue by `(job_name,business_key)`.

2. `orchestration.fn_claim_due_run_v1(worker_id text) returns orchestration.job_runs`
- Claims next due run using row lock and status transition `queued|retry_wait -> running`.

3. `orchestration.fn_mark_run_success_v1(run_id uuid) returns void`

4. `orchestration.fn_mark_run_retry_v1(run_id uuid, err text, delay_seconds int) returns void`

5. `orchestration.fn_mark_run_failed_v1(run_id uuid, err text) returns void`

6. `orchestration.fn_heartbeat_run_v1(run_id uuid, worker_id text) returns void`

## Outbox Relay Contract

Worker loop:

1. claim batch via `runtime.fn_outbox_claim_batch_v1`
2. publish each record to NATS JetStream subject
3. on success: mark published
4. on transient failure: mark retry with exponential backoff
5. on terminal failure after max attempts: dead-letter and emit alert

SLO signals:

- outbox backlog count by status
- max backlog age
- publish success/error rate
- dead-letter count

## Scheduler / Job-Runner Contract

Trigger model:

- cron tick (every minute) enqueues business-keyed runs (e.g. one EOD run per market date)

Execution model:

1. claim due run
2. execute handler by `job_name`
3. persist artifacts and reconciliation counts
4. mark success/retry/failed

State machine:

- `queued -> running -> succeeded`
- `queued -> running -> retry_wait -> running`
- `queued|running -> failed`

Invariants:

- only one active run per `(job_name,business_key)`
- handlers are idempotent and resumable
- no direct ad hoc SQL transitions from app code; use orchestration routines

## EOD Archive Job Contract

Job name:

- `eod_archive_export`

Business key:

- `market_date=YYYY-MM-DD`

Inputs:

- market calendar close window
- runtime event/trade/order cutoff query

Steps:

1. verify no open intraday write window for target market date
2. reconcile source counts (events/trades/orders)
3. export partitioned files:
   - `archive/market_date=YYYY-MM-DD/events-*.jsonl.gz`
   - `archive/market_date=YYYY-MM-DD/trades-*.jsonl.gz`
4. compute per-file checksums
5. write `manifest.json` (file list, row counts, hashes, timestamps, run id)
6. persist artifact rows in `orchestration.job_artifacts`

Success criteria:

- exported counts match reconciled source counts
- manifest checksum validation passes

## Analytics Refresh Job Contract

Job name:

- `eod_analytics_refresh`

Business key:

- `market_date=YYYY-MM-DD`

Inputs:

- sealed archive manifest for market date

Steps:

1. validate upstream archive run succeeded
2. load/transform into `analytics` schema tables/materialized views
3. publish per-table row-count checks
4. persist run artifacts and summary metrics

## Retention Baseline

1. NATS JetStream:
- short retention window (1-7 days)

2. runtime hot tables:
- full retention initially; move to time partitioning when size or vacuum pressure warrants

3. analytics:
- retain operator-relevant horizon (for example 90-365 days)

4. archive:
- long-term immutable storage by market date partition

## Performance Guardrails

1. Keep hot transaction small.
- state + event + outbox only

2. Minimize hot indexes.
- do not add analytics-oriented indexes to hot write tables by default

3. Batch relay publication.
- small batch claims to reduce lock contention

4. Isolate analytics workload.
- analytics queries must not contend with runtime write-path locks

## Test Matrix (Minimum)

1. Unit:
- routine contract tests for outbox/job state transitions

2. Integration:
- atomic write of state + event + outbox
- relay retries then publishes after broker recovery
- idempotent enqueue and duplicate-prevention by `(job_name,business_key)`

3. Failure:
- NATS unavailable during intraday writes
- job-runner crash mid-run and successful resume
- checksum mismatch forces EOD run failure

## Immediate Build Order

1. DDL + routines for `runtime.event_outbox` and relay lifecycle
2. relay worker implementation + metrics
3. orchestration schema + routines + job-runner skeleton
4. `eod_archive_export` implementation
5. `eod_analytics_refresh` implementation
6. retention/partitioning and operational runbooks
