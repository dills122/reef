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
3. Can the stack hold `2500` and `5000` submit-only stream-ack runs with `100%` success and no unexplained accepted-command gaps?
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

## Benchmark Sequence

1. Provision the Droplet with OpenTofu.
2. Install Docker, Docker Compose plugin, Git, Make, Node.js, `jq`, `curl`, `rsync`, and basic IO/CPU tools through cloud-init.
3. Sync the current Reef checkout to the Droplet.
4. Start the deploy-shaped stream-ack stack.
5. Purge the retained local benchmark stream before a measured run, or use a run-specific stream name.
6. Run a smoke check.
7. Run `2500` submit-only stream-ack stress.
8. Run `5000` submit-only stream-ack stress.
9. Only if `5000` is healthy, run one larger exploratory probe, probably `7500` before anything bigger.
10. Fetch stress reports, telemetry, logs, and selected DB/NATS diagnostics.
11. Destroy the Droplet unless we are actively iterating.

## Evidence To Capture

For each run:

- attempted/sec
- accepted/sec
- worker completed/sec
- projected/sec
- p50/p95/p99 API latency
- response code distribution
- reject taxonomy
- trace check pass/fail
- worker failures and ack failures
- JetStream stream lag/storage utilization
- projector lag and watermark state
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

Healthy `5000` target:

- accepted throughput near or above the latest local `~3.8k/sec` point
- p95 roughly under `150ms`
- worker completed throughput materially closer to accepted throughput than the local run, or clear evidence of the new limiting subsystem
- projection lag visible and bounded, not silent

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
- `start`
- `remote-status`
- `logs`
- `fetch`
- `fetch-destroy`
- `run-destroy`
- `destroy`

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

- Region: default to `sfo3` unless there is a better target.
- First size: default to `c-8`, with `c-16` as follow-up.
- State: local OpenTofu state first, or DO Spaces S3-compatible backend from day one.
- SSH key: which public key should cloud-init install.
- Repo transfer: `rsync` local checkout first, or remote `git clone`.
- Load generator: same droplet first, second droplet later only if CPU contention is ambiguous.
- Whether to open the API port publicly for local driving, or run all stress commands over SSH on the Droplet.
