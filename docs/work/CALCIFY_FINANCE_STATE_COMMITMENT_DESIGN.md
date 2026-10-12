# Calcify finance: scalable state commitment and O(touched) transitions (design)

Status: **DRAFT revision 2, not accepted.** Revision 1 went through a three-lens independent design review (cryptography, ledger
semantics and verification, performance and operations): all three returned *Not ready* (section 14). This revision answers every P1 and
P2, replaces the commitment structure the cryptography review broke, and corrects a cost model that a research spike showed was wrong by two
orders of magnitude. It needs a second review round before anything is built.
Scope: the successor to the O3a ledger for a *capacity* profile. The finite P3 path (O3a, binding v2, its goldens and digests) is frozen and
is not changed by anything here. Owner: Dylan. Written 2026-10-12.

## 0. Summary, and the recommendation

- The O3a ledger is O(n) per decision in at least **seven** places, not four, and the dominant one is not the digest. Measured on synthetic
  states (section 1): one whole-state digest costs **2.6 ms at 1,000 entities, 27 ms at 10,000 and 277 ms at 100,000**; the part of
  `checkInvariants` that is not bounded by the pins costs **2.0 ms, 177 ms and 16.4 s** at the same sizes (it is quadratic).
- Revision 1's cost figures (17.7 us at 700 entities) came from hashing a zero-filled byte array; they were a floor, not a measurement,
  and must not be cited.
- The fix is therefore not "an incremental digest". It is a successor engine whose transition, invariants, commitment, restore and audit
  are all O(touched) (or linear with a small constant for restore), specified so that an independent reference can prove it correct.
- The commitment instantiation changes from flat hash buckets (broken by chosen identifiers: 165 us per touch at 2,000 colliding ids and
  linear in the bucket) to a **path-compressed binary trie over the 256-bit hash of the entity key**, with a multiset accumulator as the
  documented alternative. At the measured sizes both cost 27 to 46 us for a 7-entity decision, flat in n.
- **Recommendation.** Accept sections 3 and 4 as the commitment specification once a second review round passes, because they are neutral to
  the open reservation/credit decision (P-5). **Do not start the engine work (sections 5, 6; stages S3 and S4) until the owner has made
  Decision 0 in section 11**: whether a capacity profile is wanted, by when, with what lifetime budget and what end-to-end target. Finite P3
  does not need any of this.

## 1. The cost model, measured

### 1.1 What was measured, and how

`StateCommitmentCostSpikeTest` (research, excluded from the default run, enabled with `-PresearchTests`) builds synthetic `FinanceState`
values of 10^3, 10^4 and 10^5 entities that bypass the pins in `FinanceConfig` and times the real internal functions: `stateDigest`,
`checkpoint`, `FinanceStore.entityWrites` and the map copies the `Draft` makes, plus a transcription of the unbounded loops of
`checkInvariants`. One JVM, warmed, single thread, in memory, on a development laptop. Numbers are indicative, not capacity claims.

| Entities | `stateDigest` | `checkpoint` encode | map copies | `entityWrites` diff | `checkInvariants` unbounded loops |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 2.6 ms | 2.5 ms | 48 us | 95 us | 2.0 ms |
| 10,000 | 27 ms | 27 ms | 274 us | 0.70 ms | 177 ms |
| 100,000 | 277 ms | 291 ms | 4.4 ms | 6.5 ms | 16.4 s |

Reading: the digest is linear but its constant is large (the canonical sort re-encodes both strings as strict UTF-8 on every comparison, then the whole
state is hashed); the invariant check is **quadratic** (`attempts.count` inside the loop over obligations); map copies and the persistence diff are
cheap by comparison. At the finite P3 ceiling (about 620 entities) the digest is about 1.5 ms, which is harmless there.

### 1.2 Every O(n) or O(history) term on the path, not only the four in revision 1

| # | Term | Where | Measured |
| --- | --- | --- | --- |
| 1 | Whole-state digest per decision | `FinanceLedger.stateDigest` | yes |
| 2 | Map copies, including for **refused and no-op inputs** (the `Draft` is built before `evaluate`) | `Draft`, `apply`, `actions +` | yes |
| 3 | Full invariant check per decision, containing an O(obligations x attempts) loop | `checkInvariants` | yes |
| 4 | Whole-map diff to find changed entities | `FinanceStore.entityWrites` | yes |
| 5 | Restore: decode and chain-verify the **whole decision log from offset 0**, re-read all history entries, three full encodes, a quadratic check | `FinanceRestoreCut`, `FinanceStore.verifyAndRestore`, `restoreAt` | encode part yes; Kafka read no |
| 6 | History cap of 312 entries, enforced in the processor and the store, and the O(history) `verifyHistory` | `FinanceStore.MAX_HISTORY_ENTRIES` | n/a (a hard ceiling) |
| 7 | Read routes: unpaginated full reads per request (worst for the operator route) | `FinanceReadStore` | no |
| 8 | Projector: about 5 to 8 SQL statements per decision under a `FOR UPDATE` checkpoint lock | `FinanceProjector` | **not measured** |
| 9 | One Streams thread: any long in-thread work (a full audit, a restore) blocks all input and can exceed `max.poll.interval.ms` | topology | n/a |

### 1.3 Consequences

1. **Revision 1's claim that the earlier research note was merely "incomplete" understated the problem.** Even a perfect incremental digest
   leaves terms 2 to 9. Revision 1 also *proposed to update an accumulator "by the entity diff `entityWrites` already computes"*, which is itself
   a whole-map comparison; that would not have raised the ceiling at all.
2. **`maxEntities` is a lifetime cap, not a capacity.** Every recorded decision adds an action entity that is never removed
   (`entities >= sequence`). With 2^20 entities the ledger is exhausted after 2^20 decisions: about 17 minutes at 1,000 decisions/s and 21
   seconds at 50,000/s. "Rate-based limits" (revision 1, section 11) are impossible without retiring entities. This is an owner decision
   (section 11, Decision 1), not an implementation detail.
3. **Whether the ledger, rather than Streams, Postgres or restore, is the bottleneck is unproven.** Term 8 was not measured; if the projector
   or restore dominates, this whole effort is not what limits throughput. The cheapest experiment that would change the decision is to
   measure terms 5, 7 and 8 end to end before funding the engine (section 10, S0 exit).

## 2. Requirements

- **R1 Cost, worst case.** Per decision, time and allocation are O(k * L) where `k` is the number of entities written (at most **7** for
  every current input: a settle writes four positions, the execution's obligation, the attempt and the action; it also reads the
  execution) and `L` is the number of hashes on a commitment update path. `L` has a hard bound of 256 and an expected value near
  `log2(entities)`. The state of the art must not depend on the identifiers being well distributed.
- **R2 Binding.** The commitment binds the whole state: any change to any entity, the entity set, or a scalar changes it, except with the work
  of a SHA-256 collision (about 2^128) for an adversary who chooses both states.
- **R3 Canonical.** The commitment is a deterministic function of the abstract state alone and has an independent encoder that reproduces
  goldens byte for byte.
- **R4 Restorable.** Rebuild from an entity scan in O(n log n) hashing; verify against the head decision's `stateDigest`. The decision-log
  and history parts of restore are bounded separately (section 6.3); this document does not claim restore is O(n log n) overall.
- **R5 Compatible.** Finite P3 artifacts are byte-identical and untouched (the stage gates in section 10 enforce this mechanically).
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

### 3.2 The commitment

```
LABEL = "calcify-finance-state-v2-patricia"                       (instantiation label; the accumulator alternative has its own)
K(e)  = SHA-256( frame("calcify-finance-key-v2") || frame(tag) || keyBytes(e) )        256-bit trie key, bit 0 = most significant
L(e)  = SHA-256( 0x00 || K(e) || SHA-256(valueBytes(e)) )                              leaf digest
T     = digest of the trie over { (K(e), L(e)) }, defined in section 4.1
C(S)  = SHA-256( frame(LABEL) || int64(sequence) || int32(controls) || T )             T is the 32 raw bytes (the empty set has T = SHA-256(0x03))
```

`C(S)` is the decision's `stateDigest` (64 lowercase hex, unchanged field and type), so **no protobuf change** is needed. Because the trie
is keyed by a hash of the entity key, **nothing is sorted**: the structure is a pure function of the key set, which removes the strict-UTF-8
re-encoding comparator (a large part of the measured digest cost) from the hot path.

### 3.3 Instantiation label and parameters

The label names the instantiation. The accumulator alternative uses `calcify-finance-state-v2-adhash`; a different label can never
produce the same outer hash, and switching instantiation is a new version. The trie needs no depth parameter. (Revision 1's `D` is gone.)

### 3.4 Domain separation

Leaf `0x00`, branch `0x02`, empty `0x03`; outer frame starts with a length prefix. The prefixes do not collide: a leaf input is
`00 || 32 || 32`, a branch input `02 || u16 || 32 || 32`, the outer input starts with the four-byte length of the label. Per layer the
encoding is injective (fixed-width fields), checked by review lens A.

### 3.5 What `C(S)` does and does not prove

It proves that a restored or presented state **is the state the head decision committed to** (R2 and the O3a `restoreAt` property). It
does **not** prove that the head was reached by valid transitions: `verifyChain` verifies links and checksums, not transitions, and the chain
is unkeyed, so whoever can write FINANCE_DECISIONS can forge a self-consistent chain with any state and recompute `C(S)`. Transition validity
needs replay of the inputs (O(history)) or a signed or externally anchored head, which is out of scope and not precluded. The field keeps the
name `stateDigest`; documentation and projector comments must call it an *unkeyed integrity digest*, and no reader-facing proof may be presented
as authentic until the head is signed or anchored.

## 4. Instantiation

### 4.1 Recommended: path-compressed binary trie over K(e)

- **Shape.** A binary trie where each entity is a leaf at the shortest unique prefix of `K(e)`; a branch stores the bit index at which its
  two subtrees first differ. The shape is a pure function of the key set (R3).
- **Digests.** `leaf = L(e)` as above. `branch = SHA-256( 0x02 || u16(bitIndex) || left || right )`, so a branch commits to *where* it splits
  (this is what prevents splicing). `T` is the root's digest, or `SHA-256(0x03)` if the set is empty.
- **Security.** Two different entity sets with the same `T` require a SHA-256 collision, with no assumption on the number of entities.
- **Cost, worst case.** An update recomputes one path: at most 256 branch hashes. An attacker who grinds identifiers whose key hashes share
  `b` bits must do about `2^b` hashes to gain one extra level per branch point, so reaching depth `L` costs about `2^L`; the practical
  worst case is the low tens of levels, hard-bounded by 256 (about 0.9 ms for a 7-entity decision). Revision 1's flat buckets had no such bound
  (measured: 165 us per touch with 2,000 colliding identifiers, linear in the bucket).
- **Measured** (7 entities written, exact structures, mean over random touches): 27 us at 1,000 entities, 34 us at 10,000, 46 us at
  100,000, with mean depth 11.3, 14.6 and 18.0. Adversarial: 50 identifiers sharing at least 18 leading hash bits (14.6 million grinding
  trials) raised the maximum depth to 8 and cost 3 us per touch, because compression makes depth depend on branching, not on shared prefix length. (That measurement shows the compression effect; the claim that depth `L` costs about `2^L` to force is an argument, not a measurement.)
- **Memory.** About 2n nodes of an object plus 32 bytes each, on top of the entity table. To be measured at the S3 exit; the store-view
  alternative (section 5.4) is the mitigation.

### 4.2 Alternative: multiset accumulator (AdHash style)

`T = sum over e of Expand(e) mod 2^3072`, with `Expand(e)_i = SHA-256( frame("calcify-finance-adhash-v2") || int32(i) || valueBytes'(e) )` for
`i = 0..11` concatenated, where `valueBytes'` is `frame(tag) || keyBytes || valueBytes`; the outer hash additionally binds the entity count.
Update: `acc += Expand(new) - Expand(old)`. Measured 29 us for 7 entities at 1,000 entities and 27 us at 100,000: the same as the trie, constant
in n and independent of identifier distribution (no worst case to attack), with 384 bytes of state and no structure to maintain.
Security is **conditional**: the generalized-birthday attack on modular subset sums costs about `2^(n/(1+lg k))` where `k` is the number of
entities by which two reachable states differ, counting a changed entity as a removal plus an addition, so `k <= 2 * maxEntities`. With `n = 3072`
the 128-bit bound holds for `k <= 2^23`, i.e. for `maxEntities <= 2^22`, and it relies on `Expand` behaving like a random oracle (the standard
heuristic; SHA-256 is Merkle-Damgard, so this is not a proof). No inclusion proofs. MuHash (multiplicative) is rejected: updates need modular
inverses, orders of magnitude slower.

### 4.3 Why the trie is recommended

Not because the accumulator needs a cap and the trie does not: both need an enforced `maxEntities` (the profile already enforces caps, and
section 1.3 shows a lifetime cap is needed regardless). The reasons are a proof that rests on SHA-256 collision resistance alone, no
random-oracle step, and the ability to produce inclusion proofs against a future signed head. The accumulator is smaller and has no
adversarial path; if the second review round prefers it, switching changes section 4 and the labels only. This remains open question Q1.

### 4.4 Rejected

- **Flat 2^D buckets (revision 1).** Cost O(m) per touch in the bucket size; the adversarial measurement above; a public salt does not help
  (the attacker computes buckets after seeing it) and a hard bucket cap lets an attacker deny specific identifiers.
- **Whole-state digest every K decisions.** Weakens "every decision commits its state", does not touch terms 2 to 9.
- **History-only commitment.** Commits inputs, not state; the O3a review (P1-1) requires state commitment.
- **Merkle-Patricia with variable node encodings.** More general than needed.

## 5. The O(touched) transition engine

### 5.1 It is a new engine; frozen files do not change

The engine is a **new class in new files**; `FinanceLedger.kt`, `FinanceModel.kt`, `FinanceStore.kt` and `FinanceWire.kt` keep an empty
`git diff` against `master` at every stage (a mechanical stage gate, section 10), so the frozen O3a remains the oracle, the goldens and the
mutation harness (which patches `FinanceLedger.kt` by exact source strings) keep working. Anything shared (for example moving `Canon`) must be
proven byte-neutral by the existing goldens and is itself a separate PR. A `FinanceLedgerEngine` interface (genesis chain, `decide`, `verifyChain`,
`verifyLink`, restore) is introduced so the processor, projector, store and restore cut stop depending on the concrete `FinanceLedger`; it is
selected by the registered profile, not by `financeStateVersion` (which names the capture-to-input mapping and must not be reused).

### 5.2 The decision sequence

Revision 1's pure `decide` returning a `Decision` was circular: the `Decision` carries `C(S')` and the checksum covers it, and `C(S')` needs the
post-delta trie. The contract is:

```
evaluate(view, input)  -> Delta (entity puts + scalar delta + aggregate delta) | refusal            reads only named entities; O(1) refusals
root' = trie.preview(delta)                                                                          path copy / overlay; nothing visible
Decision(sequence', action, inputDigest, disposition, legs, priorChain, C(S'), checksum)              pure function of the above
commit(delta, root', aggregates')                                                                      the only mutation; atomic
```

### 5.3 Staging and abort are semantic requirements

The processor stages several decisions per Kafka record (a capture envelope maps to several members; later members read earlier members' staged
state) and moves its cached state only after the store write succeeds. Therefore the engine must provide a **record-granularity overlay or
undo journal**: reads see staged writes, `commit` of the record applies them together, and discarding the overlay is O(staged) and leaves the
trie root, counters and entity tables exactly as before. This is required for the processor's all-or-nothing property to survive, and is
tested by failure injection at every point of a multi-decision record (section 9).

### 5.4 Storage

Whether `StateView` is in-memory or a read-through over the persistent store is the real architectural fork and is decided **before S3**, not at
S4: in-memory is simpler and faster but costs 300 to 600 bytes of JVM heap per entity (boxed keys, three strings per action key, the cached
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
| L137 | every obligation has an execution (and the converse) | local: created together on capture |
| L138-143 | pending residuals equal execution economics | local on capture and settle |
| L144 | settled obligation has zero residuals | local on settle |
| L145 | `sequence == actions.size` | counter |
| L146-147 | executions, obligations within caps | counters |
| L148 | `controls <= maxControls` and `controls == count(FUNDED actions)` | counters |
| L149 | record budget `sequence <= caps sum` | counter (replaced by the lifetime budget in the capacity profile) |
| L150-153 | only recorded dispositions in actions; captures == executions; settle-class actions == attempts | local plus per-class counters |
| L154-157 | attempt well-formed: number >= 1, execution exists, disposition in the settle class | local on settle |
| L158-161 | a settled obligation has exactly one SETTLED attempt, **a pending one has none** | local on settle |
| packet C4 | residuals never increase; settled is terminal; an execution key is captured at most once; a key never rebinds to different content | transition rules in `evaluate` (already how O3a decides), each with a named test |

**Coverage weakens between audits, deliberately.** O3a re-checks the whole state after every decision as defence in depth against bugs in its own
`apply`. The aggregates here are updated by the same code they check, and a running balance sum cannot detect corruption of the position table.
The detection that remains is the restore-time and audit-time full reference check. The audit runs **deterministically every N decisions and
always at restore and clean shutdown**, over an immutable snapshot **off the processing thread** so a long audit cannot exceed
`max.poll.interval.ms`; it can only halt further decisions, and it must not halt in the middle of a record (it runs between records, before or
after commit as decided in Q5).

## 6. Processor, store, restore, projection

### 6.1 Store

A v2 store has a new `META_VERSION` and parser (aggregate counters and `sequence/controls` live in `meta`); a v1 binary opening a v2 store (or the
reverse) fails with a distinct version refusal, not the generic `FINANCE_STORE_INVALID`. The trie is not persisted; it is rebuilt on restore.
`entityWrites` is replaced by the `Delta` the engine already produced. The store must not drop zero-amount positions (a store test pins this).

### 6.2 Restore of state

Scan entities, rebuild the trie and aggregates, require `C(S)` equals the head decision's `stateDigest` and the recounted aggregates equal
`meta`. The extra `checkpoint` checksum (a second full encode in `restoreAt`) is **dropped**: the head commitment already covers it; its
mutants are replaced by commitment-mismatch mutants.

### 6.3 What bounds a capacity profile outside the state (previously hidden)

- **History entries.** `MAX_HISTORY_ENTRIES = 312`, the 296 record bound and the O(history) `verifyHistory` are properties of the finite P3 run. A
  capacity profile needs either a lifetime budget with the history sized to it, or a checkpointed history with an anchor; it cannot reuse
  the finite cap. Restore cost is therefore stated as **O(n log n + H)** where H is history plus decision-log length.
- **Decision-log read.** `FinanceRestoreCut` decodes and chain-verifies the entire FINANCE_DECISIONS log from offset 0 and needs infinite
  retention. A capacity profile needs an owner-set restore-time objective (RTO) and a checkpointed chain anchor, or restore is O(N) forever.
- **Read routes.** Unpaginated full-table reads are O(n) per request; a capacity profile paginates or bounds them.
- **Projector.** Several SQL statements per decision under one checkpoint lock; end-to-end throughput is capped by it unless it is batched or
  the capacity target is defined for the ledger path only. This is an owner decision (11.2).

The projector (hash link, input digest, continuity), the history entry chain and the read routes are otherwise unchanged by *this* design.

## 7. Versioning and compatibility

Domain strings `calcify-finance-state-v2-patricia`, `calcify-finance-config-v2` (profile: caps, lifetime budget), `calcify-finance-genesis-v2`.
The decision domain `calcify-finance-decision-v1` is kept; v1 and v2 chains still never cross-verify, **because the genesis chain is derived from
the config digest and `verifyChain` fails on the first `priorChain`** (`verifyLink` alone takes the prior chain as a parameter and is not
genesis-bound, so every consumer must start from genesis). The wire and proto are unchanged. The capacity profile is registered as its own
binding version; this document does not define that binding.

## 8. Failure and operations

- A commitment mismatch at restore is a refusal, never a repair. An aggregate-versus-recount mismatch at audit halts the processor between records.
- **Halt runbook (required before S4):** stop the app; export the decision log, history and entities read-only with the evidence reader;
  verify with the reference implementation to locate the divergent entity; archive; open a new run with a new binding and a corrected engine.
  Recovery is never in place. A halt stops further decisions only; decisions already forwarded were computed from the pre-halt counters.
- **Memory:** an estimate of 300 to 600 bytes per entity in-memory plus trie nodes is **to be measured at the S3 exit**; the store-view
  alternative bounds it.
- **Single thread:** no in-thread work longer than the poll interval; audits and restores are off-thread or between polls.

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
   them). The commitment itself is compared against the reference. Beyond the pins (lifted caps, a lifetime budget) there is no existing oracle:
   it is specified in the profile before S3 and is a metamorphic suite (conservation, idempotent replay, restore at random cuts).
6. **Invariant equivalence.** After each step the counters equal a full recount, the linear reference check passes, and its verdict equals
   `checkInvariants` on the shared profile.
7. **Abort and failure injection.** An exception at every point of a multi-decision record (including the store write) leaves the live root,
   counters and tables equal to their pre-record values.
8. **Adversarial cost.** Per-touch cost with ground identifiers stays within the stated worst case; refusal paths are O(1) regardless of n.
9. **R5 gate (every stage).** `git diff master -- FinanceLedger.kt FinanceModel.kt FinanceStore.kt FinanceWire.kt` is empty; all existing finite P3
   goldens, the Go and Python binding references and the mutation baseline pass unchanged.
10. **Mutation.** Mutants for the trie (wrong bit order, branch hash missing the bit index, missing domain prefix, stale sibling, dropped dirty
   node, scalars or the label omitted, key-hash omitting the tag), for `entityBytes` (a tag, key or value field dropped; zero position dropped), for the
   incremental invariants and aggregates (counter drift, restore skipping the recount), and an orphan obligation. All non-equivalent mutants
   killed, with an equivalent-mutant adjudication procedure written down first, as for O3a.
11. **Scale evidence with pass/fail.** Cost per decision at 10^3 to 10^6 entities on the real processor path including the store, against
    thresholds the owner sets **before S2** (11.2); no claim without them.

## 10. Staging, with gates

| Stage | Output | Gate |
| --- | --- | --- |
| S0 | this document, second review round accepted; **plus the end-to-end cost experiment**: terms 5, 7 and 8 measured (restore with a persistent store, a projector transaction, the read routes) so we know whether the ledger is the bottleneck at all | reviewers Ready; experiment recorded |
| S1 | `StateCommitment.reference`, Python encoder, goldens (small and large), tests, mutants on the reference | goldens match; R5 gate |
| S2 | incremental trie, differential vs reference, crowding and adversarial tests | 10,000 schedules; mutants killed; thresholds set |
| S3a | `FinanceLedgerEngine` interface and `StateView`/`Delta` on the existing maps (semantically identical, still O(n)) | differential equals O3a; R5 gate |
| S3b | aggregates, incremental invariants, linear reference check, overlay/journal | invariant equivalence; abort tests |
| S3c | O(touched) structures; **S3 exit measurement** at 10^4 and 10^5 entities in memory including restore rebuild | numbers recorded; shape confirmed before store work |
| S4 | store integration, `META_VERSION`, restore, audit, scale evidence, runbook | restore property tests; thresholds met or the owner re-decides |

S1 and S2 are neutral to P-5 (the commitment is generic over tagged entities) and can proceed after S0 and Decision 0. **S3 and S4 wait for
P-5**, because the invariant table and entity model change if reservation or credit is adopted. S3 does not depend on the commitment (its
oracle compares everything except the commitment), so S3a to S3c may also run in parallel with S1 and S2 once P-5 is decided.

## 11. Decisions requested from the owner

**Decision 0 (gates everything beyond S2).** Is a capacity profile wanted at all, by when, and does it depend on the reservation/credit decision
(P-5, open until calibration)? If P-5 changes the economics, stop after S2 and revisit the entity model. If no capacity profile is needed
soon, this document is complete as a design record and the work waits.

1. **Lifetime budget or retirement.** Is the capacity ledger a bounded-lifetime ledger with a stated total decision budget per binding (simple,
   consistent with "one run, one binding"), or does it need to retire actions and other entities (a much larger design: tombstone deltas, trie
   deletes, restore of pruned history)? `D` is gone, but `maxEntities` and the history size follow from this.
2. **Target and scope.** End-to-end decisions/s on named hardware, including or excluding the projector and read routes, and at what entity
   count; the earlier 50,000/s figure was a placeholder for the finite fixture and was waived for P3.
3. **Restore.** A restore-time objective and the retention of FINANCE_DECISIONS (infinite retention plus full read versus a checkpointed anchor).
4. **Audit.** Cadence (every N decisions) and what the operator does on a halt (the runbook in section 8).
5. **Memory and hardware envelope** for the in-memory versus store-backed `StateView`.
6. **A stop condition:** the measured number or finding at which this effort is abandoned (for example, "if the end-to-end bottleneck is the
   projector or restore, not the ledger").

## 12. Open questions for the second review round

- **Q1.** Trie versus accumulator, now on equal measured cost (27 to 46 us for 7 entities at 10^5): is the SHA-256-only argument worth the trie's
  code, memory and the 256-hash worst case, or does the accumulator's constant cost and absence of an adversarial path win, given an enforced cap of
  `2 * maxEntities <= 2^23`?
- **Q2.** Is `Expand` and the accumulator spec in 4.2 precise enough for an independent encoder?
- **Q3.** Does the five-class split plus per-field framing in 3.1 match what an implementer in another language would produce, and is sorting by
  nothing (trie keyed by hash) acceptable as the canonical order?
- **Q4.** Is the S3a/b/c split small enough to review, and is running S3 only after P-5 the right ordering?
- **Q5.** Should the audit run before or after the record's commit? (After commit means a bad decision may already be forwarded; before commit
  puts O(n) work on the hot path unless the audit is incremental.)
- **Q6.** Is differential equality with O3a plus the independent oracle plus the metamorphic suite sufficient for behavior beyond the pins, where no
  oracle exists, or must the capacity profile's semantics be specified and reviewed first as a separate document?
- **Q7.** Is the claim "the ledger may not be the bottleneck" (1.3, item 3) strong enough that S0 should block on the end-to-end experiment?

## 13. Rejected framing

"Incremental digest is the prerequisite for any capacity claim" (S2 close-out research, section 2, and revision 1 of this document) is withdrawn.
It is one of nine terms, and not the largest.

## 14. Review ledger

| Date | Round | Lens | Verdict | Findings (summary) | Disposition |
| --- | --- | --- | --- | --- | --- |
| 2026-10-12 | 1 of 3 | A: cryptography and security argument | Not ready | P1: flat buckets attackable by grinding (O(m) per touch); P2: AdHash reasoning imprecise (cap is 2 x maxEntities, tree also needs a cap, RO caveat, Expand unspecified); no "what C(S) proves"; labels and encodings underspecified; execution/obligation merge loses orphan obligations; k is 7 | Flat buckets replaced by the compressed trie (4.1), measured; 4.2 and 4.3 rewritten; 3.5 added; 3.1 and 3.2 specify labels, framing, widths, key hash; five classes; k = 7 |
| 2026-10-12 | 2 of 3 | B: ledger semantics, compatibility, verification | Not ready | P1: `decide` circular with the `Decision`; "replaced" contradicts frozen O3a; staging/rollback; quadratic reference check; hidden history cap and O(history) restore; P2: incomplete invariant table, weakened coverage, no ledger interface, `checkpoint`, `META_VERSION`, shared-lineage oracle, untested crowded buckets | 5.1 (new engine, empty-diff gate), 5.2 (sequence), 5.3, 5.5 (linear reference, complete table, weakened coverage stated), 6.1 to 6.3, 9.3 to 9.9, 10 gates |
| 2026-10-12 | 3 of 3 | C: performance, systems, operations, process | Not ready | P1: entities equal lifetime decisions (lifetime cap); O(n) terms outside the ledger; abort granularity; P2: spike not the design; "cannot be measured" false; memory; adversarial bucket; staging; gates without thresholds; owner decisions incomplete and mis-ordered | 1 (measured, all nine terms), 1.3, 5.3, 5.4, 6.3, 8, 9, 10 (S3 split, exit measurement), 11 (Decision 0 first, new decisions, stop condition) |

The unit reached its first-three limit with three *Not ready* verdicts. Per the owner's rule, a research spike (`StateCommitmentCostSpikeTest`,
section 1) was done before this revision to find out why the design kept failing: the cost model had been inferred from a floor and from the
four terms in the ledger, not measured. A second round (declared separately, with a new limit) reviews this revision.
