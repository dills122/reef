# PM10-LIVE local batch and partition evidence — 2026-09-28

Scope: local diagnostic of one 500-outcome live window, not a sustained 10k/s,
in-load freshness, multi-JVM, or hosted capacity result. This follows PM-S3
attempt 3, where four live loops fell behind and six largest live SQL statements
accumulated about 704 ms per roughly 494-outcome window. The fixed local fixture
has 250 maker/taker pairs, 250 trades, 500 executions, 500 order identities,
500 order states, 500 receipts, and 250 market changes. It uses one partition,
one transaction per arm, no competing load, and a warm local PostgreSQL 16
container on a Mac. Source read and four-loop concurrency are excluded from
these writer timings.

The migrated named database was `reef_pm10_test` at `localhost:5436`.
`pg_stat_statements` was preloaded through the local post-match Compose command
and created only in that database. The default Compose command overrides
`ALTER SYSTEM`, so a single-container recreation with
`REEF_POSTMATCH_PG_SHARED_PRELOAD_LIBRARIES=pg_stat_statements` was needed;
the named volume and other containers stayed in place. PostgreSQL statement
`calls` were read before and after each arm. Arm order was control, treatment,
control, treatment. Each arm used a unique stream and cleaned all rows after
the exact membership checks. Both URLs differed only by
`reWriteBatchedInserts=false|true`.

| Run | Control target apply (ms) | Rewrite target apply (ms) | Control append-only insert calls | Rewrite append-only insert calls | Order-state calls, each arm |
| --- | ---: | ---: | ---: | ---: | ---: |
| First post-preload run | 332.512, 294.907 | 211.180, 227.926 | 2,000 | 30 | 500 |
| Second post-preload run | 294.397, 251.085 | 245.642, 200.838 | 2,000 | 30 | 500 |

Across these eight windows, mean control target apply was 293.225 ms and mean
rewrite target apply was 221.397 ms, 24.5% lower. Total live insert/upsert
calls fell from 2,500 to 530 per window, 78.8% lower. Per append-only table,
control calls matched row count (500 directory, 500 receipts, 500 executions,
250 trades, 250 market changes); rewrite used six calls for each. PostgreSQL
statement counters aggregate by query and database, so this method assumes no
concurrent writes of these SQL shapes during each short arm. The isolated
stream and exact row checks prevent data collision but do not isolate server
resource contention. These figures are local transaction measurements, not
the PM-S3 hosted 704 ms SQL figure or a 10k throughput projection.

All arms retained exact source-to-live receipt sequence, command ID, payload
hash, and result digest membership; exact expected trade IDs, execution IDs,
buy/sell orders, quantities, and prices; equal projection facts across arms;
and same-generation duplicate replay. The focused migrated-PostgreSQL suite
passed 10 tests: three runtime worker tests (including partition 1 progressing
while partition 0's frontier row was locked), five writer tests, and two
operational-store tests. The runtime test exercised opt-in per-window
`postmatch_live_window` read, plan, phase-write, and target-apply logging.

After independent review, the fixed-window assertion expanded to every stored
receipt, execution, and trade source/canonical column. A focused opt-in
PostgreSQL rerun passed one test with zero skips or failures on 2026-09-28.
Control arms measured 352.635 and 203.698 ms; rewrite arms measured 182.183
and 161.704 ms. SQL calls remained 2,500 versus 530 per arm. These later
times are separate from the paired-run mean above.

The same local target also ran two sequential scaling sweeps with a 16-connection
writer pool. Each arm retained the same 16 windows, 8,000 outcomes, and 4,000
trades; partition count changed how many windows each lane held. Windows on
each partition were applied in sequence. Four, eight, and sixteen writer
threads ran concurrently, with one writer per partition. Source read and
market maintenance were excluded. Counts, coverage rows, and all partition
frontiers were checked after each arm. Statement calls fell from 32,000
append-only + 8,000 state calls to 480 append-only + 8,000 state calls in each
rewrite arm. The original timed interval also included building and verifying
the synthetic windows in each worker, so its elapsed figures are not pure
target-apply timings. Per-window `target` samples timed `applyMeasured` only.

| Writers | Rewrite | Sweep 1 elapsed / target 15th / commit 15th (ms) | Sweep 2 elapsed / target 15th / commit 15th (ms) |
| ---: | --- | --- | --- |
| 4 | off | 2,014 / 426 / 16 | 2,682 / 488 / 12 |
| 4 | on | 1,153 / 218 / 20 | 1,126 / 225 / 21 |
| 8 | off | 1,077 / 374 / 7 | 1,095 / 421 / 22 |
| 8 | on | 920 / 351 / 30 | 957 / 372 / 19 |
| 16 | off | 1,116 / 527 / 9 | 1,261 / 667 / 30 |
| 16 | on | 1,096 / 491 / 18 | 1,095 / 501 / 31 |

Correction: the first scaling harness labeled the 15th of 16 ordered windows
as p95. The nearest-rank p95 for 16 windows is the 16th (maximum). The table
preserves the original 15th-window numbers under corrected labels. After local
PostgreSQL recovered, one corrected-p95 sweep passed with exact fact counts,
coverage, and partition frontiers:

| Writers | Rewrite | Elapsed (ms) | Target mean / p95 (ms) | Commit mean / p95 (ms) |
| ---: | --- | ---: | ---: | ---: |
| 4 | off | 2,363.5 | 343.4 / 551.2 | 3.8 / 10.3 |
| 4 | on | 1,145.4 | 187.3 / 243.8 | 3.6 / 16.7 |
| 8 | off | 1,177.7 | 368.8 / 567.9 | 4.6 / 8.8 |
| 8 | on | 964.7 | 278.9 / 483.4 | 6.2 / 18.1 |
| 16 | off | 1,250.1 | 626.2 / 702.5 | 4.2 / 11.8 |
| 16 | on | 1,121.8 | 469.9 / 595.0 | 3.8 / 7.0 |

Each arm ran 16 windows, so nearest-rank p95 is that arm's slowest window.
The third sweep again found eight writers' best elapsed time on this fixture;
its per-window p95 rises versus four because more windows overlap. No DB
wait-event sampler ran, so the precise cause of the 16-writer slowdown remains
unassigned. A later harness inspection found that the Hikari target pool may
have grown from four to eight to sixteen connections during the timed arms.
That startup cost can exaggerate the sixteen-writer slowdown. The harness now
builds/verifies all windows and warms all configured pool connections before
timing; a corrected rerun is required before treating the eight-writer peak as
a stable tuning choice.

Eight writers gave best elapsed time in both local rewrite-on sweeps, about
8.4–8.7k outcomes/s of target apply plus fixture setup. Sixteen writers
regressed to about
7.3k/s. That is below the integrated 10k target before source reads, market,
settlement, or legacy projection. The 16-thread target latency rise did not
appear primarily in commit; detailed PostgreSQL wait events were not captured.
The remaining 8,000 per-row mutable order-state upserts are an evident SQL-call
floor, but the sweep alone does not assign all extra latency to that statement.
The live worker now uses independent partition loops and a fair two-write
permit cap per JVM; PM-S3's four JVMs would permit eight concurrent target
transactions. This is an operational starting point for matched hosted testing,
not a proven optimum across hardware or workload mixes.

An initial rewrite-enabled small fixture failed because pgJDBC rewrote the
order-state `INSERT ... VALUES ... ON CONFLICT` batch and returned an aggregate
count, hiding the strict per-row advancement check. That mutable upsert now
uses `INSERT ... SELECT ... WHERE true ON CONFLICT`, which stays as one call per
state and keeps the per-row count guard; both URL modes pass a stale-position
rejection test. The append-only market-change batch accepts JDBC
`SUCCESS_NO_INFO` as a successful rewritten insert; SQL failures still throw
and roll back the transaction. Earlier 500-outcome diagnostic attempts had
repeated fixture event/trade IDs, then an expected-list sort error; those were
fixed before the two valid post-preload runs above.

After adding the permit cap and a source-read timing fixture, a follow-up
PostgreSQL test invocation compiled but failed before assertions because all
local Docker containers disappeared and source port 5432 refused connections.
Docker Desktop later recovered without Reef data loss. Four runtime worker
tests then passed, including a four-partition blocked case with two write
permits and all three other partitions advancing. The 500-outcome source-read
fixture used rejection payloads with 1,024 padding bytes each; 20 warm reads
measured mean 49.08 ms and nearest-rank p95 55.01 ms. Its effect shape differs
from the trade-heavy writer cohort, so these read and write figures cannot be
summed as one measured end-to-end window. The non-benchmark writer/store
regression suite also passed after the permit-cap change.

Reproduce from `services/platform-runtime` with migrated local source and
post-match databases:

```bash
RUNTIME_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://localhost:5432/reef_pm10_test \
RUNTIME_POSTGRES_USER_TEST=reef RUNTIME_POSTGRES_PASSWORD_TEST=reef \
POSTMATCH_DB_URL_TEST=jdbc:postgresql://localhost:5436/reef_pm10_test \
POSTMATCH_DB_USER_TEST=reef POSTMATCH_DB_PASSWORD_TEST=reef \
RUNTIME_DB_POOL_POSTMATCH_OPERATIONAL_MAX=16 \
POSTMATCH_LIVE_BENCHMARK=1 ./gradlew test \
  --tests 'com.reef.platform.api.PostMatchRuntimeWorkersIntegrationTest' \
  --tests 'com.reef.platform.infrastructure.persistence.PostMatchLiveEffectWriterIntegrationTest' \
  --tests 'com.reef.platform.infrastructure.persistence.PostMatchOperationalStoreIntegrationTest' \
  --console=plain
```

pgJDBC documents `reWriteBatchedInserts` as default-off and able to combine
simple batch inserts: <https://jdbc.postgresql.org/documentation/use/>.
