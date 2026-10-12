# Calcify finance: scalable state commitment and O(touched) transitions (design)

Status: **DRAFT revision 1, not reviewed.** No code exists for this design beyond the cost spike in `IncrementalCommitmentSpikeTest`.
Scope: the successor to the O3a ledger for a *capacity* profile. The finite P3 path (O3a, binding v2, its goldens and digests) is frozen
and is not changed by anything here. Owner: Dylan. Written 2026-10-12.

## 0. What this document decides, and what it asks the owner to decide

Decides (pending review): a definition of the state commitment as a **pure function of the state**; a recommended instantiation; the
rules for an **O(touched)** transition engine and its incremental invariants; versioning and compatibility with finite P3; the
verification plan that makes the incremental engine provably equal to a simple reference.

Asks the owner (section 11): the capacity profile's size caps, the acceptance thresholds, and whether this successor ledger is the one
the product will adopt, which depends on the open reservation/credit decision (P-5).

## 1. Problem, with the evidence we have and the evidence we do not

The O3a `decide` is correct and reviewed, and it was built as a *booking kernel for a bounded proof run*. For a state of `n` entities one
decision costs O(n) in four independent places:

1. **State digest.** `stateDigest(unchained)` re-encodes and hashes every entity (sorting included).
2. **Map copies.** `Draft` copies the positions, executions, obligations and attempts maps, and `state.actions + (key to ...)` copies the
   action map, for every decision, including decisions that touch two entities.
3. **Invariant check.** `checkInvariants(next)` walks every entity, folds per-asset position sums with `BigInteger`, counts dispositions
   across all actions, and so on.
4. **Entity diff for persistence.** `FinanceStore.entityWrites` compares whole maps to find the changed entities.

Measured: the whole-state digest alone is 17.7 us at 700 entities, 232 us at 10,000 and 2.3 ms at 100,000 (spike, lower bound because the
real encoder also sorts). **Not measured, and now known to be necessary:** terms 2 to 4. They cannot be measured at scale on the current
code because `FinanceConfig` pins the profile (`maxCaptures <= 128`, `maxObligations <= 256`, at most 16 accounts) and `checkInvariants`
enforces the pins, so a state beyond ~700 entities cannot be constructed. This matters: **an incremental digest alone would not raise
the ceiling.** The earlier research note (S2 close-out, section 2) called the incremental commitment "the prerequisite for any capacity
claim"; that was incomplete. The prerequisite is an O(touched) *transition*, of which the commitment is one part.

At the finite P3 profile none of this is a defect: n is bounded by construction. The design below therefore does not touch finite P3.

## 2. Requirements

- **R1 Cost.** Time and allocation per decision are O(k log n) in the number `k` of entities the decision touches (k <= 6 for every
  current input), independent of n apart from the log term.
- **R2 Binding.** The commitment binds the *entire* state: any change to any entity, to the entity set, or to a scalar changes it, except
  with probability at most 2^-128 for an adversary who chooses both states (collision resistance, not preimage).
- **R3 Canonical.** The commitment is a deterministic function of the abstract state alone: independent of insertion order, history,
  hash-map iteration order, JVM, or language. An independent encoder (Python, as for binding v2) must reproduce goldens.
- **R4 Restorable.** The full structure is rebuilt from an entity scan in O(n log n) at most, and the rebuilt commitment is checked against
  the head decision's `stateDigest` (this is the O3a `restoreAt` property, kept).
- **R5 Compatible.** Finite P3 artifacts (ledger bytes, decision bytes, chains, goldens, registered digests) are byte-identical. A v2 chain
  can never verify as v1 or the reverse (domain separation).
- **R6 Verifiable.** The incremental engine is *specified by* a simple reference implementation, and equality with it is tested over
  large randomized schedules, with mutation testing, before anything depends on it.
- **R7 Not authenticity.** The commitment is unkeyed: whoever can write the decision log can recompute it. It must not preclude later
  signing or anchoring the head (it is a fixed-size value that fits a signature or a transparency log), but providing that is a non-goal.

Non-goals: a capacity claim; reservation or credit semantics (section 11); the persistence engine; the keyed chain; inclusion proofs
for readers (supported by the structure, not built).

## 3. The commitment as a pure function

Let a *state* be the O3a abstract state: positions, executions with their obligations, attempts, actions, and the scalars `sequence` and
`controls` (the decision chain head is **excluded**, exactly as `stateDigest` excludes it today, because the head is the decision's own
checksum). Define the **entity set** `E(S)`, one canonical record per map entry:

| Tag | Key (framed) | Value (framed) |
| --- | --- | --- |
| `P` position | account, asset | amount |
| `X` execution (with its obligation) | run, session, instrument, execution | buyer, seller, quantity, priceNanos, status, cashResidual, shareResidual |
| `A` attempt | run, session, instrument, execution, attempt | disposition |
| `K` action | namespace, domain, actionId | input digest, disposition |

Framing is the O3a `Canon` framing (int32 length plus strict UTF-8 for text, big-endian integers). A zero-amount position that exists in
the map is an entity like any other (the O3a state does keep such entries; a different canonicalization would be a different state
definition and is rejected so that the reference stays a transcription of today's encoder). `entityBytes(e) = frame(tag) || frame(key) || frame(value)`.

The commitment is

```
C(S) = SHA-256( frame("calcify-finance-state-v2") || int64(sequence) || int32(controls) || T(E(S)) )
```

where `T` is the structure-specific digest of the entity set defined in section 4. The decision's `stateDigest` field carries `C(S)`
(64 lowercase hex, unchanged field and type), so **no protobuf change** is needed.

## 4. Instantiation (recommended: bucketed Merkle tree; alternative: multiset accumulator)

### 4.1 Recommended: fixed-depth sparse binary Merkle tree over hash buckets

- Parameter `D` (tree depth, a profile constant committed in the config digest). There are `2^D` buckets.
- `bucket(e) = first D bits of SHA-256(frame("bucket-key") || frame(tag) || frame(key))` (big-endian bit order).
- `leafDigest(e) = SHA-256(0x00 || entityBytes(e))`. A bucket's digest is
  `SHA-256(0x01 || int32(m) || leafDigest(e_1) || ... || leafDigest(e_m))` with the bucket's entities sorted by unsigned bytes of
  `entityBytes`; an empty bucket's digest is the constant `Z_0 = SHA-256(0x01 || int32(0))`.
- An internal node is `SHA-256(0x02 || left || right)`; the digest of an empty subtree at height `h` is `Z_h = SHA-256(0x02 || Z_{h-1} || Z_{h-1})`.
  `T(E)` is the root. (The 0x00/0x01/0x02 prefixes are the standard leaf/node domain separation that blocks second-preimage splicing.)
- Security: finding two entity sets with the same root requires a SHA-256 collision (2^128 work), with no assumption about the number of
  entities. This is the property the review should hold the design to.
- Cost per touched entity: one bucket re-digest (m is O(1) at load factor <= 1) plus `D` node hashes of 65 bytes. Touched entities in the
  same subtree share upper nodes, so recompute level by level over the union of dirty nodes (measured in the spike: 19 us per 5-entity
  decision at 700 entities and 25 us at 100,000, D = 16).
- `D` choice: `D = ceil(log2(maxEntities))` so the expected bucket load is <= 1; a profile with `maxEntities` = 2^20 uses `D = 20`. The
  finite-sized test profile uses `D = 10`.

### 4.2 Alternative: multiset accumulator (AdHash style)

`T(E) = sum over e of Expand_3072(entityBytes(e)) mod 2^3072`, where `Expand` is SHA-256 in counter mode. Update: `acc += Expand(new) - Expand(old)`.
Smaller code, same measured cost (13 to 16 us), no tree to maintain. Security is **conditional**: the generalized-birthday attack on modular
subset sums costs about `2^(n/(1+lg k))` for `k` differing entities, so with `n = 3072` the 128-bit bound holds only if `k <= 2^23`, i.e. it
needs an enforced cap on the number of entities by which two reachable states can differ (here, `maxEntities`). The proof therefore rests on
a size cap plus the random-oracle behavior of `Expand`. No inclusion proofs. MuHash (multiplicative) is rejected: updates need modular
inverses, which cost orders of magnitude more per update.

### 4.3 Why the tree is recommended despite more code

Its security argument is one line (SHA-256 collision resistance), it needs no assumed cap, it composes with later per-entity inclusion
proofs against a signed head (R7), and the cost is within a small constant of the accumulator. This **reverses the AdHash recommendation in the
S2 close-out research note**, after working out the Wagner bound above and finding that it creates a hard dependency on a size cap. The
choice remains a review question (section 12, Q1).

### 4.4 Rejected alternatives

- **Whole-state digest every K decisions.** Amortizes the O(n) hash but weakens "every decision commits its state"; restore would need to
  replay to the next checkpoint; does not address terms 2 to 4. Kept only as a fallback if the tree is rejected.
- **History-only commitment (hash chain of deltas).** Cheap, but it commits the inputs and not the state; the O3a review (P1-1) required that
  a restored state be provably *the* state of the head, which needs a state commitment.
- **Merkle-Patricia or other keyed tries.** More general than needed; variable shape makes R3 harder to specify and test.

## 5. O(touched) transition engine

### 5.1 Interfaces

```
interface StateView {            // read-only, point lookups only
  position(account, asset): Long?          execution(key): Captured?          obligation(key): Obligation?
  attempt(key, n): Disposition?            action(key): ActionRecord?         scalars: sequence, controls
  aggregates: per-asset position sum, counts (executions, obligations, attempts, controls, actions by class)
}
data class Delta(puts: List<EntityPut>, scalarDelta, aggregateDelta)   // entities never disappear (kept from O3a)
decide(view: StateView, input): Outcome(delta, disposition, decision?)
apply(state, delta) -> state'    // updates entity storage, the commitment tree and the aggregates in O(k log n)
```

`decide` reads at most the entities the input names plus those it derives (the two accounts' positions, the execution and its obligation,
the action key, the attempt key). It must not enumerate. The existing `Draft`-and-copy code is replaced by building a `Delta`.

### 5.2 Incremental invariants

Every O3a packet invariant is either *local* (checked on the touched entities only) or *aggregate* (maintained as a running counter in the
state and checked from the counters). `checkInvariants` over the whole state stays as the **reference check** used at restore, in
differential tests, and at a configurable audit interval; it is not on the per-decision path.

| O3a invariant | Incremental form |
| --- | --- |
| No negative non-opening position; opening account never positive | local: touched positions |
| Position only in the two assets; known accounts | local |
| Per-asset positions sum to zero (balance) | aggregate: per-asset running sum, updated by the legs (legs sum to zero by construction; a nonzero leg-sum is a bug) |
| Every obligation has an execution; execution has an obligation | local on capture (both created together) |
| Pending obligation residuals equal execution economics | local on capture and settle |
| Settled obligation has zero residuals and exactly one settled attempt | local on settle |
| `sequence == actions.size`; `controls == count(FUNDED actions)`; `executions.size == count(CAPTURED actions)`; `attempts.size == count(settle-class actions)` | aggregate counters per disposition class, updated per recorded decision |
| Budget limits (captures, obligations, attempts, controls) | counters |
| Only recorded dispositions in actions | local |

Equivalence of "local plus counters" with the full check is itself a test obligation (section 9): after every step of every randomized
schedule the counters equal a full recount and the full check passes.

### 5.3 Concurrency and purity

The engine remains single-writer and deterministic. `decide` does not mutate; `apply` is the only mutation and is atomic per decision.
State is a value for restore and tests purposes: either persistent (path-copying) structures or an explicit snapshot/undo journal. This is an
implementation choice for Stage 3, not a spec choice, as long as the observable behavior equals the reference.

## 6. Processor, store and restore

- The finance store already keeps one key per entity. A v2 store also keeps the aggregate counters and `sequence/controls` in `meta`.
  The tree's internal nodes are **not persisted**: they are rebuilt on restore (O(n log n) once) and kept in memory; losing them is
  harmless and unverifiable-by-assumption data is never read back.
- Restore (R4): scan entities, rebuild buckets and the tree, compute `C(S)`, require it equals the head decision's `stateDigest`, recount
  aggregates and require equality with `meta`. A mismatch refuses activation with the existing `FINANCE_STORE_INVALID` code.
- `entityWrites` is replaced by the `Delta` the engine already produced (no diff).
- History entries, the entry chain, the decision record, the projector and the read routes are **unchanged**: the projector verifies the
  hash link and the input digest and never reads `stateDigest`.

## 7. Versioning and compatibility

- New domain strings: `calcify-finance-state-v2` (commitment), `calcify-finance-config-v2` (profile: caps, `D`), `calcify-finance-genesis-v2`.
  Because the genesis chain is derived from the config digest, a v2 decision log can never verify under a v1 ledger and the reverse (R5).
- The proto and the wire are unchanged; `FinanceStateVersion` selects the ledger. Finite P3's registered digests do not change.
- The capacity profile is a separate binding schema/version when it is registered; this document does not define that binding.

## 8. Failure and operational behavior

- A commitment mismatch at restore is a refusal, never a repair. An aggregate mismatch (running counter versus recount) at an audit interval
  halts the processor fail-closed (same family as the history-budget halt).
- Memory: entities are held in the in-memory structure for lookups; for 2^20 entities of about 150 bytes this is a few hundred MB and must be
  measured, with the store-view alternative (point lookups against the persistent store, section 10 Stage 4) as the mitigation.

## 9. Verification plan (this is the evidence the owner should require)

1. **Reference.** `StateCommitment.reference(state)`: a direct transcription of sections 3 and 4.1, no incrementality, written for clarity.
2. **Independent encoder.** A Python implementation of the same function with goldens for a fixed set of small states (empty, one of each
   entity class, a bucket collision, both zero and nonzero positions, UTF-8 and boundary-length identifiers). Kotlin must reproduce them.
3. **Differential, commitment.** The incremental tree equals the reference after *every* step of at least 10,000 randomized schedules,
   including permuted application orders reaching the same state.
4. **Differential, engine.** On the O3a profile (budgets inside the pins), the new `decide/apply` produces the same dispositions, legs,
   sequences, entity maps and aggregates as the frozen O3a ledger for the same inputs; the *commitment* differs by design and is compared
   against the reference. At least the 5,000-schedule oracle used for O3a is reused.
5. **Invariant equivalence.** After each step, running aggregates equal a full recount and `checkInvariants` passes.
6. **Mutation.** A mutant set for the tree (wrong bucket bits, missing domain prefix, unsorted bucket, stale sibling, dropped dirty node,
   off-by-one depth, scalars omitted) and for the incremental invariants, with a kill rate requirement matching O3a (all non-equivalent killed).
7. **Restore.** Property test: rebuild from entities equals the incrementally maintained root at every point; tampering with any entity,
   the set, or a scalar is refused.
8. **Scale evidence.** Cost per decision at 10^3, 10^4, 10^5, 10^6 entities on the real processor path including the store; reported, with
   thresholds from the owner (section 11), not claimed in advance.

## 10. Staging

| Stage | Output | Gate |
| --- | --- | --- |
| S0 | this document reviewed (rigorous, multi-lens) and accepted | review verdicts Ready |
| S1 | reference commitment, Python goldens, unit tests | goldens match, mutation on the reference encoder |
| S2 | incremental tree, differential vs reference | 10,000 schedules, mutants killed |
| S3 | O(touched) engine (`StateView`/`Delta`) with differential vs O3a | oracle equality, invariant equivalence |
| S4 | processor/store integration, restore rebuild, scale measurement | restore property tests; numbers recorded |

Each stage is its own PR, reviewed as a declared unit.

## 11. Decisions requested from the owner

1. **Profile caps** for the capacity profile: `maxEntities` (drives `D`), maximum accounts, whether the O3a pins (128/256/160/8) are lifted
   or replaced by rate-based limits.
2. **Acceptance thresholds** for S4 (decisions/s at which entity count, and the hardware class). The earlier 50,000 decisions/s figure was a
   placeholder for the finite fixture and was waived for P3.
3. **Adoption.** The O3a economics are unreserved gross DvP. If the product adopts reservation/credit (P-5, open until calibration), the entity
   model in section 3 changes, and this design should be revisited before S1. The commitment and transition-engine structure (sections 3 to 6)
   are intended to carry over; the invariant table (5.2) would not.

## 12. Open questions for review

- **Q1.** Tree versus accumulator (4.1 versus 4.2): is a size-cap-conditional proof acceptable for a unkeyed integrity commitment, or should the
  one-line SHA-256 argument win? Is the code and memory cost of the tree justified?
- **Q2.** Is excluding the decision chain head and including `sequence`/`controls` as scalars the right split, and should the *aggregates*
  (counts) also be in the commitment or are they derivable from the entities (they are, so they are excluded)?
- **Q3.** Is "zero-amount positions are entities" the right canonicalization given that a persistence layer may drop zeros?
- **Q4.** Is the bucket load assumption (`D = ceil(log2 maxEntities)`) adequate against adversarially chosen identifiers (an attacker who can
  choose account or execution ids can concentrate entities in one bucket, making that bucket O(m))? Identifier length is capped at 128 bytes
  and the input channel is operator/capture controlled, but this is a cost-attack surface to assess; salting the bucket hash with a
  per-binding secret is not possible for an unkeyed commitment (everyone must be able to recompute it).
- **Q5.** Should the audit interval for full invariant checks be part of the profile (deterministic) or operational (non-deterministic timing is
  fine because it can only halt, never change state)?
- **Q6.** Is the staging adequate, in particular building the O(touched) engine (S3) before the store integration (S4), and is differential
  equality with the *frozen* O3a on the shared profile a sufficient semantic oracle for a ledger whose capacity profile has no other oracle?
- **Q7.** Anything in section 1's claim ("an incremental digest alone would not raise the ceiling") that the reviewer can falsify.

## 13. Review ledger

| Date | Reviewer lens | Verdict | Disposition |
| --- | --- | --- | --- |
| (none yet) | | | |
