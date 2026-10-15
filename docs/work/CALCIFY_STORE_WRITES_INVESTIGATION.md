# Calcify capacity lane: store writes per trade, and what to do about them

Status: investigation with measurements and a prototype, 2026-10-15. Not a capacity claim. One shared Apple M1 Max laptop (10 cores, other jobs running), JDK 21, Kafka Streams 4.3.1, Redpanda v26.2.3 and Postgres 16 in Docker. Prior numbers: CAL-FIN-P19 (store puts about 31 us of 75 us per decision), CAL-FIN-P22, `CALCIFY_HOT_INSTRUMENT_DESIGN.md` (option B2).

## Summary

- A booked trade is one consumed record (capture plus its instant first settlement) and cost **11 store puts and 1,799 key+value bytes** (about 1.9 KB on the changelog with record overhead) before this work. `head` and `meta` were 2 of the 11 puts and 635 of the 1,799 bytes, and `meta` recomputed the configuration digest (sort and SHA-256 of the whole configuration) for every record: **5.8 percent of the processing thread** in a sampling profile.
- Prototype (branch `worktree-agent-a04f78a3cd248565b`): head and meta every K decisions (B2, restore reads the head from the decision log), one merged store record per execution (`X`, `O`, first `A`), no per-entity version prefix, configuration digest computed once. **7.04 puts and 705 bytes per trade at K = 100 (-36 percent puts, -61 percent bytes).** On the Streams stack without Postgres: Streams thread CPU **-14 to -24 percent per decision**, changelog+decision bytes on the wire per decision 1,441 to 871 (**-40 percent**), and 395 with lz4 (**-73 percent**). On the full real stack (projector and Postgres in the loop) the lane's processed rate rose 9 to 28 percent in interleaved pairs; the projected rate did not move because the projector is the limit at one lane (CAL-FIN-P22).
- What does **not** pay: Streams' record cache (coalescing positions), RocksDB tuning at this state size, a longer commit interval, dropping the changelog (only about 6 percent more CPU than the prototype).
- Contract changes: see "Contract-change items" below. The commitment and the decision bytes are **unchanged**; the **restore rule and the store layout change**.

## Write inventory (measured, `CapacityStoreWritesTest`)

Real `CapacityProcessor` and production decoder over a counting store; 200 accounts, 64 instruments, 50,000 trades; ids as the resolver writes them (`exec-N`, `INSn`, `uN`). Per booked trade; a trade is one record with two decisions.

| Key class | What | Puts | Key B | Value B | Restore need | Derivable? |
|---|---|---|---|---|---|---|
| `e/P` position | 4 per settle (cash and shares of buyer and seller) | 4 | 139 | 152 | required (commitment leaf) | from the log by replay only |
| `e/K` action | 2 (capture action, settle action): input digest and disposition | 2 | 186 | 219 | required (idempotence, leaf) | by replay only |
| `e/X` execution | economics | 1 | 98 | 61 | required (leaf) | by replay only |
| `e/O` obligation | status, residuals (written twice in the record, one put) | 1 | 98 | 57 | required (leaf) | by replay only |
| `e/A` attempt | first settlement attempt | 1 | 106 | 41 | required (leaf) | by replay only |
| `head` | last decision (`FinanceDecisionV1`, no input) | 1 | 4 | 392 | **no**: the decision log holds it | yes, from the log |
| `meta` | binding, config digest, sequence, chain, controls | 1 | 4 | 244 | binding and config are static; sequence and controls = count of actions / funded actions; chain = head checksum | yes |
| **total (layout v1, K = 1)** | | **11** | **634** | **1,165** | | |

Per decision that is 5.5 puts and 900 bytes on the changelog; the decisions topic adds 471 bytes per decision (input, legs, four 64-hex hashes). Every entity is a leaf of the state commitment, so none can be dropped without changing the commitment (a contract change); the redundancy is in `head`/`meta`, in the 30-byte version frame in front of every value (270 bytes per trade), in three keys for one execution (hex of the same 4-string execution key, three times), and in hex doubling of keys.

After the prototype (layout v2), counted the same way:

| Variant | Puts / trade | Key+value B / trade |
|---|---|---|
| original (v1, K = 1) | 11.00 | 1,799 |
| v1, K = 100 | 9.04 | 1,169 |
| v2, K = 1 (merged execution record, no version frame) | 9.00 | 1,336 |
| v2, K = 100 | **7.04** | **705** (keys 421, values 285) |

## Options evaluated

All CPU figures are the Streams thread (plus producer network thread where stated) in microseconds per decision, from `CapacityStoreWritesBenchTest` (one lane, EOS v2, real RocksDB, changelog and Redpanda; no projector or Postgres, so only the broker shares the machine; 30,000 warm-up trades then 150,000 measured; ranges are the min..max of three interleaved rounds). Wall-clock rates on this laptop moved by 20 percent between rounds, and even thread CPU moved by about 10 percent between rounds, so compare within a round.

| Option | Streams thread us/decision | Wire bytes per decision | Verdict |
|---|---|---|---|
| baseline (v1, K = 1) | 46.8 to 53.5 (noisy matrix); 48.6 to 50.3 (quiet matrix) | 1,441 | |
| lz4 producer compression | +1.5 to +2.4 (about +4 percent) | 558 (-61 percent) | adopt: config only |
| zstd producer compression | +5.7 (about +11 percent) | 335 (-77 percent) | only if disk binds more than CPU |
| `commit.interval.ms` 1000 | no effect beyond noise (50.3, 45.5, 53.3) | 1,440 | no |
| Streams record cache (128 MB), v1 | 51.8 to 54.0 (no gain, up to +10 percent) | 1,029 (records per decision 6.5 to 4.5) | no: coalesces 2 of 11 puts but costs more than it saves |
| head and meta every 100 (v1 layout) | 40.4 to 43.8 (-13 to -24 percent per pair) | 1,115 (-23 percent) | adopt (B2) |
| v2 layout + K = 100 | **38.1 to 41.0** (quiet matrix: base 48.6 to 50.3, -19 percent) | **871 (-40 percent)** | adopt |
| v2 + K = 100 + lz4 | 40.4 to 41.5 | **395 (-73 percent)** | recommended package |
| v2 + K = 100, **changelog removed** (upper bound, cannot restore) | 36.9 to 38.8 | 483 | not worth an architecture change: only about 2.5 us (6 percent) below v2 + K |
| RocksDB `allow_concurrent_memtable_write=false` | 38.5 to 38.9 vs 38.1 to 41.0 | same | noise |
| RocksDB write buffer 64 MB x 4 | 39.3 to 40.0 | same | noise |
| RocksDB level compaction (Streams default is universal) | 36.3 to 38.6 (about -4 percent) | same | inconclusive at 54 MB of state; revisit with a multi-GB store |

Streams 4.3.1 RocksDB defaults (read from the bytecode of `RocksDBStore`): write-ahead log disabled, no compression, universal compaction, 16 MB write buffer x 3, 50 MB block cache and a bloom filter per store. So the WAL and compression options a RocksDB guide would suggest are already off.

**Where the thread's time goes** (sampling profile of the baseline, wall samples of the Streams thread): `CapacityProcessor.process` 87.7 percent, of which ledger `decide` 49.6 (state trie hashing 29), store puts 21.5 (RocksDB `put` 16.9, change logging and producer send about 4), configuration digest in `meta` 5.8, `maybeCommit` waiting on the broker 6.9. After the prototype the puts and digest drop out roughly in proportion to the 36 percent fewer puts and the removed per-record digest and protobuf of `head`. The remaining store cost is RocksDB's own put (about 1.5 us each).

### Option notes

1. **B2, head and meta every K.** Entities are still written with every record, so the store always holds whole records. `head`/`meta` are written when the sequence crosses a multiple of K (and for the first decision), so a store is at most K plus one record's decisions ahead of its meta. Restore derives the sequence (one action per decision) and the control count (funded actions) from the entities; when they are ahead of the meta it takes the chain head and the committed `stateDigest` from the **published decision log head** that the launcher already reads before Streams starts (the same transaction wrote both, so under exactly-once they agree), checks its checksum recomputes, and requires the rebuilt entities to hash to that `stateDigest`. A store behind its meta, or ahead of it with no log head, is refused. The log head check that was already there (store exactly at the head of the log) is unchanged. The design note's "read the last 2K-1 decisions" is not needed because the commitment here is synchronous: only the head decision is needed. Cost: restore now depends on the decision log for a store between checkpoints; the lane cannot restore such a store without the launcher's log head (tests that restore without it only work at a checkpoint).
2. **Layout v2** (store format only). The commitment trie still sees the same `X`, `O` and `A` entities with the same bytes, so every leaf and `stateDigest` is identical (a test restores v2 stores and compares to the entity-per-key ledger, and the end-to-end and crash tests compare to an independent oracle). `x/<hex execution key>` holds the three values behind int32 lengths; attempt 1 lives in it, attempts 2 and later stay `e/A`. A settlement in a later record than its capture rewrites the record, completing the unchanged parts from the ledger. The version frame moves to the meta version (`calcify-capacity-meta-v2`); existing v1 stores are refused (`unknown meta version`), so a v2 deployment starts from a fresh application id or topic set.
3. **Coalescing across records between commits (write-behind).** Not built as a processor-level buffer, on safety grounds: the Processor API has no pre-commit hook, so a buffer held past one `process()` call can be committed by a Streams commit (interval, rebalance or clean shutdown) before it is flushed, which leaves offsets ahead of the store (the launcher's head check would then wedge the lane). Safe forms are Streams' own record cache (measured above: no CPU gain) or driving the commits from the processor with a very long `commit.interval.ms`, which still loses the buffer at a rebalance commit unless the flush runs in an internal store flush hook (`CachedStateStore`, an internal interface). With 200 accounts and 64 instruments only the two cash positions per trade repeat within a commit window (about 1.6 puts per trade; the cache saved 2 of 11), and with thousands of accounts almost none do. Not recommended.
4. **Smaller or binary keys and values.** Hex keys could be base64 or raw bytes (needs a byte-array keyed store and a new `LifecycleKv`); with lz4 the redundancy compresses away (key bytes are most of the remaining 705), so the gain is small for the work. Not done. The `K` action value carries a 64-hex digest as text (79 bytes; 32 bytes binary) but that is the commitment's canonical value, so changing it is a commitment change.
5. **Compression of changelog and decisions topics.** lz4 is nearly free and cuts the producer's bytes by 61 percent at v1 and 55 percent at v2 (871 to 395); zstd cuts 77 percent for about 11 percent more thread CPU. Both topics are compressed by the same producer property (`producer.compression.type`), which also compresses the decisions topic (input and hashes compress about 2x). Broker CPU per decision fell with compression (about 10 to 19 us per decision to 6 to 9). Redpanda stores the producer's batches as sent. It is a Streams client property in `CapacityStreams.properties`/the launcher's client properties; not pinned by `verifyPinned`.
6. **Drop the changelog and rebuild from the decision log.** Every decision already carries its input, so state is derivable from the log (the LMAX model: journal inputs, rebuild by replay, snapshot to bound replay). Measured upper bound (changelog removed from the topology): 36.9 to 38.8 us against 38.1 to 41.0 for v2 + K, and 483 wire bytes against 871 uncompressed (395 with lz4 on the changelog). The saving is real but small next to the cost: Kafka Streams under exactly-once wipes a store after an unclean stop and relies on the changelog, so a lane without one needs its own snapshot and replay path (about 35k decisions/s replay speed, about 34 minutes for a 72-million-decision session without snapshots). Not recommended for the lane; it is the right shape for a future disk-bound regime (see sources: LMAX snapshots, TigerBeetle).
7. **Restore time.** The wiped-state restore replays the changelog; fewer, smaller records restore faster. Not timed separately.

## Verification

- `CapacityStoreTest` (9), `CapacityProcessorTest` (9), `CapacityLedgerTest`: the new restore rule at random cuts and K in {2, 5, 16} (restore-then-continue equals never-stopped; a store ahead of its checkpoint is refused without the log head or with an earlier head, an empty log, a forged self-consistent head, a head whose checksum does not recompute, a flipped entity byte, a removed action, entities behind the meta); `x/` record shapes (shared record, split over two records, retry, damaged records); processor checkpoints at the sequence crossing; restart with a published head.
- Whole finance package: 200 tests, 0 failures.
- `CapacityEndToEndTest` (4, Docker): the original three plus `checkpointsEveryTwentyFiveDecisionsStillRestoreFromTheChangelogAndTheLogHead`, which does a clean restart and a wiped-state (changelog) restore with head and meta every 25 decisions, every lane's log and projection equal to the oracle.
- New `CapacityLauncherCrashTest` (Docker, child JVM): 12,000 trades on two lanes with head and meta every 25 decisions, three SIGKILLs while decisions were flowing (progress before each kill: 202 to 1,916, 3,098 to 6,844, 6,844 to 13,012 decisions), the second restart with the local RocksDB wiped; each restart passed the lane's store-equals-log-head check and the final logs equal the oracle's chain and commitment.
- No container or volume left (`docker ps -a`, `docker volume ls`).

## Real-stack throughput (CapacityLaneThroughputTest, one lane, 300,000 trades, 64 instruments, 200 accounts, resolved source, projector and Postgres in the loop)

Interleaved pairs (base = commit before this work), processed decisions/s and Streams thread CPU seconds for 600,000 decisions:

| Round | base processed | new (v2, K = 100) processed | base Streams CPU s | new Streams CPU s |
|---|---|---|---|---|
| 1 | 19,166 | 23,196 | 30.6 | 25.1 |
| 2 | 16,192 | 17,708 | 34.3 | 29.4 |
| 3 | 16,023 | 20,433 | 34.9 | 27.9 |

Processed rate +9 to +28 percent; Streams thread CPU -14 to -20 percent. Projected decisions/s (limited by the projector, CAL-FIN-P22): base 13,439 to 15,169, new 13,089 to 15,375, no difference. A first set of three runs with the new code (K = 100) and K = 100 + lz4 gave processed 19.9k to 23.0k and 22.4k to 22.5k, projected 14.8k to 15.5k. Wall-clock spread between rounds was 20 percent (other jobs on the laptop), so the pairs, not the absolute rates, are the evidence.

## Recommendation, ranked by gain over risk

1. **lz4 on the Streams producer** (`producer.compression.type=lz4` in the launcher's client properties). Config only; no restore or contract change. -61 percent bytes at about +4 percent thread CPU, and less broker CPU. Do it first. (zstd if disk bound; +11 percent thread CPU for -77 percent.)
2. **B2, head and meta every K (default K = 100 or 1,000) plus the configuration digest computed once.** About -13 to -24 percent thread CPU, -23 percent bytes. Restore contract change (below). The digest fix alone is free and contract-neutral (5.8 percent of the thread at K = 1).
3. **Layout v2 (merged execution record, no version frame).** A further -2 puts per trade and -40 percent of the remaining bytes. Store format change only (commitment and decisions unchanged), but old stores are unreadable.
4. Not now: record cache, RocksDB options at this state size, commit interval, dropping the changelog, binary keys. Revisit RocksDB (level compaction, write buffers) and disk write amplification on a multi-GB store and a real disk; the arena disk problem (380 GB per 60-minute session) is mostly the capture/control/decision topics (2.5 KB of 5.3 KB per decision are replay inputs), which this change does not touch beyond the changelog.

Expected effect on the 5.3 KB per decision all-in (CAL-FIN-P10): the changelog share (1.5 to 1.7 KB, uncompacted) falls by about 40 percent with items 2 and 3 and by about 80 percent with lz4 added, so roughly 0.9 to 1.2 KB per decision saved, a fifth of the total, before compaction; with lz4 on every Streams-written topic the decisions topic shrinks too.

## Contract-change items (for the owner)

- **CONTRACT CHANGE, restore contract:** with `checkpointEvery` above 1 the store is verified against the published decision log head, not only its own `head`/`meta`; restore without the log head refuses a store between checkpoints. `CapacityProcessorConfig.logHead` is now a `Decision` (was sequence and checksum). Update the design text that says restore needs `head`, `meta` and entities (`CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md` section on restore, Q for B2 in `CALCIFY_HOT_INSTRUMENT_DESIGN.md`).
- **CONTRACT CHANGE, store layout:** `meta` version `calcify-capacity-meta-v2`, `x/` execution records, entity values without the version frame. Existing v1 stores and changelogs cannot be restored by this code.
- **Not changed:** entity set, leaf bytes, `stateDigest`, decision checksum and bytes, ledger behavior, the decision topic format.
- Default K stays 1 (original behavior) in `CapacityLauncher`; turning on K and lz4 is the owner's switch.

## Risks

- Restore of a store between checkpoints trusts the log head's `stateDigest` and chain; a corrupt or wrong log head is refused only if the entities do not hash to it. The launcher reads the head with a read-committed consumer before Streams starts (unchanged).
- The `x/` record is rewritten when a settlement lands in a later record, completing from the ledger's maps; tested for shared, split and retry cases but not for budget-exhausted or conflicting redelivery mixes beyond the random store tests.
- Measurements are one laptop with other jobs running; thread CPU varied about 10 percent between rounds. The ordering within each round was the same in every round for K and v2; the RocksDB and level-compaction differences are inside noise.
- Compression ratios come from the synthetic ids of this fixture (`exec-N`, `INSn`); real ids are similar in structure but not identical.

## Reproducing

- Counts: `CAP_TRADES=50000 CAP_CHECKPOINT_EVERY=100 ./gradlew test -PresearchTests --tests '*CapacityStoreWritesTest*' -i`
- Streams stack: `CAP_TRADES=150000 CAP_WARM=30000 CAP_CHECKPOINT_EVERY=100 CAP_STREAMS_PROPS=producer.compression.type=lz4 ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*CapacityStoreWritesBenchTest*' -i` (also `CAP_CACHE=true`, `CAP_NOLOG=true`, `CAP_PROFILE=true`). RocksDB options were tried with a throwaway `RocksDBConfigSetter` (concurrent memtable write off, write buffer 64 MB x 4, level compaction), not committed.
- Real stack: `CAP_LANES=1 CAP_TRADES=300000 CAP_INSTRUMENTS=64 CAP_ACCOUNTS=200 CAP_SOURCE=resolved CAP_CHECKPOINT_EVERY=100 ./gradlew test -PbrokerTests -PresearchTests --tests '*CapacityLaneThroughputTest*' -i`
- Ledger paragraph for `docs/THROUGHPUT_BASELINES.md` (not added here, to avoid colliding with other entries): "Store writes per trade, 11 puts and 1,799 bytes before; 7.04 puts and 705 bytes with head/meta every 100 and a merged execution record; Streams thread CPU -14 to -24 percent, wire bytes -40 percent (-73 percent with lz4); see `docs/work/CALCIFY_STORE_WRITES_INVESTIGATION.md`."

## Sources

- Kafka Streams RocksDB defaults and tuning (universal compaction for high write rates, transaction log disabled because the changelog is the log, cache reduces changelog traffic): [Confluent, How to tune RocksDB for Kafka Streams state stores](https://www.confluent.io/blog/how-to-tune-rocksdb-kafka-streams-state-stores-performance/); [KAFKA-12766](https://issues.apache.org/jira/browse/KAFKA-12766) (Streams disables the WAL). Defaults confirmed against the 4.3.1 `RocksDBStore` bytecode.
- Exactly-once Streams wipes and restores a store after an unclean stop: [KIP-844](https://cwiki.apache.org/confluence/display/KAFKA/KIP-844%3A+Transactional+State+Stores); [KAFKA-12634](https://issues.apache.org/jira/browse/KAFKA-12634); [KAFKA-13249](https://issues.apache.org/jira/browse/KAFKA-13249).
- RocksDB write amplification trade-offs (leveled versus universal, buffer sizes): [RocksDB tuning guide mirror](https://www.mintlify.com/facebook/rocksdb/guide/tuning-performance); [Benchmarking RocksDB's three amplification factors](https://hackernoon.com/benchmarking-rocksdbs-three-amplification-factors).
- Journal of input events, state derivable by replay, snapshot plus replay to bound recovery (nightly snapshot): [Fowler, The LMAX Architecture](https://martinfowler.com/articles/lmax.html).
- Batching amortizes cost per batch; events recorded then executed deterministically: [TigerBeetle performance](https://docs.tigerbeetle.com/concepts/performance/); [TigerBeetle architecture](https://docs.tigerbeetle.com/about/architecture) (LMAX lineage, deterministic replay).
