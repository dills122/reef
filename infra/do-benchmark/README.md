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
stress gates, or `REEF_DO_BENCHMARK_PROFILE=materializer-projection` to add
the projection/read-model freshness workload and gates. The target simulation
run plane is still
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
| `materializer-projection` | `2500` | `256` | `60s` | Materializer gates plus projector throughput, materialized/projected gap `0`, projector lag `0`, and projector health. |

The materializer profiles run the correctness smoke first, then remove the
smoke Compose stack and volumes before starting measured stress. This keeps the
benchmark's Kafka topics, ownership leases, databases, and counters isolated
from the preflight proof.

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

## Named Materializer 10k Gate

Use the wrapper below for the current Redpanda direct-stream plus
venue-event-materializer promotion gate. It pins the known-good c-16 shape,
10k target, 1024 load workers, SLO gates, repeated samples, and partition-spread
checks so operators do not need to hand-rebuild the environment block.

```bash
make do-materializer-10k-gate

REEF_DO_CONFIRM_DESTROYABLE=1 \
make do-materializer-10k-gate ARGS=run-destroy
```

Gate tiers:

| Tier | Command | Duration | Samples | Use |
|---|---|---:|---:|---|
| `short` | `make do-materializer-10k-gate ARGS=run-destroy` | `60s` | `3` | Default promotion confirmation. |
| `soak-5m` | `REEF_DO_MATERIALIZER_10K_GATE_TIER=soak-5m make do-materializer-10k-gate ARGS=run-destroy` | `5m` | `2` | First longer remote soak. |
| `soak-15m` | `REEF_DO_MATERIALIZER_10K_GATE_TIER=soak-15m make do-materializer-10k-gate ARGS=run-destroy` | `15m` | `1` | Only after `soak-5m` is clean. |

Default pass gates:

- attempted rps >= `9900`
- accepted rps >= `9900`
- p95 <= `100ms`
- p99 <= `200ms`
- direct-stream active partitions >= `16`
- direct-stream partition skew <= `4`
- DB WAL/activity/settings/`pg_stat_io` diagnostics present
- accepted/direct-acked/materialized gaps = `0`

## Named Projection Freshness Gate

Use this wrapper when local work should not run the heavier projection/read-model
freshness load. It uses the same disposable DO harness but switches to the
`materializer-projection` profile, enables venue-event-batch projection,
order-lifecycle projection, and market-data projection, and gates the fetched
reports on projection freshness. The wrapper pins four projector instances
across the active direct-stream partitions `0-15` as `0-3`, `4-7`, `8-11`, and
`12-15`; using the generic `0-63` projector defaults leaves only one projector
owning live work in the current direct-materializer profile.

```bash
make do-projection-freshness-gate

REEF_DO_CONFIRM_DESTROYABLE=1 \
make do-projection-freshness-gate ARGS=run-destroy
```

Gate tiers:

| Tier | Command | Duration | Samples | Use |
|---|---|---:|---:|---|
| `short` | `make do-projection-freshness-gate ARGS=run-destroy` | `60s` | `1` | Default remote freshness confirmation. |
| `soak-5m` | `REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=soak-5m make do-projection-freshness-gate ARGS=run-destroy` | `5m` | `1` | First longer remote freshness soak. |
| `5k-soak-5m` | `REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=5k-soak-5m make do-projection-freshness-gate ARGS=run-destroy` | `5m` | `1` | Named 5k full-projection pressure/freshness gate; run only after the lower soak is clean. |
| `soak-15m` | `REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=soak-15m make do-projection-freshness-gate ARGS=run-destroy` | `15m` | `1` | Only after `soak-5m` is clean. |

Default pass gates:

- attempted rps >= `2400`
- accepted rps >= `2400`
- projected work items/sec >= `2400`
- direct-stream active partitions >= `16`
- direct-stream partition skew <= `4`
- DB WAL/activity/settings/`pg_stat_io` diagnostics present
- accepted/direct-acked/materialized/projected gaps = `0`
- projector failed delta = `0`
- final projector lag = `0`
- projection-postgres deadlocks = `0`

Default report gates validate measured stress reports, expected rates, no unexpected `5xx`, no unallowed failures, profile-specific worker/direct/materializer health, and telemetry probes. Set `REEF_DO_MAX_P95_MS` or `REEF_DO_MAX_P99_MS` directly, or set `REEF_DO_TARGET_P95_MS` / `REEF_DO_TARGET_P99_MS` when using goal mode, to make tail-latency targets fail the report check. The check prints a normalized evidence summary for attempted, accepted, direct-acked, materialized, projected, lag, p95, and p99, and writes `do-benchmark-evidence-summary.json` into the fetched artifact directory. Embedded load-tester trace checks are diagnostic for submit-only stream-ack stress runs because not every accepted submit produces a projected runtime event; set `REEF_DO_REQUIRE_TRACE_CHECKS=1` when running a profile where every sampled command is expected to have trace events.

Optional sustained downstream freshness gate (postprocessing only):

```bash
REEF_DO_REPORT_PROFILE=materializer-projection \
REEF_DO_REQUIRED_RATES=5000 \
REEF_DO_REQUIRE_SUSTAINED_DOWNSTREAM_FRESHNESS=1 \
node scripts/dev/do-benchmark-check.mjs /path/to/fetched/artifacts
```

The same flag is displayed by host `plan-goal` and forwarded by `check`/run
report validation. It defaults off and does not change provisioning, runner
settings, or the Task 1 instrumentation-overhead A/B comparison. Enable it for
sustained freshness acceptance after measurement authority is established.

Each complete command-weighted `sourceToCanonical`, `sourceToLifecycle`, and
`sourceToMarketData` observation bound must satisfy fixed p95 ≤5000 ms,
p99 ≤10000 ms, and maximum ≤30000 ms. No argument or environment setting
can relax these limits. The gate also requires exact authority labels and all
upstream/downstream authority checks, accepted-count reconciliation, complete
residence counts, valid ordered percentiles, ≥99% scheduled-demand completion
with zero drops, explicit empty final dirty queues, idle maintainers, and zero
canonical lag. Planned `config.Duration` (nanoseconds) and observed
`durationSeconds` must each cover at least 300 seconds; valid `startedAt` and
`finishedAt` timestamps must agree with observed duration within 2 ms (timestamp
truncation tolerance). Short or inconsistent runs fail even with fresh final
state. Target rate remains controlled by existing rate/minimum-throughput gates.
Current baseline requires exactly one enabled lifecycle maintainer and one enabled
market maintainer; a different topology requires an explicit validation plan. Missing/null/malformed evidence fails. Results appear under
`sustainedDownstreamFreshness` in `do-benchmark-evidence-summary.json`.
Market status `sourceProjectionName` must also match actual canonical projector
`projectionName` and both downstream covering markers' source names. Standalone
`venue-event-materializer-stress.mjs` preserves an explicit canonical name; absent
one, it uses Compose's existing `runtime-normalized-submit` namespace and defaults
an unset market source to that name. Full-projection preflight rejects explicit
mismatches, including per-instance lifecycle/market maintainers. It never renames
existing watermark namespaces. Standard `do-benchmark-host.sh` already aligns both
names; no host/runtime default change is needed.

Prior standalone/custom profiles could combine canonical `runtime-normalized-submit`
with market source `runtime-normalized-venue-outcomes`. Captured local A/B v3
instrumented sample 1 has exactly that status mismatch while both covering markers
name `runtime-normalized-submit`; the new binding check rejects it. Earlier cohort
counts/coverage do not prove those public snapshot freshness metadata were valid.
Correct profile binding and collect new read evidence; do not relabel old reports.

These conservative sampled observation bounds are separate from HTTP latency;
this gate alone does not prove capacity headroom or crash/rebuild equivalence.

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

Benchmark evidence must live in persistent host storage (for example,
`/home/reefbench/benchmarks/`), including private raw reports and effective config.
Do not use `/tmp` for artifacts needed after reboot or resize. Before either,
verify persistent copies and hashes; aggregate exports do not replace raw evidence.
