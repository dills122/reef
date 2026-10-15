# Calcify capacity lane: where the CPU goes, and whether a faster hash library helps

Status: measurement note, 2026-10-15. Not a capacity claim: one laptop (Apple M1 Max, shared), JDK 21, short runs, no RocksDB, no Kafka client, no projector.

## Method

`CapacityLaneCostTest` (research tag, `-PresearchTests`) runs the real `CapacityProcessor` on a mock context with an in-memory store. Input is captured trades (a capture and its instant first settlement, as the decoder emits them): 2,000 accounts, 8 instruments, 200,000 trades after a 20,000 trade warm-up, 18,000 funding records. A sampler thread records the processing thread's stack every millisecond and reports inclusive time per function. Because it excludes RocksDB, the changelog and the Kafka client, it bounds the processor's own cost from below.

**Decisions per second versus trades per second.** Every rate in this note and in the notes before it is in *decisions* per second with the default `two-step` decision mode, where a booked trade is two decisions (capture and first settlement), so trades/s is half the decisions/s. In the opt-in `capture-settle-v1` mode a booked trade is one decision and the two figures are equal (see CALCIFY_CAPACITY_PROFILE.md decision 7 and CAL-FIN-P25). Compare modes in trades/s, thread CPU per trade or hashes per trade, never in decisions/s: the combined mode's decisions/s is lower on the same hardware while it books more trades.

**Combined capture and settle (CAL-FIN-P25, 2026-10-15).** Same harness, flat trie and no re-encoding code, 200,000 trades, 2,000 accounts, 8 instruments, three interleaved pairs, no broker: thread CPU 50.9, 52.1, 52.5 us per trade in `two-step` against 31.9, 32.6, 31.4 us in `capture-settle-v1` (1.59 to 1.67 times less); branch hashes per trade 170.4 against 112.1; allocation 20.2 KB against 12.7 KB per trade; 18.6k to 19.2k against 30.0k to 31.2k trades/s. On the real stack the lane gain is smaller (1.33 to 1.39 times in Streams-thread CPU per trade), because RocksDB, the changelog and the Kafka client are not reduced as much as the hashing.

## Breakdown before the encoder change (10.7k trades/s, 21.4k decisions/s)

| Share (inclusive) | Where |
|---|---|
| 66% | `CapacityLedger.decide` (settle 42%, capture 22%) |
| 33% | `StateTrie.root`: SHA-256 over stale branches, once per decision |
| 24% | `Canon.bytes`: canonical encoding, of which strict UTF-8 is 12% |
| 16% | `commit` (entity encode, leaf digest, trie put) |
| 8.5% | `StreamsLifecycleKv.apply` |
| ~10% | `FinanceWire` proto decode and encode |
| 6% | `FinanceInput.digest` |

## What changed

The canonical encoder used a `CharsetEncoder` per string and a `DataOutputStream` over a `ByteArrayOutputStream` per record, and `Canon.sha` looked up a `MessageDigest` per call. It now writes into one growing array, copies ASCII directly (ASCII is its own UTF-8 and cannot be malformed; everything else still goes through the strict encoder), and reuses a per-thread digest. The bytes are identical: `CanonEncodingTest` compares against the old encoder on 20,000 random records, edge values and invalid UTF-16.

Result on the same harness: 10.7k to 12.8k and 13.6k trades/s (25.7k to 27.2k decisions/s) on two runs, about +20-25%. The trie is now the largest term (about 40%) and it is the commitment contract itself.

## On the real stack

One lane, resolved-context source, 64 instruments, 200 accounts, 300,000 trades, local Redpanda and Postgres 16 in containers, JaCoCo off, single run: the lane processed 18,971 decisions/s (9.5k trades/s; the earlier recorded per-lane ceiling at this state size was about 13.3k decisions/s) and the projector wrote 14,924 decisions/s (7.5k trades/s) with the chain digest verified. The lane is no longer the limit on this harness; the projector is.

Where the projector's time goes (new counters `FinanceProjector.verifyNanos` and `sqlNanos`, printed by the throughput test as `verify_s` and `sql_s`): in four runs, in-memory verification was 1.3-1.5 s of a 36-48 s projection, and SQL (fence, the five set-based writes, checkpoint) was 33-42 s; the commit itself, not counted there, accounted for most of the rest of `apply_s`. The projector is bound by Postgres, not by its own CPU (the projection thread used 7-8.5 CPU-seconds).

Levers tried on that harness, one run each: `synchronous_commit=off` (13.4k and 13.0k projected decisions/s with 2,000 and 8,000 record chunks), 8,000 record chunks (16.7k), against 14.9k and 12.5k for two unchanged runs. Run to run the same configuration varied by about 20 percent (processed 15.3k to 19.0k decisions/s) because Redpanda, Postgres and the Streams thread share ten cores with everything else on the laptop, so these runs do not rank the levers. A ranking needs repeated runs on a quiet dedicated host.

## Would a faster hash library help? No.

Per branch hash (67 bytes, two SHA-256 blocks), measured with a warmed loop on JDK 21:

| Provider | ns per hash |
|---|---|
| JDK `SUN` provider (ARMv8 SHA2 intrinsic, default on) | 77-81 |
| Same, intrinsic off (`-XX:-UseSHA`) | ~600 |
| Amazon Corretto Crypto Provider 2.5.0 (AWS-LC, JNI) | ~310 |
| BouncyCastle 1.86 (pure Java) | ~500 |
| Conscrypt 2.5.2 (BoringSSL) | native library failed to load on macOS; not measured |

The JDK already uses the hardware SHA-2 instructions, and a call into native code costs more than the 67-byte hash it wraps. Concatenating the three `update` calls into one `digest(byte[])` saves only 4-10 ns per hash. Jars were fetched from Maven Central and their SHA-1 checked against the published `.sha1` files; none is a dependency of the repo.

Hash speed is also not the whole trie cost. The trie does 82 branch hashes per decision (counted by `StateTrie.branchHashes`). At 80 ns that would be 6.6 us of the roughly 37 us a decision takes, about 18%, while the profile attributes about 40% to `StateTrie.root`. The difference is pointer chasing across a trie of about a million heap nodes and the arrays allocated per hash, not SHA-256.

## What would move the number (not done)

1. A lagged commitment (design option B1): compute the root every K decisions instead of every decision. Measured with a throwaway patch (not committed) on the same harness, 150,000 trades, one run each: K=1 12.9k trades/s with 83.8 branch hashes per decision; K=8 13.8k (63.3 hashes); K=32 14.3k (54.2 hashes). The hash count falls by a third at most because the cost is the path of each newly inserted leaf, not the shared upper levels, so the gain is about 11 percent at K=32. It also changes what each decision's `stateDigest` commits to (a contract change). Recommendation: do not pursue B1 for throughput.
2. Fewer entities per trade. Each trade creates four new leaves (execution, obligation, attempt, action) plus position updates. Folding the attempt into the obligation leaf is also a commitment change.
3. Locality: hashing from one reused buffer instead of three `update` calls was tried and gave no measurable gain (12.8k trades/s against 12.8-13.6k). Storing the trie in arrays instead of linked heap nodes is contract-neutral: **done afterwards** (`FlatStateTrie`, byte-identical roots, lane CPU per decision -16 to -23 percent), together with a cheaper commitment contract proposed in [the state commitment investigation](CALCIFY_STATE_COMMITMENT_INVESTIGATION.md). That note also revisits the recommendation against batching above: a lagged root gains little, a hybrid log plus position trie gains about 4x on the commitment.
4. A different hash (BLAKE3 etc.) is a contract change with no hardware support on this JVM, so it is not expected to beat SHA-256 here.
