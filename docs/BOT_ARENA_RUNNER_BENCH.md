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
