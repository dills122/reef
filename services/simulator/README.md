# Simulator

This directory contains CLI simulation and load-testing tools for Reef.

## Load Tester CLI

`cmd/load-tester` drives mixed submit/modify/cancel traffic against the platform runtime API and prints a rich JSON session report.

What it includes:
- configurable concurrency, rate, duration, timeout
- configurable action mix percentages
- automatic reference-data seeding
- latency percentiles (global + per-action)
- status-code and error breakdown
- trace integrity checks (`/traces/{traceId}/events`) for sequence continuity

### Run

```bash
cd services/simulator
go run ./cmd/load-tester \
  --base-url http://localhost:8080 \
  --duration 45s \
  --workers 12 \
  --rate 200 \
  --mode strict-lifecycle \
  --submit-pct 60 \
  --modify-pct 25 \
  --cancel-pct 15 \
  --trace-check-limit 100 \
  --report-out /tmp/reef-load-report.json
```

### Key Flags

- `--base-url`: runtime base URL
- `--duration`: total run time
- `--workers`: concurrent workers
- `--rate`: global requests/sec (`0` = unthrottled)
- `--timeout`: per-request timeout
- `--submit-pct`, `--modify-pct`, `--cancel-pct`: must sum to `100`
- `--mode`: `chaos` or `strict-lifecycle`
- `--qty-min`, `--qty-max`: quantity randomization range
- `--price-min`, `--price-max`: price randomization range
- `--trace-check-limit`: number of unique traces validated at end of run
- `--report-out`: optional JSON report file path

### Mode Guidance

- `chaos`: randomized actions regardless of order lifecycle state; useful for resilience and rejection-path testing.
- `strict-lifecycle`: forces submit when no local live orders exist and prunes clearly terminal rejected orders from worker state; useful for cleaner business-throughput measurements.

### Report Additions

- `acceptedBusinessOpsRps`: successful business operations per second
- `rejectReasons`: grouped rejection code/reason breakdown

Environment overrides are supported using `REEF_*` variables that match the flag names, for example `REEF_BASE_URL`, `REEF_WORKERS`, `REEF_RATE`, `REEF_DURATION`.

Reference:

- [`docs/ROADMAP.md`](../../docs/ROADMAP.md)
- [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
