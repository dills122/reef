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

## Slowdown Investigation

Follow-up inspection on the same local stack found that the current `~2k accepted rps` ceiling is mostly a loaded synchronous write-path limit, not an engine correctness issue.

Important observations:

- The load tester's default `drop` scheduler keeps only one pending rate token. When workers are saturated, offered tokens are dropped instead of queued. The reported throughput is therefore a practical completed-request capacity signal, not proof that every configured target RPS reached the runtime.
- The local database is no longer clean. At inspection time it contained roughly:
  - `runtime.runtime_events`: `3.16M` rows, `4.2GB`
  - `boundary.api_command_captures`: `917k` rows, `1.5GB`
  - `boundary.api_idempotency_records`: `920k` rows, `861MB`
  - `runtime.executions`: `1.5M` rows, `927MB`
  - `runtime.trades`: `751k` rows, `583MB`
  - `runtime.submit_results`: `917k` rows, `308MB`
- Postgres logged a checkpoint during the stress window with about `3.3GB` of WAL distance and a `742s` checkpoint duration. That points to write volume and storage/checkpoint behavior as first-class bottlenecks for long-running simulator use.
- API v1 successful commands currently perform boundary command capture, idempotency lookup, engine execution, runtime persistence, idempotency save, and command capture completion synchronously before returning.
- `EXTERNAL_API_COMMAND_LOG_MODE=disabled` does not disable command capture. `EXTERNAL_API_COMMAND_CAPTURE_MODE` defaults to `postgres`, so the boundary capture table is still on the hot path.
- Runtime submits use a consolidated Postgres function for result, order, executions, trades, and lifecycle events. Modify/cancel paths still save result and lifecycle event separately.
- The runtime uses a blocking `HttpServer` fixed thread pool (`PLATFORM_HTTP_THREADS=64`) and shared Hikari pools (`RUNTIME_DB_POOL_MAX=48`). With synchronous DB work in the request path, thread and pool waits will directly show up as request latency.
- A quick legacy-route A/B probe was attempted with `--use-api-v1=false`, but the route returned `403` for order mutations because legacy mutation routes require the internal route guard. That result is a guardrail check, not a boundary-overhead measurement.

Current interpretation:

- Use `~2k accepted rps` as the conservative current loaded-stack planning cap for one runtime + engine instance.
- Keep `~3k accepted rps` as the clean/tuned synchronous-path target already seen in earlier throughput work.
- Treat `5k accepted rps` per instance as an architecture target that likely requires async or batched persistence, table lifecycle work, and better hot-path diagnostics.
- Do not plan the arena around one giant instance. Plan capacity by run, instrument shard, matching partition, sandbox worker pool, and leaderboard/read-model partition.

Likely speed-up order:

1. Add phase timing and Hikari/pool diagnostics before rewriting the hot path.
2. Add table-growth and checkpoint diagnostics to stress reports.
3. Disable or move unnecessary boundary writes for internal simulator-only profiles only if command-path parity remains explicit and audited.
4. Convert command capture from update-heavy mutable rows to append-oriented status events or a lighter hot-path receipt model.
5. Batch or async runtime persistence for arena simulations while preserving deterministic replay/audit records.
6. Partition or lifecycle-manage large append tables (`runtime_events`, command captures, idempotency records, executions, trades).
7. Re-test clean-stack, loaded-stack, and long-soak envelopes separately.

## Clean-DB Ceiling Retest

The loaded database was dumped before reset:

- compressed dump: `/tmp/reef-loaded-before-clean-reset-20260701.dump`
- compressed size: `345MB`
- pre-reset live database size: `8431MB`

The local stack was then reset with:

```bash
JS_RUNTIME=node make dev-reset
```

Immediately after reset, the `reef` database was `8359kB` and the hot runtime/boundary tables had zero live rows.

Clean-stack commands:

```bash
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 3000 --workers 384 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-clean-ceiling-3k-384w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 4000 --workers 448 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-clean-ceiling-4k-448w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 5000 --workers 512 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-clean-ceiling-5k-512w-20260701.json
node scripts/dev/sim-run.mjs --duration 60s --mode capacity-baseline --rate 6500 --workers 768 --trace-check-limit 100 --pretty-summary --report-out /tmp/reef-clean-ceiling-6500-768w-20260701.json
```

| Target RPS | Workers | Wall Window | Observed RPS | Accepted RPS | Success % | Invalid-Intent % | System-Failure % | p50 ms | p95 ms | p99 ms | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 3000 | 384 | 78.5s | 2067.95 | 2042.40 | 98.76 | 1.24 | 0.00 | 139.13 | 179.12 | 253.68 | 100/100 |
| 4000 | 448 | 80.9s | 1994.84 | 1970.13 | 98.76 | 1.24 | 0.00 | 164.20 | 218.42 | 342.35 | 100/100 |
| 5000 | 512 | 83.9s | 2036.82 | 2011.51 | 98.76 | 1.24 | 0.00 | 177.74 | 239.75 | 329.69 | 100/100 |
| 6500 | 768 | 95.2s | 1677.62 | 1657.24 | 98.78 | 1.22 | 0.00 | 280.53 | 393.65 | 545.50 | 100/100 |

After the four clean runs, the database had already grown to `6049MB`:

- `runtime.runtime_events`: `2.21M` rows, `3.0GB`
- `boundary.api_command_captures`: `654k` rows, `1.1GB`
- `runtime.executions`: `1.07M` rows, `666MB`
- `boundary.api_idempotency_records`: `649k` rows, `617MB`
- `runtime.trades`: `534k` rows, `417MB`

Clean-vs-loaded result:

- Resetting the database did not recover the old `~3k accepted rps` ceiling.
- The clean best point was `~2042 accepted rps` at `3000/384`, which is slightly below the loaded `~2070 accepted rps` point from the earlier sweep.
- The clean run confirms the current bottleneck is not only accumulated historical table size.
- The current practical single-instance ceiling should stay around `~2k accepted rps` until phase timing proves where the synchronous path is spending time.
- Table growth is still a serious arena concern: one short clean sweep generated about `6GB` of database state.

## Hot-Path Timing Diagnosis

Lightweight internal timing was added around the runtime API v1 path and order application service. The endpoint is:

```text
GET /internal/perf/hot-path
POST /internal/perf/hot-path
```

`POST` resets the in-memory counters. `GET` returns aggregate count, total, average, and max milliseconds per phase.

Matching-engine in-process microbenchmarks on the same machine do not explain the `~2k` ceiling:

| Benchmark | Result |
|---|---:|
| `BenchmarkSubmitOrderResting` | `1187 ns/op` |
| `BenchmarkSubmitOrderMatchAgainstResting` | `2751 ns/op` |
| `BenchmarkModifyOrder` | `116022 ns/op` |

The runtime phase timing points instead to synchronous DB-backed command durability and runtime persistence.

### API v1 Default Path

Command:

```bash
node scripts/dev/sim-run.mjs --duration 30s --mode capacity-baseline --rate 3000 --workers 384 --trace-check-limit 50 --pretty-summary --report-out /tmp/reef-hotpath-api-v1-3k-384w-30s-20260701.json
```

Result:

- wall throughput: `1403.32 rps`
- accepted wall throughput: `1386.53 rps`
- p50/p95/p99: `162.00ms` / `271.18ms` / `394.38ms`

Average hot-path phase timings:

| Phase | Avg ms |
|---|---:|
| `api.commandCapture.reserve` | `5.29` |
| `api.commandCapture.markCompleted` | `5.25` |
| `api.idempotency.save` | `5.15` |
| `api.operation` | `11.92` |
| `runtime.persistence.persistSubmitOutcome` | `7.75` |
| `runtime.engine.submit` | `2.20` |

### Command Capture Disabled

Runtime override:

```bash
EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled docker compose -f docker-compose.yml up -d --build platform-runtime
```

Command:

```bash
node scripts/dev/sim-run.mjs --duration 30s --mode capacity-baseline --rate 3000 --workers 384 --trace-check-limit 50 --pretty-summary --report-out /tmp/reef-hotpath-capture-disabled-3k-384w-30s-20260701.json
```

Result:

- wall throughput: `1677.43 rps`
- accepted wall throughput: `1657.40 rps`
- p50/p95/p99: `73.87ms` / `200.80ms` / `331.11ms`

Average hot-path phase timings:

| Phase | Avg ms |
|---|---:|
| `api.idempotency.save` | `5.79` |
| `api.operation` | `14.32` |
| `runtime.persistence.persistSubmitOutcome` | `9.07` |
| `runtime.engine.submit` | `2.98` |

### Boundary Persistence Removed

Runtime override:

```bash
EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled EXTERNAL_API_IDEMPOTENCY_STORE=inmemory docker compose -f docker-compose.yml up -d --build platform-runtime
```

Command:

```bash
node scripts/dev/sim-run.mjs --duration 30s --mode capacity-baseline --rate 3000 --workers 384 --trace-check-limit 50 --pretty-summary --report-out /tmp/reef-hotpath-boundary-memory-3k-384w-30s-20260701.json
```

Result:

- wall throughput: `1768.81 rps`
- accepted wall throughput: `1747.17 rps`
- p50/p95/p99: `45.60ms` / `175.16ms` / `268.44ms`

Average hot-path phase timings:

| Phase | Avg ms |
|---|---:|
| `api.operation` | `11.47` |
| `runtime.persistence.persistSubmitOutcome` | `6.35` |
| `runtime.engine.submit` | `2.67` |

Pushing the same reduced path to `5000/512` did not approach `5k`:

- command: `/tmp/reef-hotpath-boundary-memory-5k-512w-30s-20260701.json`
- wall throughput: `1494.60 rps`
- accepted wall throughput: `1476.60 rps`
- p50/p95/p99: `187.14ms` / `248.69ms` / `405.12ms`
- `runtime.persistence.persistSubmitOutcome` increased to `14.58ms avg`
- `api.operation` increased to `23.48ms avg`

Post-diagnostic table state:

- database size: `9157MB`
- `runtime.runtime_events`: `3.79M` rows, `4809MB`
- `runtime.executions`: `1.79M` rows, `1053MB`
- `boundary.api_command_captures`: `722k` rows, `1069MB`
- `boundary.api_idempotency_records`: `803k` rows, `724MB`

Diagnosis:

- The matching engine is not the first-order limiter for submit-heavy capacity traffic.
- API v1 boundary persistence is expensive, but removing it does not get close to `5k`.
- The dominant blocker is synchronous runtime persistence and write amplification under concurrency.
- The current path writes command result, order state, executions, trades, trace sequences, and runtime events before returning.
- Higher offered load increases persistence latency rather than increasing throughput.

Implication for `5k` to `10k+`:

- `5k` accepted rps per instance is unlikely through thread/pool tuning alone.
- The next architecture slice should separate accepted command latency from full audit/event persistence latency.
- The arena path needs either async/batched persistence, a write-ahead command log plus background projection, partitioned event storage, or a combination of those.
- Public/auditable command capture can remain strict, but it cannot stay as multiple synchronous per-command Postgres writes if `10k+` is the target.

## Captured-Ack Async Worker Bundle

Configuration:

- `EXTERNAL_API_COMMAND_LOG_MODE=postgres`
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=captured-ack`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=true`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=4`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE=250`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS=5`

Command:

```bash
DEV_STRESS_DURATION=30s \
DEV_STRESS_MODE=capacity-baseline \
DEV_STRESS_PROFILE=capacity-heavy \
DEV_STRESS_RATES=2500,5000,7500,10000 \
DEV_STRESS_SWEEP_WORKERS=128 \
DEV_STRESS_TRACE_CHECK_LIMIT=100 \
DEV_STRESS_MIN_SUCCESS_RATE_PCT=0 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-async-bundle-20260701 \
DEV_STRESS_REPORT_OUT=/tmp/reef-async-bundle.json \
JS_RUNTIME=node \
make dev-stress
```

Results:

| Requested RPS | Workers | Accepted RPS | Success | p50 | p95 | p99 | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|
| `2500` | `128` | `2056.26` | `100.00%` | `44.50ms` | `50.00ms` | `60.08ms` | `48.00%` |
| `5000` | `128` | `2284.65` | `100.00%` | `45.23ms` | `51.46ms` | `70.82ms` | `17.00%` |
| `7500` | `128` | `2273.70` | `100.00%` | `45.55ms` | `51.19ms` | `69.38ms` | `4.00%` |
| `10000` | `128` | `2210.72` | `100.00%` | `46.01ms` | `53.13ms` | `90.10ms` | `0.00%` |

Artifacts:

- `/tmp/reef-async-bundle-rate-2500-workers-128.json`
- `/tmp/reef-async-bundle-rate-5000-workers-128.json`
- `/tmp/reef-async-bundle-rate-7500-workers-128.json`
- `/tmp/reef-async-bundle-rate-10000-workers-128.json`
- `/tmp/reef-async-bundle-20260701/reef-async-bundle-telemetry.ndjson`
- `/tmp/reef-async-bundle-20260701/reef-async-bundle-kpi.md`

Post-run async worker stats immediately after the sweep:

```json
{"RECEIVED":43408,"PROCESSING":705,"COMPLETED":271645,"FAILED":0}
```

Post-drain async worker stats after roughly 30 seconds:

```json
{"RECEIVED":0,"PROCESSING":0,"COMPLETED":315758,"FAILED":0}
```

Hot-path averages after the sweep:

| Phase | Avg ms |
|---|---:|
| `api.commandCapture.reserve` | `3.80` |
| `api.idempotency.find` | `0.25` |
| `async.claim` | `1.46` |
| `async.operation` | `2.31` |
| `async.complete` | `0.78` |
| `runtime.persistence.persistSubmitOutcome` | `1.29` |
| `runtime.engine.submit` | `0.44` |

Diagnosis:

- The captured-ack path removes the synchronous runtime persistence wait from API response latency and holds `202` p99 below `100ms` through the tested range.
- Intake still plateaus around `2.2k` accepted rps on this single local instance, so the current HTTP/Kotlin/Postgres command-log append path is now the first accepted-response limiter.
- Durable processing did complete all commands after the sweep, but high requested rates created a temporary backlog and low immediate trace pass rates.
- The next slice should focus on command-log append throughput, HTTP/runtime concurrency, and batch/partition strategy before chasing bot-game rules.

## Command-Log-Only Intake Comparison

The captured-ack bundle above still used the legacy boundary command-capture table in addition to the command log. To isolate that cost, two follow-up runs disabled legacy command capture and switched idempotency lookup to in-memory while keeping `captured-ack`.

### Durable Command Log Only

Configuration:

- `EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled`
- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory`
- `EXTERNAL_API_COMMAND_LOG_MODE=postgres`
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=captured-ack`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=4`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE=250`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS=5`

Results:

| Requested RPS | Workers | Accepted RPS | Success | p50 | p95 | p99 | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|
| `5000` | `128` | `2377.37` | `100.00%` | `43.56ms` | `48.10ms` | `54.18ms` | `40.00%` |
| `10000` | `128` | `2375.63` | `100.00%` | `43.78ms` | `47.64ms` | `55.66ms` | `17.00%` |

Hot-path averages:

| Phase | Avg ms |
|---|---:|
| `api.commandCapture.reserve` | `1.88` |
| `api.idempotency.find` | `0.001` |
| `async.operation` | `2.24` |
| `runtime.persistence.persistSubmitOutcome` | `1.22` |

Post-drain stats:

```json
{"RECEIVED":0,"PROCESSING":0,"COMPLETED":486151,"FAILED":0}
```

Artifacts:

- `/tmp/reef-commandlog-only-rate-5000-workers-128.json`
- `/tmp/reef-commandlog-only-rate-10000-workers-128.json`
- `/tmp/reef-commandlog-only-20260701/reef-commandlog-only-telemetry.ndjson`

### In-Memory Command Log Diagnostic

Configuration changed `EXTERNAL_API_COMMAND_LOG_MODE=inmemory` while keeping legacy capture disabled and idempotency in-memory. This is not durable; it is only a ceiling diagnostic.

Results:

| Requested RPS | Workers | Accepted RPS | Success | p50 | p95 | p99 | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|
| `5000` | `128` | `2471.87` | `100.00%` | `41.84ms` | `44.02ms` | `46.84ms` | `57.00%` |
| `10000` | `128` | `2538.07` | `100.00%` | `41.97ms` | `44.18ms` | `47.83ms` | `49.00%` |

Hot-path averages:

| Phase | Avg ms |
|---|---:|
| `api.commandCapture.reserve` | `0.019` |
| `api.idempotency.find` | `0.001` |
| `async.operation` | `2.10` |
| `runtime.persistence.persistSubmitOutcome` | `1.14` |

Post-drain stats:

```json
{"RECEIVED":0,"PROCESSING":0,"COMPLETED":180109,"FAILED":0}
```

Artifacts:

- `/tmp/reef-inmemory-commandlog-rate-5000-workers-128.json`
- `/tmp/reef-inmemory-commandlog-rate-10000-workers-128.json`
- `/tmp/reef-inmemory-commandlog-20260701/reef-inmemory-commandlog-telemetry.ndjson`

Diagnosis:

- Disabling legacy boundary command capture helps: best durable captured-ack throughput improved from `2284.65` to `2377.37` accepted rps and p99 improved from `70.82ms` to `54.18ms`.
- Removing durable command-log append entirely only improved the best diagnostic result to `2538.07` accepted rps.
- The remaining gap to `5k+` is therefore not primarily the command-log table; the local ceiling is likely HTTP server/runtime concurrency, load-generator/client limits, container CPU scheduling, or shared runtime synchronization.
- The next useful test is a concurrency/thread sweep plus a low-level accepted-ack microbenchmark that bypasses simulator command generation and runtime async processing.

## Worker And Server Thread Sweep

The earlier `128`-worker results under-drove captured-ack. A follow-up sweep used:

- durable Postgres command log
- legacy boundary capture disabled
- in-memory idempotency lookup
- async worker enabled
- `REEF_RATE_SCHEDULE=precise`
- `DEV_STRESS_RATES=10000`
- `DEV_STRESS_SWEEP_WORKERS=128,256,512,1024`
- default `PLATFORM_HTTP_THREADS=32`

Results:

| Workers | Requested RPS | Accepted RPS | Success | p50 | p95 | p99 | Trace Pass |
|---:|---:|---:|---:|---:|---:|---:|---:|
| `128` | `10000` | `2001.99` | `100.00%` | `44.72ms` | `51.67ms` | `64.83ms` | `52.00%` |
| `256` | `10000` | `3853.76` | `100.00%` | `48.41ms` | `63.07ms` | `79.86ms` | `8.00%` |
| `512` | `10000` | `3090.26` | `100.00%` | `75.68ms` | `97.12ms` | `105.04ms` | `0.00%` |
| `1024` | `10000` | `1816.19` | `100.00%` | `142.70ms` | `173.38ms` | `477.81ms` | `0.00%` |

Artifacts:

- `/tmp/reef-worker-sweep-rate-10000-workers-128.json`
- `/tmp/reef-worker-sweep-rate-10000-workers-256.json`
- `/tmp/reef-worker-sweep-rate-10000-workers-512.json`
- `/tmp/reef-worker-sweep-rate-10000-workers-1024.json`
- `/tmp/reef-worker-sweep-20260701/reef-worker-sweep-telemetry.ndjson`

Diagnosis:

- The current best durable captured-ack intake result is `3853.76` accepted rps at `256` load workers.
- Over-concurrency hurts: `512` and `1024` workers reduce throughput and increase latency.
- During the sweep, `api.commandCapture.reserve` averaged `6.08ms`, so command-log append latency still rises under high concurrency.
- The async worker drains correctly but can lag badly during overload; after the sweep it had `138027` `RECEIVED` and `483` `PROCESSING` before draining.

### Intake-Only Diagnostic

The same durable command-log path was tested with `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=false` to isolate accepted-command append throughput from async processing competition.

| Workers | Requested RPS | Accepted RPS | Success | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|
| `256` | `10000` | `3991.44` | `100.00%` | `47.20ms` | `62.22ms` | `72.56ms` |
| `512` | `10000` | `3005.64` | `100.00%` | `60.81ms` | `96.36ms` | `117.04ms` |

Artifacts:

- `/tmp/reef-intake-only-rate-10000-workers-256.json`
- `/tmp/reef-intake-only-rate-10000-workers-512.json`
- `/tmp/reef-intake-only-20260701/reef-intake-only-telemetry.ndjson`

Diagnosis:

- Disabling async processing improved the best `256`-worker point only from `3853.76` to `3991.44` accepted rps.
- Async processing competes with intake, but it is not the dominant limiter for `5k+`.
- `api.commandCapture.reserve` still averaged `5.67ms` with async disabled, so the durable command-log append path itself remains a major hot path under concurrency.

### HTTP Thread Count Diagnostic

The best `256`-worker point was retested with `PLATFORM_HTTP_THREADS=128`.

| HTTP Threads | Workers | Requested RPS | Accepted RPS | Success | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| `32` | `256` | `10000` | `3853.76` | `100.00%` | `48.41ms` | `63.07ms` | `79.86ms` |
| `128` | `256` | `10000` | `3537.29` | `100.00%` | `49.00ms` | `67.19ms` | `86.61ms` |

Diagnosis:

- Raising the Java HTTP executor from `32` to `128` threads made throughput and latency worse.
- Keep the default local `PLATFORM_HTTP_THREADS=32` for now.
- The next implementation target is not more HTTP threads; it is either reducing per-command command-log append cost, partitioning command intake from async runtime writes, or replacing `HttpServer` with a server stack designed for higher connection concurrency.

## Raw Intake Microbenchmark

A narrow `cmd/intake-bench` tool was added to measure `/api/v1/orders/submit` accepted-command ingress without strategy selection, lifecycle state, trace checks, tailing, or rich simulator reporting.

Command:

```bash
DEV_INTAKE_DURATION=15s \
DEV_INTAKE_WORKERS=256 \
DEV_INTAKE_RATE=10000 \
DEV_INTAKE_RATE_SCHEDULE=precise \
DEV_INTAKE_ACTOR_ID_PREFIX=bot \
DEV_INTAKE_ARTIFACT_DIR=/tmp/reef-raw-intake-valid-20260701 \
DEV_INTAKE_REPORT_OUT=/tmp/reef-raw-intake-valid.json \
JS_RUNTIME=node \
make dev-intake-bench
```

Result:

| Workers | Requested RPS | Accepted RPS | Success | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|
| `256` | `10000` | `6937.74` | `100.00%` | `48.84ms` | `61.01ms` | `67.41ms` |

Higher offered-rate check:

```bash
DEV_INTAKE_DURATION=15s \
DEV_INTAKE_WORKERS=384 \
DEV_INTAKE_RATE=15000 \
DEV_INTAKE_RATE_SCHEDULE=precise \
DEV_INTAKE_ACTOR_ID_PREFIX=bot \
DEV_INTAKE_ARTIFACT_DIR=/tmp/reef-raw-intake-valid-384-20260701 \
DEV_INTAKE_REPORT_OUT=/tmp/reef-raw-intake-valid-384.json \
JS_RUNTIME=node \
make dev-intake-bench
```

| Workers | Requested RPS | Accepted RPS | Success | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|
| `384` | `15000` | `7784.04` | `100.00%` | `59.10ms` | `76.30ms` | `84.48ms` |

Artifacts:

- `/tmp/reef-raw-intake-valid-20260701/reef-raw-intake-valid-workers-256-rate-10000.json`
- `/tmp/reef-raw-intake-valid-384-20260701/reef-raw-intake-valid-384-workers-384-rate-15000.json`

Hot-path averages:

| Run | `api.commandCapture.reserve` | `api.idempotency.find` |
|---|---:|---:|
| `256w/10000rps` | `5.27ms` | `0.001ms` |
| `384w/15000rps` | `6.63ms` | `0.001ms` |

Async queue state immediately after `384w/15000rps`:

```json
{"RECEIVED":87803,"PROCESSING":429,"COMPLETED":1407719,"FAILED":0}
```

Diagnosis:

- Raw accepted-command ingress is above the `5k` target on one local instance.
- The richer simulator/load-tester path was measuring strategy/lifecycle/reporting overhead as well as platform-runtime intake.
- The next scaling problem is sustained end-to-end processing: async workers and runtime persistence fall behind when intake runs at `6.9k-7.8k` accepted rps.
- Architecture work should now focus less on "can the API accept 5k?" and more on "can the system durably process/drain 5k+ without unbounded backlog?"
