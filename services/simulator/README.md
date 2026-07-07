# Simulator

This directory contains CLI simulation and load-testing tools for Reef.

## Fast Tests And Long Runs

Fast simulator tests are part of the normal repository gate:

```bash
make test-simulator
make test
```

Long-running load, stress, throughput-campaign, and soak runs remain explicit
developer/operations commands (`make dev-stress`, `make dev-throughput-campaign`,
and related scripts). Do not move those into the fast test target without a
separate CI/runtime-budget decision.

## Load Tester CLI

`cmd/load-tester` drives mixed submit/modify/cancel traffic against the platform runtime API and prints a rich JSON session report.

What it includes:
- configurable concurrency, rate, duration, timeout
- configurable action mix percentages
- automatic reference-data and order-actor role seeding
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
- `--strict-min-live-orders`: strict-mode minimum local live-order depth required before modify/cancel
- `--report-out`: optional JSON report file path
- `--pretty-summary`: print a human-readable summary in console (JSON remains default output)

### Mode Guidance

- `chaos`: randomized actions regardless of order lifecycle state; useful for resilience and rejection-path testing.
- `strict-lifecycle`: forces submit when no local live orders exist, prunes clearly terminal rejected orders from worker state, and briefly backs off to submit-only recovery after stale lifecycle rejects; useful for cleaner business-throughput measurements.
- `capacity-baseline`: throughput-focused profile with submit-heavy behavior and reduced invalid modify/cancel noise; useful for capacity benchmarking.

### Profile Model

The tester now assigns workers to four behavior profiles:
- `market-maker`: tighter prices around mid, smaller frequent quote adjustments
- `institutional`: larger sizes and more modify activity
- `retail`: smaller sizes and submit-heavy behavior
- `noise`: random background flow

Before traffic starts, the load tester seeds an `order_trader` role with
`order.submit`, `order.cancel`, and `order.modify`, then binds every emitted
actor ID to that role. Persona sessions seed their named actors; default runs
seed `bot-{worker}` actors.

### Report Additions

- `acceptedBusinessOpsRps`: successful business operations per second
- `throughput`: canonical metric taxonomy with `attemptedPerSecond`, `acceptedPerSecond`, and optional `completedPerSecond`, `projectedPerSecond`, and `visiblePerSecond`
- `quality`: end-to-end success, valid-intent success, invalid-intent rate, and system-failure proxy metrics
- `rejectReasons`: grouped rejection code/reason breakdown
- `rejectTaxonomy`: reject-code counts with percentage-of-failures/rejects (includes boundary error envelopes such as `ABUSE_BLOCKED` on non-2xx responses when present)

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
Set `--seed` or `REEF_SEED` to force deterministic worker random sources for non-session runs.

## Multi-Seed Batch Runs

`make dev-sim-batch` runs the load tester repeatedly with different deterministic seeds and writes one report per seed plus an aggregate report.

```bash
DEV_SIM_BATCH_SEEDS=101,202,303 \
DEV_SIM_BATCH_ARTIFACT_DIR=/tmp/reef-sim-batch \
make dev-sim-batch ARGS="--duration 20s --workers 8 --rate 120 --mode strict-lifecycle"
```

The aggregate report summarizes totals, canonical throughput fields, p95 latency, trace pass rate, and per-seed report paths.

## Scenario Drift Checks

`cmd/scenario-plan` compiles a scenario definition into deterministic executable command steps plus replay/final-state assertions. It does not require the local stack; it is the first P1 harness gate before live API/projection assertions.

```bash
make dev-scenario-plan
```

`cmd/scenario-smoke` builds on the same compiled plan. By default it is a dry-run that emits seed requests and executable `/api/v1/orders/submit` requests without sending them. Add `ARGS="--live --base-url http://127.0.0.1:8080"` to seed reference/auth data, send executable submit requests, and wait for command status visibility.

```bash
make dev-scenario-smoke ARGS="--pretty"
make dev-scenario-smoke ARGS="--live --base-url http://127.0.0.1:8080 --pretty"
```

For the P1 live assertion path, add `--assertions`. The report records command completion, own-order final lifecycle and filled quantities, public trade tape facts, public depth non-leak proof, `/api/v1/data/availability` source/freshness metadata, and projection lag rows.

```bash
make dev-scenario-smoke ARGS="--live --base-url http://127.0.0.1:8080 --assertions --pretty"
```

For the P2 settlement assertion path, seed the narrow settlement fact bundle before the assertion smoke so the report reads the runtime API rather than an offline artifact:

```bash
make dev-scenario-smoke SCENARIO=../../packages/scenario-definitions/scenarios/v1/P2_SETTLEMENT_BREAK_REPAIR.yaml SCENARIO_RUN_ID=p2-settlement-live ARGS="--live --base-url http://127.0.0.1:8080"
make dev-seed-p2-settlement-facts SCENARIO_RUN_ID=p2-settlement-live
make dev-scenario-smoke SCENARIO=../../packages/scenario-definitions/scenarios/v1/P2_SETTLEMENT_BREAK_REPAIR.yaml SCENARIO_RUN_ID=p2-settlement-live ARGS="--live --base-url http://127.0.0.1:8080 --assertions --seed-reference=false --pretty"
```

The P1 dry-run golden report is checked in at [`replay/golden/p1-golden-hidden-cross.smoke.json`](replay/golden/p1-golden-hidden-cross.smoke.json). Refresh it only when the scenario contract intentionally changes:

```bash
make dev-scenario-smoke ARGS="--scenario-run-id p1-golden-hidden-cross-golden --pretty --report-out replay/golden/p1-golden-hidden-cross.smoke.json"
```

Direct use from `services/simulator`:

```bash
go run ./cmd/scenario-plan \
  --scenario ../../packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml \
  --scenario-run-id p1-golden-hidden-cross-local \
  --pretty
```

```bash
go run ./cmd/scenario-smoke \
  --scenario ../../packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml \
  --scenario-run-id p1-golden-hidden-cross-local \
  --pretty
```

`make dev-scenario-drift-check` compares a report against either:

- the existing replay threshold baseline shape used by `make dev-replay`
- a stable report fingerprint generated from a known-good report

Generate a stable baseline:

```bash
make dev-scenario-drift-check ARGS="--report /tmp/reef-load-report.json --write-baseline /tmp/reef-scenario.baseline.json"
```

Check later report drift:

```bash
make dev-scenario-drift-check ARGS="--baseline /tmp/reef-scenario.baseline.json --report /tmp/reef-load-report.json --out /tmp/reef-scenario.check.json"
```

The stable fingerprint intentionally excludes wall-clock timestamps and latency percentiles. It compares deterministic counts, status codes, action counts, reject taxonomy, quality counts, trace-check counts, scenario ID, mode, and seed.

## Intake Bench CLI

`cmd/intake-bench` is a narrower accepted-command ingress benchmark. It sends minimal valid `/api/v1/orders/submit` payloads and reports raw accepted RPS, latency percentiles, status-code counts, and errors. It intentionally skips strategy selection, lifecycle state, seeding, trace checks, and tailing so it can separate platform-runtime intake capacity from simulator behavior.

Example:

```bash
cd services/simulator
go run ./cmd/intake-bench \
  --base-url http://localhost:8080 \
  --duration 30s \
  --workers 256 \
  --rate 10000 \
  --rate-schedule precise \
  --actor-id-prefix bot \
  --pretty-summary \
  --report-out /tmp/reef-intake-bench.json
```

From the repo root, use:

```bash
make dev-intake-bench
```

Useful env overrides:

- `DEV_INTAKE_DURATION`
- `DEV_INTAKE_WORKERS`
- `DEV_INTAKE_RATE`
- `DEV_INTAKE_RATE_SCHEDULE=drop|precise`
- `DEV_INTAKE_ACTOR_ID_PREFIX` (`bot` by default, matching seeded load-test actors)
- `DEV_INTAKE_ARTIFACT_DIR`
- `DEV_INTAKE_REPORT_OUT`

Deterministic replay runs can set `--command-clock-start 2026-03-14T18:00:00Z`
and `--command-clock-step 1s` (or `REEF_COMMAND_CLOCK_START` /
`REEF_COMMAND_CLOCK_STEP`) so generated command `occurredAt` values are stable.

## Persona Session Support

The load tester supports config-driven persona sessions (named actors, strategies, market universe, groups, deterministic seeds, and deterministic faults).

Planning/spec:
- [`docs/SIMULATOR_PERSONA_CONFIG.md`](../../docs/SIMULATOR_PERSONA_CONFIG.md)
- [`docs/SIMULATOR_UPGRADE_BACKLOG.md`](../../docs/SIMULATOR_UPGRADE_BACKLOG.md)

Example session file:
- [`packages/scenario-definitions/persona-session.example.yaml`](../../packages/scenario-definitions/persona-session.example.yaml)

Replay drift check against baseline:

```bash
make dev-replay
```

Reference:

- [`docs/ROADMAP.md`](../../docs/archive/ROADMAP.md)
- [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
