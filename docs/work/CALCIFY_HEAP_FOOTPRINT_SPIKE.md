# Capacity ledger heap footprint

Investigation in progress, 2026-10-15. Owner authorized takeover of prior local spike.
Baseline `f09f20c7e9699dcb4156fe67eaeaa19b009452ae`; Apple M1 Max, JDK 21,
`-Xmx12g`, compressed object pointers, 200 accounts, 64 instruments.
No broker, RocksDB, projector, retained input schedule or snapshot in heap harness.
Fresh strings reproduce resolved decoder allocation. Funding live heap subtracted.
Each checkpoint forces full collection and records `GC.class_histogram` through
HotSpot DiagnosticCommand MBean (same diagnostic command as `jcmd`). Explicit
reachability fence keeps ledger live through last histogram. Raw logs currently
under `/private/tmp/reef-footprint`; final evidence publication pending.

## Read-path proof

Source: [ledger decision/read/restore paths](../../services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/CapacityLedger.kt),
[flat trie layout](../../services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/FlatStateTrie.kt),
[byte differential and malformed restore guards](../../services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/finance/CapacityFootprintCompatibilityTest.kt).

`CapacityLedger.decide` first reads action digest for prior-result/conflict.
Capture reads account/security membership and prior execution economics, then
execution/obligation counts for budgets. Settlement reads obligation status before
attempt membership, then captured economics and positions; pending failures add
attempt leaves. Successful combined capture has no attempt leaf, whereas two-step
success retains one. Funding reads positions and admitted accounts. `snapshot`,
restore and store writes expose all entity classes. `load` reconstructs execution
keys separately for X/O/A: restored heap can exceed fresh-run heap measured here.

Evicting settled map entries alone is incorrect: capture duplicate/conflict detection,
settlement ALREADY_SETTLED and budget checks still need them. Cold lookup could
preserve outcomes only with exact counters, atomic store/ledger reads, unchanged
lastWrites/snapshot behavior and fail-closed storage errors. Not implemented or
approved. Slice rotation changes identity lifetime/binding and needs owner decision.

## Information and overhead

| Component | Retained information | Avoidable overhead / candidate |
| --- | --- | --- |
| Trie | 32-byte hashed key/leaf; binary branch digests | 112 payload bytes/entity plus chunk slack; G1 region filler around large arrays |
| Execution | four IDs, buyer/seller, quantity/price | headers, repeated decoded strings, cached canonical frame |
| Obligation | status and two residuals | identical immutable settled value allocated per trade |
| Attempt | execution+attempt, disposition | HashMap node and Pair; required in two-step commitment |
| Action | 3-part identity, digest, disposition | HashMap node, record, 64-char hex String and backing bytes |
| Positions | account/asset balances | nodes, Pair, boxed Long; bounded by account×asset shape, not trades |

Histogram counts include funding/common JVM classes; net heap subtracts funded
baseline. G1 filler is occupied heap unavailable to other allocations, not additional
business information. Class bytes alone must not be treated as a portable object
layout: collector/region size and JVM flags matter.

## Options and owner gates

Prioritize smaller trie chunks, shared immutable settled values, then bounded
canonical ID dictionaries and packed action digests. All can in principle preserve
canonical serialization, but need differential/restore tests and interleaved CPU
measurements. Packed execution table replaces several hash lookups/maps but must
cover pending/retry/conflict paths, not only happy-path trades. Raw in-memory digest
storage is neutral only if serializers still emit original hex. Changing serialized
digests, entity sets, key hashing, store layout or restore rules needs owner approval.

9M / 18M budgets below must be extrapolations unless explicitly measured. Heap budget
excludes native RocksDB, Kafka buffers, JVM metadata, GC reserve and restart overlap.
Do not infer 30-minute throughput qualification from object counts.

## Prior art

[Oracle JDK 21 G1 guide](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)
documents humongous allocations and unused region tails. Existing flat trie and
canonical caching are scoped by `CALCIFY_STATE_COMMITMENT_INVESTIGATION.md` and
`CALCIFY_REENCODING_INVESTIGATION.md`; this work measures retained memory rather
than treating lower allocation rate as smaller live state.

## Forced-GC measurements (bytes per trade)

| Trades | Two-step G1 base / prototype | Two-step Parallel base / prototype | Combined G1 base / prototype | Combined Parallel base / prototype |
| --- | --- | --- | --- | --- |
| 250000 | 1861.8 / 1517.3 | 1519.7 / 1489.7 | 1162.1 / 957.8 | 972.0 / 952.9 |
| 500000 | 1893.8 / 1549.2 | 1527.5 / 1497.4 | 1217.3 / 990.9 | 994.5 / 960.7 |
| 1000000 | 1879.7 / 1539.5 | 1523.7 / 1494.0 | 1212.6 / 987.9 | 990.7 / 956.9 |
| 2000000 | 1859.6 / 1520.4 | 1521.7 / 1491.9 | 1191.6 / 975.1 | 985.1 / 955.2 |

Prototype `447e5ea1e`: 4,096-node chunks and shared immutable settled obligation.
One full series per collector/mode; deterministic object counts, observed GC layout variation.
CPU values from these series are diagnostic: broker and compiler workloads overlapped.
Three interleaved pairs per mode/collector supply separate CPU comparison.

At 2M trades, baseline trie payload is about 560 B/trade two-step and 336 B/trade
combined, before chunk rounding/headers. Remaining Parallel live heap is roughly
962 and 649 B/trade respectively (maps, strings, cached frames, common overhead).
G1 histogram additionally reports 323 and 196 B/trade of filler arrays.
Prototype removes per-trade 32-byte settled objects; smaller chunks avoid large
trie-array humongous tails. HashMap arrays can still be humongous.

## Interleaved CPU check

Three base/prototype pairs per collector/mode; 250k warmup, second 250k interval.
Values are processing-thread µs/trade / live B/trade at 500k. Concurrent broker/
compiler work still affects cache contention; these are not throughput qualification.

| Collector/mode | Base range | Prototype range |
| --- | --- | --- |
| G1 two-step | 41.41–43.22 / 1883.6–1885.6 | 39.36–41.86 / 1541.2–1544.3 |
| G1 capture-settle-v1 | 27.95–28.58 / 1208.0–1209.8 | 26.74–28.04 / 982.6–987.6 |
| Parallel two-step | 40.73–42.08 / 1520.3–1520.3 | 41.22–41.57 / 1490.2–1490.2 |
| Parallel capture-settle-v1 | 26.27–27.15 / 987.3–987.3 | 26.30–28.43 / 953.5–953.5 |

## Ranked options and planning budgets

Live-state decimal GB below extrapolate 2M endpoint with `-Xmx12g`, not GC reserve.
Changing heap size changes G1 region geometry; actual large-size results follow.

| Option | Two-step B/trade | Two-step 9M / 18M GB | Combined B/trade | Combined 9M / 18M GB |
| --- | --- | --- | --- | --- |
| Base G1 | 1859.6 | 16.74 / 33.47 | 1191.6 | 10.72 / 21.45 |
| Prototype G1 | 1520.4 | 13.68 / 27.37 | 975.1 | 8.78 / 17.55 |
| Base Parallel | 1521.7 | 13.70 / 27.39 | 985.1 | 8.87 / 17.73 |
| Prototype Parallel | 1491.9 | 13.43 / 26.85 | 955.2 | 8.60 / 17.19 |

1. Land smaller trie chunks/shared settled value: measured, byte-identical, no new
   persistence or cache invalidation path. Combined mode is existing opt-in prerequisite,
   not a change to default semantics in this prototype.
2. Evaluate raw action digests internally next. Compressed-oops model: 24-byte String
   plus 80-byte backing array replaced by 48-byte array holding 32 payload bytes saves 56 B/action, hence
   ~112 B/two-step trade or 56 B/combined trade. At 9M / 18M, modeled savings 1.008/2.016GB
   two-step or 0.504/1.008GB combined. No measured CPU result; no production claim.
3. Bounded dictionary for known instrument/account IDs can avoid three short decoded
   String/backing pairs per capture (~144 B/trade model, 1.296/2.592GB at 9M / 18M).
   Keep execution/action IDs unique; do not intern unbounded attacker-controlled IDs.
   Cached canonical frame remains unchanged. Re-measure collisions, restore, CPU and
   actual ID lengths before choosing; this model is not additive validated savings.
4. Unified packed execution table may eliminate map nodes/Pair and duplicated keys,
   especially after restore. No implementation/measurement here; no honest exact
   budget until load factor, cold paths and byte-identical serialization are fixed.
5. Spill or slice rotation has largest potential but requires explicit owner decision
   on storage availability, replay and identity lifetime. Trie alone remains roughly
   560/336 B per two-step/combined trade (5.04/3.024GB at 9M ; 10.08/6.048GB at 18M),
   so evicting maps is not bounded total memory. Rotation may change commitment scope.

Owner decisions: versioned spill/restore contract; scope/lifetime for rotated sessions;
any removal/change of committed entities or canonical bytes. None implemented.

## Reproduce and compatibility proof

From `services/platform-runtime`, JDK 21 and Docker available:

```sh
./gradlew testClasses resolverProbeDependencies
HF_MODE=capture-settle-v1 HF_CHECKPOINTS=250000,500000,1000000,2000000 HF_TOP=30 \
  "$JAVA_HOME/bin/java" -Xmx12g -XX:+UseG1GC -Xlog:gc:file=heap.gc.log \
  -cp 'build/classes/kotlin/test:build/classes/kotlin/main:build/classes/java/main:build/resources/test:build/resources/main:build/resolver-probe-deps/*' \
  com.reef.platform.calcify.finance.CapacityHeapFootprintKt
```

Use `two-step` and `-XX:+UseParallelGC` for other cells. Scale uses 28g and checkpoints
9000000,18000000. Baseline compilation from f09f20c7 retained separately; A/B classpaths
swap only Kotlin main classes, with same compiled probe/dependencies. Three alternating
base/prototype pairs at 250k,500k supply CPU ranges. Earlier incomplete Claude harness
was reused and completed with reachable-ledger fence, both modes and exact trie payload.

`CapacityFootprintCompatibilityTest` pins SHA-256 of decision/store bytes and refusals
from 1500 seeded inputs with periodic restore, against baseline and candidate actual runs.
Both modes match; original FinanceLedger/StateCommitment/CapacityStore/FinanceWire
goldens remain unchanged. Additional restore tests preserve nonzero parsed residuals
and reject trailing bytes even for settled values. Independent Python state/binding/
startup encoders pass. Clean-checkout default tests, coverage gate and runtime bench pass.
Initial default compiler heap exhausted memory; 3g compiler retry passed without changing
test thresholds. Mutation tests reject changed settled residual, status-only restore and
ignored trailing bytes. Wrong trie accessor shift caused nontermination and was bounded
by terminating owned worker; separate out-of-range accessor fails tests immediately.
Restored sources pass focused tests again. Two fresh-context reviews found no blocking
defects; measurement count-assertion follow-up accepted.

## Actual session-sized combined state

Prototype and matched G1 baseline, `-Xmx28g`, compressed object pointers, same 200-account/64-instrument
shape, no broker. G1 reports maxHeap 28672 MiB; Parallel reports 25486 MiB (collector
layout/reserve differs even with same `-Xmx`). These are live-state probes, not
full-process RSS or integrated session qualification.

| Collector | Trades | Live B/trade | Live decimal GB | Trie payload GB |
| --- | --- | --- | --- | --- |
| G1 baseline | 9M | 989.8 | 8.908 | 3.024 |
| G1 baseline | 18M | 986.9 | 17.764 | 6.048 |
| G1 prototype | 9M | 963.0 | 8.667 | 3.024 |
| G1 prototype | 18M | 960.6 | 17.291 | 6.048 |
| Parallel | 9M | 950.4 | 8.554 | 3.024 |
| Parallel | 18M | 950.6 | 17.111 | 6.048 |

G1: 76 ordinary young/mixed pauses across full run, max 365.6 ms; 12 forced full
collections (funding and checkpoints, including histogram collection), max 2822 ms.
Total reported stop-the-world young/full time 27.31 s over ~787s probe. Concurrent
mark work is additional CPU, not included in pause sum. At 9M full collections
roughly2–2.5s; at 18M2.2–2.82s. Thread CPU 38.00µs/trade to 9M, 42.49µs/trade for next 9M;
input construction included, broker/compiler work overlaps. No sustained 10k/s claim.

## Class breakdown at 2M

Whole-JVM histogram bytes/trade, funding included; **not** additive to net live-heap
column. Byte arrays include trie and String/cached-frame backing storage. Exact
trie payload split comes from layout/reflection, not class-name ownership inference.

| Class | Two-step base | Two-step prototype | Combined base | Combined prototype |
| --- | --- | --- | --- | --- |
| [B | 843.9 | 843.9 | 540.0 | 540.0 |
| Ljdk.internal.vm.FillerArray; | 323.1 | 11.7 | 196.0 | 7.3 |
| java.lang.String | 168.6 | 168.6 | 120.6 | 120.6 |
| java.util.HashMap$Node | 160.4 | 160.4 | 96.4 | 96.4 |
| [I | 80.4 | 80.4 | 48.4 | 48.4 |
| ActionKey | 48.2 | 48.2 | 24.2 | 24.2 |
| ActionRecord | 48.2 | 48.2 | 24.2 | 24.2 |
| [Ljava.util.HashMap$Node; | 42.0 | 42.0 | 25.3 | 25.3 |
| Captured | 40.0 | 40.0 | 40.0 | 40.0 |
| ExecutionKey | 40.0 | 40.0 | 40.0 | 40.0 |
| Obligation | 32.0 | below top-30 | 32.0 | below top-30 |
| kotlin.Pair | 24.2 | 24.2 | 0.2 | 0.2 |

Parallel session-sized run: 27 ordinary allocation-failure young collections, maximum
1.140 s; 8 automatic full collections, maximum 5.218 s. Forced checkpoint/histogram
full collections maximum 4.980 s. All logged young/full pauses total 60.29 s over ~768 s
probe, including forced diagnostics. At 18M, Parallel saves ~180 MB live state versus
G1 but has longer observed pauses; keep G1 as default candidate for latency-sensitive
service qualification. Runs share host with other checks; these are observations,
not controlled production latency distributions or a standalone throughput comparison.

**Region-size correction:** JVM flags show 8 MiB G1 regions at `-Xmx12g`, 16 MiB at
`-Xmx28g`. Old 4 MiB-plus-header digest chunks are humongous in first configuration,
not second. Therefore large 12 GiB A/B gain must not be claimed at 28 GiB; matched
28GiB baseline run supplies separate comparison. Shared settled-value savings remain.

Matched 28 GiB G1 baseline completed: 989.8 → 963.0 B/trade at 9M,
986.9 → 960.6 at 18M (~2.7% live-state reduction, ~0.47 GB at 18M).
Baseline ordinary young/mixed pauses: 75, max 240.1 ms; 12 forced full collections,
max 3779.8 ms; all pauses 24.50 s. Candidate all pauses 27.34 s. Shared-host runs
are not an interleaved large-size CPU/latency comparison: no scale speedup claimed.
At 18M, baseline still retains one 32-byte Obligation per trade; remaining difference
includes collector/layout variation. Use small interleaved runs for CPU guard.
