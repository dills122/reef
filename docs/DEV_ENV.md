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

Deterministic reset (down + volume wipe + rebuild + smoke):

```bash
make dev-reset
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
- runtime DB JDBC: `RUNTIME_POSTGRES_JDBC_URL` (`currentSchema=runtime`)
- boundary idempotency persistence: `EXTERNAL_API_IDEMPOTENCY_STORE=postgres`
- boundary command capture persistence: `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres`
- boundary DB JDBC: `RUNTIME_DB_URL` (`currentSchema=boundary`)

Postgres init creates domain schemas:
- `runtime`
- `auth`
- `admin`
- `boundary`

`.env` support:
- all `scripts/dev/*.mjs` load `.env` and `.env.local` automatically
- explicit shell environment variables still override file-based values

Related:
- [DB split-readiness guardrails](./DB_SPLIT_READINESS.md)
- [Observability dev profile runbook](./OBSERVABILITY_DEV_PROFILE.md)
- [Simulation + app KPI matrix](./SIMULATION_KPIS.md)
