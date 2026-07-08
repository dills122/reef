# Ephemeral Simulation Runner

Reef uses a split deployment shape for budget-sensitive hosted simulations:

- a cheap always-on core server for secrets, metadata, analytics, and durable
  report storage
- an ephemeral DigitalOcean worker for high-throughput simulation compute

The worker is created for one run, executes the simulation, sends artifacts
back, and is destroyed unless explicitly retained for debugging.

## Current Status

This directory documents the target run-plane shape. The dedicated
`infra/simulation-runner/tofu/` and
`infra/simulation-runner/server/docker-compose.yml` bundle is not built yet.

Today, `make simulation-run` calls `scripts/deploy/simulation-run.mjs`, which
wraps the older `scripts/dev/do-benchmark-host.sh` bridge harness. That bridge
provisions a disposable DigitalOcean host, syncs the checkout, starts a root
local benchmark Compose profile on the worker, collects artifacts, optionally
exports a compressed artifact bundle to R2, and destroys the worker unless it is
retained for debugging. The historical default profile is `stream-ack`; use
`REEF_DO_BENCHMARK_PROFILE=materializer` when hosted simulation testing should
mirror the current direct-stream plus venue-event-materializer path.

The bridge is useful operationally, but it is not the final hosted run-plane
layout and should not be confused with the Hetzner backbone Compose files.

## Communication Model

The first slice is local-driven:

```text
local operator
  -> creates the DO worker with OpenTofu
  -> syncs the checkout
  -> starts the simulation stack through the bridge harness
  -> runs the selected stress profile
  -> fetches reports and debug artifacts locally
  -> optionally pushes those artifacts to the always-on core
  -> destroys the worker
```

No public application ports are required on the worker. SSH is the control
channel and rsync is the artifact channel. Runtime services, brokers, and
databases stay Docker-internal on the worker.

## Always-On Core

The core server should run low-cost persistent services:

- OpenBao
- analytics/report Postgres
- report/artifact storage
- run metadata
- lightweight admin/control endpoints

The core does not run high-throughput simulations.

## Worker

The worker is a disposable DigitalOcean CPU-Optimized droplet by default.

It runs:

- platform runtime hot path
- matching engine
- broker for the selected profile
- simulator/load generator
- temporary local databases needed by the profile

The worker should not retain authoritative state after a run. Reports and logs
must be copied back before destroy.

## Running

Required:

```bash
export DIGITALOCEAN_TOKEN="..."
export REEF_DO_CONFIRM_DESTROYABLE=1
```

Optional core artifact push:

```bash
export REEF_CORE_REPORT_HOST="core.example.com"
export REEF_CORE_REPORT_USER="ops"
export REEF_CORE_REPORT_DIR="/opt/reef/reports/simulations"
```

Optional R2 export - compresses the run's artifacts and uploads them to
Cloudflare R2 directly from the worker, before fetch/destroy, reusing the same
bucket/credentials as the backbone's Postgres/OpenBao backup dumps
([D-046](../../docs/DECISIONS.md)) rather than a second object-storage vendor:

```bash
export R2_ENDPOINT="https://<account-id>.r2.cloudflarestorage.com"
export R2_BUCKET="reef-backups"
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
make simulation-run ARGS="--rate 1000 --duration 60s --workers 128 --export-to-r2"
```

This uploads `simulation-runs/<run-id>.tar.gz` to the bucket. It is opt-in
(unset by default) since not every local run has R2 credentials configured.
Shipping structured run results to the backbone's analytics service (rather
than raw compressed debug artifacts) is a separate follow-up gated on that
service existing - see D-046.

Run a short smoke:

```bash
make simulation-run ARGS="--rate 1000 --duration 60s --workers 128"
```

Run a larger profile:

```bash
make simulation-run ARGS="--run-id sim-10k-$(date -u +%Y%m%dT%H%M%SZ) --rate 10000 --duration 3m --workers 256"
```

Keep the worker after a failed run for inspection:

```bash
make simulation-run ARGS="--rate 10000 --duration 3m --workers 256 --keep-worker-on-failure"
```

Artifacts are kept locally under:

```text
reports/simulations/ephemeral-do/<run-id>/
```

When `REEF_CORE_REPORT_HOST` is set, the same bundle is pushed to:

```text
${REEF_CORE_REPORT_DIR}/<run-id>/
```

## Failure Debug Bundle

Every run attempts to fetch:

- stress JSON reports
- stress telemetry
- remote benchmark stdout/stderr log
- Docker Compose service status
- Docker stats
- Docker Compose logs tail
- host `free`, `df`, and `uptime`
- local `simulation-run-metadata.json`

By default, the worker is destroyed after artifacts are fetched. Use
`--keep-worker-on-failure` only when the debug bundle is insufficient, because
the worker keeps billing until destroyed.

## Image Strategy

Target path is Docker Hub images:

- `dills122/reef-platform-runtime`
- `dills122/reef-matching-engine`
- `dills122/reef-simulator`

The runner defaults to `--image-mode dockerhub`. In that mode, runtime and
matching engine images are pulled from Docker Hub and Compose builds are
disabled with `DEV_COMPOSE_BUILD=0`.

Use source-build fallback while package visibility is being finalized:

```bash
make simulation-run ARGS="--image-mode source --rate 1000 --duration 60s --workers 128"
```

The source mode still syncs the checkout and lets the root local Compose stack
build images on the worker.

## Cost Controls

- The default lifecycle destroys the worker after each run.
- Use `--keep-worker` only for active debugging.
- Use `scripts/dev/do-benchmark-host.sh destroy` if a worker was retained.
- Prefer short smoke runs before expensive long soaks.
