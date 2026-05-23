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
- `--mode`: `chaos`, `strict-lifecycle`, or `capacity-baseline`
- `--profile-mm-pct`, `--profile-inst-pct`, `--profile-retail-pct`, `--profile-noise-pct`: worker profile mix, must sum to `100`
- `--tail`: stream newly observed trades/events while running
- `--tail-interval`: tail poll frequency (for example `2s`)
- `--tail-lines`: max trade/event lines printed per poll
- `--qty-min`, `--qty-max`: quantity randomization range
- `--price-min`, `--price-max`: price randomization range
- `--trace-check-limit`: number of unique traces validated at end of run
- `--report-out`: optional JSON report file path
- `--pretty-summary`: print a human-readable summary in console (JSON remains default output)

### Mode Guidance

- `chaos`: randomized actions regardless of order lifecycle state; useful for resilience and rejection-path testing.
- `strict-lifecycle`: forces submit when no local live orders exist and prunes clearly terminal rejected orders from worker state; useful for cleaner business-throughput measurements.
- `capacity-baseline`: throughput-focused profile with submit-heavy behavior and reduced invalid modify/cancel noise; useful for capacity benchmarking.

### Profile Model

The tester now assigns workers to four behavior profiles:
- `market-maker`: tighter prices around mid, smaller frequent quote adjustments
- `institutional`: larger sizes and more modify activity
- `retail`: smaller sizes and submit-heavy behavior
- `noise`: random background flow

### Report Additions

- `acceptedBusinessOpsRps`: successful business operations per second
- `rejectReasons`: grouped rejection code/reason breakdown

Tail usage example:

```bash
go run ./cmd/load-tester \
  --base-url http://localhost:8080 \
  --duration 30s \
  --workers 8 \
  --rate 100 \
  --tail \
  --tail-interval 2s \
  --tail-lines 4
```

Pretty summary example:

```bash
go run ./cmd/load-tester \
  --base-url http://localhost:8080 \
  --duration 30s \
  --workers 8 \
  --rate 100 \
  --mode strict-lifecycle \
  --pretty-summary
```

Environment overrides are supported using `REEF_*` variables that match the flag names, for example `REEF_BASE_URL`, `REEF_WORKERS`, `REEF_RATE`, `REEF_DURATION`.

## Persona Session Direction

The next simulator upgrade is config-driven persona sessions (named actors + strategy params + market universe).

Planning/spec:
- [`docs/SIMULATOR_PERSONA_CONFIG.md`](../../docs/SIMULATOR_PERSONA_CONFIG.md)
- [`docs/SIMULATOR_UPGRADE_BACKLOG.md`](../../docs/SIMULATOR_UPGRADE_BACKLOG.md)

Example session file:
- [`packages/scenario-definitions/persona-session.example.yaml`](../../packages/scenario-definitions/persona-session.example.yaml)

Reference:

- [`docs/ROADMAP.md`](../../docs/ROADMAP.md)
- [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
