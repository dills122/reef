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
