# DigitalOcean Benchmark Harness

This OpenTofu stack creates one short-lived DigitalOcean Droplet and a restrictive SSH firewall for Reef benchmark runs.

Default shape:

- region: `sfo2`
- size: `c-8`
- image: `ubuntu-24-04-x64`
- SSH only from configured CIDRs
- Docker, Docker Compose, Node.js, Go, Make, jq, rsync, and basic host diagnostics installed by cloud-init

Use `scripts/dev/do-benchmark-host.sh` from the repository root. The first
harness uses local OpenTofu state intentionally; do not commit generated state
files.

This is bridge infrastructure. It provisions disposable DigitalOcean compute
and starts a root local Compose benchmark profile on that host. The default
profile is the historical `stream-ack` path; set
`REEF_DO_BENCHMARK_PROFILE=materializer` to run the direct-stream plus
venue-event-materializer path used by the current durable-canonical local
stress gates. The target simulation run plane is still
`infra/simulation-runner/`, which should eventually own its own OpenTofu and
server Compose bundle.

For budget-safe simulation runs that should fetch artifacts, optionally push
them to the always-on core, and destroy the worker by default, prefer
`make simulation-run`. This lower-level harness remains the provider-specific
primitive used by that wrapper.

`REEF_DO_IMAGE_MODE=dockerhub` pulls the published runtime/matching images and
sets `DEV_COMPOSE_BUILD=0`. `REEF_DO_IMAGE_MODE=source` keeps the historical
source-sync/build behavior.

Benchmark profiles:

| Profile | Default rates | Default workers | Default duration | Report gates |
|---|---:|---:|---:|---|
| `stream-ack` | `2500,5000` | `256` | `30s` | API accepted rate, stream-ack worker completion, projector health, telemetry. |
| `materializer` | `10000` | `384` | `60s` | API accepted rate, direct-stream ack gap `0`, durable canonical materialization gap `0`, telemetry. |

Goal-driven sizing is opt-in. Without a goal or target, the table above remains
the default. Use `plan-goal` before provisioning to see the resolved DO size,
rates, worker count, duration, and report gates:

```bash
REEF_DO_BENCHMARK_PROFILE=materializer \
REEF_DO_BENCHMARK_GOAL=latency-knee \
scripts/dev/do-benchmark-host.sh plan-goal

REEF_DO_BENCHMARK_PROFILE=materializer \
REEF_DO_BENCHMARK_GOAL=sustain \
REEF_DO_TARGET_ACCEPTED_RPS=10000 \
REEF_DO_TARGET_P95_MS=100 \
REEF_DO_TARGET_P99_MS=200 \
scripts/dev/do-benchmark-host.sh plan-goal
```

Goal modes:

| Goal | Behavior |
|---|---|
| `fixed` | Preserve profile defaults unless explicit `REEF_DO_STRESS_*` or `REEF_DO_SIZE` overrides are set. |
| `latency-knee` | Sweep around the target accepted rps to find where tail latency starts degrading. |
| `sustain` | Run at the target accepted rps and set default report min-rps gates to `90%` of target. |
| `ceiling` | Sweep below and above the target to map overload behavior. |

Current sizing heuristics select `c-8` up to `5k` target accepted rps, `c-16`
up to `10k`, and `c-32` above that. These are conservative starting points from
the July 2026 materializer evidence: c-8 kept the aligned materializer path
clean and durable around `5.4k` completed rps, but did not sustain a `10k`
scheduled run. Override `REEF_DO_SIZE`, `REEF_DO_STRESS_RATES`,
`REEF_DO_STRESS_WORKERS`, or `REEF_DO_STRESS_DURATION` when a specific
experiment needs a fixed shape.

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

Default report gates validate measured stress reports, expected rates, no unexpected `5xx`, no unallowed failures, profile-specific worker/direct/materializer health, and telemetry probes. Set `REEF_DO_MAX_P95_MS` or `REEF_DO_MAX_P99_MS` directly, or set `REEF_DO_TARGET_P95_MS` / `REEF_DO_TARGET_P99_MS` when using goal mode, to make tail-latency targets fail the report check. The check prints a normalized evidence summary for attempted, accepted, direct-acked, materialized, projected, lag, p95, and p99, and writes `do-benchmark-evidence-summary.json` into the fetched artifact directory. Embedded load-tester trace checks are diagnostic for submit-only stream-ack stress runs because not every accepted submit produces a projected runtime event; set `REEF_DO_REQUIRE_TRACE_CHECKS=1` when running a profile where every sampled command is expected to have trace events.

Optional hardening gates:

- `REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS=<count>` fails materializer
  reports when fewer direct-stream partitions carry accepted work.
- `REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW=<ratio>` fails materializer
  reports when the hottest active partition has more than `<ratio>` times the
  accepted work of the lowest active partition.

These gates are intentionally opt-in. The July 2026 c-8 latency-knee sweep was
durable and clean through `6.49k` accepted/materialized rps, but still showed
15/16 active direct-stream partitions and roughly `4x` positive-partition skew.
Use the gates when the goal is partition-spread hardening, not just throughput
or latency proof.
