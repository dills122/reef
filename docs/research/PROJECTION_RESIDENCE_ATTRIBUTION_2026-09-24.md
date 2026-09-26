# C27 — retained telemetry residence attribution

Status: bounded offline analysis complete.15-minute cap, start22:28:10UTC. No runtime/config/SQL changes, no database queries, no new load. Owner: throughput workstream. Question: which part of C26's6–7s freshness bound should the next change target? Stop at a supported target or smallest missing measurement.

## Decision

Retained data cannot identify whether canonical projection, lifecycle processing or conservative completion-proof timing dominates the remaining interval. Do not guess another SQL optimization. Smallest next change: serialize two timestamps already retained inside each pending prefix: `observedThrough` as `prefixRecordedAt`, and `lifecycleCoveredAt`. Use both from the **same emitted marker**, alongside existing database snapshot and response times. No new database query, journal, poll, or business write is needed to expose them. Existing freshness arithmetic, marker identity, cadence and thresholds must remain unchanged.

These fields support observation-bound stage attribution, not exact per-command commit latency. `prefixRecordedAt` is the later dirty-queue database snapshot timestamp used when the observer records an already-read canonical-projector frontier; it must not be called a canonical commit timestamp. A marker may cover commands that appeared in an earlier frontier too; this is not necessarily the earliest observation of each individual command.

## Reproduced measurements

Input: C26 report SHA256 `06a0ba56df568a4b87dcb1e3048cc4168fb7fb8c1357bff96e8d62520b68b12f`;36,248 materializer journal batches covering exactly2,999,951 commands. Source/cohort authority passes. Recomputed source/lifecycle/market p95,p99,max equal published values. Every interval below is calculated per source batch with exact command weights; no subtraction of separate percentiles.

| Interval | Mean | p95 | Meaning |
|---|---:|---:|---|
| Engine work finished → durable materializer commit observed | 285ms | 847ms | Existing `sourceToCanonical` label; **does not end at normalized canonical-projector completion** |
| Durable materializer commit → lifecycle covering proof | 3,130ms | 5,289ms | Canonical projection + lifecycle work + conservative proof/observation delay, not separated |
| Engine work finished → lifecycle proof | 3,415ms | 6,050ms | Existing frozen freshness bound |
| Market proof timestamp minus separate lifecycle proof timestamp | 972ms | 1,978ms | Difference between independent proof observations, not serial market service time |
| Engine work finished → market proof | 4,387ms | 7,037ms | Existing frozen freshness bound |

100,627commands (3.35%) have market proof observed before the independently emitted lifecycle proof, minimum difference−1,982ms. This is not evidence that market processing precedes lifecycle correctness: observers maintain separate pending-prefix queues and can emit different covering prefixes at different times. It demonstrates that subtracting their timestamps is not a causal stage-duration measurement. A same-prefix marker chain is needed.

298,952commands (9.97%) exceed the5s lifecycle proof bound;741,041 (24.70%) exceed market bound. For commands exceeding the lifecycle bound, paired means are828ms to durable materialization and5,342ms from there to lifecycle proof. These remain bound exceedances, not proof of exact per-command visibility delay.

## What queue and observation data establish

-302 successful in-window queue samples. Lifecycle nonempty oldest-age p951,494ms/max2,031ms (299samples); market942ms/max1,583ms (280samples). These are time-sampled oldest pending ages, not command-weighted residence. Do not subtract them from source-to-proof percentiles or substitute them for the SLO.
- Lifecycle/market diagnostics HTTP latency means50.1/50.4ms, p9595/93ms, maxima160ms. Snapshot-to-marker-response p95 is2ms for both. Multi-second response serialization is not supported as the explanation in this run.
- Lifecycle distinct markers234, inter-marker gap p952,011ms; market220,p952,045ms. Successful1000ms sampling does not guarantee a new covering marker each second; queue/prefix barrier conditions control emission.
- Canonical-projector status exists only at six main-telemetry observations roughly55s apart: first is before load, next three cover16partitions with reported total lag18,589/6,169/11,340; last two cover only4partitions. Many status HTTP calls hit2000ms timeout late in run. The incomplete4partition totals are not venue-wide lag. Neither these sparse counts nor dividing them by target rate yields command latency.

## Code-grounded interpretation

`VenueEventBatchMaterializer.materializeAndAck` records `canonicalCommitObservedAt` after `materializeVenueEventBatches` returns. The subsequent canonical projector must still advance projection-store watermarks and produce lifecycle dirty work. `DiagnosticsGateway.downstreamProjectionInstrumentation` reads that committed projector frontier before sampling dirty queues. `DownstreamProjectionCoverageMetrics.observe` retains `PendingPrefix.observedThrough`, later `lifecycleCoveredAt`, and requires an additional market barrier; current serialized markers omit both retained timestamps. `cohort-residence.mjs` chooses first covering emitted observation for each batch.

Missing middle-stage information is structural, not recoverable from more arithmetic on retained queue ages. This corrects an ambiguous metric interpretation; it does not retroactively change C26's failed qualification or rehabilitate rejected0059.

## Exact next implementation scope and gate

Add additive diagnostic marker fields `prefixRecordedAt` and `lifecycleCoveredAt`, populated from the selected pending prefix when the marker is emitted. Preserve clock/generation checks and original marker ID/watermarks/timestamps. Test that fields come from the same prefix and remain monotonic (`prefixRecordedAt <= lifecycleCoveredAt <= databaseSnapshotAt <= postCommitObservedAt`), including busy queues and independently advancing observers. Do not change acceptance decisions or replace existing residence metrics.

For each source batch covered by a market marker, retain this observation-bound chain:
1. Source work finished → durable materializer commit.
2. Durable materializer commit → selected prefix recorded (includes canonical projection plus observation delay).
3. Prefix recorded → lifecycle covered (queue/proof wait).
4. Lifecycle covered → market marker database snapshot (market queue/proof wait).
5. Snapshot → emitted response.

The measurements still do not provide exact individual commit times, but can distinguish which bound segment warrants attention. Only then choose a targeted processing/observer implementation change. Keep500 control,1000ms cadence,2000ms sampler-gap limit,5s/10s/30s freshness limits, and all correctness requirements. The scope of this analysis step excluded new load; none was run.

## Evidence and reproducibility

[Aggregate output](../evidence/projection-residence-attribution-2026-09-24.json). Read-only analyzer `/home/reefbench/benchmarks/analyze-c26-residence.py`; input reports remain under `/home/reefbench/benchmarks/reef-productive-keyedexecution-10000-300s-1`. Analyzer SHA and retained local copy live in original checkout artifacts. No raw rows, journal memberships or query text exported. Sanitized aggregate export only. No new independent review or infrastructure change.

## Five-minute follow-up: recovery feasibility

Additional source inspection on2026-09-24, started22:47:36UTC and stopped before22:52:36UTC. No new load, runtime edits or database queries. Current diagnostics serialize only covered-marker watermarks, not the current frontier used when each pending prefix was recorded. Selected pending prefixes are removed on emission, and their intermediate timestamps are omitted from the marker. Retained telemetry therefore cannot reliably recover that same-prefix chain.

Existing `busyQueuesCoverOlderPrefixesAfterSeparateLifecycleAndMarketBarriers` test supplies a concrete synthetic example: prefix sequence10 recorded at second1, lifecycle barrier observed at second3, market barrier at second5. Emitted market marker reports sequence10 and response time5.100, omitting the earlier two times. These test times demonstrate lost attribution, not measured production delay.

Stop decision: no further offline search or SQL tuning justified by this follow-up. Existing two-field proposal remains the smallest useful next diagnostic change; it partitions observation bounds and cannot by itself prove exact processing latency or meet the freshness target.

Follow-up C28 implemented and measured those two marker fields; C29 tested grouped lifecycle concurrency. Results and limits are in [the prefix experiment](PROJECTION_PREFIX_EXPERIMENT_2026-09-24.md). The preceding proposal was the state at C27, not the current implementation status.
