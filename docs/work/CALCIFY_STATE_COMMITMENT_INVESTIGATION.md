# Calcify finance lane: making the state commitment cheaper (investigation)

Date: 2026-10-15. Status: **measurement and recommendation, not a decision.** One laptop (Apple M1 Max, 10 cores, shared with other work), JDK 21 toolchain, no broker, no RocksDB, no projector. Not a capacity claim. Everything marked *contract change* needs the owner before it is built; the one item that shipped on this branch is byte-identical.

Scope: the commitment `C(S)` of [the state commitment design](CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md) (a path-compressed binary trie over `SHA-256(entity key)`, section 4.1), as used by `CapacityLedger`. Inputs: [lane cost](CALCIFY_LANE_COST.md) (the trie is about 40 percent of a lane; 82 branch hashes per decision) and the hot-instrument note.

## 1. Result in one table

Thread CPU per decision spent on the commitment alone, about 10^6 entities, 80,000 timed decisions, three interleaved repeats (min..max). Harness: `CommitmentOptionsBenchTest` (section 7). "Hashes" are trie branch hashes per decision, plus chain or MMR hash calls for the hybrids.

| Option | Contract | CPU per decision | vs E0 (the code before this branch) | Hashes | Verdict |
| --- | --- | --- | --- | --- | --- |
| E0 linked trie, per-call digest (before this branch) | n/a | 21.1 to 23.1 us | 1.00x | 88.5 | |
| **E3 flat trie + per-thread digest (shipped here)** | **byte-identical** | 15.4 to 18.1 us (a second run: 15.1 to 15.5) | **1.35x** | 88.5 | **done** |
| Lagged root every 8 / 32 / 256 decisions, flat trie | changes (design 5.6) | 13.5..14.5 / 12.7..14.0 / 11.4..15.8 us | 1.6x / 1.7x / 1.65x | 67.6 / 58.5 / 44.4 | small gain, large semantic cost |
| Lagged every 4,096 / 65,536, puts batched in key order | changes (5.6) | 8.0..8.2 / 8.8..9.2 us | about 2.7x | 24.5 / 13.0 | residual is leaf hashing and puts |
| SHA-512/256 for branch, key and leaf hashes (one compression per branch) | changes | 21.8..23.1 us against 17.3..18.7 for the SHA-256 control | **0.8x of the SHA-256 control (slower)** | 88.5 | not on M1; x86 unmeasured |
| Hybrid H1: creation hash chain for X, A, K; trie for P and O | changes | 7.5..7.7 us | 2.9x | 47.9 | |
| **Hybrid H2: creation hash chain for X, A, K; trie for positions only (O derived)** | **changes** | **3.8..3.9 us (a second run 3.8..4.3)** | **5.8x (4.3x over the shipped trie)** | **25.1** | **recommended option** |
| H2m: as H2 with an MMR instead of the chain (inclusion proofs) | changes | 4.0..4.1 us | 5.5x | 28.1 | the proof-capable variant |
| H4c: as H2, one trie leaf per account (2 leaves per settle) | changes | 2.65..2.73 us at 8 instruments; **5.95..6.65 us at 64** (H2: 4.9..5.4) | 8.1x at 8 instruments | 11.3 | only for few assets per account |
| LtHash16 / AdHash 3072 / MuHash 3072 | changes | 2.3 / 1.6 / 17.8 us **per element**, 7.5 element operations per decision | 17 / 12 / 130 us per decision | n/a | slower than the trie it replaces |

Whole lane, real `CapacityProcessor` on the mock context (`CapacityLaneCostTest`, 150,000 trades, 2,000 accounts, 8 instruments, three interleaved base/new pairs): processing-thread CPU **39.0, 41.3, 38.9 us per decision before and 32.7, 31.9, 32.3 after** (-16 to -23 percent), 12.3k, 11.4k, 12.1k trades/s before and 14.7k, 15.1k, 14.8k after, allocation 33.4 to 33.7 KB per decision before and 22.6 to 23.0 KB after. The branch hash count is unchanged (83.8), as it must be.

## 2. What the commitment really has to provide

Read from the design (sections 2, 3.5, 5.5, 5.6, 9) and from who actually reads `stateDigest` in the code (`CapacityStore.restore` is the only runtime comparison; the projector and the read store never look at it; `DecisionChain`/`verifyChain` treat it as opaque text inside the checksum):

| Property | Who uses it, when | Needs |
| --- | --- | --- |
| **Binding of the whole state** (R2): any change to any entity, the set or the scalars changes `C` | Restore: the entity scan must hash to the **head** decision's `stateDigest` (`CapacityStore.restore`); a store-only attacker is caught | a collision-resistant canonical function of the stored data |
| **Rebuild from an entity scan** (R4) in O(n log n) hashing | every start, rebalance and wiped-state restart | order recoverable from what the store holds |
| **Audit of a past decision** (5.5, 5.6) | off-thread, every N decisions: rebuild `C(S_s)` from a snapshot and compare with published decision `s` | a digest per audited sequence, not necessarily every one |
| **Independent verifier** (R3, R6, 9.2, 9.12) | the Python encoder reproduces goldens and a 300-input decision-level replay byte for byte | a specification small enough to re-implement; every contract change regenerates the goldens |
| Chain coverage | `checksumOf` covers `stateDigest`, so the chain commits to the state at each step | any 64-hex value computed deterministically |
| Per-decision availability | nobody checks it online; it localizes the first divergence in an audit or replay | a lagged profile (5.6) trades this away on purpose |
| Membership proofs (4.3) | **non-goal** today ("reader-facing inclusion proofs") | only if a signed head ever exists |
| Cheap abort (R9) | processor all-or-nothing per Kafka record | O(staged) discard |

So the hard requirements are: bind the whole state, rebuild from a scan, audit at a cadence, be reproducible by an independent encoder. Per-decision tree structure and key-ordered membership are not used by anything today.

## 3. Prior art (and why each does or does not help here)

- **Homomorphic multiset hashes.** AdHash (Bellare and Micciancio, EUROCRYPT 1997) and its lattice form LtHash, deployed at Facebook with 2 KiB state and at least 200-bit security ([Lewi, Kim, Maykov, Weis, ePrint 2019/227](https://eprint.iacr.org/2019/227), [Facebook engineering](https://engineering.fb.com/2019/03/01/security/homomorphic-hashing/)). Wagner's generalized birthday attack ([Wagner, CRYPTO 2002](https://people.eecs.berkeley.edu/~daw/papers/genbday.html)) is why small AdHash moduli are unsafe, and why the design's 4.2 accumulator had zero margin at 3072 bits. MuHash multiplies in a 3072-bit group to avoid that attack; Bitcoin Core keeps a numerator and a denominator and inverts once ([MuHash3072](https://doxygen.bitcoincore.org/class_mu_hash3072.html), 2017 thread: 1.2 us to expand an element, 1.5 to 2.5 us per multiplication). **Measured here on the JDK:** section 5.5. Per element, expansion plus accumulate costs 1.6 to 17.8 us, and a decision touches about 7.5 elements (adds for new entities, a remove and an add for each updated position or obligation), so 12 to 130 us per decision against 15 us for the whole trie.
- **Append-only logs: Certificate Transparency, Trillian, Merkle mountain ranges.** An append-only Merkle tree ([RFC 9162](https://www.rfc-editor.org/rfc/rfc9162.html)) with a frontier (compact range, [transparency-dev notes](https://github.com/transparency-dev/merkle/blob/main/docs/compact_ranges.md)) appends in O(1) amortized hashes because new leaves always land at the right edge and share their ancestors; a Merkle mountain range is the same shape with peaks bagged ([Merkle Mountain Belt, arXiv 2511.13582](https://arxiv.org/html/2511.13582v1) compares them). This is the structural answer to "the cost is the path of each newly inserted leaf": stop inserting write-once leaves at hash-random positions of a 20-level tree.
- **Sparse and radix Merkle trees.** Jellyfish Merkle Tree (Diem) uses radix-16 nodes, replaces any subtree of 0 or 1 leaves by that leaf, and is designed for LSM storage ([Diem paper](https://developers.diem.com/docs/technical-papers/jellyfish-merkle-tree-paper)). Higher radix cuts depth and cache misses but not SHA blocks: counting 64-byte blocks per update path at 10^6 entities, binary is about 2 x 20 = 40, radix 4 about 3 x 10 = 30, radix 16 about 9 x 5 = 45 (less where the lower levels are sparse). At most a quarter fewer blocks, a different contract, and not measured here.
- **TigerBeetle and ledger databases.** TigerBeetle protects its log with hash-chained checksums and replays it deterministically ([architecture](https://docs.tigerbeetle.com/about/architecture)); I did not find a Merkle tree over balances in the sources I could read. Amazon QLDB's journal digest is a Merkle root over a hash-chained journal (from memory, not verified here). The common pattern: **commit the log, not a keyed tree over every record**; commit mutable balances separately and small.
- **Verkle trees, ECMH.** Need elliptic-curve point operations; the JDK has no fast native path for them and a BigInteger implementation would cost far more than the SHA-256 trie. Not prototyped.
- **Hardware.** The JDK intrinsifies SHA-256 on x86 with AVX2 and BMI2 ([JDK-8154495](https://bugs.openjdk.org/browse/JDK-8154495)) and SHA-512 likewise ([JDK-8165381](https://bugs.openjdk.org/browse/JDK-8165381)), so the Skylake droplet is not running interpreted hashes; it is 462 ns per 67-byte branch because it has no SHA instructions.

## 4. Where the commitment time goes (and why a layout change was worth doing)

At about 10^6 entities, per decision on the M1, current code (diagnostic engines D0 to D2 in the harness):

| Part | us |
| --- | --- |
| entity key and leaf hashes (4.8 entity writes) | 1.3 |
| trie `put` descents (random memory, about 10 cold levels per path), no hashing | 5.1 |
| 88.5 SHA-256 of 67 bytes from a hot buffer | 5.5 |
| `root()`: second walk, sibling digests, copies | about 3.4 |
| **total (E3)** | **15.3** |

So SHA-256 arithmetic is about a third; memory latency is about half. That is why a faster hash library (previous note) and a lagged root gained little, and why a cache-friendly layout does. On the x86 droplet the proportions differ: one 67-byte hash is about 460 ns instead of 70 ns, so the same 88.5 hashes are about 41 us of the 140 us decision there, and hash count matters more than layout.

## 5. Options

### 5.1 Contract-neutral, shipped: `FlatStateTrie` and per-thread digest (same bytes out)

- Branch metadata in flat int chunks; both child digests of a branch in one 64-byte record that is exactly the tail of its hash input, so a node's digest lives in its parent's slot and a hash reads one record; leaf keys in a key chunk; no allocation per hash (the digest is written into its slot); chunks of 65,536 nodes so growth never copies a large array. `StateCommitment` reuses one `MessageDigest` per thread and encodes the key prefix once per tag (looking up a digest per call was about 5 percent of a lane).
- The `put` descent also reads each visited branch's digest record, so those cache misses overlap with the pointer chase instead of following it. A/B on the commitment alone (two builds interleaved, two runs each): 18.1 us (17.7 to 18.4) without the touch, 16.2 us (15.5 to 16.9) with it.
- Proof of neutrality: `FlatStateTrieTest` compares it with the linked `StateTrie` and with `StateCommitment.trieRoot` after random operations on 10,000 schedules (uniform, prefix-crowded and caterpillar keys, replacements, roots taken at random points), checks the same branch-hash count, runs the committed goldens in three insertion orders, crosses chunk boundaries at 200,000 leaves and checks the 255-level caterpillar cost. All `*finance*` tests pass unchanged (the capacity ledger, store, processor and end-to-end tests exercise it through `CapacityLedger`). `StateTrie` stays as the oracle.
- Memory: by layout 32 + 16 + 64 = 112 bytes per entity (linked: about 200). A heap probe in the harness was inconclusive about the split (the two measurements add to 277 bytes in either order), so treat 112 as computed.
- Gain: 1.35x on the commitment, -16 to -23 percent lane CPU per decision, 11 KB less allocation per decision (a third), which matters more under a real consumer thread's GC. On x86, where SHA dominates, expect less; **not measured**.

Contract-neutral options **not** built: a second thread computing the same-bytes root (the decision needs its digest before the record forwards, so the thread would wait at the end of every record; it also costs a core per lane); multi-buffer SHA-256 (the JDK does not expose it).

### 5.2 Contract change: hybrid, an append-only creation log plus a small trie (H2)

Observation: the five entity classes are not alike. Executions X, attempts A and actions K are **written once and never changed**; obligations O are a function of X and A (a settled obligation has exactly one SETTLED attempt and residuals 0; a pending one has none; checked here on 3,000 obligations including 223 refused attempts); only positions P are mutable, and they are few (accounts times assets, 18,000 in the harness, against 10^6 entities). Today all five go into one hash-keyed trie, and the cost is the path of each newly inserted leaf. The hybrid commits:

```
T = SHA-256( 0x04 || trieRoot(P entities, as today) || creationChain )
creationChain_s = SHA-256( 0x05 || creationChain_{s-1} || records of decision s )     one hash per decision
record = for each X, A, K the decision wrote, in write order: tag || len(key) || key || len(value) || value
C(S) = SHA-256( frame(LABEL') || int64(sequence) || int32(controls) || T )
```

- Cost: one hash of about 150 to 250 bytes per decision for all write-once entities, no key hash, no leaf hash, no tree walk, no cache misses; the trie holds only positions. Per decision: 25.1 hashes, **3.8 us** against 15.3 (E3) and 22.0 (E0); lane CPU would fall from about 32 us to about 20 us per decision (computed: 32.2 - 16.0 + 3.9), i.e. 1.6x over what is shipped here and about 2x over the original, still before anything else in the lane.
- It also removes entities that attackers could grind from the trie: execution and action identifiers no longer shape its depth (design 4.1's caterpillar argument); only operator-admitted accounts do.
- Restore: needs each X, A and K to carry its creation sequence (add one field to their stored values; K is one per decision so it is also the decision index). Restore scans, groups by sequence, orders by tag within a sequence (X or A before K, the order the ledger writes them) and rebuilds the chain; O is rebuilt from X and A and the existing bijection and residual checks stay. **Verified in the harness** (`hybridObligationsAreDerivedAndRestoreFromAnUnorderedScanEqualsIncremental`): rebuilding from a shuffled scan of 12,160 immutable entities and 164 positions gives the same digest as the incremental engine after 6,160 decisions. Restore hashing falls from O(n log n) trie nodes to O(n) chain hashes plus the position trie.
- **What is lost.** (1) The digest is no longer a pure function of the entity *set*; it depends on creation order, which the store must therefore keep (the extra field). (2) No inclusion proof for an X, A or K against the digest (the trie gave one in principle; nothing uses it; H2m below restores it by position). (3) No tree walk to find the one divergent entity between two replicas (an audit would binary-search the chain by sequence instead). (4) The independent Python encoder and every golden change; the byte layout is simpler than the Patricia trie for the three classes that leave the trie. (5) `CapacityStore` entity value layout and `META_VERSION`/label change. What is **kept**: collision-resistant binding of the whole state, restore-equals-head verification, per-decision digests (so no exposure window like 5.6), audit at any sequence, abort (the chain is one 32-byte value to save and restore).
- **Variants.** *H2m* uses an append-only Merkle mountain range for the log instead of the chain (4.0 us, 28.1 hashes): O(log n) inclusion proofs by sequence and the ability to audit or restore a range of the log in parallel, for 0.2 us. *H4* puts one trie leaf per account holding all its positions (a settle touches 2 leaves, not 4): 2.7 us at 8 instruments, but 6.3 us at 64, because every update re-hashes a 1.2 KiB record; not recommended as the default. *H1* keeps O in the trie (7.6 us), which is why dropping it matters.

### 5.3 Contract change: lagged and batched root (design 5.6, already owner-directed)

Measured on the flat trie, with and without deferring puts to the boundary (last write wins) and applying them in key order: hashes per decision fall as 88.5, 67.6, 58.5, 44.4, 24.5, 13.0 for K = 1, 8, 32, 256, 4,096, 65,536, and CPU as 15.3, 13.9, 13.2, 12.6, 8.1, 8.9 us. The floor around 8 us is leaf hashing, the pending map and the puts. **The gain is real only at K in the thousands, which is the largest exposure window and the most machinery (second thread, undo ring, lagged restore, `2K - 1` decisions unattested).** It does not combine well with the hybrid either (the chain part is already O(1)). Against the hybrid it is half as fast and has the larger semantic cost. Keep it as a fallback, not the next step.

### 5.4 Contract change: SHA-512/256 for the nodes

A 67-byte branch is two SHA-256 blocks but fits one 128-byte SHA-512 block, and SHA-512/256 keeps 256-bit output and 128-bit collision resistance. On the M1 (hardware SHA-2 for both) it is **slower**: 21.8 to 23.1 us against 17.3 to 18.7 for the same code with SHA-256. On Skylake the JDK has AVX2 stubs for both ([JDK-8165381](https://bugs.openjdk.org/browse/JDK-8165381) reports 2x for SHA-512 over the plain code), and at about 460 ns per two SHA-256 blocks a one-block SHA-512 may win, but **I could not measure x86 here** (no cloud, and Rosetta has no AVX2), so it is a hypothesis for the owner's next droplet run, one microbenchmark of `MessageDigest` on the 8168, not a recommendation. Truncating children to 27 bytes so a branch fits one SHA-256 block (55-byte limit) also halves compressions but cuts collision resistance to about 108 bits; not recommended.

### 5.5 Homomorphic multiset hashes: measured, rejected on this JVM

Per element (expand to the group element, then accumulate), thread CPU, 100,000 elements after warm-up: LtHash16 geometry (1,024 lanes of 16 bits) with SHA-256 counter expansion 5.3 us, with ChaCha20 (the JDK intrinsic) 2.3 us; MuHash3072 geometry (ChaCha20 to 384 bytes and a BigInteger multiplication modulo 2^3072 - 1103717) 17.8 us; AdHash over 2^3072 as in design 4.2 (12 SHA-256 blocks and one addition) 1.6 us, with the zero security margin already recorded there. A leaf hash and key hash for the trie cost 0.3 us per entity. With 7.5 element operations per decision the totals are 17, 12 and 130 us, against a whole trie decision of 15 us today and 3.8 us for the hybrid. The constant-time update is real but the constant is large when an element is a 2 KiB expansion, and the per-decision finalization (hashing the 2 KiB state) is extra. Homomorphic hashing helps where updates are deletions of huge sets or where the commitment must be mergeable across machines, neither of which this lane needs.

## 6. Ranking and recommendation

| Rank | Option | Gain (measured, M1) | Risk | Verification property lost | Contract change |
| --- | --- | --- | --- | --- | --- |
| 1 | **Flat trie + per-thread digest** | -16..-23 percent lane CPU, -33 percent allocation | low (differential tests, same bytes, oracle kept) | none | **no** (shipped on this branch) |
| 2 | **Hybrid H2 (creation chain + position trie, O derived)** | commitment 15.3 to 3.8 us; lane about 1.6x more | medium: store value gains a creation sequence, new goldens and Python encoder, O-derivation must be a tested restore rule | set-canonicality (digest depends on creation order), per-key inclusion proofs, trie-walk divergence search | **yes, owner approval** |
| 3 | H2m (MMR instead of chain) | as H2 minus 0.2 us | as H2 plus a little more code | keeps inclusion proofs by sequence | **yes** |
| 4 | Lagged K in the thousands, batched, sorted | 15.3 to 8.1 us | high (second thread, ring, lagged restore, exposure window) | per-decision post-state attestation for `2K - 1` decisions | **yes** (already directed, design 5.6) |
| 5 | SHA-512/256 nodes | unknown on x86; -20 percent on M1 | low code, unmeasured gain | none beyond the algorithm change | **yes** |
| 6 | H4 per-account leaves | 2.7 us at 8 instruments, worse at 64 | model change (positions become records) | position-level proofs | **yes** |
| 7 | LtHash, AdHash, MuHash | slower here | cryptographic review | tree and proofs | **yes** |

**Recommendation.** Keep rank 1 (done). Ask the owner to approve **rank 2** as the next commitment contract, as a new label (`calcify-finance-state-v3-hybrid`) in the existing capacity profile, built the way S1/S2 were: reference function first, Python encoder and goldens, differential test of the incremental engine against it, restore test from a shuffled scan, mutants (a record field dropped, chain order swapped, O not derived, sequence off by one). It is the only option that removes the cost rather than shaves it, keeps per-decision digests and the synchronous restore story, and is simpler to re-implement independently than the Patricia trie for the classes it moves. Decide MMR versus chain by whether inclusion proofs are ever wanted (0.2 us). Do not build the lagged profile for throughput. Run the SHA-512/256 microbenchmark on the next droplet slot.

## 7. Open risks and what was not measured

- **No x86 measurement.** All timings are M1. On the 8168, SHA-256 is about 6x slower per hash, so the hash-count reduction (88.5 to 25 per decision, 203 to about 65 SHA blocks per decision by my count, estimated, not instrumented) matters more and the layout change matters less. A hash-bound estimate of the droplet gain from the hybrid is 140 us to about 108 us per decision (1.3x, assuming 231 ns per block and ignoring memory effects, which only help it); the shipped trie's gain on x86 is probably under the M1's 17 to 23 percent. Both need a droplet run under the spend rules.
- Engine bench excludes the store puts, the Kafka client and protobuf; the lane figures include everything but those and RocksDB. The hybrid's lane figure is computed from the engine delta, not run through `CapacityProcessor`. A prototype ledger wired to the hybrid would be the next measurement if approved.
- Thread CPU time on a shared laptop still moves with frequency and cache pressure (E3 spans 15.1 to 18.1 us across runs); every table above is interleaved and repeated, and the verdicts use ratios inside one run.
- The hybrid prototype in the harness is a measurement engine, not product code: it has no abort journal, no overlay and no restore code path beyond the equality test. The position trie is still grindable only through operator-admitted accounts; the log has no depth to grind.
- `FlatStateTrie` is one implementation of 4.1 more than the linked one: its invariant (a clean branch's digest sits in its parent's slot) is covered by the differential test with roots at random points, but a new engine for the hybrid must re-run that test shape.

## 8. Reproducing

```
cd services/platform-runtime
./gradlew test -PresearchTests --rerun --tests '*CommitmentOptionsBenchTest.commitmentOptions' -i      # CAP_PRE, CAP_MEAS, CAP_REPS, CAP_ACCOUNTS, CAP_INSTRUMENTS, CAP_ENGINES=E0,E3,H2,...
./gradlew test -PresearchTests --rerun --tests '*CommitmentOptionsBenchTest.homomorphicElementCost' -i
./gradlew test -PresearchTests --rerun --tests '*CommitmentOptionsBenchTest.hybridObligationsAreDerivedAndRestoreFromAnUnorderedScanEqualsIncremental' -i
CAP_TRADES=150000 ./gradlew test -PresearchTests --tests '*CapacityLaneCostTest*' -i      # now prints thread CPU and allocation per decision
./gradlew test --tests '*finance*'                                                          # includes FlatStateTrieTest
```

Engine names: E0 linked trie with the pre-change leaf code, E1 flat trie with it, E2 linked trie with the new leaf code, E3 shipped, L8/L32/L32b/L256b/L256s/L4096s/L65536s lagged variants, S256/S512 hash control and SHA-512/256 (object trie, so compare S512 with S256, not with E3), H1/H2/H2m/H4c/H4m hybrids, D0 to D2 diagnostics. Add `-Dorg.gradle.jvmargs=-Xmx4g` if the Kotlin compile runs out of memory on a busy machine.


## Review note (independent review, instance 1)
The flat trie allocates its first chunks eagerly: about 2 MB for keys at the first leaf and 1 MB of metadata plus 4 MB of digests at the first branch, so a lane costs roughly 7 MB before it holds any state (the 4 MB chunk is a humongous allocation under G1), and a replaced rotating ledger pays it again. At the lane counts in use this is negligible; at hundreds of lanes it is about 1.8 GB and the first chunk should be sized down. Each `ExecutionKey` also keeps one extra frame array (about 100 bytes) once it has been framed twice.
