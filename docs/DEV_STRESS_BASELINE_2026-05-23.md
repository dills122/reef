# Dev Stress Baseline (2026-05-23)

Environment:
- Dockerized local stack
- runtime base URL: `http://localhost:18080`
- matching-engine URL: `http://localhost:18081`
- load profile: strict lifecycle, 12 workers, stepped rates `100 -> 200 -> 300 -> 400`
- duration per step: `10s`

Artifacts:
- `/tmp/reef-load-report-dev-stress-rate-100.json`
- `/tmp/reef-load-report-dev-stress-rate-200.json`
- `/tmp/reef-load-report-dev-stress-rate-300.json`
- `/tmp/reef-load-report-dev-stress-rate-400.json`

## Results

| Rate | Throughput RPS | Accepted RPS | Success % | p95 ms | p99 ms | Trace Pass % |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 22.81 | 17.50 | 76.73 | 875.11 | 1538.44 | 100.00 |
| 200 | 25.11 | 19.51 | 77.70 | 763.01 | 1443.92 | 96.00 |
| 300 | 26.68 | 19.05 | 71.38 | 769.21 | 1184.30 | 98.00 |
| 400 | 27.80 | 19.93 | 71.72 | 789.32 | 1070.29 | 95.00 |

## Baseline thresholds (dev env)

These thresholds are practical early guardrails for the current local stack:

- success rate: `>= 70%` across stepped rates
- trace pass rate: `>= 95%` across stepped rates
- throughput floor at 400 configured rate: `>= 25 rps`
- p95 latency ceiling: `<= 900 ms`

Outcome:
- all above thresholds passed in this baseline run

## Notes

- This baseline is for local dev-env regression detection, not production sizing.
- Re-run after major runtime/engine/persistence changes and compare against this file.
