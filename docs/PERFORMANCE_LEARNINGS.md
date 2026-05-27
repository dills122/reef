# Reef Performance Learnings

## Purpose

Capture practical speed and impact lessons so performance stays a design constraint during normal delivery, not a late-stage recovery effort.

## Core Learnings

1. Measure accepted business throughput, not just raw request throughput.
2. Separate business rejections from infrastructure failures before diagnosing performance.
3. Run isolated benchmark sessions for baseline numbers; do not run parallel load tests when establishing capacity.
4. Use connection pooling by default for any persistent store path.
5. Batch lifecycle/event writes on hot paths to reduce round-trips.
6. Avoid aggregate scans (`MAX(...)`) in write-heavy paths; prefer sequence counters or append-safe allocators.
7. Keep benchmark modes explicit:
   - `capacity` mode to stress infra limits
   - `strict-lifecycle` mode to stress correctness and state-machine behavior
8. Always compare clean-reset and aged-state runs. Short tests can look healthy while persistence pressure is building.

## Aged-State Soak Learnings (May 26, 2026)

From a 30-minute fixed-load soak (`capacity-baseline`, `capacity-heavy`, `2500 rps`, `workers=128`):

1. Runtime sustained ~`2412 rps` total and ~`2179 rps` accepted with `p95 ~59ms`.
2. Infra remained stable (no transport failure burst), but Postgres showed heavy WAL/checkpoint pressure:
   - frequent WAL-triggered checkpoints (~every 35-40s)
   - large write amplification in `buffers_backend` and `buffers_alloc`
3. Runtime domain tables grew rapidly (GB-scale in a single soak):
   - `runtime_events`, `executions`, `trades`, `submit_results`, `orders`

Immediate implications:

1. Throughput target is reachable, but long-run stability depends on datastore lifecycle controls.
2. Postgres WAL/checkpoint tuning and data retention/partitioning are not optional follow-up items.

## Performance Budgets

Use these as initial guardrails for simulator/dev-env iteration:

1. Accepted throughput regression budget: no more than 10% drop versus latest baseline.
2. p95 latency regression budget: no more than 20% increase versus latest baseline.
3. Infra failure budget: unexpected `5xx` responses must remain near zero under normal baseline load.
4. Trace integrity budget: trace checks should pass at 99%+ in standard benchmark runs.

## Benchmark Discipline

For performance-sensitive changes, record:

1. Full benchmark command and mode.
2. Test window and date.
3. Throughput (`total` and `accepted`).
4. Latency (`p50`, `p95`, `p99`).
5. Top errors and top reject reasons.
6. Runtime and datastore utilization snapshot.

Store results in `docs/DEV_STRESS_BASELINE_*.md` and link the latest run from sprint trackers.

## PR Performance Checklist

Include this in PR descriptions for runtime/engine/simulator/dev-env changes:

- [ ] Baseline command(s) included
- [ ] Before/after throughput reported (`total` + `accepted`)
- [ ] Before/after p95 latency reported
- [ ] Top errors and reject reasons reported
- [ ] Any new tunables documented (env vars, defaults, expected range)
- [ ] Risk notes added (what could regress under different workloads)

## Operational Defaults

1. Optimize for deterministic reproducibility first, then for peak throughput.
2. Treat performance regressions as test failures when they exceed the budgets above.
3. Add lightweight phase timing (`validate`, `engine`, `persist`) in hot paths before deeper refactors.
4. Prefer reversible tuning flags over one-way hardcoded changes.

## Cross-References

- Steering index: [`docs/steering/README.md`](./steering/README.md)
- Architecture steering: [`docs/steering/architecture.md`](./steering/architecture.md)
- Work plan: [`docs/WORK_PLAN.md`](./WORK_PLAN.md)
- Current stress baseline: [`docs/DEV_STRESS_BASELINE_2026-05-23.md`](./DEV_STRESS_BASELINE_2026-05-23.md)
