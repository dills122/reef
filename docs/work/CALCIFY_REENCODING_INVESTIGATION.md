# Calcify finance lane: redundant re-encoding, what was removed, what is left

Status: measurement and change note, 2026-10-15. Not a capacity claim: one shared laptop (Apple M1 Max, JDK 21), no RocksDB, no changelog, no Kafka client, no Postgres. Companion to [CALCIFY_LANE_COST.md](CALCIFY_LANE_COST.md) and [CALCIFY_CAPACITY_PROFILE.md](CALCIFY_CAPACITY_PROFILE.md).

Question: between the wire record arriving and the projection row being written, how much work re-encodes, re-hashes or re-parses the same logical data, and how much of it can go **without changing a byte of the verification contract** (decision checksum preimage, input digest, state key and leaf bytes, store entity bytes, protobuf wire format)?

Answer: a lot of small waste and one large item (the per-record configuration digest), worth **about 10% of lane CPU per decision at 8 instruments and 14-19% at 64**, **about half of the lane's allocation (-49% to -55%)**, and **about three quarters of the projector's in-JVM decode cost**. The rest of the lane is the commitment trie (about half of CPU), which is the contract itself and out of this note's scope. Every change is byte-identical, proven by differential tests against the code it replaced.

## Method

Wall-clock trades/s is useless while other builds share the machine (observed +-20%). Two contention-immune metrics instead, both for the **processing thread only**, from `ThreadMXBean`:

- CPU nanoseconds per decision (`getCurrentThreadCpuTime`), allocated bytes per decision (`getThreadAllocatedBytes`).
- `CapacityReencodingCostTest` (research tag): the real `CapacityProcessor` on a mock context with an in-memory store, fed by the real `CapacityResolvedContextDecoder` (one `MatchContextResolvedV1` per trade, as on the product path; the older `CapacityLaneCostTest` decodes a bare `FinanceInputV1`, which understates decode). 2,000 accounts, 8 instruments (or 200 and 64), 20,000 trade warm-up, then two segments of 100,000 trades. Each trade is two decisions (capture, instant settle). It then replays the published records through the real `FinanceProjectionRunner.decode` and `FinanceProjector.apply` against a no-op JDBC stub (fence answers, writes swallowed), so SQL and pgjdbc are excluded and only the projector's own JVM work remains.
- A/B across processes: the pre-change commit was exported to a scratch directory, and baseline and working tree ran **interleaved** (base, new, base, new, ...), 3-4 rounds. Segment 1 is the same state size on both sides.
- `ReencodingMicroCostTest` (research tag): each shortcut against the code it replaced **inside one JVM**, alternating old and new round by round (7 rounds, min and median). Min and median agree within 1-2%, and a control row that compares identical code differs by 0.1-1.6%, so differences above about 3% are real.
- Programmatic JFR (`CAP_JFR=1`) for CPU samples and allocation sites.

Run: `cd services/platform-runtime && CAP_TRADES=100000 CAP_SEGMENTS=2 ./gradlew cleanTest test -PresearchTests --tests '*CapacityReencodingCostTest*' -i --console=plain` (lines starting `REENC`), and the same with `*ReencodingMicroCostTest*` (lines starting `MICRO`). `CAP_JFR=1` adds the profile.

## Stage table

Counts are per consumed trade (a capture decision and a settle decision, 10 entity writes: capture writes X, O, K; settle writes four P, A, O, K). Costs are the micro measurements, nanoseconds per call and bytes allocated per call, old code then new.

| # | Stage | Same logical data | Produces | Consumer | Per trade | Cost old -> new |
|---|---|---|---|---|---|---|
| 1 | Decode (lane) | the resolved context | parsed message, `Capture`, `Settle` | ledger | 1 parse | about 550 ns, 1.6 KB per decision (unchanged); the decoder also hashed every raw record eagerly for its `Bad` digest, now only when the record is bad |
| 2 | Id validation | every account, asset, action and execution id | a `ByteArray` per id (strict UTF-8 only to count bytes), a `listOf` per key | model constructors | about 14 | `ExecutionKey` + `ActionKey` 280 -> 76 ns, 1,672 -> 80 B |
| 3 | Input digest | canonical input | writer buffer, canonical array, SHA-256, 64-char hex | ledger (action check, decision, checksum), published-record check, projector | lane 2, plus 2 more for the published-record check; projector 2 per decision | 283 -> 262 ns first call, 888 -> 184 B; a repeat call on the same input 279 -> 5 ns (remembered) |
| 4 | Execution key framing | the four framed fields | 4 `str` calls each | canonical input (x2), entity keys X, O, O, A | 6 | 260 -> 215 ns, 1,472 -> 720 B (cached from the second use) |
| 5 | Entity framing | key fields, value fields | two fresh writers and arrays per entity | entity | 10 | CPU flat, allocation -77% (reused writer) |
| 6 | Entity key and leaf hash | key bytes, value bytes | three `MessageDigest.getInstance`, a constant (label, tag) frame re-encoded, intermediate arrays | trie | 10 | 427 -> 278 ns, 1,088 -> 160 B |
| 7 | Trie put and root | leaf digests | branch hashes, a 32-byte array per hash | state digest | 10 puts, 2 roots | unchanged, about 52% of lane CPU |
| 8 | State commitment | sequence, controls, trie root | writer, `getInstance`, hex | decision | 2 | folded into one digest of the writer plus the root |
| 9 | Decision checksum | decision fields, three hex strings | writer, SHA-256, hex | chain | 2 | 606 -> 529 ns, 1,790 -> 120 B |
| 10 | Balance check | legs | `BigInteger` per leg and a map | invariant | 2 | 85 -> 34 ns, 480 -> 96 B |
| 11 | Store key and value | key bytes, value bytes | a hex `String` through a hex formatter; version frame re-encoded then concatenated | RocksDB (the String serde encodes the key again) | 10 | 238 -> 214 ns, 595 -> 374 B |
| 12 | Head record | the last decision | message builders, `toByteArray` | store `head` | 1 | 655 -> 314 ns, 1,583 -> 439 B |
| 13 | Published record | decision plus input | message builders, `toBuilder` copy, digest recheck, `toByteArray` | decisions topic | 2 | 1,180 -> 422 ns, 2,639 -> 951 B |
| 13+12 | one trade: two published records and the head | | | | | **3,188 -> 727 ns, 7,006 -> 1,918 B** |
| 14 | Meta | the lane configuration | configuration digest recomputed per record: sort the security set with a byte-wise comparator, canonical encode, SHA-256 | store `meta` | 1 per record | 8 securities 892 ns, 64 securities 4,480 ns, 256 securities 15,973 ns, about 1-30 KB -> 5 ns (computed once at init) |
| 15 | Decision key | lane, sequence | two `padStart` strings | decisions topic key | 2 | one pass, one array (not measured alone) |

Projector, per decision (in the JVM; the real projector also pays pgjdbc array encoding and SQL, which this harness excludes):

| Stage | Cost old -> new |
|---|---|
| Parse `FinanceDecisionV1` | unchanged, 1.2 us |
| Unknown-field check: reflective `allFields` (a sorted map per message) over the decision and the input | 2.4 us -> 0 (typed, checks the default instances) |
| Four 64-hex digest checks with a `Regex` | 2,485 -> 157 ns, 880 -> 16 B |
| Input digest verified in decode and again in `apply` | two computations -> one (second is remembered) |
| Decode in total (`FinanceProjectionRunner.decode`) | 6.8 us -> 1.9 us, 8.0 -> 3.0 KB |
| `apply` (digest, chain link, row arrays) | 1.5 us -> 1.1-1.4 us, 2.8 -> 0.8 KB |

## Measured result

Interleaved A/B, processing-thread CPU per decision. The first number is segment 0 (state grows from 58k to 258k entities), the second segment 1 (to 458k). Ranges are over all rounds.

| Workload | Baseline | After | Change |
|---|---|---|---|
| 8 instruments, 2,000 accounts, 4 rounds, segment 0 | 31.2-31.7 us (median 31.6) | 27.6-28.7 us (28.2) | **-10.8%** |
| same, segment 1 | 35.4-36.4 us (35.8) | 32.0-32.5 us (32.2) | **-10.2%** |
| same, allocated bytes per decision | 29.6-30.6 KB | 15.0-15.7 KB | **-49%** |
| 64 instruments, 200 accounts, 3 rounds, segment 0 | 32.7-34.3 us (33.9) | 26.5-28.7 us (27.3) | **-19%** |
| same, segment 1 | 37.2-38.4 us (37.4) | 29.9-32.3 us (32.2) | **-14%** |
| same, allocated bytes per decision | 33.6-34.2 KB | 15.0-15.5 KB | **-55%** |
| Projector decode, per decision | 6.8-6.9 us, 8.0 KB | 1.85-1.92 us, 3.0 KB | **-72%**, -63% |
| Projector `apply`, per decision | 1.51-1.61 us, 2.8 KB | 1.09-1.46 us, 0.8 KB | -5% to -27%, -70% |

(Earlier intermediate rounds on the same harness: after the first set of changes -7% to -10% CPU, after the forward-writing protobuf encoder a further -3%; a run before any change differed from itself between worktrees by up to 7%, which is why only interleaved pairs are quoted.)

In the 64-instrument case the per-record configuration digest alone costs about 4.5 us per consumed trade-record (about 2.2 us per decision, 6% of a decision), and the gain is larger.

## What changed (all byte-identical)

| Change | Where |
|---|---|
| One reusable per-thread writer, digest and output for canonical encoding (`Canon.bytes`, new `digestHex` and `digestBytes` that hash the writer's buffer without copying it; nested use gets a fresh writer, and a failed encoding leaves the shared one clean) | `FinanceModel.kt` |
| Allocation-free id validation (ASCII needs no encoder: UTF-8 size is the length, control is below 0x20 or 0x7f, blank-without-control is spaces only; non-ASCII keeps the old path) | `FinanceModel.kt` |
| Inputs remember their digest (immutable; a `copy` starts clean); `ExecutionKey` remembers its framed fields from the second use | `FinanceModel.kt` |
| `StateCommitment.keyOf`, `leafOf`, `commitment`: thread digest, constant (label, tag) frames cached, one array per leaf | `StateCommitment.kt` |
| Store key (one Latin-1 array and one string), store value (cached version frame, one copy) | `CapacityStore.kt` |
| Balance check on `Long` with exact `BigInteger` fallback on overflow; `checksumOf` straight into the digest | `CapacityLedger.kt` |
| Direct protobuf encoders (`FinanceWire.encode`, `withInput`, `encodeTrusted`): same bytes as `toProto(..).toByteArray()`, written forward into a reusable buffer, ASCII copied, other text through `CodedOutputStream`; the lane encodes the decision once (its bytes are the store head and the prefix of the published record, input being the last field) and does not hash the input a second time to check what the ledger just computed | `FinanceWire.kt`, `CapacityProcessor.kt`, `CapacityStore.kt` |
| Configuration digest computed once at processor init, not per record | `CapacityProcessor.kt` |
| Decision key in one pass | `CapacityProcessor.kt` (`LaneByKey`) |
| Decoders hash a record for its `Bad` digest only when it is bad | `CapacityResolvedContextDecoder.kt`, `CapacityCaptureMapping.kt` |
| Typed unknown-field check and table-driven 64-hex check on decode | `FinanceWire.kt` |

Proof (new file `ReencodingDifferentialTest`, with the old code kept as the reference): `boundedText` accepts and refuses exactly what it did on 60,000 random and edge strings (whitespace, controls, surrogates, 127-129 byte boundaries); the reused writer and the direct digests equal a fresh encoding on 5,000 random records, including nested use and use after a failure; execution frames equal across repeated and cached use; key and leaf digests equal the definition for random entities and tags; store keys and values equal the old formulas; the balance check equals the exact sum including overflow; **direct protobuf encoders equal `toProto(..).toByteArray()` on 20,000 random inputs and decisions (every disposition, authority, zero and negative numbers, empty fields, non-BMP text) and when nested lengths need multi-byte varints (the test caught a real off-by-one there)**; unknown fields are refused at exactly the levels the reflective check refused them; digest-format acceptance equals the regex on 20,000 strings; decision keys equal the padded format; and an end-to-end run of the real processor over 1,500 random trades with redeliveries checks that **every store entry, the head, the meta and every published record are byte-equal to what the old formulas give** and that the store restores to the committed state. The existing golden tests (`FinanceLedgerTest`, `StateCommitmentTest`, `CapacityStoreTest`, `FinanceWireTest`, `CanonEncodingTest`) pass unchanged. `./gradlew test --tests '*finance*'`: 211 tests, 0 failures.

## Rejected or not worth it

- **Explicit scratch passing instead of a `ThreadLocal`**: a `ThreadLocal` read measures 7 ns against 5 ns for a held reference, about 60 ns per decision. Dropped.
- **De-duplicating the store writes of a two-decision record** (the obligation is written by the capture and again by the settle, and the first value is overwritten): saves about one entity key and value per trade (250 ns), and a hash-based de-duplication costs about as much. Dropped.
- **Caching the position entity key hash per (account, asset)**: saves a key frame and one SHA (about 200 ns) on four of ten entities per trade, about 1.3% of CPU, for a per-position cache that grows with accounts times instruments. Not done.
- **Dropping the digest recheck in the projector's `apply`**: `apply` is the verifier of what it writes and takes records from callers; the saving (one 0.26 us digest) is already taken by remembering the digest on the decoded input, with the check kept.
- **Carrying raw digest bytes instead of hex strings inside the lane**: the hex String is the contract's representation in the checksum preimage, the state commitment and the entity value, so every use would convert back. Gain under 1.5%; see the contract candidates.
- **Trie internals** (`StateTrie.put` copies key and leaf, `Branch.digest` allocates a result array per branch hash; together about 30% of the remaining allocation, and the trie is about half of lane CPU): contract-neutral but another investigator's area; not touched here.

## Projector side

Per decision the projector's JVM work was decode 6.8 us plus apply 1.5 us, about 8.3 us of the roughly 67 us a decision takes at the measured 14.9k decisions/s, and the decode runs on the projector thread inside the read call (`CapacityLauncher` wires `FinanceProjectionRunner.decode` into the tailing reader), serially before the SQL. It is now 1.9 plus 1.1-1.4 us: about 5 us per decision, **about 7-8% of the projector thread's time**, and none of it is SQL. Postgres and the commit still dominate (33-42 s of 36-48 s in the recorded runs). What remains in the JVM is protobuf parse (1.2 us, 12 strings per decision), the input digest (0.26 us), the chain checksum (0.5 us) and the row arrays; the pgjdbc array text encoding and the statements are not measured here and are likely the larger JVM cost inside the SQL time. Worth doing: the changes above, which are cheap. Not worth doing from this angle: anything further in the projector's decode. Honest limit: the harness cannot show the effect on projected decisions per second because Postgres, not the thread's CPU, sets the rate.

## Contract-change candidates (owner decision, not done)

| Candidate | What changes | Estimated gain | Cost |
|---|---|---|---|
| Binary digests in `FinanceDecisionV1` (`bytes` fields for input digest, prior chain, checksum, state digest) and as 32 raw bytes in the checksum preimage | wire format, checksum preimage, entity value of the action entity `K`, projection columns | lane: three hex formats and four ASCII copies per decision, about 0.5 us, **1-2% of lane CPU**, one fewer SHA block per checksum; published record 450 -> about 320 bytes (-28%); projector: four hex checks, four 64-char strings and the text columns in Postgres become 32-byte values | new digest format version, migration of stored decisions and projection tables, golden vectors, independent encoder |
| Fold the settle attempt into the obligation, and do not store an action entity for settles | state commitment (fewer leaves) | each entity costs about 5% of lane CPU (non-trie 0.9 us, trie path about 3.5 us, store 0.25 us): **up to about 10%** for two of ten | commitment contract and restore |
| Lifecycle-envelope content digest from the raw bytes instead of `toBuilder().clearContentDigest().build().toByteArray()` | verification of the envelope source (applies only to the lifecycle capture source, not to resolved contexts) | the documented 16 us per record, **up to about 20% of a lane on that source** | needs the producer's field order pinned as canonical, or the digest defined over the raw bytes minus the digest field |
| Lagged commitment root | `stateDigest` per decision | about 11% at K=32 (see CALCIFY_LANE_COST.md) | already assessed there: not recommended |

## What remains in the lane (JFR after the change, 8 instruments)

`StateTrie.put` 23.7% and `StateTrie.root` 28.4% of CPU (the hash path itself about 20%), `CapacityLedger.decide` 69% in total, decode 5.5%, protobuf writers 3.5%, the in-memory test store about 10% (a `TreeMap` of `Bytes`; RocksDB replaces it in production), the remainder maps, boxing and the ledger's own objects. Re-encoding is no longer a visible term: entity framing, hashing and store formatting outside the trie are about 5% together.
