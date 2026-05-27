# Capacity Baseline Lock + Tuning Validation (2026-05-27)

Lock a high-load baseline for the current simulator/runtime branch and validate targeted `capacity-baseline` action-policy tuning.

## Commands

```bash
docker compose -f docker-compose.yml down --volumes --remove-orphans
JS_RUNTIME=node make dev-up
node scripts/dev/sim-run.mjs --duration 5m --mode capacity-baseline --rate 5000 --workers 512 --pretty-summary --report-out /private/tmp/reef-sim-5k-5m.json
```

Post-tuning validation:

```bash
node scripts/dev/sim-run.mjs --duration 5m --mode capacity-baseline --rate 5000 --workers 512 --pretty-summary --report-out /private/tmp/reef-sim-5k-5m-tuned.json
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
