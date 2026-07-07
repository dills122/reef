# DigitalOcean Benchmark Harness

This OpenTofu stack creates one short-lived DigitalOcean Droplet and a restrictive SSH firewall for the Reef stream-ack benchmark harness.

Default shape:

- region: `sfo3`
- size: `c-8`
- image: `ubuntu-24-04-x64`
- SSH only from configured CIDRs
- Docker, Docker Compose, Node.js, Go, Make, jq, rsync, and basic host diagnostics installed by cloud-init

Use `scripts/dev/do-benchmark-host.sh` from the repository root. The first harness uses local OpenTofu state intentionally; do not commit generated state files.

For budget-safe simulation runs that should fetch artifacts, optionally push
them to the always-on core, and destroy the worker by default, prefer
`make simulation-run`. This lower-level harness remains the provider-specific
primitive used by that wrapper.

`REEF_DO_IMAGE_MODE=dockerhub` pulls the published runtime/matching images and
sets `DEV_COMPOSE_BUILD=0`. `REEF_DO_IMAGE_MODE=source` keeps the historical
source-sync/build behavior.

Required local environment:

- `DIGITALOCEAN_TOKEN` or `DO_TOKEN`
- `REEF_DO_CONFIRM_DESTROYABLE=1`
- an SSH public key at `REEF_DO_SSH_PUBLIC_KEY` or `~/.ssh/id_ed25519.pub`

Typical flow:

```bash
REEF_DO_CONFIRM_DESTROYABLE=1 scripts/dev/do-benchmark-host.sh up
scripts/dev/do-benchmark-host.sh sync
scripts/dev/do-benchmark-host.sh run
scripts/dev/do-benchmark-host.sh check
scripts/dev/do-benchmark-host.sh destroy
```

Use `run` while iterating; it provisions or reuses the stack, syncs the checkout, runs the benchmark, fetches artifacts, validates reports, and leaves resources available for inspection. Use `check` to rerun report gates against fetched artifacts without touching DigitalOcean. Use `destroy` or `fetch-destroy` only when you intentionally want to remove the billable resources.

`run-destroy` provisions the stack, syncs the checkout, runs the benchmark, fetches artifacts, validates reports, and destroys resources in one command. Keep it for final lifecycle confirmation, not for every debugging iteration.

Default report gates validate measured stress reports, expected rates, no unexpected `5xx`, no unallowed failures, worker failure counters, projector health, and telemetry probes. The check prints a normalized evidence summary for attempted, accepted, direct-acked, materialized, projected, lag, p95, and p99, and writes `do-benchmark-evidence-summary.json` into the fetched artifact directory. Embedded load-tester trace checks are diagnostic for submit-only stream-ack stress runs because not every accepted submit produces a projected runtime event; set `REEF_DO_REQUIRE_TRACE_CHECKS=1` when running a profile where every sampled command is expected to have trace events.
