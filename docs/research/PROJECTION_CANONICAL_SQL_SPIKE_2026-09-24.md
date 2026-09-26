# C30: canonical projection SQL attribution

Started 2026-09-24 23:46 UTC. This is diagnostic research for sustained 10,000 source commands/s through full materialization and projection. C28 is the matched application and topology control; its freshness proof failed at lifecycle p95 6,314ms and market p95 7,315ms against the unchanged 5,000ms limit. Its final 2,999,868 accepted/materialized/projected count equality includes drain and does not prove in-load capacity or individual row visibility. C29's eight lifecycle loops were rejected and the four-loop control was restored.

## C28 hot-path evidence reviewed

The final successful per-projector C28 telemetry samples sum to 7,777 productive `projector.venueEventBatch.projectionSql` calls and 3,511,296ms of SQL time across 16 canonical writers, or 451.5ms per productive batch. The same samples record 298,442ms for transform (7,790 calls), 154,842ms for canonical reads (24,771 calls), 62,122ms for commits (7,777 calls), and 10,971ms for watermarks (7,777 calls). The enclosing `projectCanonicalCommandOutcomes` calls total 4,200,990ms across 24,755 calls. SQL therefore represents about 84% of that summed enclosing time, subject to different call counts and sampling boundaries. These are summed worker durations, not wall-clock elapsed time or a direct utilization measure. C28 disabled `pg_stat_statements`, so it cannot identify its nested SQL statements.

An earlier C16 single-worker `auto_explain` sample on a different 7.5k fixture measured a 211ms mean full persistence function, with a roughly 122ms status/fill CTE and 63ms timeline CTE. Nested and parent durations overlap. That sample supports a status-stage hypothesis, not C28 stage attribution. A separate older fixed-backlog sample recorded 11.72s for the nested status stage and 6.17s for the timeline body over 408 calls. The workload and age differ from C28.

## C30 diagnostic protocol

Hypothesis: under the current 16-writer 10k workload, one or more nested statements in the status/fill stage consume most canonical SQL time. C30 keeps the C28 application image `sha256:361e76e00287197656a3e3b55ece6f5e1287ab38bfd4a47edf40aabbe84e56bb`, source manifest, 16 canonical writers, grouped four lifecycle loops plus market helper, batch 500, c-32 host, 10k/s target and 300s duration. Its only change is `pg_stat_statements` preloading and nested tracking on the three benchmark databases. It uses a fresh disposable database fixture and preserves C28/C29 raw reports. Diagnostic counters add overhead; any C30 freshness or throughput difference from C28 is not a causal application improvement claim. Full business reference is deferred for this attribution run. The bounded question is which nested statements dominate; retain no code or worker setting from the diagnostic.

The separate remote C30 compose and protocol hashes are `728ad25abf8ba2d7d1eb9d571b82e718b0907848c834bfd8b3454108fb2e8e72` and `795ee6f9972cf83c7a629b1a63d0ba21efdebe716bbe1fe3c1b658c4216b8f25`. Raw output is `/home/reefbench/benchmarks/reef-productive-c30-nested-sql-10000-300s-1` on the owned benchmark host. The source code and frozen C28 control compose bytes were not edited.

## Parallel read-only spikes

The market worker invokes a lifecycle batch before every market batch, adding a fifth lifecycle caller to four dedicated loops. Skipping that call could reduce contention in this exact topology, but the call protects configurations without dedicated lifecycle workers. C29's extra lifecycle concurrency produced no material end-to-end benefit, so this remains a lower-priority hypothesis requiring an opt-in controlled treatment and correctness proof.

The current global covering markers cannot bound first visibility of an individual lifecycle row. They retain no per-order history, and mutable `updated_at` is a transaction-start timestamp rather than a first-visibility clock. A limited sampled `GET /orders/{id}` probe could measure acceptance to first observed lifecycle state for sampled submits, but it would add read load and would not explain canonical SQL cost. It is deferred.

## Result and decision

C30's 300s load accepted, direct-acked, materialized, and canonically projected 2,999,771 commands by final drain; zero reported command failures and no final count/lag gap. The unchanged checker rejected the run because conservative covering freshness failed: lifecycle p95 44,641ms and market p95 46,020ms. Instrumentation changed the workload enough that those bounds must not be compared causally with C28's 6,314/7,315ms. The protocol exit was 1 for that checker result; the stress command exited 0 and cleanup exited 0. Full business reference was deferred. This run is diagnostic only, not a sustained 10k qualification.

`pg_stat_statements` tracked 6,624 full persistence calls. Its nested rows overlap the parent; each line below is a separate view of the same call path, not an additive total:

| Statement scope | Calls | Mean execution/call | Total execution | WAL |
| --- | ---: | ---: | ---: | ---: |
| Full `runtime_persist_submit_outcomes` top-level call | 6,624 | 604.5ms | 4,004,238ms | 15.55GB |
| Nested status/fill multi-table statement | 6,624 | 357.8ms | 2,370,199ms | 8.93GB |
| Nested timeline event/payload statement | 6,624 | 144.7ms | 958,399ms | 6.62GB |
| Separate submit-result conflict check | 6,624 | 43.0ms | 284,629ms | 0 |

This confirms under current load that the status/fill statement is the largest nested component, about 2.5 times the timeline body's tracked execution. The conflict check is material but smaller. The C28 and C30 per-call times are **not** a performance comparison: C30 preloaded nested statement tracking and used a fresh fixture. C16's older profile agrees on the direction, though its absolute times are different. The status statement combines inserts/updates on `submit_results`, `orders`, `executions`, `trades`, and lifecycle dirt, so `pg_stat_statements` alone does not distinguish those sub-writes.

Post-C30 read-only index statistics identify a narrow candidate within the status stage: `idx_submit_results_occurred_typed` is 270MB and recorded zero scans while roughly three million submit results were inserted. Repository runtime reads of `submit_results` use `command_id`; no current runtime query was found that orders or filters by `submit_results.occurred_at_ts`. This is evidence for a controlled index-ablation experiment, **not** proof that the index is globally unused or that dropping it would meet the 5s freshness limit. Other zero-scan indexes support public order/trade reads and should not be removed from this observation.

Next treatment: on a fresh fixture, remove only `idx_submit_results_occurred_typed` through a migration and matching bootstrap change, preserve the primary key and typed columns, run the exact C28 no-profiler 10k/300s control protocol, then compare canonical SQL time, conservative lifecycle/market bounds, counts, full business reference, replay/race tests, and read-query plans. Retain only a measured material improvement with those correctness checks; otherwise restore the index. This is a proposed experiment, not a retained code change. Given the 1.3s C28 lifecycle p95 deficit, a small index gain would be insufficient by itself.

The [sanitized C30 statement aggregate](../evidence/projection-canonical-sql-c30-2026-09-25.json) contains query IDs, call counts, timings, I/O and WAL without SQL text or business rows. The raw report remains on the benchmark host at `/home/reefbench/benchmarks/reef-productive-c30-nested-sql-10000-300s-1`, report SHA256 `0dc9e95796b8967cbd9218a0e67de44ba4db78f9cc18a6123bdd3aacd83317b5`; diagnostics summary SHA256 `9728bb27cbe3e5696ec2e88fb424db67bfe182fda21fb34e93ab55d88fc8df8b`. The C28 control compose remains byte-for-byte at SHA256 `34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`. All runtime writers stopped; five database/broker containers remain running. No application code, SQL migration, or worker configuration was retained from C30.

**Follow-up correction, 2026-09-25:** The proposed single-index treatment was executed as C31 and rejected. An adjacent fresh C32 control addressed a user challenge about fixture/configuration validity. The control returned near C28 SQL and read behavior, while C31 had elevated submit-result table scans and database reads. The exact amplification mechanism remains unproven; no index or bootstrap change was retained. See the [C31–C32 experiment](PROJECTION_SUBMIT_RESULT_INDEX_ABLATION_2026-09-25.md) and ledger for the superseding decision.
