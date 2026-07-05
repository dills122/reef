# Hetzner Bring-Up Notes

This file records platform-specific issues found during the first Reef Hetzner
core deployment and the repo changes that now cover them.

## Host Bootstrap

- Ubuntu 24.04 did not provide `awscli` through the default apt package set on
  the first host. `backup-dbs.sh` now falls back to `docker run amazon/aws-cli:2`
  when a host `aws` binary is unavailable.
- Cloud-init brace expansion is not a reliable way to create nested directories.
  The template now creates `/opt/reef` subdirectories with explicit `mkdir -p`
  commands.
- OpenBao in this container shape should run without requiring host `IPC_LOCK`.
  The deployment config avoids requiring that capability.

## Hetzner / OpenTofu

- Hetzner SSH keys are globally unique by public key. Reusing an existing main
  SSH key can return `SSH key not unique`. The Tofu module normalizes the public
  key material and is intended to work with importing the existing key into
  state when Hetzner already has it.
- The default deployment is internal-only: only SSH and ICMP are opened by the
  Hetzner firewall, and platform runtime binds to `127.0.0.1:8080`.

## Images

- Docker Hub images are the target path, but a fresh package setup may not be
  public yet. `make hetzner-core ARGS=build-local-images` syncs service build
  contexts to `/opt/reef-build/services`, builds `reef-*:local` images on the
  host, and writes `/opt/reef/.env` image overrides.
- The matching-engine Dockerfile must not depend on BuildKit-only platform
  arguments for host-local builds. It now defaults `TARGETARCH` to `amd64`,
  installs `git`, and allows direct module fallback through `GOPROXY`.

## Database

- Running migrations as `postgres` creates domain schemas and objects that
  `reef_app` cannot see unless grants are applied. `apply-migrations.sh` now
  grants usage, table, sequence, and function privileges after migration apply.
- `verify-runtime.sh` checks both platform health and `reef_app` visibility of
  migrated tables to catch this failure before runtime traffic.

## Simulator And Soaks

- The simulator seeds reference data through legacy setup routes before load.
  The Hetzner core deployment enables `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED`
  for the private loopback/Docker-internal runtime only. The API remains
  non-public by default.
- `run-soak.sh` records the simulator report, stdout JSON, and before/after
  Docker stats under `/opt/reef/reports/soak`.
- The first Hetzner CX33 captured-ack shape stayed healthy but did not sustain
  the requested `10k/s` target. The observed limiter was Postgres/runtime write
  pressure, not matching-engine CPU.

## First Soak Evidence

On July 5, 2026:

- `100 rps / 20s` preflight: `0` system failures, trace checks `20/20`.
- `10k/s / 3m`, `384` workers: about `920 rps` completed, `0` system
  failures, trace checks `100/100`, most scheduled requests dropped by the
  load scheduler.
- `10k/s / 3m`, `2048` workers: about `920 rps` completed, p99 about `2.58s`,
  trace checks `99/100`.

Do not promote to `15k/s / 10m` on this deployment shape until a lower gate can
complete near target rate with no trace failures and acceptable accounting
evidence.

## Stream-Ack Comparison

The initial Hetzner deployment was not using the local/DO high-throughput
profile. It ran one runtime in `sync-result` mode over HTTP. The comparison
profile adds JetStream, gRPC engine transport, stream-ack processing, four
worker roles, four projector roles, 64 partitions, worker-side publish marking,
canonical event-row duplication disabled, broader canonical query indexes
disabled, and Postgres WAL/connection tuning.

Additional July 5, 2026 evidence:

- `stream-ack`, direct Docker-network simulator, submit-only 64-instrument
  spread, `1k/s / 30s`, `256` workers: about `988 rps`, `0` failures, p99
  about `316 ms`.
- `stream-ack`, direct Docker-network simulator, submit-only 64-instrument
  spread, `2k/s / 2m`, `256` workers: about `1399 rps`, `0` failures, p99
  about `352 ms`.
- During the `2k/s` run, Postgres remained the main limiter at roughly
  `230-250%` CPU and several GiB of block I/O. API, workers, NATS, and matching
  engine were active but not saturated.

This confirms the original `10k/s` failure was partly a profile mismatch, but
the Hetzner CX33 single-Postgres shape still does not reach the first DO gate of
`2k completed/sec`. Do not run `5k`, `10k`, or `15k` promotion soaks on this
shape before either lowering write amplification further or splitting the
boundary/projection/canonical database workload the way local Compose does.
