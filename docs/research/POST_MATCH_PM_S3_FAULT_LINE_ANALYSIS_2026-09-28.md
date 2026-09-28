# PM-S3 post-match capacity fault lines — 2026-09-28

Status: forensic assessment of the corrected **treatment-only** run. This is
not a matched architecture comparison, settlement correctness qualification,
or approval for public reads. Raw evidence and checksums are in
[PM-S3 attempt 3](../../artifacts/postmatch-capacity-20260928/attempt-3/).
The earlier [capacity decision](POST_MATCH_SETTLEMENT_CAPACITY_DECISION_2026-09-27.md)
and [D-059](../DECISIONS.md#d-059-durable-settlement-admission-order-for-scarce-accounts)
remain the correctness boundary.

## What the run proves

The corrected `sfo3` `c-32` treatment used six materializers, sixteen legacy
projector owners, four dedicated live JVMs, four dedicated settlement JVMs,
source PostgreSQL `max_connections=320`, and settlement PostgreSQL
`max_connections=240`. It accepted and directly acknowledged 2,776,548
commands in 300 seconds, or 9,254.07/s, below the 9,900/s rate gate. At the
load-report snapshot, durable canonical materialization had 2,620,026 items,
156,522 fewer than accepted. The downstream cohort gate failed. This is a
combined capacity failure even though no requests failed.

Four qualified in-load stage intervals passed the observer-duty gate. The
following are approximate `pg_stat_user_tables.n_tup_ins` rates, so they
locate work accumulation but do not prove exact membership or latency:

| Stage | In-load observed rate | Comparable backlog signal |
| --- | ---: | --- |
| Canonical source outcomes | 6,255–7,415 outcomes/s | Accepted commands averaged 9,254/s; outcome insert rate is a different sampled interval and the load-report canonical snapshot lagged. |
| Settlement intake | 2,018–2,215 trades/s | Obligations grew at roughly the same trade rate. |
| Settlement admission | 38.7–40.9 windows/s | The shared rank counter processed one transaction per small window. |
| Settlement completion | 2.06–2.56 windows/s | Only roughly 5–7% as many windows completed as were admitted in these intervals. |
| Transition attempts | 75.6–96.3 trades/s | Roughly 4% of the contemporaneous intake trade rate. |

At the final retained sample, about three minutes after load, table statistics
showed 1,220,848 intake trades, 22,211 admissions, 1,300 completions, and
52,564 attempts. Intake versus attempts compares trade units; admissions
versus completions compares window units. The exact closed-cohort checkers did
not finish before the two-hour droplet cutoff, so these estimates are not a
final correctness or drain result.

The same post-run diagnostic captured **another independent backlog**:
post-match live had about 883,373 outcome receipts and 388,356 trade facts,
while settlement intake had about 2,776,548 outcome receipts and 1,220,848
trade facts. Both paths consume canonical trade effects from the fresh cohort.
Their snapshots are not simultaneous exact membership proofs, but the roughly
threefold count gap is too large to treat live projection as caught up. Live
recorded 1,787 source-coverage windows, about 494 outcome receipts per window;
settlement intake recorded 5,722 coverage windows, about 485 receipts per
window. These approximate statistics rule out tiny live windows as the main
explanation for the gap.

## Fault lines, evidence, and confidence

### 1. One admission-counter row serializes rank commits — high confidence

`SettlementBoundedTransitionStore.admit` updates the same
`canonical_transition_admission_counter` row once per window, then inserts
rank, dependency, and account-membership facts before committing the
transaction. The row stays locked while that remaining work finishes. In the
fresh settlement database, 22,465 counter updates accumulated 3,410,622 ms
of PostgreSQL execution time; 22,474 insert-on-conflict calls on the same row
accumulated 2,052,346 ms. A live snapshot found ten active tuple-lock waits
and two transaction-ID waits. These are summed concurrent SQL times, **not**
wall time or a separable CPU total. Four worker logs averaged 159–161 ms per
counter call and 297–302 ms per admission write. This supports a contended
admission critical section. It does not establish the exact lock-hold share
of those timings.

### 2. Account dependency is close to a serial chain — high for fixture,
medium for corrected run

The five-actor, 64-instrument fixture reuses cash accounts. An earlier
invalid 200-connection treatment produced a retained graph with maximum
dependency depth 27,617 across 27,622 admissions. That graph is evidence of
what this fixture can do, **not** a graph measurement from the corrected
320/240 treatment. D-059 requires a window to wait for its prior rank on
each affected account and partition; skipping a predecessor could change
which trade consumes scarce resources. Corrected-run worker logs independently
show 77.5–80.2% of readiness checks blocked and about 4,823–4,947 admitted
but only 285–293 applied windows per JVM snapshot. A diverse-account fixture
is needed to separate structural cost from hot-account skew.

### 3. Admission and execution share partition threads — strong mechanism,
unmeasured ready-to-start delay

`PostMatchSettlementTransitionWorker.processPartition` checks and executes
the earliest admitted window, performs an admission read/write, then may
execute again. The same partition threads perform both paths. Across the four
JVM snapshots, admission writes averaged about 0.30 seconds per window,
while successful execution writes averaged 0.14–0.16 seconds and execution
reads 0.016–0.019 seconds per window. Each JVM's admission-write time sums
across four threads and cannot be compared directly to wall time. Still, the
loop can defer a ready cross-partition successor while its assigned thread is
in a contended admission transaction. The run did **not** record the time
from last predecessor completion to successor execution start; that is the
measurement needed to assign a scheduler-delay fraction. Decoupling the
loops is a plausible part of the redesign, but by itself cannot explain or
close the full trade-rate gap.

### 4. Per-window execution and fact persistence are too expensive for a
hot-account serial path — high directional confidence

Each applied window in the worker snapshots held about 39–40 obligations.
`apply` repeats source, admission, opening, dependency, and account proofs,
then writes attempts, four-leg ledger entries, obligation updates, account
checkpoints, coverage, completion, and frontier in one transaction. The
0.14–0.16-second execution-write mean implies only a few hundred obligations
per second on one strictly serial chain even if every successor starts
immediately. Observed attempts were lower, 76–96 trades/s. Exact scaling of
larger windows or coalesced ranked transactions is unknown; the code's batched
JDBC calls still issue many per-row SQL executions. A useful redesign must
reduce proof/round-trip and commit work **per trade** while preserving each
window's digest, rank-order balance decision, four-leg DvP, and replay facts.
Three top-level per-trade statements alone accumulated about 86.4 seconds
of SQL time across roughly 52,564 attempts: ledger insertion 49.98 seconds
for 212,836 calls, attempt insertion 21.10 seconds for 53,209 calls, and
obligation status update 15.35 seconds for 53,209 calls. That is about
1.64 ms per attempted trade for only these statements. The totals may include
retries and concurrent execution; they are not an end-to-end lower bound, but
show why merely putting more windows in one transaction can leave the hot
serial path far above its target budget.
At the run's approximately 0.44 trade/outcome mix, a sustained 10,000
outcomes/s would produce about 4,400 trades/s. At roughly 40 applied trades
per window, that requires about 110 completed windows/s, versus 2.06–2.56/s
observed in-load. If the fixture remains nearly one dependency chain, the
effective ordered work must fall below about 9 ms per window, or several
ranked windows must be processed together. This is a workload-specific
design target, not a measured capacity of the proposed implementation.

### 5. Live post-match projection is also behind — high confidence for backlog,
unmeasured stage cause

`PostMatchRuntimeWorkers` starts one live loop per dedicated live JVM; that
loop visits four assigned source partitions sequentially. For each verified
window, `PostMatchLiveEffectWriter` checks identities and prior order state,
then writes executions, one trade fact per trade, order state, and market
changes. At the diagnostic snapshot, live trade facts were about 32% of
settlement intake trades, and live outcome receipts about 32% of settlement
intake receipts despite similar average window sizes. The live database
recorded about 7.95 GB WAL. Its six largest live fact/receipt statements
accumulated 1,258.7 seconds of top-level SQL execution time: order-state
upserts 360.7 seconds, execution inserts 293.1, trade inserts 217.2,
order-directory inserts 141.6, market-change inserts 125.5, and outcome
receipts 120.6. Dividing by 1,787 coverage windows gives about 704 ms of SQL
time per live window before source reads, planning, other SQL, and commit.
These statements execute serially inside each live window's transaction, so
this is strong evidence of writer cost under the four-loop topology. It is
not an exact transaction-duration measurement: PostgreSQL statement counters
can include retries and differ slightly from table/window counters. No
permanent live-worker failure was retained, but no per-window read/plan/write
timings or in-load live frontier samples were captured. The next run must add
those metrics. Fixing settlement execution alone cannot qualify the live read
path. With about 500 outcomes/window, 10,000 outcomes/s requires roughly 20
live windows/s. Four loops would each need to finish a window in about 200 ms;
the six SQL statements alone averaged roughly 704 ms/window in this run.
Adding threads may remove head-of-line waiting, but the writer also needs
less work per window and enough database headroom.

The live writer already uses JDBC `addBatch`/`executeBatch`, yet the
`pg_stat_statements` call counts for its largest inserts remain close to row
counts. The Compose default post-match JDBC URL has no batch-rewrite option;
[pgJDBC documents](https://jdbc.postgresql.org/documentation/use/) that
`reWriteBatchedInserts` defaults to `false` and can combine simple batched
inserts into multi-row statements. This is a specific prototype candidate for
the append-only live facts and receipts, not a guaranteed fix for order-state
upserts, foreign-key/index work, or database saturation. The prototype must
measure actual SQL call-count reduction and exact replay/fact parity.

### 6. Four databases share a large write and compute budget — observed cost,
unattributed ingress effect

The post-run diagnostics recorded 7.36 GB source WAL, 10.28 GB legacy
projection WAL, 7.95 GB post-match WAL, and 12.40 GB settlement WAL, about
38.0 GB combined over the run and drain. Settlement alone inserted about
8.06 million tuples and made about 706,000 commits. Its largest tables were
trade intake (2.45 GB including indexes), obligations (1.93 GB), intake
receipts (1.83 GB), and order directory (1.25 GB). Source materialization's
top-level SQL accumulated 1.74 million ms; legacy projection submit
persistence accumulated 4.14 million ms. Nested SQL/function timings overlap
their callers and must not be added. The live database snapshot showed source
167/320 sessions, projection 154/160, and settlement 77/240; most source and
projection sessions were idle, while settlement had active lock waits. It did
not show measured device saturation. Without a matched 320/240 control, this
evidence cannot assign the 9,254/s ingress rate loss to a particular stage or
quantify the incremental post-match cost. Legacy projection and new post-match
paths still run together as shadow work.

### 7. Connection limit was a setup blocker, not the corrected-run explanation

The preceding seed failed at settlement PostgreSQL's default 100/100 clients.
The earlier source-database attribution was wrong. Raising the settlement
limit to 240 let the corrected treatment seed and run; a live snapshot showed
77 sessions. Four transient `source coverage has a missing position` worker
messages later recovered. No retained log proves a permanent coverage stall.

## Design and next proof

The evidence rules out treating rank batching alone as the solution. A local
fixed-cohort prototype should measure a combined design: live partition
workers that avoid four-partition head-of-line blocking, bulk live fact
persistence, bounded multi-rank admission commits, execution scheduling that
does not queue behind admission, and deterministic ordered processing of
consecutive dependent windows with amortized proof and bulk fact persistence.
Preserve a durable rank and exact account/partition predecessors for every
window. A failed transaction must
leave no rank gap or partial ledger facts; same-generation replay must verify
the retained rank, source, account, dependency, opening, policy, checkpoint,
and four-leg facts. Keep disjoint-account parallel execution valid.

The bounded admission prototype should advance the shared counter by a rank
range and commit every individual rank, membership, dependency, digest, and
frontier in the same transaction. Calculate predecessor links in rank order
within the range; never reserve ranks in a separate committed transaction.
For execution, use the **same retained admission log** in control and
treatment. Select rank-sorted windows only when external predecessors are
complete or earlier selected windows are in the same group. Lock their union
of accounts in stable key order, evaluate balances window by window in rank
order, and commit each window's proof and ledger facts atomically with the
group. Keep disjoint components parallel. Test bounded groups of 8, 16, and
32 with trade, byte, and transaction-time caps. At the fixture's roughly
110-window/s target, 16-window groups would need to finish in about 145 ms;
that is a design budget, not a measured outcome. D-059 currently says one
settlement transaction commits each window's facts. A transaction containing
multiple windows changes that boundary even if every window retains its own
proof. Treat grouped execution as an experiment until an accepted amendment
to D-059 and the
[transition contract](../work/POST_MATCH_BOUNDED_SETTLEMENT_TRANSITION_CONTRACT_2026-09-27.md)
defines atomic grouped commits, failure/replay behavior, and exact per-window
facts; review and test that decision with the implementation.
[Modern Treasury's ledger guidance](https://docs.moderntreasury.com/ledgers/docs/handle-concurrency)
also identifies hot shared accounts as a throughput constraint and batches
entries when no synchronous balance condition is needed. Reef's scarce-account
winner depends on an ordered balance condition, so its asynchronous hot-account
option does not replace D-059; the relevant analogy is amortizing ordered
work without relaxing the decision rule.

For the live path, measure pgJDBC batch rewrite on append-only facts first,
then tackle mutable order-state upserts separately. If multi-row inserts leave
the dominant cost, [PostgreSQL `COPY`](https://www.postgresql.org/docs/16/sql-copy.html)
into transaction-local staging followed by constrained `INSERT ... SELECT`
is a prototype option. Preserve destination keys, foreign keys, indexes,
transaction atomicity, and exact replay. Do not infer a speedup from the
database's general bulk-load guidance; measure it against Reef's schema.

Before another disposable test, add the missing attribution metrics: last
predecessor completion to execution start, execution transaction duration and
trades per transaction, rank-counter lock/commit time, live source windows and
read/plan/write time, in-load live/intake/admission/execution frontiers,
per-database wait and WAL deltas, and completed exact cohort checks.
Run both the hot-account and a diverse-account fixture locally. The hosted
pair must use the same 320/240 settings, source workload, owner topology,
observer, and host; finish exact stopped-source proofs within the two-hour
droplet cap. Only that pair can quantify incremental integrated cost or show
whether the redesign reaches 10k/s with correct post-trade drain.

The read-only PM10 research spike and lead rechecked PM-S3 file integrity with
`shasum -a 256 -c evidence.sha256` from the attempt-3 artifact directory
(11/11 passed). The analysis uses the retained compressed JSON counters and
stage samples linked above; no PostgreSQL A/B was run. All proposed capacity
gains remain unmeasured.
