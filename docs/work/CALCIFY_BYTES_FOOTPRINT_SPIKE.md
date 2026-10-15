# Capacity lane byte footprint

Investigation in progress, 2026-10-15. Baseline handoff commit
`f09f20c7e9699dcb4156fe67eaeaa19b009452ae`; local Docker, Redpanda v26.2.3,
Postgres 16-alpine, one lane, resolved-context input, 300k trades, 200 accounts,
64 instruments. Compression explicit: handoff branch lacks PR571's later default.
No cloud resources. Raw logs currently `/private/tmp/reef-footprint`.

## Measurement definitions and correction

`CapacityByteInventory` snapshots after funding and after exact projection completion.
Kafka counts are read-committed key+value bytes, excluding record/batch framing,
transaction markers, indexes and compression. Disk uses allocated KiB (`du -sk`),
including preallocated segments and indexes. PostgreSQL reports heap main fork,
table including auxiliary/TOAST storage, indexes, total and row counts separately.
WAL uses insert-LSN delta: bytes generated, not retained `pg_wal` directory capacity.
Do not add generated WAL to retained disk as if both accumulated forever.

First two-step/no-compression pilot completed, but concurrent changelog compaction
removed historical writes before inventory: 8.297 retained records/trade, not the
11 emitted puts/trade. Preserve pilot as real default-compaction disk evidence.
Subsequent inventory runs precreate changelog with 24h minimum compaction lag to
count every emitted record. This diagnostic setting increases disk use; it is not
recommended production tuning. Comparisons must state this difference.

## Reader and constraint audit

Source: [processor writes and published input](../../services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/CapacityProcessor.kt),
[SQL projector](../../services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/FinanceProjector.kt),
[measured inventory](../../services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/finance/CapacityByteInventory.kt).

Source search across `services/platform-runtime/src/main` and `scripts` found table
reads in `FinanceReadStore`, projector replay/checkpoint logic, and operational
fixtures/bootstrap. Decision PK serves `(binding_digest,sequence)` range/replay
reads; position PK serves account filtering; obligation/attempt PKs serve ordered
reads, updates and FK joins. Action-key unique index has no action-key SELECT in
those reader files, but enforces no duplicate action within binding. Dropping it
weakens DB integrity and is not a safe unused-index cleanup. Keep FKs and checks.

## Options and decision gates

- Producer compression: measure none/lz4/zstd with unchanged key/value payload and
  exact projected counts. Include capture producer, not only Streams producer.
- WAL compression: compresses full-page images, not every WAL record; measure
  combined setting rather than multiply isolated gains. Keep fsync and synchronous
  commit on in durability-preserving runs.
- UNLOGGED: diagnostic/rebuildable-soak option only. All related projection tables,
  including checkpoint, must reset coherently. Crash truncation is expected and
  requires full replay from retained decision log; no live rollout claim.
- Compaction: removes old versions of repeated positions/head/meta, not unique
  execution/action keys. Tune segment closure/cleaner delays with retained-key and
  wiped-state restore proof. Deleting live changelog keys breaks restore.
- Retention: 15/30-minute session length is not a safe Kafka retention interval.
  Replay, projection rebuild, audit retention and oldest recoverable input need
  explicit owner policy. Existing runtime contract says unlimited for run.
- Fillfactor: lowering append-only table fillfactor consumes more pages; position
  update/HOT behavior could benefit, but must measure separately. No blanket default.
- Raw digests/bytea, removing input echo, omitting prior_chain/input_digest, binary
  store keys and v2 head/meta checkpointing are owner-gated record/restore changes.
  Preserve old commitment bytes even if internal representations shrink.

Prior store prototype measured 1,799 to 705 key+value B/trade (v2/K100), not disk bytes
and not this combined mode. See `CALCIFY_STORE_WRITES_INVESTIGATION.md`; do not add
its percentage gain to compression or extrapolate it as measured combined savings.

## Prior art

[Kafka 4.3 producer settings](https://kafka.apache.org/43/generated/producer_config.html)
cover batch compression. [Redpanda compaction](https://docs.redpanda.com/streaming/current/manage/cluster-maintenance/compaction-settings/)
explains cleaner scheduling. [Postgres 16 page layout](https://www.postgresql.org/docs/16/storage-page-layout.html)
describes tuple/page overhead. [WAL settings](https://www.postgresql.org/docs/16/runtime-config-wal.html)
and [non-durable settings](https://www.postgresql.org/docs/16/non-durability.html)
distinguish compressed WAL from unlogged crash behavior.

## Controlled inventory

All six runs completed exact 300k trades; compaction delayed 24h for emitted-record counting.

| Mode | Capture records / B | Decision records / B | Changelog records / B | Total payload B/trade |
| --- | --- | --- | --- | --- |
| Two-step | 1 / 162.582 | 2 / 945.847 | 11 / 1806.360 | 2914.789 |
| Combined | 1 / 162.582 | 1 / 532.114 | 9 / 1473.148 | 2167.844 |

Compression leaves these logical bytes unchanged. No trade-time CONTROL records.

| Mode/codec | Broker allocated growth B/trade | PG growth B/trade | WAL generated B/trade | Broker+PG extrapolated GB at 9M / 18M |
| --- | --- | --- | --- | --- |
| capture-settle-v1-lz4 | 671.1 | 1528.1 | 2124.4 | 19.79 / 39.58 |
| capture-settle-v1-none | 2152.5 | 1528.2 | 2127.8 | 33.13 / 66.25 |
| capture-settle-v1-zstd | 335.5 | 1527.5 | 2126.9 | 16.77 / 33.53 |
| two-step-lz4 | 1006.5 | 2174.1 | 2956.3 | 28.63 / 57.25 |
| two-step-none | 2941.8 | 2174.3 | 2953.5 | 46.05 / 92.09 |
| two-step-zstd | 559.3 | 2173.9 | 2953.4 | 24.60 / 49.20 |

Decimal GB. Disk columns include actual segment/index allocation; compressed capture
growth was zero because records fit already allocated 32MiB segment, **not** because
capture costs zero. These are finite-run allocation slopes, not exact future storage.
Reserve at least one extra 32MiB allocation quantum per topic/partition and retain
funding baseline; compression ratio, batch fullness and compaction vary at scale.
WAL column is traffic; retained WAL directory is controlled separately by checkpoint/
recycling/replication. Broker replicas, backups, RocksDB local state and disk reserve
are additional; RF1/write-cache diagnostic is not a durable deployment budget.

Combined projector intentionally still creates attempt 1 row, preserving read-model
shape across modes (`FinanceProjector`); only decision row count halves. Dropping
attempt table from combined budget would understate actual projection storage.

## Record-shape estimates, not implemented

Four 64-character digest protobuf fields each have one-byte tag and length. Replacing
hex with 32 raw bytes saves exactly 128 payload bytes/decision for this shape: 256 B/trade
two-step or 128 combined, before compression. Removing echoed input also removes its
field tag/length plus serialized input; amount depends on IDs and input type. It
breaks projector independent verification and cannot be treated as safe duplication.
Decision SQL stores input_digest/prior_chain/checksum: raw representations save 96
logical value bytes/row (192 two-step, 96 combined), before tuple alignment, indexes
and TOAST. Binding digest appears in every row and PK; raw binding could save 32
value bytes/row plus index-key savings but changes schema and bindings' representation.
Removing prior_chain/input_digest saves 128 logical bytes/decision row, but changes
replay/audit dependency. Do not add overlapping raw/remove estimates.

Store keys currently expand canonical frames to hex; binary keys save half that
hex payload but require new layout/restore version. Prior v2/checkpoint prototype
saves 1094 key+value B/trade for its two-step workload (9.85/19.69GB at 9M / 18M), not
measured compressed disk savings. Owner must choose versioned migration and recovery
contract before implementation. New wire/state bytes require separate review.

## PostgreSQL breakdown

Representative zstd runs, net funding baseline; codec does not control SQL layout.
TOAST growth zero; initial per-table 8192-byte TOAST allocation remains in baseline.
Total includes auxiliary forks, so heap+indexes need not exactly equal total.

| Mode/table | Rows/trade | Heap B/trade | Index B/trade | TOAST B/trade | Total B/trade |
| --- | --- | --- | --- | --- | --- |
| two-step attempt | 1.0000 | 146.34 | 236.07 | 0.00 | 382.51 |
| two-step checkpoint | 0.0000 | 0.00 | 0.00 | 0.00 | 0.00 |
| two-step decision | 2.0000 | 712.35 | 668.08 | 0.00 | 1380.60 |
| two-step obligation | 1.0000 | 182.05 | 226.67 | 0.00 | 408.84 |
| two-step position | 0.0000 | 1.88 | 0.03 | 0.00 | 1.91 |
| capture-settle-v1 attempt | 1.0000 | 146.34 | 236.07 | 0.00 | 382.51 |
| capture-settle-v1 checkpoint | 0.0000 | 0.00 | 0.00 | 0.00 | 0.00 |
| capture-settle-v1 decision | 1.0000 | 372.38 | 360.97 | 0.00 | 733.43 |
| capture-settle-v1 obligation | 1.0000 | 182.05 | 226.67 | 0.00 | 408.84 |
| capture-settle-v1 position | 0.0000 | 2.65 | 0.03 | 0.00 | 2.68 |

Tuple headers, alignment and free space explain part of heap overhead. Each decision
PK repeats binding+sequence; unique action index repeats binding+three action fields.
Obligation and attempt indexes repeat binding+four execution identifiers (attempt
adds attempt number). These are necessary current reads/integrity, not unused payload.
Funding dominates fixed position indexes; trade-time growth reflects HOT/update churn.

## Measured combined configuration

All runs: combined mode, zstd for capture+Streams, WAL lz4, synchronous_commit/fsync
on. `wal`: 24h compaction lag; `compact`: default 0 lag; `fillfactor`: default lag plus
position fillfactor 90; `unlogged`: default lag, fillfactor 100, all five related tables
unlogged. One 300k run each; comparisons include normal page/allocation variation.

| Variant | Broker growth B/trade | PG B/trade | Generated WAL B/trade | Broker+PG 9M / 18M GB |
| --- | --- | --- | --- | --- |
| wal | 335.54 | 1527.97 | 2108.89 | 16.77 / 33.54 |
| fillfactor | 335.54 | 1526.93 | 2105.23 | 16.76 / 33.52 |
| unlogged | 335.54 | 1528.11 | 0.10 | 16.77 / 33.55 |
| compact | 335.54 | 1527.78 | 2108.40 | 16.77 / 33.54 |

WAL lz4 versus off: 2126.86 to 2108.89 B/trade in paired codec/mode shape (~0.8%).
This mostly append workload provides little compressible full-page traffic. No
fillfactor 90 default recommendation: difference is within few B/trade variation.
Default compaction does remove historical versions during scan, but allocated disk
stayed in same 32 MiB quanta; immediate disk reclamation cannot be assumed.
Durable variants' retained WAL grew 32MiB to 640MiB; unlogged stayed 16MiB. These are
short-run directory allocations, not projected 18M retained WAL. Unlogged's0.10
B/trade residual WAL comes from remaining database housekeeping; it does not make
projection durable. Require full retained decision replay after crash.

Rank: (1) keep shipped lz4 baseline; (2) consider zstd for storage-constrained sessions
after hardware CPU qualification; (3) WAL lz4 optional small measured gain; (4) retain
default compaction rather than measurement-only 24h lag; (5) no index/constraint drop
or blanket fillfactor change; (6) unlogged only explicitly disposable soak projections.
Compression CPU/hardware rates are not qualified by these footprint runs.

Owner decisions: audit/recovery retention window, versioned binary store/v2 checkpoint
restore, raw/omitted decision fields and SQL representation, and unlogged deployment
recovery policy. No retention truncation or contract change applied.

## Reproduce

From `services/platform-runtime`:

```sh
CAP_FOOTPRINT=true CAP_LANES=1 CAP_TRADES=300000 CAP_INSTRUMENTS=64 CAP_ACCOUNTS=200 \
  CAP_SOURCE=resolved CAP_DECISION_MODE=capture-settle-v1 CAP_COMPRESSION=zstd \
  CAP_WAL_COMPRESSION=lz4 ./gradlew cleanTest test -PbrokerTests -PresearchTests \
  --tests '*CapacityLaneThroughputTest*' -i --console=plain
```

Default inventory delays compaction 24h for exact emitted counts. Set
`CAP_COMPACTION_LAG_MS=0` for retained/default-compaction inventory; counts then exclude
compacted history. `CAP_POSITION_FILLFACTOR=90` and `CAP_UNLOGGED=true` are opt-in
research variants. Latest harness asserts capture/decision counts and controlled
changelog 11/9 puts per trade. All settings affect disposable test containers only.

## Exact logical savings for owner-gated shape choices

Counted from 300k uncompacted records in asserted reruns. Echo includes protobuf
field 9 tag/length. Binary entity key keeps `e/<tag>/` prefix, replaces only hex suffix
with original bytes; head/meta unchanged. These are logical traffic reductions, not
compressed disk or a tested alternative implementation. Do not combine with v2 store
percentage because layouts overlap.

| Option/mode | Logical B/trade saved | Logical GB saved at 9M / 18M |
| --- | --- | --- |
| two-step: Remove echoed input field | 193.108 | 1.738 / 3.476 |
| two-step: Binary canonical entity key suffix | 298.172 | 2.684 / 5.367 |
| two-step: Four raw wire digests | 256.000 | 2.304 / 4.608 |
| capture-settle-v1: Remove echoed input field | 109.005 | 0.981 / 1.962 |
| capture-settle-v1: Binary canonical entity key suffix | 208.068 | 1.873 / 3.745 |
| capture-settle-v1: Four raw wire digests | 128.000 | 1.152 / 2.304 |

## Actual per-topic allocation

Allocated KiB after funding → after 300k trades, including segment/index overhead;
24h compaction lag. CONTROL unchanged (~32MiB fixed allocation), omitted from growth
budget. Broker root includes controller metadata, reported separately in raw logs.

| Mode/codec | CAPTURE KiB | DECISIONS KiB | Changelog KiB |
| --- | --- | --- | --- |
| capture-settle-v1-lz4 | 32784 → 32784 | 32784 → 98320 | 32788 → 163864 |
| capture-settle-v1-none | 32784 → 65552 | 32784 → 192120 | 32788 → 471296 |
| capture-settle-v1-zstd | 32784 → 32784 | 32784 → 65552 | 32788 → 98324 |
| two-step-lz4 | 32784 → 32784 | 32784 → 163804 | 32788 → 196632 |
| two-step-none | 32784 → 65552 | 32784 → 305072 | 32788 → 589596 |
| two-step-zstd | 32784 → 32784 | 32784 → 98320 | 32788 → 131096 |

## Allocation-aware planning allowance

Growth slopes above undercount data that fits initially empty preallocated segments.
For planning, use **final full allocation** of CAPTURE/DECISIONS/changelog divided
by 300k (includes slack/funding), plus PG net row growth; add broker baseline outside
these three topics once (~1.86GB in this Docker fixture, including CONTROL/controller
metadata). This conservatively amortizes observed active-segment space repeatedly;
it is not a measured 9M/18M disk run or a guaranteed upper bound for other batching,
IDs, replicas or workloads. PG funded baseline (~12.6MB) is additional once.

| Mode/codec | Full three-topic B/trade | Plus PG B/trade | Planning GB at 9M / 18M, fixed broker included |
| --- | --- | --- | --- |
| capture-settle-v1-lz4 | 1006.8 | 2534.9 | 24.68 / 47.49 |
| capture-settle-v1-none | 2488.2 | 4016.5 | 38.01 / 74.16 |
| capture-settle-v1-zstd | 671.3 | 2198.7 | 21.65 / 41.44 |
| two-step-lz4 | 1342.2 | 3516.3 | 33.51 / 65.16 |
| two-step-none | 3277.6 | 5451.9 | 50.93 / 100.00 |
| two-step-zstd | 895.0 | 3068.8 | 29.48 / 57.10 |

Lowest measured footprint combination (combined/zstd/WALlz4/default compaction)
is approximately same planning row as combined-zstd: **~22 GB / 42 GB broker+PG** rounded,
before retained WAL, RocksDB, backups and reserve. Observed retained WAL 640 MiB is
reported separately; generated WAL 18.98/37.96 GB at 9M/18M is traffic, not retained disk.
Compression ratio and segment slack must be rechecked on target hardware/session.

16MiB/10s changelog segments with default compaction retained 7.039 records/trade
(1137.649 logical B/trade) at scan versus 9 emitted. Changelog allocation grew 16800
to 132600 KiB; combined three-topic growth 507.11 B/trade versus 335.54 with default
segments. More compaction did not mean less allocated disk here; no tuning rollout.
Raw emitted bytes remain original 9 puts, 1473.148 B/trade. Short asynchronous cleaner
observation does not establish eventual compacted size or deleted-key recovery.

Processor detail: one capture record can produce two decisions, but coalesces entity
writes and persists HEAD/META only once. Stored HEAD excludes echoed input; published
decisions add field 9 afterward. Thus input-echo savings above apply only to decision
topic. Raw four-digest encoding would additionally save 128 logical HEAD bytes/trade
in uncompacted changelog (both modes), beyond 256/128 in two-step/combined decisions.
After compaction, one retained HEAD per lane makes this a traffic saving rather than
per-trade live-key saving. No wire/layout change implemented.
