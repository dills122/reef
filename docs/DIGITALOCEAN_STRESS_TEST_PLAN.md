# DigitalOcean Stress Test Plan

## Purpose

Use a single DigitalOcean Droplet to run the deploy-shaped stream-ack stack on hardware closer to the target environment than Docker Desktop. This test is for capacity discovery and bottleneck identification, not for declaring the architecture finished.

The local result justifies the move:

- API returns `202` only after JetStream durable publish acknowledgement.
- API, workers, projectors, NATS, matching engine, boundary DB, runtime DB, and projection DB run as separate containers.
- Worker ack happens after canonical commit.
- Projections are asynchronous and report lag.
- Drain-side backpressure is available without adding request-path NATS/DB calls.
- Latest clean local `5000` run accepted `127010` commands at `3816.61/sec`, p95 `85.27ms`, p99 `124.16ms`, with `0` DB pool waiters and end-window projector lag `4716`.

## Questions To Answer

1. Does the expected droplet hardware improve accepted, completed, and projected throughput versus local Docker Desktop?
2. Does the bottleneck move to CPU, disk IO/WAL, JetStream storage, runtime canonical persistence, projection persistence, or the load generator?
3. Can the stack hold `2500` and `5000` submit-only stream-ack runs with `100%` success, accepted throughput within `5-10%` of worker completed throughput, and no unexplained accepted-command gaps?
4. At `5000`, do workers and projectors lag only temporarily, and do they catch up without failures or ack failures?
5. Does drain-side backpressure stay inactive under healthy conditions and reject before publish when lag/storage thresholds are exceeded?
6. Is one droplet a reasonable first deployment shape, or do we need to split load generation or databases before the next test?

## Non-Goals

- No production hardening claim.
- No high availability design.
- No managed database decision yet.
- No multi-droplet or Kubernetes design yet.
- No public internet product exposure beyond SSH and the benchmark API port needed for controlled tests.
- No final `7500-10000 completed/sec` claim unless completed/projected throughput and replay evidence support it.

## First Test Shape

Start with one CPU-Optimized Droplet and the same Docker Compose role split used locally:

```text
platform-api
platform-worker-0
platform-worker-1
platform-projector-0
platform-projector-1
matching-engine
nats
postgres
boundary-postgres
projection-postgres
platform-ui, optional
```

Suggested first sizes:

| Profile | Droplet | Why |
| --- | --- | --- |
| baseline | `c-8` | Mirrors the Liars Dice session-runner default and gives a clean 8 vCPU / 16 GB first signal. |
| follow-up | `c-16` | Use only if `c-8` is CPU-bound or if `5000` is healthy and we want a larger ceiling probe. |

Use a single-host load generator first because it keeps setup simple and removes laptop/Docker Desktop noise. If CPU saturation is ambiguous, split load generation onto a second droplet later.

## Current DO Soak Finding

The clean `7500 rps`, `384` worker, `5m` DO soak on July 3, 2026 proved that the current single-droplet shape is not a healthy `7500` soak configuration:

- Accepted: `388260` of `867415` requests, or about `1281 accepted/sec`.
- Worker completed: `310807`, or about `1025 completed/sec`.
- Projector applied: `268782`, or about `887 projection work items/sec`.
- Rejections were protective `429`s, dominated by projector lag backpressure and then worker lag backpressure.
- Runtime Postgres and projection Postgres were both CPU-hot and write-heavy.
- Several partitions were hot while other partitions had little or no lag.

Do not keep rerunning `7500/384` at the same shape. The next target is `1500-2000 completed/sec` for `5m`, with accepted throughput close to completed throughput, bounded worker lag, and projection lag either bounded or explicitly non-gating in the test mode.

## Backpressure Policy Modes

Stream-ack drain backpressure has two explicit policies:

- `control-room-fresh`: default. Worker lag and projection lag can both reject new intake before durable acceptance. Use this when UI/read-model freshness is part of the service objective.
- `venue-core`: worker/stream/canonical health can reject new intake, but projection lag is reported without blocking command acceptance. Use this to measure canonical venue capacity separately from projection freshness.

Projection lag is still operationally important in `venue-core` mode. It is just not allowed to define the venue-core completed/sec ceiling unless it threatens storage, recovery, or another canonical safety boundary.

## Pre-IaC Defaults

Use these defaults for the first implementation unless a later decision explicitly changes them:

- Region: `sfo3`.
- First size: `c-8`; treat `c-16` as a follow-up ceiling probe, not the default.
- OpenTofu state: local state for the first benchmark harness. Add Spaces/S3-compatible remote state only when the benchmark environment becomes shared or long-lived.
- Repo transfer: `rsync` the current local checkout to the Droplet. This keeps uncommitted benchmark-harness iteration testable without pushing work-in-progress branches.
- Load generator: run on the same Droplet over SSH for the first signal. Add a second load-generator Droplet only if Docker CPU stats or host metrics show ambiguous contention.
- API exposure: do not open the API publicly by default. Run stress commands over SSH on the Droplet, and restrict the firewall to SSH from configured CIDRs.
- Cleanup: destroy is the normal end state for `run-destroy` and `fetch-destroy`.

## Benchmark Sequence

1. Provision the Droplet with OpenTofu.
2. Install Docker, Docker Compose plugin, Git, Make, Node.js, `jq`, `curl`, `rsync`, and basic IO/CPU tools through cloud-init.
3. Sync the current Reef checkout to the Droplet.
4. Start the deploy-shaped stream-ack stack.
5. Purge the retained local benchmark stream before a measured run, or use a run-specific stream name.
6. Run a smoke check.
7. Run `2500` submit-only stream-ack stress.
8. Run `5000` submit-only stream-ack stress.
9. Only if `2000` completed/sec is healthy and boring, move toward `3000`; do not return to `7500` until completed throughput, drain, and write amplification evidence support it.
10. Fetch stress reports, telemetry, logs, and selected DB/NATS diagnostics.
11. Destroy the Droplet unless we are actively iterating.

## Harness Validation Gates

The host-control script should fail the run, fetch artifacts, and keep or destroy the Droplet according to the requested command when any required gate fails.

Required gates for `run` and `run-destroy`:

- `make dev-smoke` passes before measured stress.
- Every measured report exists and has parseable JSON.
- `2500` and `5000` report `100%` success, except intentional pre-acceptance `429` backpressure when a backpressure scenario is explicitly requested.
- Unexpected `5xx` count is `0`.
- `streamAckWorkers.delta.failedDelta == 0`.
- `streamAckWorkers.delta.ackFailedDelta == 0`.
- `streamAckWorkers.delta.unsupportedDelta == 0`.
- Worker completed delta is nonzero and can be compared with accepted throughput.
- Projector stats are present, projected delta is nonzero, and final lag is reported.
- DB pool stats are captured; any nonzero waiter count must be visible in the fetched artifact bundle.
- Hot-path phase timing and stream-ack health probes are captured.
- Artifacts are fetched locally before any destroy step.

These gates should be enforced by the benchmark host script or a small report-check helper, not left as manual interpretation of console output.

## Evidence To Capture

For each run:

- attempted/sec
- accepted/sec
- worker completed commands/sec
- canonical events/sec
- projection work items/sec
- projection rows/sec where available
- rows/command
- WAL bytes/command
- commits/command
- p50/p95/p99 API latency
- response code distribution
- reject taxonomy
- trace check pass/fail
- worker failures and ack failures
- JetStream stream lag/storage utilization
- projector lag and watermark state
- partition skew: completed, pending, ack-pending, and stream lag by partition
- hot instruments/bots by partition when workload attribution is available
- DB pool waiters by pool
- hot-path phase timings
- Docker CPU/memory stats
- Postgres checkpoint/WAL/table-size diagnostics where available
- exact artifact paths

Run artifacts should be fetched into a stable local directory such as:

```text
reports/do-benchmark/<timestamp-or-run-name>/
```

## Success Criteria

Minimum for the first DO test:

- `2500` and `5000` runs have `100%` success or only intentional pre-acceptance `429` backpressure.
- No unexpected `5xx`.
- Worker `failed=0` and `ackFailed=0`.
- Accepted commands are either completed/projected during the window or have visible lag that drains afterward.
- DB pool waiters stay at `0` or are clearly isolated to a known pool with an explanation.
- `202` semantics remain unchanged.
- Stress artifacts are fetched locally before destroying the Droplet.
- Embedded load-tester trace checks are diagnostic by default for submit-only stream-ack stress runs. Use `REEF_DO_REQUIRE_TRACE_CHECKS=1` only with profiles where every sampled command is expected to have projected trace events.

Healthy `5000` target:

- accepted throughput near or above the latest local `~3.8k/sec` point
- p95 roughly under `150ms`
- worker completed throughput materially closer to accepted throughput than the local run, or clear evidence of the new limiting subsystem
- projection lag visible and bounded, not silent

## Next Ablation Sequence

Run these before another broad high-rate soak:

1. Canonical-only venue-core run:
   - set `REEF_DO_DRAIN_BACKPRESSURE_POLICY=venue-core`
   - start at `1500`, then `2000`
   - success means accepted is within `5-10%` of worker completed, worker lag is bounded, failures and ack failures are `0`, and post-run drain is clean

2. Projector catch-up run:
   - preload or retain a fixed canonical backlog
   - stop API/worker intake
   - measure projection work items/sec, projection rows/sec, projection DB CPU, WAL bytes/sec, lag drain rate, and top SQL where available

3. Hot-partition versus even-distribution run:
   - compare a few hot instruments against evenly distributed instruments
   - report commands/completions/pending by partition, top instruments, top bots, and cancel/modify partition routing versus original submit partition where available

Scale worker and projector process counts only after these measurements show the DB write path can absorb the extra drain. More consumers can make canonical or projection Postgres hotter if write amplification remains the limiter.

## OpenTofu Harness Plan

Add an intentionally small stack under something like:

```text
infra/do-benchmark/
  versions.tf
  providers.tf
  variables.tf
  main.tf
  outputs.tf
  cloud-init.yml.tftpl
  backend.hcl.example
scripts/dev/do-benchmark-host.sh
```

The first infrastructure stack should include:

- one `digitalocean_droplet`
- one `digitalocean_firewall`
- SSH restricted to configured CIDRs, defaulting to current public IPv4 `/32`
- DigitalOcean monitoring enabled
- tags like `reef`, `benchmark`, `stream-ack`
- cloud-init user, no password SSH, root disabled
- Docker and benchmark dependencies installed by cloud-init
- output for public IPv4 and SSH user

The host-control script should support:

- `up`
- `status`
- `sync`
- `run`
- `check`
- `start`
- `remote-status`
- `logs`
- `fetch`
- `fetch-destroy`
- `run-destroy`
- `destroy`

`run` should start the stack and execute the benchmark sequence without destroying the Droplet. `check` should rerun local report gates against fetched artifacts without touching DigitalOcean resources. `run-destroy` should execute the same sequence, fetch artifacts, and destroy only after fetch succeeds or after enough failure artifacts have been collected to diagnose the run. `fetch-destroy` should be available for manual cleanup after failed or interrupted runs.

## Reference Pattern From Liars Dice

The useful pattern in `/Users/dsteele/repos/liars-dice-private/lab/infra/do-runner` is:

- OpenTofu manages a session-scoped DO Droplet and firewall.
- Variables cover token, count, name prefix, region, size, image, SSH keys, allowed SSH CIDRs, and tags.
- `cloud-init.yml.tftpl` creates a non-root user, disables password SSH, installs dependencies, and runs a setup script.
- A local lifecycle script resolves the DO token from env or `.env`, auto-detects the current public IPv4 for SSH firewalling, runs OpenTofu, waits for SSH, syncs the repo with `rsync`, runs the workload remotely, fetches reports, and destroys the Droplet.
- The plan explicitly treats destroy as the normal cleanup path because DigitalOcean bills Droplets while they exist, even when powered off.

For Reef, adapt the lifecycle and security shape, but do not copy the GitHub self-hosted runner registration path. Reef needs a benchmark host, not a CI runner.

## Current External References

- DigitalOcean Droplet pricing docs: <https://docs.digitalocean.com/products/droplets/details/pricing/>
- DigitalOcean Terraform/OpenTofu droplet resource docs: <https://docs.digitalocean.com/reference/terraform/reference/resources/droplet/>
- OpenTofu S3 backend docs for optional Spaces-backed state: <https://opentofu.org/docs/language/settings/backends/s3/>

The DigitalOcean docs currently state that CPU Droplet billing begins when the Droplet is created and ends when it is destroyed; powered-off Droplets still reserve compute and are still billed. Treat this as an operational rule for benchmark cleanup.

## Decisions Needed Before Implementation

- SSH key: which public key should cloud-init install.
- Allowed SSH CIDR: auto-detect current public IPv4 `/32` by default, with an override for known fixed CIDRs.
- Cost guard: whether the lifecycle script should require an explicit `REEF_DO_CONFIRM_DESTROYABLE=1` or similar acknowledgement before creating billable resources.
