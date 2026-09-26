# C28–C29: same-prefix projection attribution and lifecycle loop experiment

Started 2026-09-24 22:58:52 UTC; completed 23:29:22 UTC (30m30s), before the firm 23:33:52 UTC deadline. The objective is sustained 10,000 source commands/s through full projection under the unchanged correctness and freshness rules. These are diagnostic experiments, not a promotion claim.

## C28: expose and measure the existing prefix barriers

**Hypothesis.** The 3.13s C26 mean from durable materialization to lifecycle proof hides a material canonical-frontier observation interval, so an isolated lifecycle SQL gain need not improve the end-to-end bound. The discriminator is the first covering marker's own prefix-recorded and lifecycle-covered timestamps. The control is C23/C26's c-32 topology, batch 500, 16 canonical owners, grouped four lifecycle loops plus the market helper, 1000ms diagnostics cadence, and frozen 5s/10s/30s limits. The fields add no SQL, journal write, poll, or changed acceptance arithmetic. Retain only if same-prefix ordering tests and source/cohort authority pass; reject any malformed marker or correctness failure.

The additive diagnostic fields are `prefixRecordedAt` and `lifecycleCoveredAt`. They are copied from the **selected** pending prefix when a marker emits, and are serialized beside its existing database snapshot and response times. The focused `DownstreamProjectionInstrumentationTest` passed. The live marker chain was monotonic, and the C28 analyzer reproduced the published p95/p99/max freshness bounds exactly from 36,259 journal batches and 2,999,868 commands.

| Command-weighted paired interval | Mean | p95 | Interpretation |
| --- | ---: | ---: | --- |
| Engine work finished → durable materializer commit | 330ms | 1,166ms | Existing `sourceToCanonical` label ends here, before normalized canonical projection. |
| Durable commit → lifecycle marker prefix recorded | 1,982ms | 3,701ms | Canonical projector processing **plus** frontier observation delay. |
| That prefix recorded → lifecycle covered | 1,337ms | 2,022ms | Lifecycle work and conservative dirty-queue barrier/observation. |
| Lifecycle covered → lifecycle proof response | <1ms | 2ms | Response serialization is negligible on this marker chain. |
| Market marker lifecycle covered → market snapshot | 956ms | 1,052ms | Market work and its separate barrier/observation. |

The market marker's prefix is independently selected; do not subtract its timestamps from a lifecycle marker to infer service time. Its own durable→prefix mean was 2,048ms and prefix→lifecycle-covered mean 1,302ms. No selected prefix preceded its batch's durable commit. Marker chronology passed `prefixRecordedAt <= lifecycleCoveredAt <= databaseSnapshotAt <= postCommitObservedAt`.

**Outcome.** 2,999,868 accepted, materialized, and canonically projected by final drain; zero reported failures; final queues empty; lifecycle peak 5,567. Source/cohort, observation, queue, clock, topology, and 300s demand authority passed. Freshness failed at lifecycle p95 6,314ms and market p95 7,315ms. These are conservative covering-proof bounds, not exact per-order visibility latencies. Final equality includes drain and does not establish independent in-load processing capacity. Full business reference was deferred for this diagnostic; C22 remains the last full reference pass.

C28 image `sha256:361e76e00287197656a3e3b55ece6f5e1287ab38bfd4a47edf40aabbe84e56bb`, source manifest SHA256 `e28c3865cad0f80be78e129d213e2f0531e259fcfd591dc335a02bce8a4fe9be`, frozen control compose SHA256 `34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`, report SHA256 `85f75657ae6e37851e079bbeee5acbaaea58a599e45a706d587215a432494ba3`. Raw report is retained on the owned host at `/home/reefbench/benchmarks/reef-productive-c28-prefix-10000-300s-1`. [Selected run aggregate](../evidence/throughput-prefix-attribution-c28-2026-09-24.json) and [paired-interval aggregate](../evidence/projection-prefix-attribution-c28-2026-09-24.json) are local. The first aggregate capture command used an incorrect helper path, failed without changing data, and was corrected; the load was not repeated.

## C29: test grouped lifecycle concurrency

**Hypothesis.** If lifecycle consumer capacity contributes materially to C28's 1.34s prefix→coverage interval, doubling the grouped lifecycle loops from four to eight should reduce that interval and lifecycle/market proof bounds while keeping queues bounded. C28 is the control. C29 changes only `ORDER_LIFECYCLE_PROJECTOR_WORKERS` on projector 0 from `4` to `8`; batch 500, image/source, 16 canonical owners, market worker, hardware, workload, diagnostic cadence and limits remain fixed. The fresh fixture is a comparison, not an identical restored-data A/B. The treatment compose SHA256 is `4ab9b671d3bc3a9c529b53bf584ca10c96a0d7c621199ecaaeddd94660e1597b`.

The retention rule is a material decrease in the same-prefix lifecycle interval and freshness bounds with authoritative cohort, zero count gaps/failures/retries/deadlocks, bounded queues, and no business correctness regression. Otherwise restore the exact four-loop compose bytes. Full business reference, recovery, warm/aged, and headroom gates are still required for any winning configuration to be promoted.

**Result: reject and restore four loops.** C29 accepted/materialized/canonically projected 2,999,995 commands by final drain, with zero failures/retries/deadlocks, final queues empty, and lifecycle peak 5,092. The command-weighted residence calculation covered 36,413 batches and reproduced the report percentiles. All source-membership, marker, clock, topology, count, and final-drain checks passed, but the unchanged sampler rule failed: 301/301 HTTP observations succeeded while the maximum gap was **2,005ms > 2,000ms**. Thus C29 has diagnostic interval evidence, not authoritative sustained freshness evidence. The unchanged 5s p95 limit also fails: lifecycle 6,280ms and market 7,280ms.

| Paired command-weighted interval | C28 four loops mean / p95 | C29 eight loops mean / p95 |
| --- | ---: | ---: |
| Durable commit → lifecycle marker prefix recorded | 1,982 / 3,701ms | 2,146 / 4,322ms |
| Same prefix recorded → lifecycle covered | 1,337 / 2,022ms | 1,200 / 2,010ms |
| Engine work finished → lifecycle proof | 3,650 / 6,314ms | 3,633 / 6,280ms |
| Engine work finished → market proof | 4,637 / 7,315ms | 4,580 / 7,280ms |

Eight loops reduced the mean lifecycle-prefix barrier by 137ms, but its p95 changed only 12ms. The durable→prefix segment grew by 164ms mean / 621ms p95. The overall p95 proof bounds improved by only 34–35ms and still fail. These are one fresh run per arm, so the apparent segment tradeoff is not proof that the extra consumers caused canonical slowdown. It is enough to reject eight loops under the predeclared end-to-end retention rule. Full business reference was deferred for the losing treatment.

The exact four-loop control compose bytes were restored after the protocol stopped all writers, with SHA256 `34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`. Both databases and broker containers remain running. C29 raw directory: `/home/reefbench/benchmarks/reef-productive-c29-lifecycle8-10000-300s-1`; report SHA256 `a3646886c830556ee3ba5b3b25a99108816a9d4e4b60667bd6e6aa154e9dd102`. [Run aggregate](../evidence/throughput-lifecycle8-c29-2026-09-24.json) and [diagnostic paired intervals](../evidence/projection-prefix-attribution-c29-2026-09-24.json) retain the failed authority and all measured values.

## Decision

Retain the additive marker timestamps and focused tests, because they passed same-prefix chronology and C28's authoritative cohort calculation without changing acceptance. Keep four lifecycle loops and batch 500. C28 identifies two sizable observation-bound components; C29 shows that simply doubling lifecycle consumers does not produce a material end-to-end gain. The next bounded implementation should capture each canonical projector batch's post-commit watermark and observation time in an in-memory timing journal, keyed by partition/sequence, then pair it with the existing materializer and prefix markers. This would split canonical processing from frontier sampling without a hot-path database write. It needs a size bound, restart/gap checks, and a fresh load comparison before any SQL or worker scheduling change is chosen. This is a proposal, not a measured cure. Promotion still requires fresh authoritative 10k proof, full business reference, recovery, warm/aged and headroom gates.
