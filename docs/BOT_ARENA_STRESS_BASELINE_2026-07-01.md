# Bot Arena Stress Baseline - 2026-07-01

## Purpose

Capture first local stress evidence for the Bot Arena planning work.

This baseline uses the existing simulator/load-tester against the local Docker stack. It is not a full arena benchmark because sandbox workers, arena storage, replay indexing, and game-mode scoring do not exist yet. It is useful as a starting point for venue command-path capacity, bot action-policy design, and simulator traffic-shape planning.

## Environment

- branch: `codex/bot-arena-plan`
- local stack: Docker Compose `postgres`, `matching-engine`, `platform-runtime`
- runtime URL: `http://127.0.0.1:8080`
- engine transport: compose default, currently gRPC runtime-to-engine
- simulator profile mix: `market-maker=35`, `institutional=30`, `retail=25`, `noise=10`
- trace check limit: `100`

The first non-elevated stress attempt failed before generating load because Go could not write to the normal build cache from the sandbox:

```text
open /Users/dsteele/Library/Caches/go-build/...: operation not permitted
```

The stress and capacity runs below were rerun with filesystem approval so the Go load tester could use the normal build cache.

## Commands

Default stepped stress profile:

```bash
JS_RUNTIME=node \
DEV_STRESS_REPORT_OUT=/tmp/reef-bot-arena-baseline.json \
DEV_STRESS_ARTIFACT_DIR=/tmp \
make dev-stress
```

Capacity baseline at 1k target:

```bash
node scripts/dev/sim-run.mjs \
  --duration 60s \
  --mode capacity-baseline \
  --rate 1000 \
  --workers 128 \
  --trace-check-limit 100 \
  --pretty-summary \
  --report-out /tmp/reef-bot-arena-capacity-1k-128w.json
```

Capacity baseline at 3k target:

```bash
node scripts/dev/sim-run.mjs \
  --duration 60s \
  --mode capacity-baseline \
  --rate 3000 \
  --workers 384 \
  --trace-check-limit 100 \
  --pretty-summary \
  --report-out /tmp/reef-bot-arena-capacity-3k-384w.json
```

## Artifacts

- `/tmp/reef-bot-arena-baseline-rate-100.json`
- `/tmp/reef-bot-arena-baseline-rate-200.json`
- `/tmp/reef-bot-arena-baseline-rate-300.json`
- `/tmp/reef-bot-arena-baseline-rate-400.json`
- `/tmp/reef-bot-arena-baseline-telemetry.ndjson`
- `/tmp/reef-bot-arena-baseline-recommendation.json`
- `/tmp/reef-bot-arena-baseline-kpi.json`
- `/tmp/reef-bot-arena-baseline-kpi.md`
- `/tmp/reef-bot-arena-capacity-1k-128w.json`
- `/tmp/reef-bot-arena-capacity-3k-384w.json`

## Results

### Default Mixed Stress

Mode: `strict-lifecycle`, duration `30s` per step, workers `12`.

| Target RPS | Observed RPS | Accepted RPS | Success % | p50 ms | p95 ms | p99 ms | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 97.15 | 86.93 | 89.48 | 44.25 | 47.01 | 50.19 | 100/100 |
| 200 | 195.01 | 172.34 | 88.38 | 43.73 | 45.59 | 47.51 | 100/100 |
| 300 | 291.34 | 259.67 | 89.13 | 43.01 | 45.19 | 46.90 | 100/100 |
| 400 | 363.64 | 323.63 | 89.00 | 43.03 | 45.24 | 47.62 | 100/100 |

The stress script exited nonzero because the default end-to-end success-rate guardrail is `90%`, and all four mixed steps landed slightly below it.

Important context:

- valid-intent success-rate proxy was `100%`
- system-failure proxy was `0%`
- all HTTP responses were `200`
- all sampled traces passed sequence checks
- rejects were overwhelmingly lifecycle/action-policy rejects

Top reject pattern across mixed stress:

- `INVALID_STATE: order is not modifiable`
- `INVALID_STATE: filled order cannot be cancelled`
- occasional `VALIDATION_ERROR: quantityUnits must remain above already filled quantity`

### Capacity Baseline

Mode: `capacity-baseline`, submit-heavy, reduced invalid modify/cancel noise.

| Target RPS | Workers | Observed RPS | Accepted RPS | Success % | p50 ms | p95 ms | p99 ms | Max ms | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1000 | 128 | 841.69 | 830.09 | 98.62 | 43.24 | 47.15 | 76.67 | 212.28 | 100/100 |
| 3000 | 384 | 2133.21 | 2102.04 | 98.54 | 136.90 | 166.03 | 201.60 | 834.89 | 100/100 |

The 3k target run took `78.1s` wall time for a nominal `60s` run, which suggests client/runtime backlog at this pressure point.

## Interpretation

The current local command path is healthy for low-to-mid mixed stress:

- trace integrity stayed clean
- no transport-level failures appeared
- no obvious engine/runtime correctness failures appeared
- p99 stayed under `50ms` in the 100-400 rps mixed profile

The default mixed profile is not a good proxy for arena bot quality because the failure rate is dominated by bots attempting to modify or cancel orders that have already filled. For Bot Arena, bot action policy and snapshot freshness matter as much as raw throughput.

Capacity mode gives a clearer single-instance signal:

- `~830` accepted rps at 128 workers with p99 under `80ms`
- `~2100` accepted rps at 384 workers with p99 around `202ms`
- higher target pressure increases latency materially before correctness breaks

## Bot Arena Design Implications

- Controlled market maker and background-flow bots need deterministic lifecycle-state tests before they become arena liquidity.
- Bot SDK snapshots should include enough own-order/fill state to avoid wasteful amend/cancel attempts.
- Arena risk gates should count invalid lifecycle actions separately from infrastructure/system failures.
- Action budgets should include amend/cancel rates, not only submit rates.
- Arena leaderboards should retain invalid-action and timeout metrics even if the headline ranking starts with final equity.
- The run plane should be designed to scale by run/shard/worker/partition rather than assuming one runtime instance can host all arena traffic.
- Replay and leaderboard aggregation should stay asynchronous so scoring/debug writes do not sit in the venue hot path.

## Follow-Up Mitigation

After the baseline, the simulator lifecycle recovery policy was tightened so lifecycle-managed modes enter a short submit-only recovery window immediately after a terminal order-state reject. This models an arena bot detecting stale own-order state and rebuilding liquidity before trying more amend/cancel actions.

Comparison run:

```bash
node scripts/dev/sim-run.mjs \
  --duration 30s \
  --mode strict-lifecycle \
  --rate 400 \
  --workers 12 \
  --submit-pct 70 \
  --modify-pct 20 \
  --cancel-pct 10 \
  --profile-mm-pct 35 \
  --profile-inst-pct 30 \
  --profile-retail-pct 25 \
  --profile-noise-pct 10 \
  --trace-check-limit 100 \
  --pretty-summary \
  --report-out /tmp/reef-bot-arena-lifecycle-recovery-400.json
```

| Run | Observed RPS | Accepted RPS | Success % | p95 ms | p99 ms | Trace Pass |
|---|---:|---:|---:|---:|---:|---:|
| Baseline 400 rps mixed | 363.64 | 323.63 | 89.00 | 45.24 | 47.62 | 100/100 |
| After recovery change | 366.32 | 344.42 | 94.02 | 45.31 | 48.03 | 100/100 |

The change did not remove lifecycle rejects entirely, which is correct. It reduced repeated stale-state pressure while preserving realistic invalid-action accounting for bot quality and arena scoring.

## Single-Instance Ceiling Sweep

After adding report-level quality metrics, a current-code ceiling sweep was run against the same local Docker stack. This was not a clean-stack run; prior stress/capacity runs had already created data in the local database. Treat these as practical current-stack limits, not a durable clean-room benchmark.

Commands:

```bash
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 5000 --workers 512 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-ceiling-5k-512w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 6500 --workers 768 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-ceiling-6500-768w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 4000 --workers 448 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-ceiling-4k-448w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 3000 --workers 384 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-ceiling-3k-384w-20260701-current.json
```

| Target RPS | Workers | Wall Window | Observed RPS | Accepted RPS | Success % | Valid-Intent Success % | Invalid-Intent % | System-Failure % | p50 ms | p95 ms | p99 ms | Max ms | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3000 | 384 | 77.7s | 2096.39 | 2070.02 | 98.74 | 100.00 | 1.26 | 0.00 | 136.82 | 169.86 | 340.01 | 622.21 | 100/100 |
| 4000 | 448 | 80.7s | 2054.90 | 2029.80 | 98.78 | 100.00 | 1.22 | 0.00 | 161.54 | 202.30 | 315.60 | 652.30 | 100/100 |
| 5000 | 512 | 83.5s | 1909.03 | 1884.71 | 98.73 | 100.00 | 1.27 | 0.00 | 189.03 | 260.05 | 332.46 | 698.20 | 100/100 |
| 6500 | 768 | 95.7s | 1732.76 | 1711.48 | 98.77 | 100.00 | 1.23 | 0.00 | 272.70 | 355.31 | 570.91 | 747.73 | 100/100 |

Interpretation:

- The best accepted-throughput point in this current-stack sweep was the `3000/384` run at `~2070 accepted rps`.
- Increasing offered load above that did not increase accepted throughput; it increased backlog and tail latency.
- The `6500/768` point degraded to `~1711 accepted rps` with p99 around `571ms`, so an `8000/1024` probe was intentionally skipped.
- Trace integrity stayed clean across all ceiling points, and the system-failure proxy stayed at `0%`.
- The practical single-instance planning cap for arena design should be around `~2k accepted rps` until diagnostics prove the bottleneck and the hot path changes.

Design implication:

- Arena scale should not depend on one runtime/engine instance handling all tournament traffic.
- Use the single-instance budget as a partition capacity estimate, then scale by run, shard, instrument group, sandbox worker pool, and matching-engine partition.
- Before pushing higher, capture phase timing, DB pool/table-growth diagnostics, and client backlog metrics so the next optimization targets the actual bottleneck.

## Next Measurements

- Run `dev-stress-diagnostics` to capture DB pool/table-growth evidence.
- Run a clean-stack ceiling comparison after `dev-reset` when destructive local reset is acceptable.
- Compare HTTP boundary versus gRPC/in-process command gateway before changing the arena write path.
- Add simulator metrics that separate client backlog, runtime queueing, engine round-trip, persistence, and serialization.
- Add arena-like profile variants with stricter own-order state handling to estimate avoidable invalid lifecycle noise.
