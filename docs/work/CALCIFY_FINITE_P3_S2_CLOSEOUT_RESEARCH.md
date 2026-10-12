# Finite P3 S2 close-out research

Status: research record closing the open ends listed in `CALCIFY_FINITE_P3_S2A_RESULT.md`. Written 2026-10-12. Each item states the
question, what was measured or read, the decision, and what code or spec change follows. Decisions marked **owner-approved** were
accepted in chat on 2026-10-12; the rest are recommendations.

## 1. Decisions confirmed by the owner

| Item | Decision |
| --- | --- |
| 50,000 decisions/s gate | **Waived for finite P3** (state is bounded to about 700 entities). The incremental state commitment (section 2) is a named prerequisite for any capacity claim. |
| "Auto attempt 1" | **Not implemented.** All repairs, including the first attempt, are operator `Settle` inputs on CONTROL (D4). The history stays purely input-driven. |
| v2 digest versus the capture lane digest | Resolved in section 3 below. |

## 2. Incremental state commitment (the 10k/s prerequisite)

**Question.** The O3a `decide` recomputes `stateDigest` over the whole state per decision. What does an incremental commitment cost,
and which design should replace it?

**Method.** `IncrementalCommitmentSpikeTest` (research spike, not product code) compares, per decision that touches 5 entities:
(a) hashing the whole state (a lower bound for today's cost: the real `stateBytes` also sorts), (b) an AdHash-style multiset
accumulator over 3,072 bits (subtract the old entity hash, add the new one), (c) a bucketed Merkle tree (2^16 buckets, dirty-node
recompute level by level). One JVM, warmed, SHA-256 from `MessageDigest`.

| Entities in state | whole-state hash | AdHash 3072 | Merkle 2^16 |
| ---: | ---: | ---: | ---: |
| 700 (finite P3 ceiling) | 17.7 us | 16.3 us | 19.3 us |
| 10,000 | 231.9 us | 13.1 us | 18.9 us |
| 100,000 | 2,316 us | 16.3 us | 24.6 us |

**Reading.** At the finite ceiling the three are equal, so nothing is lost by keeping the current digest for P3. Beyond a few thousand
entities the whole-state digest dominates everything else on the path (2.3 ms per decision at 100k entities, so a hard ceiling near
430 decisions/s on one thread). Both incremental designs are flat. AdHash is smaller and simpler; Merkle additionally gives
membership proofs, which only matter if O4 ever has to prove a single entity to a reader.

**Recommendation.** Adopt the AdHash multiset commitment when O3a is next opened, behind a new `calcify-finance-state-v2` string, with:
the accumulator updated by the same entity diff `FinanceStore.entityWrites` already computes; restore recomputes it once by scanning
entities; a differential test against the current digest on every schedule (equal state implies equal commitment under both, and any
single-entity change moves both); the modulus kept at 3,072 bits because additive multiset hashes over a small modulus fall to
generalized-birthday attacks. Not implemented here: it changes the O3a decision bytes and needs its own review.

## 3. v2 digest versus the capture lane digest (resolved)

**Finding.** `binding.digest` is stamped into every source command row by ingress and into every capture envelope
(`finite_binding_digest`, checked in `FiniteLifecycleContract` at four places). It is the digest of the registered opening, computed by
whichever version registered the run. For a v2 run the registered digest is the v2 digest, so ingress, capture and finance all carry
**the one v2 digest**. There is no second identity.

**Decision.** A v2 run has exactly one binding digest (the v2 digest). `FiniteOpeningV2` gets its own `toCaptureBinding()` that builds the
capture binding with the v2 digest; the v1-core `toCaptureBinding()` must never be called on a v2 opening, because it would hash the
v2 document with the v1 prefix and produce a digest nothing else knows. The finance store `meta.bindingDigest` and the processor
config use the same value. Follow-up code: add and test `FiniteOpeningV2.toCaptureBinding()` (the digest equals `digest`, every other
field equals the v1 mapping) and build `FinanceProcessorConfig` from a parsed v2 opening instead of fixtures.

## 4. P-5: operator authentication for the control writer

**Options.**

| Option | Authority is | Surface | Fit |
| --- | --- | --- | --- |
| A. CLI tool run by an operator, holding the `calcify_finite_control` SASL credential | possession of the control credential, held by no trading service | none (no HTTP route) | finite P3 is a bounded, operator-run proof; matches how the O2 supervisor provisions and runs |
| B. Admin HTTP route in platform-runtime behind `AdminSessionAuth` with the OPERATOR role, which writes through the control principal | an authenticated admin session | a new public-ish admin route | needed once funding and repair become product features |

**Decision recommended.** A for finite P3. The writer stamps `OPERATOR` itself; there is no caller-supplied authority anywhere because
there is no caller. The credential is never present in ingress, capture or the finance app (the ACL test in section 5 proves the broker
refuses them anyway). B is a product-phase change that wraps the same writer class; it adds no new authority model because the writer
already refuses anything but Fund and Settle and enforces the 8/168 write budgets. P-5 as the spec phrased it (a platform authority,
not a caller-controlled header) is satisfied by A trivially and by B through `AdminSessionAuth`.

## 5. Isolation matrix on a secured broker

`FinanceAclTest` runs a SASL/SCRAM Redpanda (v26.2.3, authorization on, one superuser, users created through `rpk` before SASL is
enabled) with the spec section 9 principals: `ingress`, `capture`, `control`, `finance`, `observer`, `matcher`. Established (all
asserted, one test, passing):

- **Authentication.** Anonymous and wrong-password clients are refused.
- **Write matrix** (4 principals-by-topic combinations per principal across COMMAND, CAPTURE, CONTROL, DECISIONS): each principal writes
  exactly the topic it owns and nothing else; `observer` and `matcher` write nothing.
- **Read matrix.** `ingress`, `capture` and `matcher` cannot read CONTROL or DECISIONS; `control` reads only CONTROL (the recovery read,
  no group) and cannot read CAPTURE or DECISIONS; `observer` reads CAPTURE, CONTROL and DECISIONS.
- **Transactional-id separation.** `finance` cannot `initTransactions` with the control writer's id (it cannot fence or abort the
  control writer); `control` cannot use a finance-prefixed id; `capture` and `ingress` cannot use either; each principal can use its own.
- **The finance Streams app runs end to end under only its own grants**, fed by a transactional control writer and an idempotent
  capture writer, and `FinanceRestoreCut` verifies the resulting evidence when read by the read-only `observer` through the
  production `FinanceEvidenceReader`.

Minimal grants the test found sufficient for `finance` (this is the provisioning list): topics CAPTURE and CONTROL Read+Describe;
FINANCE_DECISIONS Write+Describe; topics with prefix `<financeApplicationId>` All (the changelog and Streams internals); group prefix
`<financeApplicationId>` Read+Describe; transactional id prefix `<financeApplicationId>` Write+Describe. No cluster-level
IdempotentWrite was needed for the transactional finance app. `control`: CONTROL Write+Read+Describe and its literal transactional id
Write+Describe. `observer`: Read+Describe on the four topics and Describe on the group. The `capture` and `ingress` idempotent
producers were granted cluster IdempotentWrite in the test; whether Redpanda requires it was not separately tested.

## 6. Aborted tails and the offset rule (spec section 8.4 amendment)

**Finding (real broker).** With a transactional control writer, a transaction that aborts at the tail of CONTROL leaves an aborted
record plus a marker that no read-committed consumer sees and that is never in history. Streams commits its consumer position, and the
position moved **past** that aborted tail (committed 12 against last positioned offset 7 and read-committed end 12 on Redpanda
v26.2.3). The rule "`committedNext` must equal `lastRecorded + 1`" refuses this valid state as `FINANCE_OFFSET_AHEAD_OF_HISTORY`.
Redpanda also consumes extra offsets for transaction bookkeeping, so offset arithmetic by marker counting is not portable.

**Amended rule.** For each input topic: `lastRecorded + 1 <= committedNext <= readCommittedEnd`, **and** no record that a read-committed
consumer can see exists in `[lastRecorded + 1, committedNext)`. The evidence reader supplies "first visible offset at or after
`lastRecorded + 1`" from the broker, so the verifier never infers markers. A committed offset beyond an unprocessed visible record
remains `FINANCE_OFFSET_AHEAD_OF_HISTORY`; below history remains `FINANCE_HISTORY_AHEAD_OF_OFFSET`; absent or zero with history remains
`FINANCE_GROUP_OFFSETS_EXPIRED`. Implemented in `FinanceRestoreCut` with unit tests and a real-broker test of the aborted tail.

## 7. CI wiring for the broker tests

Hosted `calcify-tests` already uses Docker (compose Postgres) on `ubuntu-latest` with a 30-minute budget and runs 8 to 11 minutes.
Added to it: a step running `FinanceBrokerTest` and `FinanceAclTest` with `-PbrokerTests`, followed by an executed-not-skipped gate on
both result files (same pattern as the Postgres suites). Expected cost: Redpanda image pull plus about 2 to 4 minutes.

**Flake data.** Three consecutive local runs passed (SIGKILL loop: 3 kills per run, 3, 2 and 2 of them inside an open transaction).
Later runs failed at `createTopics` with "timed out waiting for a node assignment". Cause found: **the local Docker VM disk was 100%
full** (8.5 MB free of 150 GB; 188 local volumes, 128 GB, left by earlier `calcify-o2-*` proof runs), so Redpanda could not allocate
partitions. It is an environment fault, not a test or code fault; freeing 9 GB of regenerable build cache restored the runs. **Owner
action:** `docker volume ls` shows the old O2 volumes (127 GB reclaimable); I did not delete any volume because they may hold proof
evidence. Hosted CI runners start clean and are not affected.

Test hardening made while investigating: readiness now requires a successful topic create and delete (not only `describeCluster`), the
broker is addressed as `127.0.0.1` (no `localhost` IPv4/IPv6 ambiguity), and a readiness failure prints the container log tail. The
image is still pinned by tag; pin by digest when the owner chooses the production Redpanda version.

## 8. Operator procedure when the history bound halts the processor

At 312 entries the processor throws `FINANCE_HISTORY_BUDGET_EXCEEDED` and stays down by design (no entry, no offset commit). The run
is finished: finite P3 admits no more inputs. The procedure is: stop the app; read the evidence (decision log, history) with
`FinanceEvidenceReader`; verify with `FinanceRestoreCut`; archive; open a **new run** with a new source generation and a new binding
(fresh topics). Capacity is not recoverable inside a binding because the budgets are part of the registered digest.

## 9. Production activation verifier

`FinanceEvidenceReader` (main code) reads the evidence with a read-only principal and feeds `FinanceRestoreCut`; the test fixture now
uses it, so the production path is the exercised path. What remains: calling it from the activation witness builder (S2-A1 follow-up)
and surfacing the refusal code to the operator.
