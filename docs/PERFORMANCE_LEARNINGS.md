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

## DigitalOcean Stream-Ack Soak Learnings (July 3, 2026)

From a clean single-droplet DO soak (`stream-ack`, `7500 rps`, `workers=384`, `5m`):

1. The current shape is not a healthy `7500 rps` soak configuration:
   - `867415` attempted
   - `388260` accepted, about `1281 accepted/sec`
   - `310807` worker completed, about `1025 completed/sec`
   - `268782` projected, about `887 projection work items/sec`
2. Protective `429` backpressure worked, but the reject taxonomy showed downstream drain pressure rather than API instability:
   - projector lag backpressure dominated
   - worker stream lag backpressure followed
   - worker failed and ack-failed counters stayed clean
3. Accepted/sec alone is the wrong success target. The next milestone is `2000 completed/sec` sustained for at least `5m`, with accepted throughput within `5-10%` of completed throughput and a clean post-run drain.
4. Projection/UI freshness must be measured separately from venue-core capacity:
   - `control-room-fresh` mode may reject on projection lag
   - `venue-core` mode should report projection lag without letting it define canonical command capacity
5. Runtime and projection Postgres both showed heavy CPU/write pressure. Future runs must report rows/command, WAL bytes/command, commits/command, and partition skew before scaling workers or projectors.

Immediate implications:

1. Do not keep rerunning `7500/384` at the same config.
2. Run venue-core canonical ablations until `2000 completed/sec` is stable for a `5m` soak, then promote to `5000/sec`, then `7500/sec`.
3. Run projector catch-up and hot-partition versus even-distribution ablations before broad scaling.
4. Treat projection write amplification and partition skew as first-class bottleneck suspects.
5. Do not accept configured-rate results as benchmark evidence unless actual attempted and accepted RPS meet the active target; the July 3 follow-up runs under-delivered actual load even when response failures were clean.

Current fix batch:

- Use the deploy-shaped stream-ack profile with `64` partitions, `4` worker processes, and `4` projector processes before the next `2k/sec` soak.
- Keep `venue-core` as the default DO drain-backpressure policy so projection lag is measured but does not throttle canonical command acceptance.
- Gate DO reports on actual attempted and accepted RPS, defaulting to `2000/sec` for the current milestone.

## Stream-Ack Sunset Checkpoint (July 3, 2026)

The `64`-partition, `4`-worker, `4`-projector, venue-core soak did not recover the target and should be treated as a stop point for this architecture shape, not as a prompt for more small tuning.

Clean DO run: `reports/do-benchmark/do-benchmark-20260703T195008Z`

- configured load: `4000 rps`, `768` load-generator workers, `5m`
- actual attempted/accepted: `1636.20 rps`, `496128` accepted, `0` HTTP failures
- worker completed: `1486.67 rps`
- projected: `819.98 rps`, ending projection lag `202156`
- API phase average: `19.52ms`, including `7.77ms` reserve, `6.66ms` publish ack, and `4.13ms` backpressure
- runtime Postgres: about `2.18KB` WAL per accepted command and `4.42` commits per worker-completed command
- projection Postgres: about `6.24KB` WAL per accepted command, with `runtime_events`, `executions`, `trades`, `submit_results`, `orders`, and trace tables dominating write amplification

Conclusion: this stack preserved correctness semantics better than the older path, but it is not a credible base for the `2k/sec` with headroom target. Further work should pivot to a new design rather than continue incremental stream-ack/Postgres projection tuning.

## Stream-Ack Post-Soak Optimization Priorities

This section is retained as historical context from before the sunset checkpoint above. Do not treat it as the active delivery path.

The macro architecture is now in the right family: durable stream ingress, ordered partition workers, canonical facts, and async projections. The remaining performance risk is that the hot path still performs too much database and projection work per command.

Highest-value fixes after the next ablations:

1. Collapse canonical writes while preserving replay and audit:
   - consider compact command/event records or worker-batch event records
   - preserve partition sequence ranges, stream sequence ranges, command counts, event counts, payload format, checksum, and command lookup
   - reduce rows/command, commits/command, WAL bytes/command, and hot indexes
2. Make projectors cheaper:
   - coalesce repeated aggregate updates inside a batch
   - write final current state once per batch
   - move nonessential timeline/search/report writes to slower rebuildable jobs
   - use staging/merge paths and unlogged caches only for rebuildable projection data
3. Treat partition skew as domain signal:
   - even-distribution and hot-book tests measure different capacities
   - more partitions do not fix one legitimately hot instrument that must preserve order
4. Tune configuration only with measurement:
   - NATS pull batch, `MaxAckPending`, worker fetch loop, DB batch size, and pool sizes should move together
   - raising pending limits or pool sizes can hide overload if the DB write path remains the limiter
5. Provision for practical headroom:
   - `2-3x` subsystem headroom over the current target is acceptable when cost and complexity are reasonable
   - avoid `10x` cost/complexity jumps or broad brute-force scaling that hides avoidable write amplification
6. Avoid barely-stable milestones:
   - a `2000 completed/sec` run with saturated CPU/IO, growing lag, slow drain, or no credible path to `5000/sec` is not a capacity win
   - prefer bottleneck-removing changes and practical overcapacity over small parameter nudges that only make one run pass
7. Keep the hard pivot explicit:
   - JetStream as the canonical event log and Postgres as projection/query storage is a reserve option only if compact canonical Postgres append remains the ceiling
   - adopting that option would require a new architecture decision, retention/replay/checksum requirements, and an updated audit/query story

First batch fix applied before the next DO soak:

1. Remove duplicate API-side publish-marker DB pressure from the stream-ack throughput profile.
2. Batch worker publish-marker repair before JetStream ack.
3. Stop duplicating submit lifecycle events into per-event canonical rows in throughput mode; keep the full outcome payload in canonical command results.
4. Avoid nonessential canonical query indexes in throughput mode.
5. Raise worker/projector batch and ack-pending headroom together so the next run tests a meaningfully different drain shape.

## Runtime Library Investigation Priorities

Before swapping libraries, benchmark candidates against Reef's actual command, persistence, and simulator workloads.

Priority order:

1. Runtime DB write path and batching:
   - pgjdbc + HikariCP prepared batches
   - explicit multi-row inserts
   - `reWriteBatchedInserts`
   - `CopyManager`/`COPY` only for append-only bulk, archive, report, and replay-load paths
2. Runtime JSON parser/serializer:
   - current baseline
   - `kotlinx.serialization` as the first typed default candidate
   - DSL-JSON only for ultra-hot DTO spikes after validation behavior is stable
3. Runtime HTTP boundary stack:
   - current JDK `HttpServer`
   - Ktor Netty
   - Vert.x Web
4. Go simulator and fallback codecs:
   - standard library baseline
   - `goccy/go-json`, `sonic`, `segmentio/encoding/json`, `easyjson`
   - tuned `net/http` versus `fasthttp`
   - `klauspost/compress` for archive/report compression

Reference plan:
- [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)

## Industry Patterns To Apply

Use these patterns as implementation priorities for sustained high-throughput operation:

1. Keep hot write paths single-purpose and append-friendly; defer non-critical fan-out work asynchronously.
2. Partition and age off high-volume runtime tables so long soaks do not force full-table growth in the hot path.
3. Tune checkpoint/WAL behavior for sustained write workloads, then validate with soak diagnostics (not just short burst tests).
4. Add explicit ingress protection (rate limits/circuit breakers) so malformed or abusive client traffic cannot starve valid flow.
5. Use disciplined retry/backoff behavior to avoid synchronized retry storms under partial failure.

Reef mapping (near-term):

1. Keep `dev-stress` as the primary harness, and run with DB diagnostics enabled before long soak campaigns.
2. Prioritize runtime event lifecycle controls (partitioning/retention/archival) as the first DB durability milestone.
3. Treat `checkpoints_req` growth and rapid table-size expansion as release-blocking signals for sustained-rate goals.

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
- Performance library investigation: [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)
- Current stress baseline: [`docs/DEV_STRESS_BASELINE_2026-05-23.md`](./DEV_STRESS_BASELINE_2026-05-23.md)
