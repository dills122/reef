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
platform-worker-2
platform-worker-3
platform-projector-0
platform-projector-1
platform-projector-2
platform-projector-3
matching-engine
nats
postgres
boundary-postgres
projection-postgres
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

Do not keep rerunning `7500/384` at the same shape. The next gate is `2000 completed/sec` sustained for a minimum `5m` soak, with accepted throughput close to completed throughput, bounded worker lag, and projection lag either bounded or explicitly non-gating in the test mode. Once `2000/sec` is stable and boring, the promotion ladder is `5000/sec`, then `7500/sec`, then larger ceiling probes.

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
7. Run venue-core `2000 completed/sec` target soak for at least `5m`.
8. Only after `2000/sec` is stable, run `5000 completed/sec` target soak for at least `5m`.
9. Only after `5000/sec` is stable, return to `7500 completed/sec` and larger ceiling probes.
10. Fetch stress reports, telemetry, logs, and selected DB/NATS diagnostics.
11. Destroy the Droplet unless we are actively iterating.

## Harness Validation Gates

The host-control script should fail the run, fetch artifacts, and keep or destroy the Droplet according to the requested command when any required gate fails.

Required gates for `run` and `run-destroy`:

- `make dev-smoke` passes before measured stress.
- Every measured report exists and has parseable JSON.
- The active promotion-tier reports `100%` success, except intentional pre-acceptance `429` backpressure when a backpressure scenario is explicitly requested.
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

Minimum for each promotion tier:

- The tier holds its target completed throughput for at least `5m`.
- `2000 completed/sec` is the first required stable target; after that, promote to `5000/sec`, then `7500/sec`.
- Accepted throughput is within `5-10%` of worker completed throughput unless the difference is explained by intentional pre-acceptance backpressure.
- The tier has `100%` success or only intentional pre-acceptance `429` backpressure.
- A run that barely holds `2000/sec` with saturated CPU/IO, rapidly growing lag, poor post-run drain, or no credible path to `5000/sec` is a failed capacity result even if the process stays alive for `5m`.
- No unexpected `5xx`.
- Worker `failed=0` and `ackFailed=0`.
- Accepted commands are either completed/projected during the window or have visible lag that drains afterward.
- DB pool waiters stay at `0` or are clearly isolated to a known pool with an explanation.
- `202` semantics remain unchanged.
- Stress artifacts are fetched locally before destroying the Droplet.
- Embedded load-tester trace checks are diagnostic by default for submit-only stream-ack stress runs. Use `REEF_DO_REQUIRE_TRACE_CHECKS=1` only with profiles where every sampled command is expected to have projected trace events.

Healthy promotion target:

- the current tier sustains the target completed/sec rate for `5m`
- the observed bottleneck has enough headroom that the next tier looks like tuning/provisioning, not another architecture phase
- p95 roughly under `150ms`
- worker completed throughput materially close to accepted throughput, or clear evidence of the next limiting subsystem
- projection lag visible and bounded, not silent

## Next Ablation Sequence

Run these before another broad high-rate soak:

1. Canonical-only venue-core run:
   - set `REEF_DO_DRAIN_BACKPRESSURE_POLICY=venue-core`
   - use `2000 completed/sec` as the first required stable `5m` soak target; smaller probes are allowed only as diagnostics
   - success means accepted is within `5-10%` of worker completed, worker lag is bounded, failures and ack failures are `0`, and post-run drain is clean

2. Projector catch-up run:
   - preload or retain a fixed canonical backlog
   - stop API/worker intake
   - measure projection work items/sec, projection rows/sec, projection DB CPU, WAL bytes/sec, lag drain rate, and top SQL where available

3. Hot-partition versus even-distribution run:
   - compare a few hot instruments against evenly distributed instruments
   - report commands/completions/pending by partition, top instruments, top bots, and cancel/modify partition routing versus original submit partition where available

Scale worker and projector process counts as part of a batch fix only when the failed evidence already shows under-provisioned drain shape and clean worker correctness counters. More consumers can still make canonical or projection Postgres hotter if write amplification remains the limiter, so the next run must preserve DB/WAL/lag diagnostics and actual delivered-RPS gates.

## Capacity Headroom Rule

Do not optimize for just-barely-enough capacity. If the active target is `2000 completed/sec`, it is acceptable for a subsystem to handle `5000-6000/sec` when the extra cost and complexity are reasonable. The goal is to make each promotion tier boring, not fragile. Barely stable at `2000/sec` is not a success state for this track.

Headroom is healthy when:

- it gives roughly `2-3x` the current target capacity
- it avoids constant retuning between `2000`, `5000`, and `7500`
- it does not weaken `202` semantics, ordering, replay, audit, or idempotency
- it does not create a major cost jump just to hide avoidable write amplification

Avoid brute-force scaling when it is close to `10x` cost/complexity for a small gain, or when it hides rows/command, WAL bytes/command, commits/command, projection write amplification, or hot-partition behavior that should be fixed.

Avoid a micro-tuning mindset for this phase. Prefer changes that remove a real bottleneck class or add practical headroom across the promotion ladder over small parameter nudges that only make the current `2000/sec` run pass by a narrow margin.

## Post-Ablation Fix Order

Use the ablation results to choose implementation work in this order:

1. Projection write reduction:
   - coalesce repeated aggregate updates inside a projector batch
   - write current state once per batch where possible
   - move nonessential timeline/search/report conveniences to slower rebuildable jobs
   - reduce hot projection indexes
   - use staging/merge or unlogged rebuildable caches only where correctness allows

2. Canonical write collapse:
   - keep Postgres as the authoritative canonical outcome store for now
   - reduce canonical rows/command, commits/command, WAL bytes/command, and hot indexes
   - evaluate compact command/event batch records that preserve partition ordering, replay, checksums, idempotency, and command lookup

3. Hot-lane and worker batching:
   - treat hot partitions as either routing bugs or legitimate hot-book limits
   - tune fetch batch, ack-pending, and worker DB batch size together
   - move toward batch or long-lived stream interaction with engine shards only after persistence metrics show engine overhead is visible

4. Physical resource split:
   - split load generation first
   - split canonical Postgres next if canonical commit/WAL remains hot after write reduction
   - split projection Postgres if freshness matters and projection DB remains hot
   - split NATS only if publish/consumer metrics show JetStream pressure

If compact canonical Postgres append still caps completed throughput around `1k-2k/sec`, evaluate a separate architecture decision for a retained JetStream canonical event stream with Postgres as async projection/query storage. Do not make that pivot implicitly during benchmark tuning.

## Current Fix Batch Before Next Soak

The next DO soak should not rerun the failed shape. This branch applies a batch fix across the substantiated failure spots from the `7500/384/5m` run:

- API publish-marker pressure: `STREAM_ACK_MARK_PUBLISHED_MODE=worker` removes the duplicate API-side post-publish DB update from the throughput profile while preserving `202` after JetStream publish ack.
- Worker publish repair: workers mark intake rows published in one batch before acking deliveries instead of one DB update per command.
- Canonical write amplification: stream-ack throughput defaults keep full submit outcomes in `canonical_command_results.result_payload` but stop duplicating every lifecycle event into `canonical_venue_events` rows.
- Hot canonical indexes: stream-ack throughput defaults avoid broader query indexes on canonical append tables; projection still keeps the partition/sequence index it needs.
- Drain headroom: worker batch, ack wait, max ack-pending, projector batch, and projector poll defaults are raised together to reduce commits and avoid tiny in-flight ceilings.
- Deploy shape: the default stream-ack stack now uses `64` partitions, `4` worker processes, and `4` projector processes with explicit non-overlapping partition ownership.
- Venue-core intake gate: the default stream-ack and DO profiles use `STREAM_ACK_DRAIN_BACKPRESSURE_POLICY=venue-core`, and venue-core no longer samples projection status for admission control. Projection lag stays visible but does not define canonical venue intake capacity.
- Benchmark truth gate: DO report validation now fails when actual attempted or accepted throughput is below `REEF_DO_MIN_ATTEMPTED_RPS` / `REEF_DO_MIN_ACCEPTED_RPS`, defaulting to `2000` for the current milestone.
- Evidence: stress reports include worker batch publish-repair timing so the next run shows whether this path remains hot.

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
