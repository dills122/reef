# Calcify finance: scalable state commitment and O(touched) transitions (design)

Status: **DRAFT revision 3, not accepted.** Round 1 (three lenses) returned *Not ready* x3; revision 2 answered it. Round 2 (three fresh
lenses; the unit's first-three limit is now spent) returned A *Ready with follow-ups* (trie only), B *Not ready*, C *Not ready to build, fund
S0 only* (section 14). Between rounds, as the owner directed, a second research spike (**S0**, section 1.4) measured the costs that are not the
ledger. This revision answers every round-2 finding. **No further review instance starts until the owner decides section 11 and approves a new
limit**; the next evidence is S0 and its extension, not another reading of the prose.
Scope: the successor to the O3a ledger for a *capacity* profile. The finite P3 path (O3a, binding v2, its goldens and digests) is frozen and
is not changed by anything here. Owner: Dylan. Written 2026-10-12.

## 0. Summary, and the recommendation

- The O3a ledger is O(n) or O(history) per decision in **ten** places (section 1.2); the dominant one is not the digest. Corrected spike figures
  (entity counts are exact now; round 2 found the earlier ones 25% high): a whole-state digest costs **2.7 ms at 1,000 entities, 25 ms at 10,000
  and 344 ms at 100,000**; the part of `checkInvariants` not bounded by the pins costs **1.3 ms, 118 ms and 9.6 s** (it is quadratic).
- Revision 1's 17.7 us figure came from hashing a zero-filled array; it was a floor and must not be cited.
- **S0 (section 1.4) says the ledger is necessary but not sufficient.** On a real Redpanda with the production Streams settings, a processor that
  does nothing but ten 300-byte store puts per input ran at **about 11,100 inputs/s sustained over 90 s** (14,000 at 100,000 inputs) on one thread and one partition (about
  47,900/s with no puts; the first 20,000-record run showed 7,600 and 11,000, which were ramp-up limited, see the correction in 1.4), and the
  projector as written does **106 decisions/s** one call at a time and **740/s** at best when batched. Restore is cheap (a 1,000,000-record changelog
  rebuilds RocksDB in 1.8 s and reads into the heap in 1.9 s, holding about 337 MB). So a ledger that is O(touched) lifts the ledger from a few
  decisions/s at 10^5 entities to tens of thousands/s, and then the **projector** binds first, with the Streams lane next.
- **The repo's own throughput goalpost changes the question.** `CALCIFY_ARENA_THROUGHPUT_CONTRACT.md` recommends 10,000 booked trades/s, which is
  about 50,000 new entities/s (five per settled trade) and 15 million entities in a 5-minute session. No single lifetime budget survives that, and
  the projector as written is two orders of magnitude below it. **Rotation (one ledger per session or slice, sealed by a cut) is therefore the
  recommended answer to Decision 1, not retirement or a huge budget**, and the settlement lane's required service rate (about 9.1k/s for a 5-minute session, `CALCIFY_ARENA_THROUGHPUT_CONTRACT.md`) is a gate this lane has not yet shown it can meet.
- The commitment is a **path-compressed binary trie** over the hash of each entity key (sections 3 and 4.1), now specified byte-exactly. The
  multiset accumulator is demoted to a one-paragraph "not selected" note (4.2): its bound has zero margin at 3072 bits and its spec was incomplete.
- The engine is **new files only**, and the R5 gate is a pinned content hash over every finite-path file, with `-PbrokerTests` required (5.1).
- **Recommendation.** (1) Treat this document as a design record plus a gated option. (2) Run the S0 extension (section 10) *before* any further
  review. (3) Decide section 11 (Decision 0 first). (4) Nothing past the commitment specification is built until P-5 and Decision 0 are
  decided. Finite P3 needs none of this.

## 1. The cost model, measured

### 1.1 What was measured, and how

`StateCommitmentCostSpikeTest` (research, excluded from the default run, enabled with `-PresearchTests`) builds synthetic `FinanceState`
values of 10^3, 10^4 and 10^5 entities that bypass the pins in `FinanceConfig` and times the real internal functions: `stateDigest`,
`checkpoint`, `FinanceStore.entityWrites` and the map copies the `Draft` makes, plus a transcription of the unbounded loops of
`checkInvariants`. One JVM, warmed, single thread, in memory, on a development laptop. Numbers are indicative, not capacity claims.

| Entities | `stateDigest` | `checkpoint` encode | map copies | `entityWrites` diff | `checkInvariants` unbounded loops |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 2.7 ms | 2.7 ms | 39 us | 90 us | 1.3 ms |
| 10,000 | 25 ms | 25 ms | 229 us | 0.56 ms | 118 ms |
| 100,000 | 344 ms | 262 ms | 3.3 ms | 5.6 ms | 9.6 s |

Runs on a loaded development laptop vary by about 15% between reviewers' reruns (round 2 reproduced the shape and the order of magnitude every
time). `StateCommitmentCostSpikeTest.synthetic(n)` now builds exactly `n` entities (round 2 lens C found it built 1.25 n), and the trie spike no
longer hashes the leaf twice. The invariant row is a hand transcription of the unbounded loops (the real `checkInvariants` rejects the synthetic
state at the caps); its verdict equivalence with the real function is a stage requirement, not a spike result (section 9.6). Nothing here
measures heap or GC: the map copies at 10^5 entities allocate megabytes per decision, which GC will amplify.

Reading: the digest is linear but its constant is large (the canonical sort re-encodes both strings as strict UTF-8 on every comparison, then the whole
state is hashed); the invariant check is **quadratic** (`attempts.count` inside the loop over obligations); map copies and the persistence diff are
cheap *relative to those*, but at 10^5 entities copies plus diff alone are about 9 ms, i.e. about 110 decisions/s. At the finite P3 ceiling (about 620
entities) the digest is about 1.5 ms, which is harmless there.

### 1.2 Every O(n), O(history) or fixed-ceiling term on the path

Counted once, here: **ten** (revisions 1 and 2 said four, seven and nine in different places).

| # | Term | Where | Measured |
| --- | --- | --- | --- |
| 1 | Whole-state digest per decision | `FinanceLedger.stateDigest` | yes (1.1) |
| 2 | Map copies, including for **refused and no-op inputs** (the `Draft` is built before `evaluate`; a *prior-result* lookup returns before the `Draft` at FinanceLedger.kt L23-26 and is already O(1)) | `Draft`, `apply`, `actions +` | yes |
| 3 | Full invariant check per decision, containing an O(obligations x attempts) loop | `checkInvariants` | yes |
| 4 | Whole-map diff to find changed entities | `FinanceStore.entityWrites` | yes |
| 5 | Restore: decode and chain-verify the whole decision log from offset 0, re-read all history entries, three full encodes, a quadratic check, **and the evidence reader materializes the whole decision log and the whole changelog as `List<ByteArray>`/`MemoryLifecycleKv`; restore runs twice (launcher evidence check, then `FinanceProcessor.init`)** | `FinanceRestoreCut`, `FinanceEvidenceReader`, `FinanceStore.verifyAndRestore`, `restoreAt` | topic read and RocksDB rebuild **yes (S0: cheap at 2x10^5 records)**; heap per record yes; the ledger half (encodes, quadratic check) yes |
| 6 | History cap of 312 entries, enforced in the processor and the store, and the O(history) `verifyHistory`. **History grows with every consumed input, including refusals, duplicates and prior-result inputs, not only with recorded decisions** | `FinanceStore.MAX_HISTORY_ENTRIES`, `FinanceProcessor` position++ per item | n/a (a hard ceiling) |
| 7 | Read routes: the positions, obligations and attempts routes are unpaginated full reads; only `decisions()` is paginated | `FinanceReadStore` | no |
| 8 | Projector: 2 (capture), 3 (fund) or 7 (settle) un-batched SQL statements per decision; the checkpoint `FOR UPDATE` lock is **per `apply` call (batch)**, not per decision; **the projection DDL itself is pinned at the finite size** (`last_sequence <= 320`, 256 obligations) | `FinanceProjector`, migration 0014 | **yes (S0: 106/s unbatched, 740/s at batch 120)** |
| 9 | Streams and RocksDB: every `kv.put` is a changelog record (about ten puts per decision: seven entities, the history entry, `head` and `meta`), caching is disabled, EOS v2 commits every 100 ms | `FinanceTopology.storeBuilder`, `streamsProperties` | **yes (S0: about 14,000 inputs/s with 10 puts per input at 100,000 inputs; 7,600 at 20,000 was ramp-limited)** |
| 10 | One Streams thread **and one partition**: no long in-thread work (a full audit, a restore) and a hard parallelism ceiling no ledger work moves | topology | n/a, see S0 |

### 1.3 Consequences

1. **Revision 1's claim that the earlier research note was merely "incomplete" understated the problem.** Even a perfect incremental digest
   leaves terms 2 to 10. Revision 1 also *proposed to update an accumulator "by the entity diff `entityWrites` already computes"*, which is
   itself a whole-map comparison; that would not have raised the ceiling at all.
2. **`maxEntities` is a lifetime cap, not a capacity, and the entity budget is an upper bound on decisions.** Every recorded decision adds an
   action entity that is never removed. By input class (FinanceLedger.kt L39-43, L75-76, L101, L107-108): a capture adds 3 entities (execution,
   obligation, action), a settle attempt 2 (attempt, action), a fund 1 (action); positions are bounded by the account and asset counts. A
   capture-and-settle pair is therefore **5 entities**, and `maxEntities / 5` booked trades is the real lifetime (2^20 entities is about 210,000
   trades, not 2^20 decisions). Revision 2's "17 minutes at 1,000 decisions/s" was a ceiling, not a figure.
3. **The repo's throughput goalpost is 10,000 booked trades/s** (`CALCIFY_ARENA_THROUGHPUT_CONTRACT.md` L14, L82, L171-174; sprint plan L32, L48,
   "not adopted"). At that rate: 50,000 entities/s; a 2^20 budget lasts 21 seconds; a 5-minute session is 3 million trades and about 15
   million entities (4.5 to 9 GB of heap at 300 to 600 bytes per entity, before about 2n trie nodes); a 15-minute session about 45 million.
   Retirement or rotation is therefore a first-class requirement of any capacity profile, not "a much larger design".
4. **S0 shows the ledger is not the only bottleneck** (1.4): the projector and the single Streams partition cap throughput well below the
   goalpost even if `decide` were free. Whether the ledger is worth rebuilding depends on the end-to-end target, not on the ledger.

### 1.4 S0: the costs that are not the ledger (measured)

`S0EndToEndCostSpikeTest` and `S0ProjectorCostSpikeTest` (research and broker tagged: `-PbrokerTests -PresearchTests`; they start and remove their
own Redpanda v26.2.3 and Postgres 16 containers with `docker rm -f -v`). Docker Desktop on a development laptop, local sockets, one run each;
indicative, with the caveats below.

| Test | Setup | Result |
| --- | --- | --- |
| A. Streams ceiling | production `FinanceTopology.streamsProperties` (EOS v2, 1 thread, `commit.interval.ms` 100), persistent store, caching off, default changelog, one partition; a processor doing P puts of 300 bytes per input and forwarding one 100-byte output; inputs sent at once, clock started after Streams reports RUNNING (first run 20,000 inputs, rerun 100,000, see the correction below) | 20,000 inputs: P=0 11,054/s; P=1 10,701/s; P=10 7,643/s (superseded). **100,000 inputs: P=0 47,880/s; P=1 43,498/s; P=10 14,079 inputs/s (76,425 puts/s)** |
| B1. Evidence read | `FinanceBrokerFixture.readAll` (what `FinanceEvidenceReader` does) of the 200,000-record changelog | 200,000 records: 1.2 s, 26 to 56 MB held. **1,000,000 records: 1.9 s, about 337 MB held (about 340 bytes per record, linear)** |
| B2. Store restore | stop Streams, delete the state dir, restart: store restore phase (restore listener) | 345 ms for 200,000 changelog records; **1,764 ms for 1,000,000** (warm restart with an intact dir: 9 to 10 ms, 0 records) |
| B3. Restart to RUNNING | the same restarts, wall clock | **43 s in both cold and warm cases**: this is not restore. It equals neither store time, so it is a fixed rejoin delay (suspected previous-member session timeout); **explained afterwards: the default `close` does not leave the group, so the restart waits out the 45 s session timeout; `leaveGroup(true)` gives 0.4 to 0.5 s** (crash restart still open) |
| C. Projector | `FinanceProjector.apply` against Postgres 16 in Docker, 10 bindings x 120 capture-and-settle decisions; 1, 10 and 120 decisions per call | **106/s (9.5 ms each) at batch 1; 427/s at 10; 740/s (1.35 ms) at 120** (579/s at 120 after 12,000 decisions). A research variant with JDBC batches per statement class in one transaction: **3,688 to 4,313/s at 120** (2,651 to 3,413/s after 12,000 decisions), so batching gives about 5x and the projector is still about 2.7x below the arena contract's 9,091/s settlement rate |

Caveats, so these are not over-read: nothing in A to C includes the ledger; the store is small (at most 2x10^5 entries, nothing about a
multi-gigabyte RocksDB); inputs are produced in one burst (favourable to batching); one run each on a laptop with Docker's VM; C uses a local
socket and a fresh table (no index growth). A therefore bounds the *non-ledger Streams path from above*, and C is the figure most likely to
dominate. The numbers that matter: **the Streams lane with a free ledger sustained 11,111 inputs/s over 90 s at ten puts each (about 5.5k trades/s, since a trade is a capture
plus a settle), which is below the goalpost's about 19k inputs/s; the projector as written is two orders of magnitude
below the goalpost.**

**Correction, 2026-10-12 (later the same day).** The first S0 run used 20,000 inputs (about a 2.6 s window) and I reported its 7,600 inputs/s as a
"ceiling". A rerun at 5,000 inputs gave 2,500 to 2,900/s and one at 100,000 gave 14,000 to 47,900/s: the measured rate rises steeply with run length
because ramp-up (consumer join, first commit intervals, JIT) dominates short runs. The 20,000-record figures were therefore **under-estimates of
steady-state**, and the earlier claim that a single Streams partition cannot carry the goalpost is **withdrawn**. A sustained run then measured **1,000,000 inputs in 90.0 s = 11,111 inputs/s** at ten puts each, so the 100,000-input figure also overstated steady state, by about
27%. **Unit caution:** one booked and settled trade is two finance inputs (a capture and a settle), so 11.1k inputs/s is about 5.5k trades/s, against the arena
contract's 10,000 booked and about 9,091 settled trades/s (about 19k inputs/s together). The Streams stand-in (no ledger, one partition, one thread, untuned) is
therefore about **1.7x short** of that, the reading that it "is not shown to be the bottleneck" is withdrawn, and an O(touched) ledger does not close this gap on its own. The projector figures were not
sensitive to size (103 to 106/s at batch 1 at both sizes). Original figures are kept above, marked superseded, per the throughput ledger's append-only rule.

**Ceiling after each fix, at 10^5 entities** (from the corrected 1.1 and S0; one thread; decisions/s, order of magnitude):

| Cumulative fix | Ledger-side cost per decision | Ceiling | Next binding term |
| --- | --- | --- | --- |
| today | 344 ms digest + 9.6 s invariants | about 0.1 | invariants |
| + linear reference invariant (still per decision) | 344 ms | about 3 | digest |
| + incremental commitment, today's map copies and diff | about 9 ms (copies 3.3 + diff 5.6) | about 110 | copies and diff |
| + overlay/`Delta`, incremental invariants (full O(touched) engine) | tens of microseconds | tens of thousands | **projector, 740/s at best; then the Streams lane (11,100 inputs/s sustained, about 5.5k trades/s, about 1.7x short of the goalpost's 19k inputs/s)** |

The middle rows matter for a *rotating* ledger (Decision 1): a ledger that holds at most a few 10^4 entities needs only the middle rows, and
the full engine may not be justified.

## 2. Requirements

- **R1 Cost, worst case.** Per decision, time and allocation are O(k * L) where `k` is the number of entities written (at most **7** for
  every current input (a settle that is refused for insufficient funds writes fewer): a settle writes four positions, the execution's obligation, the attempt and the action; it also reads the
  execution) and `L` is the number of hashes on a commitment update path. `L` has a hard bound of 256 and an expected value near
  `log2(entities)`. The state of the art must not depend on the identifiers being well distributed.
- **R2 Binding.** The commitment binds the whole state: any change to any entity, the entity set, or a scalar changes it, except with the work
  of a SHA-256 collision (about 2^128) for an adversary who chooses both states.
- **R3 Canonical.** The commitment is a deterministic function of the abstract state alone and has an independent encoder that reproduces
  goldens byte for byte.
- **R4 Restorable.** Rebuild from an entity scan in O(n log n) hashing; verify against the head decision's `stateDigest`. The decision-log
  and history parts of restore are bounded separately (section 6.3); this document does not claim restore is O(n log n) overall.
- **R5 Compatible.** Finite P3 artifacts are byte-identical and untouched. Mechanically: a pinned content hash for every file on the finite
  path (section 5.1), checked at every stage, with the broker suites required.
- **R6 Verifiable.** The new engine is *specified by* simple references and proved equal by differential, metamorphic and mutation tests.
- **R7 Not authenticity.** Unkeyed integrity only. What it does and does not prove is stated in section 3.5.
- **R8 Cheap refusals.** Refused, no-op and prior-result inputs cost O(1) (no state copy), so junk input cannot amplify cost.
- **R9 Abortable.** An uncommitted stage of several decisions can be discarded at no O(n) cost, leaving the live commitment and counters equal
  to their pre-stage values (section 5.3).

Non-goals: a capacity claim; reservation or credit semantics; the persistence engine; the keyed chain; reader-facing inclusion proofs.

## 3. The commitment as a pure function

### 3.1 State, entities and encodings

Let a *state* be the O3a abstract state minus the chain head (the head is the decision's own checksum): positions, executions, obligations,
attempts, actions, and the scalars `sequence` and `controls`. The entity set `E(S)` has one record per map entry, in **five** classes
(revision 1 merged execution and obligation; they are separate here so that an orphan obligation is bound by the commitment, a settle writes
smaller entities, and the store's two key families map one-to-one):

| Tag | Key fields (each individually framed) | Value fields (each individually framed) |
| --- | --- | --- |
| `P` position | account, asset | amount (int64) |
| `X` execution | run, session, instrument, execution | buyer, seller, quantity (int64), priceNanos (int64) |
| `O` obligation | run, session, instrument, execution | status, cashResidual (int64), shareResidual (int64) |
| `A` attempt | run, session, instrument, execution, attempt (int32) | disposition |
| `K` action | namespace, domain, actionId | input digest (64 lowercase hex, as text), disposition |

Encoding is the O3a `Canon` framing and nothing else: **text is `int32(length)` plus strict UTF-8; `int64` and `int32` are big-endian
fixed width.** Every field is framed on its own (there is no "wrap the whole key in one frame"), each tag has a fixed field count, and
therefore the encoding is injective. A zero-amount position that exists in the map is an entity like any other (the O3a state keeps such
entries and the store writes them back; a different canonicalization would be a different state definition).
`keyBytes(e)` is the concatenation of the framed key fields; `valueBytes(e)` of the framed value fields.

Byte-exactness rules an independent encoder must follow (round 2, lens A and B):
- **Enums are encoded as their `.name` text** (`status` is `PENDING` or `SETTLED`; `disposition` is the `Disposition` name), never as an ordinal;
  this is what `FinanceLedger.stateBytes` does.
- The action's input digest is 64 lowercase hex characters, encoded as text. `ActionRecord.digest` is not validated as hex by the model today,
  so **restore and the reference check must validate it** (a value that is not 64 lowercase hex is a restore refusal).
- Identifiers are admissible only if `boundedText` accepts them (not blank, no ISO control characters, at most 128 UTF-8 bytes). A non-Kotlin
  encoder must enforce the same rule before producing goldens; it is an input-side rule, not part of the commitment.
- **No two entities may have the same `K(e)`.** Restore and the reference refuse duplicate keys as a collision-grade error; they are never
  silently overwritten.

### 3.2 The commitment

```
LABEL = "calcify-finance-state-v2-patricia"                       (instantiation label; the accumulator alternative has its own)
K(e)  = SHA-256( frame("calcify-finance-key-v2") || frame(tag) || keyBytes(e) )        256-bit trie key, bit 0 = most significant
L(e)  = SHA-256( 0x00 || K(e) || SHA-256( 0x01 || valueBytes(e) ) )                      leaf digest
T     = digest of the trie over { (K(e), L(e)) }, defined in section 4.1 (a single-leaf trie has T = L(e), with no branch)
C(S)  = SHA-256( frame(LABEL) || int64(sequence) || int32(controls) || T )             T is the 32 raw bytes (the empty set has T = SHA-256(0x03))
```

`C(S)` is the decision's `stateDigest` (64 lowercase hex, unchanged field and type), so **no protobuf change** is needed. All multi-byte
integers in this document are **big-endian**; bit 0 is the most significant bit of `K(e)`. Because the trie
is keyed by a hash of the entity key, **nothing is sorted**: the structure is a pure function of the key set, which removes the strict-UTF-8
re-encoding comparator (a large part of the measured digest cost) from the hot path.

### 3.3 Instantiation label and parameters

The label names the instantiation and is **bound into the config-v2 digest** (so into genesis-v2, section 7): a chain built under one
instantiation can never verify as another's, even before restore. The trie needs no depth parameter. (Revision 1's `D` is gone.)

### 3.4 Domain separation

Leaf `0x00`, branch `0x02`, empty `0x03`. These first bytes alone do **not** separate the layers (the key-hash input and the outer input also begin
with a `0x00` byte because `int32` lengths are big-endian). Separation rests on **distinct total input lengths and structure**: key-hash input
is variable but starts with the frame of a fixed label; leaf input is 65 bytes (`00 || 32 || 32`); branch input is 67 bytes
(`02 || u16 || 32 || 32`); empty is 1 byte; the outer input is `4 + len(LABEL) + 8 + 4 + 32` bytes. Per layer the encoding is injective
(fixed-width fields). The inner value hash `SHA-256(valueBytes)` carries no tag byte; it is a hardening opportunity (use the unused `0x01`),
adopted here: `L(e) = SHA-256( 0x00 || K(e) || SHA-256( 0x01 || valueBytes(e) ) )`.

### 3.5 What `C(S)` does and does not prove

It proves that a restored or presented state **is the state the head decision committed to** (R2 and the O3a `restoreAt` property). It
does **not** prove that the head was reached by valid transitions: `verifyChain` verifies links and checksums, not transitions, and the chain
is unkeyed, so whoever can write FINANCE_DECISIONS can forge a self-consistent chain with any state and recompute `C(S)`. Transition validity
needs replay of the inputs (O(history)) or a signed or externally anchored head, which is out of scope and not precluded. The field keeps the
name `stateDigest`; documentation and projector comments must call it an *unkeyed integrity digest*, and no reader-facing proof may be presented
as authentic until the head is signed or anchored. Two further limits, stated so that nobody infers more: `C(S)` is a **public deterministic function of public
data** (the genesis chain derives from the config digest), so anyone can compute a valid commitment for a fabricated state; and a **prefix of a
genuine chain also verifies**, so rollback, truncation and fork are not detected even by a signed head, only by an external freshness anchor.
Read-model labels and docs should therefore say `integrityDigest` or "unkeyed integrity digest" even though the proto field keeps its name.

## 4. Instantiation

### 4.1 Recommended: path-compressed binary trie over K(e)

- **Shape.** A binary trie where each entity is a leaf at the shortest unique prefix of `K(e)`; a branch stores the bit index at which its
  two subtrees first differ, as an **absolute position from the most significant bit, 0 to 255**. The **left child is the subtree whose bit at the
  branch index is 0**, the right child the one where it is 1. The shape is a pure function of the key set (R3). Deleting an entity (not needed
  under a lifetime or rotating budget) would require the canonical collapse rule "a branch left with one child is replaced by that child", or the
  shape stops being a function of the key set.
- **Digests.** `leaf = L(e)` as above. `branch = SHA-256( 0x02 || u16_be(bitIndex) || left || right )`. The leaf digest already commits the full
  256-bit `K(e)`, so the bit index is **redundant for the uniqueness of `T`**; it is kept because an inclusion-proof verifier otherwise cannot
  check where a path splits (the future use in 4.3), and a proof verifier must also check the keys are consistent with the claimed indices (the hash
  does not enforce that). `T` is the root's digest, or `SHA-256(0x03)` if the set is empty.
- **Security.** Two different entity sets with the same `T` require a SHA-256 collision, with no assumption on the number of entities.
- **Cost, worst case.** An update recomputes one path: at most 256 branch hashes. An attacker who grinds identifiers whose key hashes share
  `b` bits must do about `2^b` hashes to gain one extra level per branch point, so reaching depth `L` costs about `2^L`; the practical
  worst case is the low tens of levels, hard-bounded by 256 (about 0.9 ms for a 7-entity decision). Revision 1's flat buckets had no such bound
  (measured: 165 us per touch with 2,000 colliding identifiers, linear in the bucket).
- **Measured** (7 entities written, exact structures, mean over random touches, corrected spike): 27 us at 1,000 entities, 45 us at 10,000, 44 us at
  100,000, with mean depth 11.3, 14.6 and 18.0. The adversarial run (50 identifiers sharing at least 18 leading hash bits, 14.6 million grinding
  trials) is a **clustering** test, not the worst case: it shows depth stays near `log2(50)` plus compression. It does *not* bound the worst case. The strongest
  attack is a **caterpillar** (one key diverging at each bit 1..L along one path); one stream of about `2^h` trials against a fixed target yields all
  levels up to `h`, so depth `L` costs about `2^L` total. That is an argument, not a measurement; S2 adds a caterpillar test with an injectable key
  hash at `L` about 20 that asserts per-touch cost scales as `L` (section 9.8). Forcing depth 45 to 50 costs about `2^45` to `2^50` hashes, within reach of a
  motivated GPU adversary, for an amplification of about 3x over the typical 18 hashes.
- **Memory.** About 2n nodes of an object plus 32 bytes each, on top of the entity table. To be measured at the S3 exit; the store-view
  alternative (section 5.4) is the mitigation.

### 4.2 Alternative, not selected: multiset accumulator (AdHash style)

Kept only as a record. `T = sum Expand(e) mod 2^3072`, `Expand(e)` twelve counter-mode SHA-256 blocks. Constant cost in `n` (33 us for 7 entities at
10^3 to 10^5, corrected spike), no adversarial path, 384 bytes of state, and a trivially cheap overlay or journal. It is **not selected** because:
(1) its security is conditional: the generalized-birthday bound `2^(n/(1+lg k))` with `k <= 2 * maxEntities` gives exactly 128 bits at
`n = 3072` and `maxEntities = 2^22`, i.e. **zero margin**, and it relies on a random-oracle heuristic; the repair is `n = 4096` or a cap of `2^20`
enforced at restore and in every verifier from the config-bound value; (2) its specification was not byte-exact (integer interpretation of the
384-byte output, outer hash layout, count width, subtraction reduction, and two different labels were all missing); (3) it cannot produce inclusion proofs.
If the owner prefers it (lens C leaned that way for the cheaper overlay), 4.2 must be completed to the same byte-exactness as 3 and 4.1 before S1.

### 4.3 Why the trie is selected (closes Q1)

The trie's proof rests on SHA-256 collision resistance alone, needs no random-oracle step and no cap for security, and can produce inclusion
proofs against a future signed head. Costs are equal at the measured sizes (27 to 45 us versus 31 to 33 us). The price is code, about 2n nodes of
memory, and a bounded adversarial depth (4.1). Under a rotating ledger (Decision 1) memory is bounded by the rotation size, which removes most of the
accumulator's remaining advantage.

### 4.4 Rejected

- **Flat 2^D buckets (revision 1).** Cost O(m) per touch in the bucket size; the adversarial measurement above; a public salt does not help
  (the attacker computes buckets after seeing it) and a hard bucket cap lets an attacker deny specific identifiers.
- **Whole-state digest every K decisions.** Weakens "every decision commits its state", does not touch terms 2 to 9.
- **History-only commitment.** Commits inputs, not state; the O3a review (P1-1) requires state commitment.
- **Merkle-Patricia with variable node encodings.** More general than needed.

## 5. The O(touched) transition engine

### 5.1 It is a new engine in new files; the R5 gate is a pinned hash over the whole finite path

Revision 2 froze four files and also said the processor, projector, store and restore cut would "stop depending on the concrete `FinanceLedger`".
Round 2 (lens B1, lens C F5) showed those cannot both hold: `FinanceStore.genesisEntryChain/verifyHistory/verifyAndRestore` take the concrete class,
`Meta`, `META_VERSION` and `entityWrites` live in the frozen store, and `FinanceProcessor`, `FinanceRestoreCut`, `FinanceProjector`,
`FiniteFinanceLauncher`, `FiniteBindingContract` and the Go and Python binding references are the finite P3 runtime yet were outside every gate.
Decision (a): **the capacity profile gets its own files** for engine, store, processor, restore cut, projector and launcher (about 500 lines of
accepted duplication), selected by the registered profile and never by `financeStateVersion`. No file of the finite path is edited for it.
Where a genuinely shared extraction is wanted (for example moving `Canon`), it is a separate, allow-listed, behaviour-neutral PR whose gate is
the full existing finite P3 suite including the broker suites, run unchanged.
The **R5 gate** is a checked-in manifest of pinned SHA-256 content hashes of every guarded file (`FinanceLedger.kt`, `FinanceModel.kt`,
`FinanceStore.kt`, `FinanceWire.kt`, `FinanceProcessor.kt`, `FinanceRestoreCut.kt`, `FinanceEvidenceReader.kt`, `FinanceProjector.kt`,
`FinanceProjectionRunner.kt`, `FiniteFinanceLauncher.kt`, `FiniteBindingContract.kt`, the Go and Python binding v2 references, the O3a goldens and
the mutation script), verified by a test, plus the existing suites run **with `-PbrokerTests`** (the default run excludes `FinanceBrokerTest` and
`FiniteP3EndToEndTest`). A hash survives unrelated churn on master, which `git diff master` does not. The mutation harness patches
`FinanceLedger.kt` by exact source strings and keeps working because that file never changes.

### 5.2 The decision sequence

Revision 1's pure `decide` returning a `Decision` was circular: the `Decision` carries `C(S')` and the checksum covers it, and `C(S')` needs the
post-delta trie. Round 2 (lens B2) also found revision 2's sequence omitted the budget step and conflated three outcome kinds. The sequence is the
exact O3a order (FinanceLedger.kt L21-50):

```
lookup(view, input)        prior result or conflicting reuse of the action key -> returns before any state is built (O(1))
evaluate(view, input)      -> outcome; reads only named entities
classify                   unrecorded (no state change: UNAUTHORIZED, ALREADY_SETTLED, ...)           -> return, O(1)
                           recorded with a Delta, no legs (INSUFFICIENT_CASH / INSUFFICIENT_SHARES)
                           recorded BALANCE_OVERFLOW attempt
                           recorded with legs (CAPTURED, FUNDED, SETTLED)
budget(pre-state counters) the lifetime/per-class budget check; it OVERRIDES the evaluated disposition with BUDGET_EXCEEDED even for the recorded
                           INSUFFICIENT_*/BALANCE_OVERFLOW dispositions (O3a L32-37); it reads pre-state counts, before the delta
localInvariants(delta)     incremental checks on touched entities and counters; a violation THROWS (as O3a L48 does) before preview or commit
root' = trie.preview(delta)  path copy / overlay; nothing visible
Decision(sequence', action, inputDigest, disposition, legs, priorChain, C(S'), checksum)   pure function of the above
commit(delta, root', aggregates')   the only mutation; atomic
```

Config-v2 must be a **strict superset** of the O3a per-class budgets (`maxCaptures`, `maxObligations`, `maxSettleAttempts`, `maxControls`) so the
pinned-profile differential can run; its field list is part of the profile spec (section 9.11), not defined here.

### 5.3 Staging and abort are semantic requirements

The processor stages several decisions per Kafka record (a capture envelope maps to several members; later members read earlier members' staged
state) and moves its cached state only after the store write succeeds. Therefore the engine must provide a **record-granularity overlay or
undo journal**: reads see staged writes, `commit` of the record applies them together, and discarding the overlay is O(staged) and leaves the
trie root, counters and entity tables exactly as before. This is required for the processor's all-or-nothing property to survive, and is
tested by failure injection at every point of a multi-decision record (section 9).

### 5.4 Storage

Whether `StateView` is in-memory or a read-through over the persistent store is the real architectural fork. S3a to S3c are built and measured on the in-memory view, and the store-backed view is **decided once, at the S3c exit, on that
measurement** (not before, and not left to S4): in-memory is simpler and faster but costs 300 to 600 bytes of JVM heap per entity (boxed keys, three strings per action key, the cached
`valueBytes`, leaf digests, trie nodes), to be measured; store-backed bounds memory but makes `decide` do point reads through the staged overlay.
Both implement the same `StateView`, and the engine is tested against a trivial map-backed view and the indexed view to separate storage bugs
from logic bugs.

### 5.5 Incremental invariants

Every O3a invariant is either *local* (checked on touched entities), an *aggregate counter* (maintained per recorded decision), or a
*transition rule* (enforced where the transition is made). The full check stays as the **reference check**, rewritten as a linear single pass
(group attempts by execution once), and proven to give the same verdict as `checkInvariants` on the shared profile. It runs at restore, in
differential tests, and as an audit; it is not on the per-decision path. Complete table, against `FinanceLedger.checkInvariants` and packet C4:

| Source | Rule | Form |
| --- | --- | --- |
| L127-132 | no negative non-opening position; opening never positive; position only in the two assets; known accounts | local on touched positions |
| L133-136 | per-asset positions sum to zero | aggregate: running sum per asset, **128-bit or BigInteger width** (the opening position can be `Long.MIN_VALUE`) |
| L137 | every obligation has an execution | local: created together on capture |
| (`stateBytes` L234) | every execution has an obligation (O3a enforces this only by throwing inside `stateBytes`, so it fires at every digest and at `restoreAt`; the five-class split removes that throw) | **explicit**: counter `|X| = |O|` and, at restore and audit, a per-key bijection between executions and obligations |
| L138-141 | pending residuals equal execution economics (pending, L141) | local on capture and settle |
| L142 | settled obligation has zero residuals | local on settle |
| L145 | `sequence == actions.size` | counter |
| L146-147 | executions, obligations within caps | counters |
| L148 | `controls <= maxControls` and `controls == count(FUNDED actions)` | counters |
| L149 | record budget `sequence <= caps sum` | counter (replaced by the lifetime budget in the capacity profile) |
| L150-153 | only recorded dispositions in actions; captures == executions; settle-class actions == attempts | local plus per-class counters |
| L154-157 | attempt well-formed: number >= 1, execution exists, disposition in the settle class | local on settle |
| L158-161 | a settled obligation has exactly one SETTLED attempt, **a pending one has none** | local on settle |
| packet C4 (amended) | residuals never increase; settled is terminal; an execution key is captured at most once; a key never rebinds to different content | transition rules in `evaluate` (already how O3a decides), each with a named test |

**This section amends packet C4** ("invariants checked on every decision") **for the capacity profile only**, as the next paragraph states; the finite profile keeps C4 as is.

**Coverage weakens between audits, deliberately.** O3a re-checks the whole state after every decision as defence in depth against bugs in its own
`apply`. The aggregates here are updated by the same code they check, and a running balance sum cannot detect corruption of the position table.
The detection that remains is the restore-time and audit-time full reference check. The audit runs **deterministically every N decisions and
always at restore and clean shutdown**, over an immutable snapshot **taken at a known sequence `s` and processed off the processing thread** so a long
audit cannot exceed `max.poll.interval.ms`. It (a) runs the linear reference check and the aggregate recount, **and (b) rebuilds `C(S_s)` from the
entity tables and compares it with the published decision `s`'s `stateDigest`** (otherwise a stale-sibling or dropped-node bug in the incremental trie
would publish wrong digests until the next restore). It runs after commit, so it can only detect, with exposure bounded by N decisions, and a halt stops
further decisions only (Q5 is closed this way). The cheap local invariants and counters run synchronously before `context.forward`; a thrown exception
aborts the store write and the forwards together under EOS and the processor is rebuilt through restore, so no extra abort mechanism is needed.

## 6. Processor, store, restore, projection

### 6.1 Store

A v2 store has a new `META_VERSION` and parser (aggregate counters and `sequence/controls` live in `meta`); a v2 binary opening a v1 store fails with a distinct version refusal. **The reverse cannot be delivered**: the frozen v1 `Meta.parse` fails with "unknown entity version" and `FinanceRestoreCut` wraps it as the generic `FINANCE_STORE_INVALID`; v1 code cannot change. The trie is not persisted; it is rebuilt on restore.
`entityWrites` is replaced by the `Delta` the engine already produced. The store must not drop zero-amount positions (the v2 store test copies the existing FinanceStore behaviour test that a position reaching 0 persists).

### 6.2 Restore of state

Scan entities, rebuild the trie and aggregates, require `C(S)` equals the head decision's `stateDigest` and the recounted aggregates equal
`meta`. The extra `checkpoint` checksum (a second full encode in `restoreAt`) is **dropped**: the head commitment already covers it; its
mutants are replaced by commitment-mismatch mutants.

### 6.3 What bounds a capacity profile outside the state (previously hidden)

- **History entries.** `MAX_HISTORY_ENTRIES = 312`, the 296 record bound and the O(history) `verifyHistory` are properties of the finite P3 run.
  **History grows with every consumed input, including refusals, duplicates and prior-result inputs** (the processor appends one entry per item), so
  a history "sized to the lifetime decision budget" is unsound: junk input could exhaust it and halt the processor (a liveness failure). A capacity
  profile needs either a *separate* consumed-input budget with a stated halt policy, or a checkpointed history with an anchor (restore also scans the
  whole `h/` family). Restore cost is stated as **O(n log n + H)** where H is history plus decision-log length.
- **Decision-log read.** `FinanceRestoreCut` decodes and chain-verifies the entire FINANCE_DECISIONS log from offset 0 and needs infinite
  retention. A capacity profile needs an owner-set restore-time objective (RTO) and a checkpointed chain anchor, or restore is O(N) forever.
- **Read routes.** Only `decisions()` is paginated; the positions, obligations and attempts routes are unpaginated full reads, O(n) per request. A capacity profile paginates or bounds them.
- **Projector.** 2 to 7 un-batched statements per decision; the checkpoint lock is per batch. S0 measured 106/s at one decision per call and 740/s at
  120 per call; the projection tables themselves are pinned at the finite size (`last_sequence <= 320`, 256 obligations), so a capacity profile needs
  its own tables. End-to-end throughput is capped here unless it is batched and re-schemed, or the target is defined for the ledger path only (11.2).
- **Streams.** One thread and one partition with a changelog record per put measured 11,111 inputs/s sustained over 90 s at ten puts each with a free ledger, about 5.5k trades/s (14,000 at 100,000 inputs; the 20,000-input figure of 7,600 was ramp-limited and is superseded).

The projector's *behaviour* (hash link, input digest, continuity) and the read routes' contracts are unchanged, but the projector holds `FinanceLedger`
(`genesisChain`, `verifyLink`), so a v2 profile gets its own projector class (5.1), not a re-typed one.

## 7. Versioning and compatibility

Domain strings `calcify-finance-state-v2-patricia`, `calcify-finance-config-v2` (profile: caps, lifetime or rotation budget, **the commitment
label and engine id**), `calcify-finance-genesis-v2` (derived from the config-v2 digest, so it binds the instantiation). The decision domain
`calcify-finance-decision-v1` is kept. v1 and v2 chains never cross-verify **because the genesis chain is derived from the config digest and
`verifyChain` fails on the first `priorChain`** (`verifyLink` alone takes the prior chain as a parameter and is not genesis-bound, so every consumer must
start from genesis). Binding the commitment label into config-v2 extends that separation to two v2 instantiations (round 2, lens B4: without it a
patricia chain and an accumulator chain under the same config would pass `verifyChain`, which treats `stateDigest` as opaque text). A test pins v1/v2 and
patricia/accumulator non-cross-verification. The wire and proto are unchanged. The capacity profile is registered as its own binding version; this
document does not define that binding, nor how the config-v2 and binding classes avoid editing `FiniteBindingContract.kt` (new files, 5.1).

## 8. Failure and operations

- A commitment mismatch at restore is a refusal, never a repair. An aggregate-versus-recount or commitment-recompute mismatch at audit halts the processor
  between records (the audit runs after commit; Q5 is closed in 5.5).
- **Halt runbook (required before S4):** stop the app; export the decision log, history and entities read-only with the evidence reader;
  verify with the reference implementation to locate the divergent entity; archive; open a new run with a new binding and a corrected engine.
  Recovery is never in place. A halt stops further decisions only; decisions already forwarded (at most N, the audit cadence) were computed before the audit detected the fault.
- **Memory:** an estimate of 300 to 600 bytes per entity in-memory plus trie nodes is **to be measured at the S3 exit**; the store-view
  alternative bounds it.
- **Single thread:** no in-thread work longer than the poll interval; audits and restores are off-thread or between polls.
- **Restart latency.** S0 measured 43 s from process start to RUNNING for both a cold and a warm restart, of which store restore is under 0.4 s. The cause is
  the default `close` not leaving the consumer group (the next member waits out the 45 s session timeout); with `leaveGroup(true)` it is 0.4 to 0.5 s. A crash still
  waits out the session timeout unless it is lowered or static membership is used; that is a restore-time-objective input, not a ledger cost.

## 9. Verification plan

1. **Reference.** `StateCommitment.reference(state)`: a direct transcription of section 3 and 4.1 with a naive trie, no incrementality.
2. **Independent encoder and goldens.** A Python implementation with goldens for small states (empty; one entity of each of the five classes;
   zero and nonzero positions; UTF-8 and boundary-length identifiers) **and a large generated golden of about 10^3 entities**. Kotlin reproduces them.
3. **Crowding tests.** The differential runs with an injectable key-hash that forces long shared prefixes (so deep and unbalanced tries occur at
   test sizes), plus the real hash at production size.
4. **Differential, commitment.** The incremental trie equals the reference after every step of at least 10,000 randomized schedules, including
   permuted application orders reaching the same state, and at random restore cuts (restore equals continuing).
5. **Differential, engine, two oracles.** On the pinned profile the new engine produces the same dispositions, legs, sequences, entity tables
   and aggregates as (a) the frozen O3a ledger and (b) the independent naive oracle `FinanceOracleDifferentialTest` (string keys, BigInteger,
   plan-then-apply), **generalized to parameterized caps and many accounts and extended to compare zero-amount positions** (it currently drops
   them). The oracle already takes caps as constructor arguments and is a plain-map simulator, so it can drive 10^5-step schedules at lifted caps;
   what it lacks is a rule for the lifetime budget and many accounts. The oracle comparison must name **executions and action records** (its
   `assertSame` compares positions, obligations, attempts, sequence and controls, and only `actions.size`). Beyond the transition semantics, the
   profile's new rules (config-v2 fields, budget precedence, the lifetime or rotation rule, entity caps) are specified in a short **profile spec that
   gets its own review before S3**. The metamorphic suite is replay-from-genesis equals live state and restore-at-random-cut equals continue
   (conservation is the very aggregate being checked, so it is weak).
6. **Invariant equivalence.** After each step the counters equal a full recount, the linear reference check passes, and its verdict equals
   `checkInvariants` on the shared profile. Because both always pass on honest states, this needs a **negative corpus**: single-fault mutations of states
   reached by the schedules (drop an entity, change a residual, orphan an execution or obligation, duplicate a settled attempt, tamper a counter) that both
   checkers must reject; the comparison unit is `checkInvariants` plus "`stateDigest` does not throw" (the orphan execution case, 5.5).
7. **Abort and failure injection.** An exception at every point of a multi-decision record (including the store write) leaves the live root,
   counters and tables equal to their pre-record values.
8. **Adversarial cost.** Per-touch cost with ground identifiers stays within the stated worst case; refusal paths are O(1) regardless of n. Includes the
   **caterpillar test** (an injectable key hash building one key per divergence bit along a single path at depth about 20, asserting per-touch cost
   scales as the depth).
9. **R5 gate (every stage).** The pinned-hash manifest of 5.1 verifies; all existing finite P3 goldens, the Go and Python binding references and the
   mutation baseline pass unchanged; and the existing suites pass **with `-PbrokerTests`**.
10. **Mutation.** Mutants for the trie (wrong bit order, branch hash missing the bit index (killable by the goldens and the reference; not an equivalent mutant), missing domain prefix, stale sibling, dropped dirty
   node, scalars or the label omitted, key-hash omitting the tag), for `entityBytes` (a tag, key or value field dropped; zero position dropped), for the
   incremental invariants and aggregates (counter drift, restore skipping the recount), and an orphan obligation. All non-equivalent mutants
   killed, with an equivalent-mutant adjudication procedure written down first, as for O3a.
11. **Profile spec first.** Config-v2's field list (a strict superset of the O3a per-class budgets), the budget precedence of 5.2, the lifetime or
    rotation rule, entity caps and the rotation seal (11, Decision 1) are written as a short profile spec and reviewed before S3.
12. **Decision-level golden.** A Python-generated end-to-end replay (about 300 inputs) giving the per-step `stateDigest` and `checksum`, pinned in the
    Kotlin test; the chain and checksum encoding of the new engine is also compared with the independent `forge` helper (the O3a `decisionBytes` is
    private, so a copy could otherwise drift). Multi-byte and 128-byte identifiers and many accounts are covered here, not only by static goldens.
13. **Conformance and non-cross-verification.** The v2 engine, behind the shared interface of 5.1, runs the existing processor, restore-cut and projector
    suites (history cap, crash windows, every `FINANCE_*` refusal); v1 and v2 (and patricia and accumulator) chains do not cross-verify; a negative
    restore test drops entities (the `restoreAt` checkpoint checksum is tautological on the store path, so this test replaces it).
14. **Scale evidence with pass/fail.** Cost per decision at 10^3 to 10^6 entities on the real processor path including the store, against
    thresholds the owner sets **before S2** (11.2); no claim without them.

## 10. Staging, with gates

| Stage | Output | Gate |
| --- | --- | --- |
| S0 (done in part) | the second research spike: terms 8, 9, 5 and 10 measured (section 1.4). **S0-extension, before any further review:** pre-age a RocksDB-backed *real* `FinanceProcessor` at lifted caps (needs the config-v2 pins) or a faithful stand-in to 10^5 entities and time decisions/s under EOS on the unmodified or O(n) ledger; time the projector with JDBC batching (`addBatch`) against Postgres at the same scale; measure heap and GC per entity; explain the 43 s restart | numbers recorded; **if any non-ledger term is within 2x of the ledger term, the engine work is not the next step** |
| S1 | `StateCommitment.reference`, Python encoder, goldens (small and large), tests, mutants on the reference; the byte rules of 3.1 to 3.4 | goldens match; R5 gate |
| S2 | incremental trie, differential vs reference, crowding, caterpillar and adversarial tests | 10,000 schedules; mutants killed; **thresholds set by Decisions 0 and 2** |
| S3a | a plain-map engine `StateView`/`Delta` in new files (semantically identical to O3a, still O(n)); **no edit to any guarded file** | differential equals O3a and the oracle; R5 gate with broker suites |
| S3b | aggregates, incremental invariants, linear reference check, overlay/journal, negative corpus | invariant equivalence; abort tests |
| S3c | O(touched) structures; **S3 exit measurement** at 10^4 and 10^5 entities in memory including restore rebuild and heap/GC | numbers recorded; shape confirmed before store work |
| S4 | v2 store, processor, restore cut, projector (own tables, batched), launcher, `META_VERSION`, restore, audit, scale evidence, runbook | restore property tests; thresholds met or the owner re-decides |

S1 is contingent on Q1, now closed for the trie (4.3). S1 and S2 are neutral to P-5 as *algorithms* but not as goldens: the five fixed classes, labels and
the large golden are instantiation-specific, and a reservation or credit class adds tags and may change what a position means, so the large golden would be
regenerated. **S3 and S4 wait for P-5**, because the invariant table and entity model change. S3's oracle compares everything except the commitment, so
S3a to S3c may run in parallel with S1 and S2 once P-5 is decided. If Decision 1 is *rotation* with a small bound, S3a and S3b (the plain-map engine with
incremental invariants and the overlay) may be all that is needed and S3c and the trie become optional (1.4, last table).

## 11. Decisions requested from the owner

**Decision 0 (gates everything beyond S2).** Is a capacity profile wanted at all, by when, and does it depend on the reservation/credit decision
(P-5, open until calibration)? **Recommended: not now.** The 50k decisions/s gate is waived for finite P3; the finite profile needs none of this; and
S0 shows the projector as written is two orders of magnitude below the repo's 10,000 booked trades/s goalpost, and the Streams lane with a free ledger is itself about 1.7x short of it (11.1k inputs/s sustained is about 5.5k trades/s) (the first 7,600/s figure was superseded). If P-5
changes the economics, stop after S2 and revisit the entity model. If no capacity profile is needed soon, this document is complete as a design record.

1. **Run duration and rotation (D7, replaces the earlier lifetime-or-retirement question).** Recommended: **rotation**, one ledger per session or slice,
   sealed by a cut (final decision, `C(S)`, chain head) that the next ledger's genesis references. The goalpost's entity growth (50,000/s; 15 million per 5-minute session)
   rules out a single lifetime budget; retirement (tombstone deltas, trie deletes with the collapse rule, restore of pruned history) is the much larger design.
2. **Lane (D8).** Does the capacity ledger share the finite topics (one partition, one thread, a changelog per put) or get new ones and partitions? It sets the
   ceiling independent of the ledger; the 10k/s goalpost belongs to the keyed lane, not to this one.
3. **Authenticity (D9).** Explicit sign-off that the digest is an unkeyed integrity digest only (3.5), with no reader-facing proof presented as authentic.
4. **Target and scope.** End-to-end decisions/s on named hardware, including or excluding the projector and read routes, at what entity count. S0 suggests
   a realistic single-lane target is in the low thousands per second, bounded by projector batching.
5. **Restore.** A restore-time objective (after the 43 s restart floor is understood) and the retention of FINANCE_DECISIONS (infinite retention plus a full
   read versus a checkpointed anchor).
6. **Audit.** Cadence (every N decisions) and the operator action on a halt (the runbook in section 8).
7. **Memory and hardware envelope** for the in-memory versus store-backed `StateView`.
8. **A stop condition.** Recommended: abandon the engine work if, in the S0 extension, any non-ledger term (projector, Streams, restore) is within 2x of the
   ledger term at the target.

## 12. Open questions

Round 2 closed or answered the questions of revision 2: **Q1** (trie versus accumulator) closed for the trie (4.3); **Q2** (is 4.2 precise enough) no, so 4.2 is
demoted; **Q3** (per-field framing, order) acceptable once the byte rules of 3.1 to 3.4 are added; **Q4** (S3 split) S3b/S3c fine, S3a reworked (5.1); **Q5**
(audit placement) closed in 5.5; **Q6** (is differential plus oracle enough) yes for the transition semantics given section 9, no for the profile's new rules
(profile spec, 9.11); **Q7** (does S0 block) yes, and the extension is the next deliverable. Still open:

- **Q8.** Is rotation with a small bound enough that S3c and the trie can be dropped (1.4, last table)? This is answered by Decision 1 and the S0 extension, not by review.
- **Q9.** Can the Streams lane reach the goalpost's about 19k inputs/s (it sustained 11.1k over 90 s with a free ledger) with the real ledger, once `commit.interval.ms`, batch size and changelog settings are tuned? (Pinned
  settings are part of the spec; tuning needs a spec change.)
- **Q10 (answered).** The 43 s restart floor is the previous member's 45 s session timeout: `KafkaStreams.close(Duration)` does not leave the consumer group. With `close(CloseOptions().leaveGroup(true))` a restart reached RUNNING in 0.4 to 0.5 s (CAL-FIN-S0). A crash (SIGKILL) restarted in 44.1 s on the production properties and 6.1 s with `session.timeout.ms` 6,000; the finance app's session timeout or static membership is an owner decision.

## 13. Rejected framing

"Incremental digest is the prerequisite for any capacity claim" (S2 close-out research, section 2, and revision 1 of this document) is withdrawn.
It is one of ten terms, and not the largest.

## 14. Review ledger

| Date | Round | Lens | Verdict | Findings (summary) | Disposition |
| --- | --- | --- | --- | --- | --- |
| 2026-10-12 | 1 of 3 | A: cryptography and security argument | Not ready | P1: flat buckets attackable by grinding (O(m) per touch); P2: AdHash reasoning imprecise (cap is 2 x maxEntities, tree also needs a cap, RO caveat, Expand unspecified); no "what C(S) proves"; labels and encodings underspecified; execution/obligation merge loses orphan obligations; k is 7 | Flat buckets replaced by the compressed trie (4.1), measured; 4.2 and 4.3 rewritten; 3.5 added; 3.1 and 3.2 specify labels, framing, widths, key hash; five classes; k = 7 |
| 2026-10-12 | 2 of 3 | B: ledger semantics, compatibility, verification | Not ready | P1: `decide` circular with the `Decision`; "replaced" contradicts frozen O3a; staging/rollback; quadratic reference check; hidden history cap and O(history) restore; P2: incomplete invariant table, weakened coverage, no ledger interface, `checkpoint`, `META_VERSION`, shared-lineage oracle, untested crowded buckets | 5.1 (new engine, empty-diff gate), 5.2 (sequence), 5.3, 5.5 (linear reference, complete table, weakened coverage stated), 6.1 to 6.3, 9.3 to 9.9, 10 gates |
| 2026-10-12 | 3 of 3 | C: performance, systems, operations, process | Not ready | P1: entities equal lifetime decisions (lifetime cap); O(n) terms outside the ledger; abort granularity; P2: spike not the design; "cannot be measured" false; memory; adversarial bucket; staging; gates without thresholds; owner decisions incomplete and mis-ordered | 1 (measured, all nine terms), 1.3, 5.3, 5.4, 6.3, 8, 9, 10 (S3 split, exit measurement), 11 (Decision 0 first, new decisions, stop condition) |
| 2026-10-12 | R2 of 3 (instance 1) | A: cryptography and security argument | Ready with follow-ups (trie only) | P2: 4.2 not byte-exact, 128-bit bound zero margin, trie byte rules incomplete (enum names, child order, bit index, u16 endianness, root and duplicate cases, hex validation); caterpillar evidence; P3: "prefixes do not collide" false, "prevents splicing" overclaimed, 3.5 omits public computability and rollback/fork, delete-collapse rule | 3.1 byte rules; 3.2, 3.4 (length separation, tagged value hash); 3.5; 4.1 (orientation, bit index rationale, caterpillar, delete rule); 4.2 demoted; 4.3 closes Q1; 9.8 caterpillar test |
| 2026-10-12 | R2 of 3 (instance 2) | B: ledger semantics, compatibility, verification | Not ready | P1: frozen-file gate contradicts the interface and v2-store plan, the finite runtime path is unguarded; P2: 5.2 omits the budget step and conflates outcome kinds; converse of L137 lost and mis-cited; commitment label not bound into genesis; verification weaker than it reads (vacuous equivalence, oracle comparison gaps, no decision-level golden, no conformance run); a history sized to the lifetime budget is unsound; audit must recompute the commitment; P3: spec precision, v1-opens-v2 refusal undeliverable, spike labelling | 5.1 (new files, pinned-hash manifest, `-PbrokerTests`); 5.2 (exact order); 5.5 (bijection row, citations, C4 amendment, audit recompute); 6.1, 6.3; 7 (label bound into config-v2); 9.5, 9.6, 9.11 to 9.13 |
| 2026-10-12 | R2 of 3 (instance 3) | C: performance, systems, operations, process | Not ready to build; fund S0 only | P1: the design ignores the repo's 10,000 trades/s goalpost (entities per trade, session scale); gate and S3a inconsistent; P2: lifetime cap is an upper bound; missed terms (changelog write amplification, whole-topic reads, double restore, JDBC batching, single partition); spike mislabelled (1.25n), double leaf hash, weak adversarial run, no heap; middle option missing; staging weaker than stated; P3: audit-after-commit contradiction, k not always 7 | 0, 1.1 (corrected), 1.2 (ten terms), 1.3 (goalpost, entities per input class), 1.4 (S0 measured, ceiling after each fix), 5.1, 10 (S0 extension), 11 (D7 to D9, stop condition) |

Round 2 spent the second three-instance budget (first-three rule applied per declared unit). Between rounds the S0 spike was run as the owner directed. **No
round 3 starts without an owner-approved limit and the S0 extension.**

The unit reached its first-three limit with three *Not ready* verdicts. Per the owner's rule, a research spike (`StateCommitmentCostSpikeTest`,
section 1) was done before this revision to find out why the design kept failing: the cost model had been inferred from a floor and from the
four terms in the ledger, not measured. A second round (declared separately, with a new limit) reviews this revision.
