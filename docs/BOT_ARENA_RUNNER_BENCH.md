# Bot Arena Runner Bench

This spike measures the cost of grouped TypeScript-capable bot runners before
the arena orchestrator, venue API, or command lifecycle are in the loop.

The benchmark driver starts one or more runner processes. Each runner hosts
multiple workers, and each worker owns multiple synthetic bots. Bots receive
deterministic fake market snapshots, do bounded CPU work, and return proposed
limit-order actions.

Two runtime modes are available:

- `node-worker`: local baseline using Node `worker_threads`; this is the default
  so the bench can run without a Deno install.
- `deno-worker`: target TypeScript runner shape using Deno workers.

## Command

```bash
bun scripts/dev/arena-runner-bench.mjs
```

Equivalent package script:

```bash
bun run arena:runner-bench
```

Use the Deno mode explicitly when Deno is installed on `PATH`:

```bash
bun scripts/dev/arena-runner-bench.mjs --runtime=deno-worker
```

The script intentionally exits with a clear message when a selected runtime is
missing so local dev machines and future runner containers fail before producing
partial measurements.

## Useful Knobs

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=2 \
  --workers-per-runner=4 \
  --bots-per-worker=8 \
  --ticks-per-bot=250 \
  --actions-per-tick=5 \
  --work-units=1000 \
  --tick-deadline-ms=50 \
  --out=/tmp/reef-arena-runner-bench.json
```

Optional SES path:

```bash
bun scripts/dev/arena-runner-bench.mjs --runtime=node-worker --ses=true
bun scripts/dev/arena-runner-bench.mjs --runtime=deno-worker --ses=true
```

The SES mode loads `ses` through the selected runtime and evaluates each
synthetic bot in a compartment. Use it when dependencies are installed locally or
when the runner container bakes them in.

## Report Shape

The JSON report includes:

- runner process count, worker count, bot count, ticks, and proposed actions
- elapsed wall time, tick throughput, and action throughput
- startup/setup time
- aggregate tick latency and late-tick count
- synthetic output bytes
- max queue depth and stale tick drops
- sampled RSS for each runner process
- total runner-pool RSS peak approximation
- per-runner and per-worker reports

This benchmark does not submit commands to Reef. It is only meant to size the
runner pool and validate backpressure defaults before wiring the arena
orchestrator to real venue paths.

## Initial Local Evidence

These local runs used `node-worker` because Deno is not installed on this
machine. They are a baseline for grouped worker overhead, not a final decision on
the Deno runner.

Small baseline:

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=1 \
  --workers-per-runner=2 \
  --bots-per-worker=4 \
  --ticks-per-bot=100 \
  --actions-per-tick=5 \
  --work-units=1000 \
  --out=/tmp/reef-arena-runner-bench-node-small.json
```

Observed:

- 8 bots, 800 bot-ticks, 4,000 proposed actions
- 236.67 ms elapsed
- 3,380.30 bot-ticks/sec
- 16,901.51 proposed actions/sec
- 0 late ticks at a 50 ms per-bot tick deadline
- about 39.98 MiB runner RSS

Medium grouped baseline:

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=2 \
  --workers-per-runner=4 \
  --bots-per-worker=8 \
  --ticks-per-bot=250 \
  --actions-per-tick=5 \
  --work-units=1000 \
  --out=/tmp/reef-arena-runner-bench-node-medium.json
```

Observed:

- 64 bots, 16,000 bot-ticks, 80,000 proposed actions
- 353.48 ms elapsed
- 45,263.81 bot-ticks/sec
- 226,319.04 proposed actions/sec
- 0 late ticks at a 50 ms per-bot tick deadline
- about 111.41 MiB combined runner RSS

Heavier synthetic CPU baseline:

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=2 \
  --workers-per-runner=4 \
  --bots-per-worker=8 \
  --ticks-per-bot=250 \
  --actions-per-tick=5 \
  --work-units=25000 \
  --tick-deadline-ms=50 \
  --out=/tmp/reef-arena-runner-bench-node-heavy.json
```

Observed:

- 64 bots, 16,000 bot-ticks, 80,000 proposed actions
- 2,381.28 ms elapsed
- 6,719.08 bot-ticks/sec
- 33,595.39 proposed actions/sec
- 0 late ticks at a 50 ms per-bot tick deadline
- about 103.66 MiB combined runner RSS

Takeaway: grouped workers look cheap enough for Phase 1 in this synthetic
baseline. The next evidence gap is Deno worker overhead, SES compartment
overhead with dependencies installed or baked into a runner image, and then an
outer container run with CPU and memory caps.

SES synthetic grouped baseline after installing local dependencies:

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=2 \
  --workers-per-runner=4 \
  --bots-per-worker=8 \
  --ticks-per-bot=250 \
  --actions-per-tick=5 \
  --work-units=1000 \
  --ses=true \
  --out=/tmp/reef-arena-runner-bench-node-ses-medium.json
```

Observed:

- 64 bots, 16,000 bot-ticks, 80,000 proposed actions
- 460.40 ms elapsed, including 183.90 ms setup
- 34,752.60 bot-ticks/sec
- 173,762.98 proposed actions/sec
- 0 late ticks
- about 136.17 MiB combined runner RSS

SES heavier synthetic CPU baseline:

```bash
bun scripts/dev/arena-runner-bench.mjs \
  --runtime=node-worker \
  --runner-processes=2 \
  --workers-per-runner=4 \
  --bots-per-worker=8 \
  --ticks-per-bot=250 \
  --actions-per-tick=5 \
  --work-units=25000 \
  --ses=true \
  --out=/tmp/reef-arena-runner-bench-node-ses-heavy.json
```

Observed:

- 64 bots, 16,000 bot-ticks, 80,000 proposed actions
- 2,644.16 ms elapsed, including 153.72 ms setup
- 6,051.08 bot-ticks/sec
- 30,255.38 proposed actions/sec
- 0 late ticks
- about 124.95 MiB combined runner RSS

## Real-World Hosted Bot Bench

The synthetic runner bench isolates worker overhead. The next bench exercises
real Bot SDK examples through the existing hosted artifact and hosted-runner
path while still keeping the Reef venue API out of the loop.

```bash
bun run arena:runner-realworld-bench
```

Useful knobs:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs \
  --bots=simple,lifecycle,refreshing,multi-symbol \
  --iterations=25 \
  --concurrency=4 \
  --compartment=vm \
  --build-tmp-root=tmp \
  --out=/tmp/reef-arena-runner-realworld-bench.json
```

The default `vm` compartment uses the same local-only unsafe test compartment
already used by hosted-runner regression tests. It measures real artifact
building, hosted-runner loading, SDK context behavior, fixture reads, strategy
logic, command-shaping, and report generation. It is not a security boundary.

When dependencies are installed or baked into a runner image, run the same bench
with SES or include dependency-bearing examples such as the technical-indicator
bot:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs --compartment=ses
bun scripts/dev/arena-runner-realworld-bench.mjs --bots=technical
```

This real-world bench reports:

- artifact build time
- run throughput and action/data-call throughput
- per-run latency p50/p95/max
- completed versus failed runs
- per-bot-case summaries
- process memory and CPU usage

The evidence target is not maximum throughput. The target is whether real hosted
bot execution remains comfortably under the Phase 1 tick budgets before adding
venue transport and command-status waiting.

### Initial Real-World Evidence

Default hosted-bot bench:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs \
  --iterations=25 \
  --concurrency=4 \
  --out=/tmp/reef-arena-runner-realworld-default.json
```

Observed with the local `vm` compartment:

- 4 example bots: simple, lifecycle-safe, refreshing, multi-symbol
- 100 hosted scenario runs
- 48.49 ms elapsed
- 2,062.39 scenario runs/sec
- 9,280.73 proposed actions/sec
- 7,218.35 data calls/sec
- p50 1.00 ms, p95 5.05 ms, max 7.32 ms per hosted scenario run
- about 101.45 MiB process RSS

Expanded hosted-bot bench:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs \
  --iterations=50 \
  --concurrency=8 \
  --out=/tmp/reef-arena-runner-realworld-expanded.json
```

Observed with the local `vm` compartment:

- 4 example bots: simple, lifecycle-safe, refreshing, multi-symbol
- 200 hosted scenario runs
- 78.06 ms elapsed
- 2,562.01 scenario runs/sec
- 11,529.05 proposed actions/sec
- 8,967.04 data calls/sec
- p50 1.75 ms, p95 7.17 ms, max 11.30 ms per hosted scenario run
- about 105.45 MiB process RSS

The technical-indicator bot is still a useful real-world package case, but this
worktree does not currently have `trading-signals` installed. Run it after
dependencies are present or inside a runner image that bakes approved packages.
Use repo-local build temp when package resolution needs the repository install:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs --bots=technical --build-tmp-root=repo
```

Takeaway: real hosted Bot SDK execution is still comfortably under the initial
25-50 ms per-bot tick budget in the local VM-compartment path. The remaining
evidence gap is SES compartment overhead, Deno worker/process overhead, and then
the same test under container CPU/memory caps.

Package-inclusive hosted-bot bench after installing local dependencies:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs \
  --bots=simple,lifecycle,refreshing,multi-symbol,technical \
  --iterations=25 \
  --concurrency=4 \
  --compartment=vm \
  --out=/tmp/reef-arena-runner-realworld-vm-packages.json
```

Observed:

- 5 example bots, including technical-indicator package case
- 125 hosted scenario runs
- 91.82 ms elapsed
- 1,361.42 scenario runs/sec
- 5,173.41 proposed actions/sec
- 3,811.99 data calls/sec
- p50 2.21 ms, p95 7.13 ms, max 11.63 ms per hosted scenario run
- about 102.14 MiB process RSS

SES package-inclusive hosted-bot bench:

```bash
bun scripts/dev/arena-runner-realworld-bench.mjs \
  --bots=simple,lifecycle,refreshing,multi-symbol,technical \
  --iterations=25 \
  --concurrency=4 \
  --compartment=ses \
  --out=/tmp/reef-arena-runner-realworld-ses-packages.json
```

Observed:

- 5 example bots, including technical-indicator package case
- 125 hosted scenario runs
- 88.95 ms elapsed
- 1,405.36 scenario runs/sec
- 5,340.37 proposed actions/sec
- 3,935.01 data calls/sec
- p50 2.19 ms, p95 7.11 ms, max 12.47 ms per hosted scenario run
- about 115.14 MiB process RSS

## Container Bench

The container wrapper runs the same real-world hosted-bot bench inside an outer
Docker boundary with no network, read-only repo mount, tmpfs `/tmp`, CPU cap,
memory cap, and PID cap.

```bash
bun run arena:runner-container-bench
```

Defaults:

- image: `oven/bun:1.1.38`
- cpus: `1`
- memory: `256m`
- pids: `128`
- tmpfs `/tmp`: `128m`
- output directory: `/tmp/reef-arena-runner-container`
- inner bench: package-inclusive real-world hosted-bot bench with SES

Override with environment variables:

```bash
ARENA_RUNNER_CONTAINER_IMAGE=oven/bun:1.1.38 \
ARENA_RUNNER_CONTAINER_CPUS=2 \
ARENA_RUNNER_CONTAINER_MEMORY=512m \
bun run arena:runner-container-bench
```

Or pass a custom command after the wrapper:

```bash
bun scripts/dev/arena-runner-container-bench.mjs \
  bun scripts/dev/arena-runner-bench.mjs --runtime=node-worker --ses=true
```

### Initial Container Evidence

Container smoke:

```bash
bun scripts/dev/arena-runner-container-bench.mjs \
  bun scripts/dev/arena-runner-realworld-bench.mjs \
  --bots=simple \
  --iterations=1 \
  --concurrency=1 \
  --compartment=ses \
  --out=/artifacts/container-smoke.json
```

Observed with `cpus=1`, `memory=256m`, no network, read-only repo mount:

- 1 hosted scenario run
- 7.41 ms elapsed
- p50/p95/max 6.81 ms
- about 75.51 MiB process RSS

Default package-inclusive container bench:

```bash
bun run arena:runner-container-bench
```

Observed with `cpus=1`, `memory=256m`, no network, read-only repo mount:

- 5 example bots, including technical-indicator package case
- 125 hosted scenario runs
- 305.70 ms elapsed
- 408.89 scenario runs/sec
- 1,553.79 proposed actions/sec
- 1,144.90 data calls/sec
- p50 4.95 ms, p95 48.48 ms, max 67.30 ms per hosted scenario run
- about 134.32 MiB process RSS

Larger cap comparison:

```bash
ARENA_RUNNER_CONTAINER_CPUS=2 \
ARENA_RUNNER_CONTAINER_MEMORY=512m \
bun run arena:runner-container-bench
```

Observed with `cpus=2`, `memory=512m`, no network, read-only repo mount:

- 5 example bots, including technical-indicator package case
- 125 hosted scenario runs
- 185.37 ms elapsed
- 674.32 scenario runs/sec
- 2,562.41 proposed actions/sec
- 1,888.09 data calls/sec
- p50 4.51 ms, p95 14.74 ms, max 21.46 ms per hosted scenario run
- about 131.45 MiB process RSS

Takeaway: the outer container boundary is viable, but 1 CPU is close to the
initial 25-50 ms p95 budget when several real hosted bot cases run concurrently.
For hosted/internal Phase 1, use 2 CPU / 512 MiB as the first comfortable runner
container profile, then tune down only with sustained evidence.

## Pooled Runner Prototype

The next prototype is the first arena-callable runner shape:

```bash
bun run arena:runner-pool-smoke
```

It uses a small worker-process pool and a JSON-line protocol. Each worker can:

- `loadBot`: load and cache a hosted artifact as a guarded `BotClass`
- `runScenario`: run a fixture/scenario through the cached bot class
- `startSession`: create a per-run bot instance for orchestrator-owned ticks
- `runTick`: execute one market tick and return actions plus venue command drafts
- `stopSession`: stop the per-run bot instance and return a session summary
- `freezeBot`: unload a bot from the worker
- `heartbeat`: return loaded bot count and resource usage
- `shutdown`: stop the worker

This keeps the protocol narrow enough to put the same boundary behind a runner
container later. The smoke driver currently loads each selected bot into every
worker so jobs can be round-robin dispatched without placement logic. Placement
can become smarter once the orchestrator owns bot assignment.

Useful commands:

```bash
bun scripts/dev/arena-runner-pool-smoke.mjs \
  --workers=2 \
  --bots=simple,lifecycle,refreshing,multi-symbol \
  --iterations=10 \
  --concurrency=4 \
  --compartment=vm \
  --out=/tmp/reef-arena-runner-pool-smoke-vm.json
```

```bash
bun scripts/dev/arena-runner-pool-smoke.mjs \
  --workers=2 \
  --bots=simple,lifecycle,refreshing,multi-symbol,technical \
  --iterations=10 \
  --concurrency=4 \
  --compartment=ses \
  --out=/tmp/reef-arena-runner-pool-smoke-ses-technical.json
```

Initial pool evidence:

- tiny smoke, 1 worker / 1 bot / 1 run: p95 2.91 ms, 0 failed
- VM pool, 2 workers / 4 bots / 40 runs: load 8.09 ms, run 8.86 ms,
  p95 2.98 ms, 0 failed
- SES pool, 2 workers / 4 bots / 40 runs: load 9.06 ms, run 8.87 ms,
  p95 3.10 ms, 0 failed
- SES pool with technical package bot, 2 workers / 5 bots / 50 runs:
  load 15.50 ms, run 12.14 ms, p95 3.14 ms, 0 failed

Takeaway: loading hosted artifacts once per worker and dispatching cached
scenario jobs is materially cheaper than repeatedly loading artifacts. This is
the right shape for the first arena orchestrator integration: orchestrator owns
policy and venue transport; runner workers own isolated bot execution and return
proposed actions, venue command drafts, summaries, and resource reports.

## Orchestrator-Owned Tick Smoke

The session/tick protocol smoke proves the runner can accept ticks owned by an
arena orchestrator instead of only running complete fixture scenarios:

```bash
bun run arena:runner-tick-smoke
```

Useful command:

```bash
bun scripts/dev/arena-runner-tick-smoke.mjs \
  --bots=simple,lifecycle,refreshing \
  --compartment=ses \
  --out=/tmp/reef-arena-runner-tick-smoke-ses.json
```

Observed:

- 3 hosted bots
- 9 orchestrator-supplied ticks
- 16 proposed actions
- 14 venue command drafts
- 0 failures
- p95 0.62 ms

The tick path maintains simple local order state across ticks, so lifecycle-aware
bots can read current orders and emit cancel actions against prior submitted
drafts. It does not submit to the venue; it returns command drafts for the
orchestrator to risk-check and route through `/api/v1`.

## Local Arena Run Wrapper

The first local arena-style wrapper now drives the pooled runner and produces an
arena report:

```bash
bun run arena:local-run
```

The wrapper is still pre-venue. It uses the runner pool to execute hosted bot
fixtures, then applies a minimal local scoring/enforcement pass and writes:

- `schemaVersion`
- `runId`, `modeId`, and `scoringPolicyVersion`
- runner profile and runner report path
- runner timing and aggregate counters
- per-bot results
- enforcement events
- deterministic local leaderboard

Useful command:

```bash
bun scripts/dev/arena-local-run.mjs \
  --workers=2 \
  --bots=simple,lifecycle,refreshing,multi-symbol \
  --iterations=10 \
  --concurrency=4 \
  --compartment=ses \
  --out=/tmp/reef-arena-local-run-default.json
```

Observed:

- 4 bots, 40 runner scenario jobs
- runner p95 3.15 ms
- 0 freezes
- leaderboard: refreshing, simple, lifecycle, multi-symbol

Forced-freeze validation:

```bash
bun scripts/dev/arena-local-run.mjs \
  --workers=1 \
  --bots=simple \
  --iterations=1 \
  --concurrency=1 \
  --compartment=vm \
  --max-scenario-p95-ms=1 \
  --out=/tmp/reef-arena-local-run-freeze.json
```

Observed:

- status `completed_with_freezes`
- 1 enforcement event with decision `freeze`
- disqualified bot result retained in the leaderboard payload

Takeaway: the runner pool can now feed an arena report shape with scoring and
freeze events.

## Local Arena Tick Wrapper

The next wrapper replaces fixture-only runner jobs with orchestrator-owned ticks
and mode/catalog inputs:

```bash
bun run arena:local-tick-run
```

It reads:

- `packages/scenario-definitions/arena/equity-sprint.v1.json`
- `packages/scenario-definitions/arena/bot-catalog.v1.json`

The wrapper loads hosted artifacts into the runner worker, starts one session per
catalog bot, feeds deterministic ticks, applies first-pass freeze checks from
the catalog risk profile, scores eligible bots, and writes one arena report with
per-bot results plus a deterministic leaderboard.

Useful command:

```bash
bun run arena:local-tick-run --compartment=ses \
  --out=/tmp/reef-arena-local-tick-run-ses.json
```

Observed:

- 5 catalog bots
- 15 orchestrator-owned ticks
- 14 venue command drafts
- 0 freezes
- status `completed`

Live submit mode routes accepted command drafts through `/api/v1`, waits for
terminal command status, records rejected/failed/timed-out commands, and captures
readback evidence from data availability, own orders, and market snapshots:

```bash
make dev-smoke-bot-arena-local
```

Useful direct command:

```bash
bun run arena:local-tick-run --submit-mode=live \
  --venue-url=http://127.0.0.1:8080 \
  --seed-reference \
  --compartment=ses \
  --out=/tmp/reef-arena-local-tick-run-live.json
```

The same report now includes:

- `commandAccounting`
- per-tick `submission` records
- terminal command status bodies
- `venueReadback.availability`
- `venueReadback.ownOrders`
- `venueReadback.snapshots`
- freeze events for bot-caused tick or command failures
- optional `persistence` evidence when `--persist-results` is set

Observed live smoke:

- status `completed`
- 14 venue command drafts
- 14 submitted commands
- 14 terminal commands
- 0 rejected commands
- 0 timed-out commands
- 0 freezes

Persisted local smoke:

```bash
make dev-smoke-bot-arena-local-persist
```

This wraps:

```bash
bun run arena:local-tick-run --submit-mode=live \
  --venue-url=http://127.0.0.1:8080 \
  --arena-admin-url=http://127.0.0.1:8080 \
  --seed-reference \
  --persist-results \
  --compartment=ses \
  --out=/tmp/reef-arena-local-tick-run-persist.json
```

The persist path registers catalog bot metadata and versions when needed,
registers the arena run, posts all `botResults` to
`/internal/admin/arena/run-bot-results`, then reads back raw run results and
`/internal/admin/arena/leaderboard` into the report.

Local stack requirement: `platform-runtime` must run with
`PLATFORM_ARENA_ADMIN_ENABLED=true`. Host-based smoke calls to raw
`/internal/admin/*` routes also require an exposure mode that allows the caller,
such as `PLATFORM_INTERNAL_HTTP_MODE=enabled` for local-only test runs. Keep the
default fail-closed posture for non-local environments.

Takeaway: the local arena now has a stable mode/catalog/report shape around the
tick protocol and can route accepted venue command drafts through `/api/v1`.
