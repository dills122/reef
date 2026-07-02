# Reef Local Dev Environment

This runbook defines the Docker-first local workflow introduced by the dev-env sprint.

## Prerequisites

- Docker with Compose plugin
- `curl`
- Bun runtime
- Go toolchain (only needed for `make dev-stress`, which runs the Go load tester)

## Base workflow

From repo root:

```bash
make dev-up
make dev-smoke
```

Recommended first step for configuration:

```bash
cp .env.example .env
```

If Bun is not available locally yet, you can temporarily run with Node:

```bash
JS_RUNTIME=node make dev-up
JS_RUNTIME=node make dev-smoke
```

If default host ports are already in use, override them at runtime:

```bash
REEF_PLATFORM_RUNTIME_HOST_PORT=18080 \
REEF_MATCHING_ENGINE_HOST_PORT=18081 \
REEF_POSTGRES_HOST_PORT=15432 \
JS_RUNTIME=node make dev-up
```

Shutdown:

```bash
make dev-down
```

Deterministic reset (down + volume wipe + rebuild + compose health wait):

```bash
make dev-reset
```

Forward-only DB migrations run automatically during `make dev-up` and `make dev-reset` after Postgres is healthy and before the full stack starts. Manual migration is still available for repair/debug:

```bash
make dev-db-migrate
```

Optional inline smoke during reset:

```bash
DEV_RESET_RUN_SMOKE=1 make dev-reset
```

## Postgres tuning knobs

Compose applies WAL/checkpoint defaults optimized for local soak runs:

- `REEF_PG_MAX_WAL_SIZE=16GB`
- `REEF_PG_MIN_WAL_SIZE=4GB`
- `REEF_PG_CHECKPOINT_TIMEOUT=15min`
- `REEF_PG_CHECKPOINT_COMPLETION_TARGET=0.9`
- `REEF_PG_WAL_COMPRESSION=on`
- `REEF_PG_LOG_CHECKPOINTS=on`

Override per run:

```bash
REEF_PG_MAX_WAL_SIZE=24GB \
REEF_PG_CHECKPOINT_TIMEOUT=20min \
make dev-reset
```

## Optional profiles

Enable optional services using `DEV_COMPOSE_PROFILES`.

Redis profile:

```bash
DEV_COMPOSE_PROFILES=redis make dev-up
```

Observability profile:

```bash
DEV_COMPOSE_PROFILES=observability make dev-up
```

Multiple profiles:

```bash
DEV_COMPOSE_PROFILES=redis,observability make dev-up
```

## Stress baseline entrypoint

Run stepped load profile (100 -> 200 -> 300 -> 400 rps):

```bash
make dev-stress
```

Run stress with automatic pre/post DB diagnostics capture:

```bash
make dev-stress-diagnostics
```

Run the durable captured-ack queue profile used for async drain and bot-arena capacity work:

```bash
make dev-up-captured-ack
make dev-stress-captured-ack
```

`dev-up-captured-ack` starts `platform-runtime` with:
- `EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled`
- `EXTERNAL_API_COMMAND_LOG_MODE=postgres`
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=captured-ack`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=true`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=4`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE=250`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS=5`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS=60000`

In this profile, `command_log.commands` is the canonical durable command capture path. The legacy boundary command-capture table is disabled by default to avoid duplicate hot-path writes; set `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres` explicitly when testing legacy capture behavior.

Durable captured-ack intake backpressure is opt-in:
- `EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS=0` disables active queue-depth rejection.
- `EXTERNAL_API_COMMAND_INTAKE_MAX_STALE_PROCESSING=0` disables stale-processing rejection.
- When enabled, new commands receive `429 COMMAND_INTAKE_BACKPRESSURE` before durable acceptance; duplicate idempotency replays still return their existing command status.

Override the worker count for drain sweeps, for example:

```bash
EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=16 make dev-up-captured-ack
```

Diagnostic artifacts are written under the stress artifact root with suffix `-diagnostics`:
- `pre-db-diagnostics.json`, `post-db-diagnostics.json`
- `pre-pg_stat_bgwriter.csv`, `post-pg_stat_bgwriter.csv`
- `pre-pg_stat_checkpointer.csv`, `post-pg_stat_checkpointer.csv` (Postgres 17+ when available)
- `pre-table-stats.csv`, `post-table-stats.csv`
- `postgres-logs.txt`

Stress telemetry also samples runtime health, hot-path timings, async command queue stats, runtime DB pool stats, engine health, and Docker container stats into `*-telemetry.ndjson`.

Captured-ack stress reports also attach `commandAccounting` when the runtime exposes `/internal/commands/accounting`. The accounting block records the run-scoped pre/post snapshots, accepted delta, completed/failed terminal delta, active queue depth after the step, stale processing count, completed rps, and accepted-command accounting gap. `make dev-stress-captured-ack` sets `DEV_STRESS_FAIL_ON_ACCOUNTING_GAP=1` by default.

Tune diagnostics capture knobs (optional):
- `DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1`
- `DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING=1`
- `DEV_STRESS_FAIL_ON_ACCOUNTING_GAP=0|1`
- `DEV_STRESS_RUN_ID=<stable-run-id>`
- `DEV_STRESS_RUN_KIND=stress`
- `DEV_STRESS_SCENARIO_ID=<mode-or-scenario>`
- `DEV_STRESS_DB_SERVICE=postgres`
- `DEV_STRESS_DB_USER=reef`
- `DEV_STRESS_DB_NAME=reef`
- `DEV_STRESS_DB_SCHEMAS=runtime,boundary,command_log`
- `DEV_STRESS_DB_LOG_SINCE=30m`
- `DEV_STRESS_RATE_SCHEDULE=drop|precise` controls load-tester rate scheduling (`drop` is the default; `precise` is useful for capacity sweeps with larger worker counts)

Raw intake benchmarks accept matching run metadata:
- `DEV_INTAKE_RUN_ID=<stable-run-id>`
- `DEV_INTAKE_RUN_KIND=intake-bench`
- `DEV_INTAKE_SCENARIO_ID=raw-intake`
- `DEV_INTAKE_CAPTURE_COMMAND_ACCOUNTING=1` attaches command accounting and drain-rate data to the JSON report.
- `DEV_INTAKE_COMMAND_DRAIN_WAIT_MS=30000` controls how long the runner waits for active commands to drain after load stops.
- `DEV_INTAKE_COMMAND_DRAIN_POLL_MS=1000` controls drain polling frequency.
- `DEV_INTAKE_FAIL_ON_ACCOUNTING_GAP=1` fails the run if the final accounting snapshot reports a gap.

Run replay-pack drift validation against baseline scenario:

```bash
make dev-replay
```

Run matching-engine benchmark baseline:

```bash
make bench-matching-engine
```

Run matching-engine benchmark guardrail check (CI-equivalent):

```bash
make bench-matching-engine-check
```

Run the throughput campaign (quality + capacity lanes with cap summary):

```bash
make dev-throughput-campaign
```

Run campaign with optional intentional-trip abuse lane:

```bash
DEV_CAMPAIGN_INCLUDE_ABUSE_TRIP=1 \
DEV_CAMPAIGN_ABUSE_TRIP_RATES=1200 \
DEV_CAMPAIGN_ABUSE_TRIP_WORKERS=128 \
make dev-throughput-campaign
```

Optional guardrails for intentional-trip lane (enabled by default):

- `DEV_CAMPAIGN_ENFORCE_ABUSE_TRIP_GUARDRAIL=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_TRIPS=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_BLOCKS=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_ABUSE_BLOCKED_FAIL_PCT=1`

Run campaign with an automatic clean reset first (recommended for fair high-rate comparisons):

```bash
DEV_CAMPAIGN_RESET_STACK=1 make dev-throughput-campaign
```

Compare two throughput campaign summaries (baseline vs candidate):

```bash
DEV_CAMPAIGN_BASELINE_SUMMARY=/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.json \
DEV_CAMPAIGN_CANDIDATE_SUMMARY=/tmp/reef-throughput-campaign/throughput-campaign-summary.json \
make dev-throughput-compare
```

Run ad hoc simulator load against active dev env:

```bash
make dev-sim ARGS="--duration 30s --workers 8 --rate 100 --mode strict-lifecycle --pretty-summary"
```

Simulator mutating traffic uses `/api/v1` routes by default (idempotency + client headers enabled):
- `--use-api-v1=true` (default)
- `--client-id-prefix=sim-client` (default)
- `DEV_SIM_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack` recreates `platform-runtime` with that processing mode before running simulator traffic
- `DEV_SIM_COMMAND_LOG_MODE=postgres|inmemory|disabled` overrides simulator-triggered command-log mode; captured simulator modes default this to `postgres`

Optional abuse-breaker guardrail for `/api/v1` writes:
- `EXTERNAL_API_ABUSE_BREAKER_MODE=off|reject-rate` (default `off`)
- `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS` (default `50`)
- `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS` (default `30`)
- `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS` (default `60`)
- `EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES` (default `INVALID_STATE,NOT_FOUND,REFERENCE_DATA_ERROR,VALIDATION_ERROR`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTES` (default `/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTE_POLICIES` (optional `route:maxRejects/windowSeconds/blockSeconds` list)
- `EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY=true|false` (default `false`)

Example enablement:

```bash
EXTERNAL_API_ABUSE_BREAKER_MODE=reject-rate \
EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS=25 \
EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS=30 \
EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS=90 \
make dev-up
```

Breaker telemetry snapshot:

```bash
curl -s http://127.0.0.1:8080/internal/boundary/abuse/stats
```

Disable boundary route usage only for legacy comparison/debug:

```bash
make dev-sim ARGS="--duration 30s --workers 8 --rate 100 --mode capacity-baseline --use-api-v1=false --pretty-summary"
```

Raw accepted-command intake benchmark:

```bash
DEV_INTAKE_DURATION=30s \
DEV_INTAKE_WORKERS=256 \
DEV_INTAKE_RATE=10000 \
DEV_INTAKE_RATE_SCHEDULE=precise \
DEV_INTAKE_ACTOR_ID_PREFIX=bot \
DEV_INTAKE_ARTIFACT_DIR=/tmp/reef-intake-$(date +%Y%m%d-%H%M%S) \
DEV_INTAKE_REPORT_OUT=/tmp/reef-intake-bench.json \
make dev-intake-bench JS_RUNTIME=node
```

Use this before architecture changes when the goal is to separate platform-runtime `/api/v1/orders/submit` intake capacity from simulator strategy/lifecycle overhead.

`dev-intake-bench` captures pre/post DB diagnostics by default and embeds a `dbDiagnostics` object into the JSON report. It also writes raw snapshots under `*-db-diagnostics-workers-<workers>-rate-<rate>/`. Disable this with `DEV_INTAKE_CAPTURE_DB_DIAGNOSTICS=0`, or adjust schemas with `DEV_INTAKE_DB_SCHEMAS=runtime,boundary,command_log`.

When command accounting is enabled, the report also includes `commandAccounting` with before/after/drained snapshots, accepted rps, completed-during-load rps, active backlog after load, final active depth, final accounting gap, drain seconds, post-load drained count, and post-load drain rps. This is the preferred local gate for captured-ack throughput work because accepted rps alone does not prove the worker queue can drain.

## Command-log lifecycle

Prune terminal command-log history with a dry-run-first dev tool:

```bash
make dev-command-log-prune
```

The default mode reports how many completed/failed command records are eligible for pruning but does not delete rows. It never selects active `command_work_queue` rows. Apply pruning explicitly:

```bash
DEV_COMMAND_LOG_PRUNE_APPLY=1 \
DEV_COMMAND_LOG_PRUNE_OLDER_THAN=24h \
make dev-command-log-prune
```

Useful knobs:
- `DEV_COMMAND_LOG_PRUNE_OLDER_THAN=24h` accepts `ms`, `s`, `m`, `h`, or `d`
- `DEV_COMMAND_LOG_PRUNE_BATCH_SIZE=50000`
- `DEV_COMMAND_LOG_PRUNE_MAX_BATCHES=100`
- `DEV_COMMAND_LOG_PRUNE_VACUUM=1`
- `DEV_COMMAND_LOG_PRUNE_CAPTURE_DB_DIAGNOSTICS=1`

Vacuum runs after applied pruning using `PARALLEL 0` to reduce Docker shared-memory pressure. If vacuum still fails locally, pruning remains applied and the report records the vacuum error; rerun later with more Docker shared memory or `DEV_COMMAND_LOG_PRUNE_VACUUM=0`.

Protect a named run/session before pruning by adding a retention pin. Intake and load-test idempotency keys start with a session prefix, so `idempotency_prefix` is the usual selector:

```bash
DEV_COMMAND_LOG_PIN_ACTION=upsert \
DEV_COMMAND_LOG_PIN_SELECTOR_TYPE=idempotency_prefix \
DEV_COMMAND_LOG_PIN_SELECTOR_VALUE=intake-1782954356175044000 \
DEV_COMMAND_LOG_PIN_REASON="keep post-prune benchmark run" \
make dev-command-log-pin
```

List pins:

```bash
make dev-command-log-pin
```

Delete a pin:

```bash
DEV_COMMAND_LOG_PIN_ACTION=delete \
DEV_COMMAND_LOG_PIN_SELECTOR_TYPE=idempotency_prefix \
DEV_COMMAND_LOG_PIN_SELECTOR_VALUE=intake-1782954356175044000 \
make dev-command-log-pin
```

Supported selectors are `command_id`, `idempotency_prefix`, `trace_id`, `correlation_id`, and `client_id`. Prune excludes pinned commands in addition to active queue rows.

For loaded local benchmark databases, use `DEV_COMMAND_LOG_PRUNE_OLDER_THAN=0s` only when all unpinned terminal command history can be discarded. Export important audit history or add retention pins before pruning.

30-minute fixed-load soak (clean reset recommended first):

```bash
DEV_STRESS_DURATION=30m \
DEV_STRESS_MODE=capacity-baseline \
DEV_STRESS_PROFILE=capacity-heavy \
DEV_STRESS_RATES=2500 \
DEV_STRESS_SWEEP_WORKERS=128 \
DEV_STRESS_TRACE_CHECK_LIMIT=500 \
DEV_STRESS_TELEMETRY_INTERVAL_MS=1000 \
DEV_STRESS_MIN_SUCCESS_RATE_PCT=0 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-soak-30m-$(date +%Y%m%d-%H%M%S) \
DEV_STRESS_REPORT_OUT=/tmp/reef-soak-30m.json \
make dev-stress JS_RUNTIME=node
```

10-minute investigative soak with DB diagnostics (fast root-cause pass before long soak):

```bash
DEV_STRESS_DURATION=10m \
DEV_STRESS_MODE=capacity-baseline \
DEV_STRESS_PROFILE=capacity-heavy \
DEV_STRESS_RATES=2500 \
DEV_STRESS_SWEEP_WORKERS=128 \
DEV_STRESS_TRACE_CHECK_LIMIT=500 \
DEV_STRESS_TELEMETRY_INTERVAL_MS=1000 \
DEV_STRESS_MIN_SUCCESS_RATE_PCT=0 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-soak-diagnostics-$(date +%Y%m%d-%H%M%S) \
DEV_STRESS_REPORT_OUT=/tmp/reef-soak-diagnostics.json \
make dev-stress-diagnostics JS_RUNTIME=node
```

## Admin CLI To Dev Env

Admin operations against the active runtime API:

```bash
make dev-admin CMD="instrument-upsert AAPL AAPL"
make dev-admin CMD="participant-upsert participant-1 'Participant 1'"
make dev-admin CMD="account-upsert account-1 participant-1"
make dev-admin CMD="traces trace-1-1"
```

Reports are written to `/tmp` as:
- `/tmp/reef-load-report-dev-stress-rate-100.json`
- `/tmp/reef-load-report-dev-stress-rate-200.json`
- `/tmp/reef-load-report-dev-stress-rate-300.json`
- `/tmp/reef-load-report-dev-stress-rate-400.json`

Additional stress artifacts:
- `/tmp/reef-load-report-dev-stress-telemetry.ndjson`
- `/tmp/reef-load-report-dev-stress-recommendation.json`
- `/tmp/reef-load-report-dev-stress-kpi.json`
- `/tmp/reef-load-report-dev-stress-kpi.md`

## Environment contract

Compose sets:
- runtime persistence: `RUNTIME_PERSISTENCE=postgres`
- DB bootstrap mode: `RUNTIME_DB_BOOTSTRAP_MODE=validate` (`compat` remains a local repair fallback)
- runtime DB JDBC: `RUNTIME_POSTGRES_JDBC_URL` (`currentSchema=runtime` remains configured, but runtime storage uses explicit `runtime.*` and `auth.*` names)
- boundary idempotency persistence: `EXTERNAL_API_IDEMPOTENCY_STORE=postgres`
- boundary command capture persistence: `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres`
- optional append-only command-log capture: `EXTERNAL_API_COMMAND_LOG_MODE=disabled|postgres|inmemory` (default `disabled`). Postgres command-log mode stores immutable intake rows in `command_log.commands`, active worker state in `command_log.command_work_queue`, and terminal status/responses in `command_log.command_results`.
- command processing mode: `EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack` (default `sync-result`; captured modes require command-log capture)
- async command worker: `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=false|true` (default `false`; when `true` with `captured-ack`, queued command-log records are processed in the background)
- async command worker tuning: `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS`, `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE`, `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS`, and `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS`
- async command worker lease: claimed `PROCESSING` rows are reclaimable after `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS`; this prevents runtime restarts from permanently stranding in-flight commands.
- async command worker stats: `GET /internal/commands/async/stats` returns worker settings, active queue status counts from `command_log.command_work_queue`, and async claim/complete/fail counters. Postgres mode does not count historical terminal results on this hot probe.
- DB pool stats: `GET /internal/perf/db-pools` returns Hikari active/idle/total/waiter counts for runtime-managed pools.
- legacy/internal mutation routes: `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true` in local compose; POSTs to `/orders/*` and `/reference/*` must include `X-Reef-Internal-Route: true`
- boundary DB JDBC: `RUNTIME_DB_URL` (`currentSchema=boundary` remains configured, but boundary storage uses explicit `boundary.*` names)

Postgres init creates domain schemas:
- `runtime`
- `auth`
- `admin`
- `boundary`

Runtime, auth, and boundary persistence validates migrated schema objects by default in Docker/local startup. Set `RUNTIME_DB_BOOTSTRAP_MODE=compat` only for local repair/debug if a migration path needs to be bypassed temporarily.

`make dev-up`, `make dev-reset`, and `make dev-db-migrate` apply SQL files under `scripts/dev/db/migrations/` in deterministic domain order and record checksums in `public.reef_schema_migrations`. Use `$(JS_RUNTIME) scripts/dev/db/migrate.mjs --dry-run` to validate order/checksums without touching Docker.

Schema-placement verification:
- `PostgresSchemaMigrationIntegrationTest` is opt-in.
- run it with `RUNTIME_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://localhost:5432/reef`, `RUNTIME_POSTGRES_USER_TEST=reef`, and `RUNTIME_POSTGRES_PASSWORD_TEST=reef` after `make dev-up` or `make dev-db-migrate`.

`.env` support:
- all `scripts/dev/*.mjs` load `.env` and `.env.local` automatically
- explicit shell environment variables still override file-based values

Related:
- [DB split-readiness guardrails](./DB_SPLIT_READINESS.md)
- [Observability dev profile runbook](./OBSERVABILITY_DEV_PROFILE.md)
- [Simulation + app KPI matrix](./SIMULATION_KPIS.md)
