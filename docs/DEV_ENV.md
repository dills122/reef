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

Diagnostic artifacts are written under the stress artifact root with suffix `-diagnostics`:
- `pre-meta.json`, `post-meta.json`
- `pre-pg_stat_bgwriter.csv`, `post-pg_stat_bgwriter.csv`
- `pre-pg_stat_checkpointer.csv`, `post-pg_stat_checkpointer.csv` (Postgres 17+ when available)
- `pre-table-sizes.csv`, `post-table-sizes.csv`
- `pre-table-count-estimates.csv`, `post-table-count-estimates.csv`
- `postgres-logs.txt`

Tune diagnostics capture knobs (optional):
- `DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1`
- `DEV_STRESS_DB_SERVICE=postgres`
- `DEV_STRESS_DB_USER=reef`
- `DEV_STRESS_DB_NAME=reef`
- `DEV_STRESS_DB_SCHEMA=runtime`
- `DEV_STRESS_DB_LOG_SINCE=30m`

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
- optional append-only command-log capture: `EXTERNAL_API_COMMAND_LOG_MODE=disabled|postgres|inmemory` (default `disabled`)
- command processing mode: `EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack` (default `sync-result`; captured modes require command-log capture)
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
