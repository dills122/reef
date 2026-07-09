# Capacity Baseline Lock + Tuning Validation (2026-05-27)

Lock a high-load baseline for the current simulator/runtime branch and validate targeted `capacity-baseline` action-policy tuning.

## Commands

```bash
docker compose -f docker-compose.yml down --volumes --remove-orphans
JS_RUNTIME=node make dev-up
node scripts/dev/reef-dev.mjs sim run --duration 5m --mode capacity-baseline --rate 5000 --workers 512 --pretty-summary --report-out /private/tmp/reef-sim-5k-5m.json
```

Post-tuning validation:

```bash
node scripts/dev/reef-dev.mjs sim run --duration 5m --mode capacity-baseline --rate 5000 --workers 512 --pretty-summary --report-out /private/tmp/reef-sim-5k-5m-tuned.json
```

## Baseline (Pre-Tuning)

- artifact: `/private/tmp/reef-sim-5k-5m-baseline-pre-tuning.json`
- throughput: `2091.89 rps`
- accepted throughput: `1884.95 rps`
- success rate: `90.11%`
- p95 / p99 latency: `404.83ms / 566.72ms`
- failures: `62,214`
- dominant reject class: `INVALID_STATE` (`61,880`, `99.46%` of rejects)

## Candidate (Post-Tuning)

- artifact: `/private/tmp/reef-sim-5k-5m-tuned.json`
- throughput: `2478.04 rps`
- accepted throughput: `2439.98 rps`
- success rate: `98.46%`
- p95 / p99 latency: `285.86ms / 416.36ms`
- failures: `11,428`
- dominant reject class: `INVALID_STATE` (`11,032`, `96.53%` of rejects)

## Comparator (Candidate - Baseline)

- throughput: `+386.15 rps`
- accepted throughput: `+555.03 rps`
- success rate: `+8.36 pts`
- p95 latency: `-118.97ms`
- p99 latency: `-150.36ms`
- failures: `-50,786`

## Notes

1. Current tuning meets the short-term success objective (`>=95%`) in this high-rate profile.
2. Trace integrity remained stable (`50/50` pass in both runs).
3. Remaining rejects are still mostly lifecycle-state business outcomes, not transport/runtime instability.

## Post-Fix Validation (ID Namespacing + Clean Reset)

- artifact: `/private/tmp/reef-sim-5k-5m-post-fix.json`
- throughput: `2694.15 rps`
- accepted throughput: `2655.29 rps`
- success rate: `98.56%`
- p95 / p99 latency: `236.28ms / 354.31ms`
- failures: `11,668`
- reject taxonomy: `INVALID_STATE` only (`11,668`, `100%`)
- trace integrity: `50/50` pass

### Comparator (Post-Fix - Post-Tuning Candidate)

- throughput: `+216.11 rps`
- accepted throughput: `+215.31 rps`
- success rate: `+0.10 pts`
- p95 latency: `-49.58ms`
- p99 latency: `-62.05ms`
- failures: `+240` (with materially higher total throughput)

### Key Outcome

- `REFERENCE_DATA_ERROR` rejects dropped to `0` in the 5-minute high-load run.

## Quick-Win Transport + Pool Sweep (2026-06-03)

Clean `60s` capacity-baseline checks on branch `codex/throughput-quick-wins`:

| Profile | Throughput | Accepted | Success | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| HTTP defaults (`32` runtime threads, DB pool `24`) | `2233.25 rps` | `2202.08 rps` | `98.60%` | `337.33ms` | `539.87ms` |
| gRPC transport (`32` runtime threads, DB pool `24`) | `2421.76 rps` | `2387.20 rps` | `98.57%` | `296.61ms` | `396.38ms` |
| gRPC + tuned runtime (`64` runtime threads, DB pool `48`, min idle `16`) | `2852.64 rps` | `2810.50 rps` | `98.52%` | `249.13ms` | `387.92ms` |
| gRPC + tuned runtime ceiling (`6500` target, `768` workers) | `2961.43 rps` | `2919.27 rps` | `98.58%` | `325.39ms` | `484.59ms` |
| gRPC + tuned runtime overdrive (`8000` target, `1024` workers) | `2868.83 rps` | `2828.26 rps` | `98.59%` | `474.70ms` | `701.00ms` |

Outcome:
- gRPC + tuned runtime improved accepted throughput by `+27.6%` over same-run HTTP defaults.
- The tuned profile also cleared the prior 5-minute post-fix accepted baseline by `+5.8%`.
- The best measured ceiling point in this sweep was `6500` target / `768` workers; pushing to `8000` / `1024` reduced accepted throughput and materially worsened p99.
- Docker dev defaults now use the tuned profile; override `ENGINE_TRANSPORT=http`, `MATCHING_ENGINE_ENABLE_GRPC=0`, `PLATFORM_HTTP_THREADS=32`, `RUNTIME_DB_POOL_MAX=24`, and `RUNTIME_DB_POOL_MIN_IDLE=4` to reproduce the old HTTP profile.
