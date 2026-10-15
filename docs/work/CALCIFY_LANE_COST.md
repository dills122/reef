# Calcify capacity lane: where the CPU goes, and whether a faster hash library helps

Status: measurement note, 2026-10-15. Not a capacity claim: one laptop (Apple M1 Max, shared), JDK 21, short runs, no RocksDB, no Kafka client, no projector.

## Method

`CapacityLaneCostTest` (research tag, `-PresearchTests`) runs the real `CapacityProcessor` on a mock context with an in-memory store. Input is captured trades (a capture and its instant first settlement, as the decoder emits them): 2,000 accounts, 8 instruments, 200,000 trades after a 20,000 trade warm-up, 18,000 funding records. A sampler thread records the processing thread's stack every millisecond and reports inclusive time per function. Because it excludes RocksDB, the changelog and the Kafka client, it bounds the processor's own cost from below.

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

1. Fewer hashes per decision. The hash count is `entities written x path depth` less shared upper levels. A lagged commitment (design option B1) computes the root every K decisions, so the upper levels are hashed once per K instead of once per decision. That changes what each decision's `stateDigest` commits to, so it is a contract change for the owner to approve.
2. Fewer entities per trade. Each trade creates four new leaves (execution, obligation, attempt, action) plus position updates. Folding the attempt into the obligation leaf is also a commitment change.
3. Locality: hashing from one reused buffer instead of three `update` calls was tried and gave no measurable gain (12.8k trades/s against 12.8-13.6k). Storing the trie in arrays instead of linked heap nodes is contract-neutral and untried.
4. A different hash (BLAKE3 etc.) is a contract change with no hardware support on this JVM, so it is not expected to beat SHA-256 here.
