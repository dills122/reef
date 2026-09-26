# C31: submit-result time-index ablation

Started 2026-09-25. The objective remains sustained 10,000 source commands/s through full projection with unchanged correctness, replay and 5s/10s/30s conservative freshness gates. C28 is the no-profiler control: 2,999,868 accepted/materialized/projected by final drain, lifecycle p95 6,314ms, market p95 7,315ms, and canonical projection SQL mean 451.498ms over 7,777 productive batches. C28 source/cohort and sampler authority passed but freshness failed; its full business reference was deferred (C22 is the last pass). C30 nested profiling found the status/fill statement larger than timeline, but C30's instrumentation invalidates direct latency comparison.

## Hypothesis and isolation

The status/fill stage inserts roughly three million `runtime.submit_results` rows. Post-C30 read-only statistics found `idx_submit_results_occurred_typed` at 270MB and zero scans under the write-heavy workload. Repository search found no runtime query filtering or ordering `submit_results` by `occurred_at_ts`; the current command-result lookup plans on `submit_results_pkey`. The index's original migration describes potential operator/audit use, so zero scans in this load alone does not justify removal.

C31 adds only a diagnostic SQL migration, `runtime/0059_submit_result_time_index_ablation.sql`, to an isolated copy of C28's source tree. It drops `idx_submit_results_occurred_typed` and preserves the submit-result primary key, typed fact columns, trigger, other indexes, data and function semantics. The application image remains C28 `sha256:361e76e00287197656a3e3b55ece6f5e1287ab38bfd4a47edf40aabbe84e56bb`. The frozen C28 compose file is reused byte-for-byte (SHA256 `34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`): c-32, 16 canonical writers, four lifecycle loops plus market helper, batch500, same workload, observer cadence and thresholds. PostgreSQL nested statement profiling is off. C31 uses a fresh fixture, so C28/C31 are matched settings rather than identical restored-data trials.

The candidate migration SHA256 is `060e13494ae25c2643cf2de618e0136c94d1cf5968d469c839405b32c71b9e62`; source manifest SHA256 `df77da3e8cc8b126c370dffa6eaf91214576c01de8880c46bb28be0eabb9b63e`. Runtime proof before load confirmed the migration ledger row, absent typed time index, and present primary key (`1|t|t`). The frozen C28 phase analyzer was independently rerun and reproduced its 451.498ms mean before C31 analysis.

## Decision rule set before results

This single fresh-fixture pair can reject a weak candidate but cannot by itself prove a small causal gain. A potentially retainable result requires at least a 10% lower canonical projection SQL mean (≤406.3ms/batch), at least a 10% lower lifecycle **and** market conservative p95 bound than C28, unchanged cohort/sampler authority, zero final count gaps/failures/retries/deadlocks, and a passing full business reference. It also requires that command-result reads still plan on the primary key and that focused replay/race checks pass. If the apparent gain is near these cutoffs, repeat it before retaining code. Failure of any required condition rejects the index change; preserve the raw evidence and leave the authoritative worktree without this migration. This diagnostic alone cannot promote sustained 10k qualification unless the unchanged freshness limits pass and later recovery/headroom gates are satisfied.

## Result

C31's stress command completed its 300s load and final drain. It accepted, direct-acked, materialized and canonically projected 3,000,012 commands, with zero reported command failures and zero final count/lag gap. The unchanged checker failed conservative freshness: lifecycle p95 14,577ms, market p95 15,520ms. Downstream authority also failed `diagnosticSamplerComplete`, so these bounds are diagnostic, not a qualifying matched freshness comparison.

The same final-successful-sample extractor used for C28 produced the following summed 16-writer phase measurements:

| Phase | C28 control | C31 index absent | Observation |
| --- | ---: | ---: | --- |
| Canonical projection SQL | 451.498ms / 7,777 productive calls | 529.123ms / 7,618 productive calls | C31 is ~17.2% higher, not the required ≥10% lower. |
| Transform | 38.311ms / 7,790 calls | 44.249ms / 7,618 calls | Also higher in this fresh fixture. |
| Canonical read | 6.251ms / 24,771 calls | 4.423ms / 61,125 calls | More calls, lower per-call time; not a capacity improvement claim. |
| Commit | 7.988ms / 7,777 calls | 7.482ms / 7,618 calls | Similar. |

These are cumulative worker timers sampled during separate fresh loads, not per-command visibility times or a causal estimate of index cost. A one-pair apparent regression cannot prove dropping the index caused it. It does decisively fail the predeclared improvement rule, so **reject the index removal**. The diagnostic migration exists only in the isolated C31 host source tree; the authoritative dirty worktree and C28 control compose were not changed.

The full stopped-writer business reference began, but its protocol hashes every runtime table on three databases before and after a rollback-only rebuild. Because C31 had already failed the performance and observation gates, that expensive reference was intentionally terminated and deferred. The reference process was stopped, the parent protocol's cleanup exited0, no runtime writers or active reference query remained, and raw report/diagnostics were preserved. C31 protocol exit1 therefore records both the failed checker and this deferred reference; it is not a full business-parity result.

## Fresh-fixture and host-state audit after user challenge

C28 and C31 each ran the same `docker compose ... down --volumes` reset before a fresh fixture: 37 removal lines, no reset error, 31 pre-reset project containers. The private benchmark environment SHA256 matched exactly (`af66d46b6398c6f63f3d675c2b113febc17ddd3b63740b546b83ee3efde64497`), as did the frozen compose SHA256. Selected workload settings (seed, duration, rate, worker count, action mix) matched, and the report's effective runtime configuration fingerprint and service configuration were equal. C31 applied only the index-ablation migration; schema check showed the migration ledger row, absent typed time index, and present submit-result primary key. Thus the evidence supports a fresh database fixture with matched declared settings; it does not support a claim that old database rows polluted C31.

The measured storage behavior differed sharply despite that reset: projection PostgreSQL recorded 21,000,403 block reads in C31 versus 3,963,512 in C28 (~5.3×). Sampled total container CPU was lower in C31 (~1,825% versus ~2,083%), and PostgreSQL `track_io_timing` was off in both runs, so read latency cannot be recovered. C31's diagnostic sampler had 337/337 successful observations but max gap 2,002ms, exceeding the frozen 2,000ms authority limit. These differences prevent attributing the slower SQL time directly to the index removal.

An adjacent C32 fresh control used the original C28 source, image, compose, no-profiler settings and disposable-volume reset after C31 cleanup. Its reset again removed 37 project resources without errors. Source verification passed, `runtime/0059` was absent, and the typed time index and primary key were present before load. The result did **not** reproduce C31's slow SQL and high reads:

| Observation | C28 control | C31 index absent | C32 adjacent control |
| --- | ---: | ---: | ---: |
| Canonical SQL mean/productive batch | 451.498ms | 529.123ms | 428.673ms |
| Projection PostgreSQL block reads | 3,963,512 | 21,000,403 | 4,186,036 |
| `submit_results` sequential scans | 455 | 2,883 | 506 |
| Final accepted/materialized/projected | 2,999,868 | 3,000,012 | 3,000,003 |
| Conservative lifecycle / market p95 | 6,314 / 7,315ms | 14,577 / 15,520ms | 6,867 / 7,777ms |
| Sampler maximum gap (2,000ms limit) | 1,005ms | 2,002ms | 2,001ms |

C32's stress command exited0 with zero reported command failures and final count/lag equality. Its unchanged checker/protocol exited1 because lifecycle/market p95 still exceed5s and the sampler missed authority by1ms. C32 is not a new qualification pass. The original index recorded zero scans in the adjacent control while the submit-result primary key was used, so the extra C31 table scans are a correlated symptom, not demonstrated direct use of the removed index. They could reflect different planner decisions or more status polling during a slower drain. A persistent host setting mismatch, stale database rows, or uniform host slowdown is not supported by the reset/configuration and adjacent-control evidence. The exact mechanism behind C31's read amplification remains unproven.

An interim six-sample C31 phase extraction during post-run work showed 542.656ms SQL over6,409 productive calls; the final seven-sample extraction above supersedes it. Both miss the predeclared retention threshold. No replay/race test or full business reference was run to completion for this rejected treatment.

[Sanitized three-run phase comparison](../evidence/projection-submit-index-ablation-phases-c31-c32-2026-09-25.json). The interim [six-sample comparison](../evidence/projection-submit-index-ablation-phases-c31-2026-09-25.json) is preserved for correction traceability. Raw output paths on the owned host: `/home/reefbench/benchmarks/reef-productive-c31-submitindex-10000-300s-1` and `/home/reefbench/benchmarks/reef-productive-c32-control-10000-300s-1`.
C31 raw report SHA256 `1e833077a0b2b2a0de47f93c801187094a5e2c06359e587876d1f9e610926ad0`; diagnostics summary SHA256 `2630a8715a73fa54b426f8cb31dd6d72146517cf57e90065ea3f205faabf197b`.
C32 raw report SHA256 `c183d14a0fac346351490486e533deb21e058e2fbb61a390378d9723f0414cb5`; diagnostics summary SHA256 `4f29a9f19ada07044eb692e674f5ca09e780104831b48ab179cca1f92baf6c9e`. Both run protocols stopped all runtime writers; five database/broker containers remain running. The authoritative dirty worktree contains only this research/evidence change from C31/C32, not the diagnostic SQL migration.
