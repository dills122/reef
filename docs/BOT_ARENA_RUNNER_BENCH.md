# Bot Arena Runner Bench

This spike measures the cost of grouped TypeScript-capable bot runners before
the arena orchestrator, venue API, or command lifecycle are in the loop.

The benchmark driver starts one or more Deno runner processes. Each runner hosts
multiple Deno workers, and each worker owns multiple synthetic bots. Bots receive
deterministic fake market snapshots, do bounded CPU work, and return proposed
limit-order actions.

## Command

```bash
bun scripts/dev/arena-runner-bench.mjs
```

Equivalent package script:

```bash
bun run arena:runner-bench
```

Deno must be installed on `PATH`. The script intentionally exits with a clear
message when Deno is missing so local dev machines and future runner containers
fail before producing partial measurements.

## Useful Knobs

```bash
bun scripts/dev/arena-runner-bench.mjs \
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
bun scripts/dev/arena-runner-bench.mjs --ses=true
```

The SES mode loads `ses` through Deno's npm support and evaluates each synthetic
bot in a compartment. Use it when the Deno runner environment has npm package
resolution available or when the runner container bakes the dependency in.

## Report Shape

The JSON report includes:

- runner process count, worker count, bot count, ticks, and proposed actions
- elapsed wall time, tick throughput, and action throughput
- aggregate tick latency and late-tick count
- sampled RSS for each Deno runner process
- total runner-pool RSS peak approximation
- per-runner and per-worker reports

This benchmark does not submit commands to Reef. It is only meant to size the
runner pool and validate backpressure defaults before wiring the arena
orchestrator to real venue paths.
