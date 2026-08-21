# Fresh Review Bootstrap: Projection Throughput Diagnosis

## Review objective

Conduct a fresh-context, read-only engineering review of Reef's current
projection-throughput problem and the uncommitted Gate 0 implementation.
Determine whether the repository implementation and benchmark artifacts
support the proposed problem framing, bottleneck hypotheses, investigation
sequence, configuration guard, observability, and fixed-backlog drain harness.

Review both:

1. the current implementation and deployed benchmark topology;
2. the proposed plan for reaching sustained full-projection freshness at
   `5k/s`, then `7.5k/s` and `10k/s`.

Do not implement fixes, edit files, commit, push, or change external state.

## Repository location and state

- Repository: `/Users/dsteele/repos/reef`
- Branch: `codex/fix-hot-path-invariants`
- Review baseline commit: `bd92423a5f4732ff1eb0cc645dec41a31b45425d`
- Base branch merge point: `fe2b611ea58f82e3a8ec5d79115d41a0ca072aab`
  (`origin/master` when this packet was refreshed)
- Review target: all tracked modifications and untracked files in the working
  tree relative to the review baseline. Do not omit dirty or untracked files.
- The working tree includes pre-Gate-0 research documents plus the Gate 0 code,
  tests, scripts, and documentation. Independently verify their claims.

## Canonical context to inspect first

- `AGENTS.md`
- `REEF_PROJECT_OVERVIEW.md`
- `REEF_TECHNICAL_DESIGN.md`
- `docs/steering/README.md`
- `docs/steering/repository-scope-and-priorities.md`
- `docs/steering/architecture.md`
- `docs/steering/repository.md`
- `docs/PERFORMANCE_LEARNINGS.md`
- `docs/ENGINEERING_DELIVERY_POLICY.md`
- `docs/DECISIONS.md`
- `docs/PROJECTION_THROUGHPUT_SCALING_PLAN.md`

## Implementation scope

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/CanonicalProjectionWorker.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/RuntimeLoopStarter.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/OrderLifecycleProjectionWorker.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/MarketDataProjectionWorker.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/RuntimeDataSources.kt`
- `scripts/dev/db/migrations/runtime/0040_split_submit_outcome_projection_stages.sql`
- `scripts/dev/db/migrations/runtime/0043_runtime_event_payload_cold_table.sql`
- `scripts/dev/db/migrations/runtime/0046_order_modified_lifecycle_index.sql`
- `scripts/dev/db/migrations/runtime/0049_execution_replay_conflicts.sql`
- `compose.base.yml`
- `compose.local.yml`
- `scripts/dev/do-benchmark-host.sh`
- `scripts/dev/stress.mjs`
- `scripts/dev/do-benchmark-check.mjs`
- `scripts/dev/do-benchmark-check.test.mjs`
- `scripts/dev/projection-drain-bench.mjs`
- `scripts/dev/lib/projection-drain-report.mjs`
- `scripts/dev/lib/projection-drain-report.test.mjs`
- `scripts/dev/lib/db-diagnostics.mjs`
- `scripts/dev/lib/db-diagnostics.test.mjs`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformRuntimeProfileValidation.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/DiagnosticsGateway.kt`
- `services/platform-runtime/src/test/kotlin/com/reef/platform/api/PlatformRuntimeProfileValidationTest.kt`
- `services/platform-runtime/src/test/kotlin/com/reef/platform/api/StreamCommandWorkerTest.kt`
- `Makefile`

## Gate 0 success criteria

- A command-status-only projector cannot run alongside lifecycle-dependent
  consumers without an explicit diagnostic-ablation acknowledgement.
- Projector telemetry distinguishes batch count/size and canonical-read,
  transform, projection-SQL, watermark, and commit phases.
- Stress telemetry captures hot-path and DB-pool state from every projector;
  the evidence checker fails closed when a required pool probe is missing.
- A projection-only benchmark measures an already-created fixed backlog,
  reports drain rate/duration/final lag/lag area, and fails when canonical input
  changes during the measured window.
- No projection SQL shape, durable facts, watermark transaction semantics, or
  DigitalOcean infrastructure is changed by Gate 0.

## Benchmark evidence

Inspect exact reports and diagnostics, not only prose summaries:

- `reports/do-benchmark/do-benchmark-20260712T143401Z/`
- `reports/do-benchmark/do-benchmark-20260820T220557Z/`
- `reports/do-benchmark/do-benchmark-20260820T222945Z/`
- `reports/do-benchmark/do-benchmark-20260820T230011Z/`

Check at minimum:

- whether historical and current `10k` runs had projection enabled;
- whether the `5k/5m` run used the intended full projection topology;
- accepted, materialized, projected, gap, and watermark-lag counts;
- per-projector and per-partition distribution;
- PostgreSQL WAL, temp, tuple, table/index, activity, wait, and vacuum evidence;
- whether reported projected rate mixes load and drain windows;
- whether evidence distinguishes observation from inference.

## Preliminary-pass rule

Before reading the author explanation or system overview, inspect the canonical
docs, implementation, migrations, benchmark configuration, and raw artifacts.
Record a preliminary view of:

- actual architecture and transaction boundaries;
- what `10k/s` does and does not prove;
- whether `5k/5m` was configured correctly;
- the top three likely bottlenecks;
- any correctness hazard in independently running status, timeline, lifecycle,
  or market-data projection stages;
- missing evidence required before implementation.

Only after that preliminary pass should the author explanation be supplied.

## Explicit exclusions

- Do not review unrelated API, matching-engine, terminal-retention, or CI work
  except where it changes benchmark validity.
- Do not treat a short burst as evidence of sustained projection capacity.
- Do not recommend weakening `fsync`, `full_page_writes`, durable ingress,
  broker acknowledgement, deterministic ordering, replay conflict detection,
  or transactional watermarks.
- Do not assume a technology migration is justified without comparing it to
  measured changes within the current PostgreSQL architecture.

## Expected review report

Report in this order:

1. findings first, ordered by severity, with exact repository locations and
   evidence;
2. review of the proposed investigation plan;
3. claim-by-claim reconciliation against the author explanation;
4. verification performed and evidence not available;
5. residual correctness and performance risk;
6. verdict: `approve`, `approve with changes`, or `reject`.

Specifically challenge:

- whether projection SQL/write amplification is sufficiently supported as the
  primary bottleneck;
- whether canonical polling, JVM transform, connection pools, lifecycle worker
  multiplicity, or autovacuum could instead be primary;
- whether the proposed instrumentation can distinguish those causes;
- whether the order-state/timeline split preserves cancel/modify correctness;
- whether `6k/9k/12k` drain-headroom gates are defensible;
- whether any cheaper or safer experiment is missing.

## Verification already run by the author

- `cd services/platform-runtime && ./gradlew test` — passed.
- `node scripts/dev/lib/projection-drain-report.test.mjs` — passed.
- `node scripts/dev/do-benchmark-check.test.mjs` — passed.
- `node scripts/dev/script-surface-check.mjs` — passed.
- `bash -n scripts/dev/do-benchmark-host.sh` — passed.
- `docker compose -f compose.base.yml -f compose.local.yml config --quiet` —
  passed.
- `make test-dev-tooling JS_RUNTIME=node` — passed.
- `git diff --check` — passed before the review packet refresh; rerun it.

A live fixed-backlog drain has now been run in an isolated local Compose
project. It drained an observed `52,250` items to zero in `13.297s`
(`3,929.46/s`) with stable canonical ceilings, no projector failures/retries,
and no connection waiters; projection SQL dominated the instrumented phase
timers. Inspect
[`PROJECTION_DRAIN_LOCAL_VALIDATION_2026-08-20.md`](./PROJECTION_DRAIN_LOCAL_VALIDATION_2026-08-20.md)
and its stated limitations. No paid DigitalOcean benchmark has been run for
this implementation, and the local rate is not promotion evidence.

The named remote projection gate has also been extended to require pre/post
`pg_stat_statements` evidence with nested-statement tracking and I/O timing. A
disposable local PostgreSQL check proved the collector can distinguish a
top-level PL/pgSQL call from its internal statement and preserve 64-bit query
IDs. No paid run has used those settings yet, so they are collection readiness,
not bottleneck attribution from the DigitalOcean host.

A follow-up local cardinality A/B now exists. With four canonical writers but
only projector 0 running lifecycle/market-data maintenance, `100,002` fixed
outcomes completed within `10.383s` of container start (`>=9,631.32/s`), both
dirty queues cleared, and no failure/retry/deadlock occurred. Review
[`PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md`](./PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md),
including its excluded setup attempt and non-identical-window caveat. The named
remote gate now selects this topology, records each projector's effective
maintainer flags, and fails unless exactly one lifecycle and one market-data
maintainer are observed. No paid rerun has validated it.

## Author explanation handoff

After the preliminary pass, read:

- `docs/research/PROJECTION_THROUGHPUT_AUTHOR_EXPLANATION_2026-08-20.md`

The author explanation links to the complete system overview and research
spike. Treat those as claims to reconcile, not as authority.
