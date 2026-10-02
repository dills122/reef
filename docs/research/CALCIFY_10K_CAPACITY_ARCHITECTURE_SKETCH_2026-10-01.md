# Calcify — 10,000/s-directed architecture sketch (WIP)

Status: working sketch, not an accepted design. No decision recorded in
`docs/DECISIONS.md`. Companion to
[`CALCIFY_PHASE1_2_ARCHITECTURE_REVIEW_2026-10-01.md`](CALCIFY_PHASE1_2_ARCHITECTURE_REVIEW_2026-10-01.md),
which this sketch assumes has been read — every "fix X first" reference
below points back to that review's numbered findings.

## Why this doc exists

The review found real correctness gaps (two merged-unfixed P1s, a run-scoping
gap in the matching engine, no real-infra CI) sitting underneath capacity
work that has never actually hit its own target under its own gates. Before
sketching what a 10k/s-capable Calcify looks like, it's worth being explicit
that this is a *shape*, not a plan to build more on the current foundation —
the prerequisite section below is load-bearing, not boilerplate.

This is deliberately a sketch: parts and mechanisms, not finished contracts.
Treat every section as "here's the shape that would make the 10k/s goal
reachable and provable," open to being wrong in the details.

## North star and current gap, stated numerically

Target: **10,000 durable resolved commitments/s, sustained 300s+, across
hot/spread/skew/aged workload profiles, surviving a broker/node failure
during the run, on a genuinely hosted (multi-node) deployment** — this is
the full target implied by Piece 6 of the original Phase 2 architecture
review plus a fault-tolerance requirement that was always implicit in
"production-ready" but never actually tested.

Current state, per the review's Finding 4:

| Dimension | Target | Best measured | Gap |
| --- | --- | --- | --- |
| Sustained rate, all gates passing | 10,000/s, 300s | 10,160.50/s but 310.73s (fails duration gate); no run has ever passed every gate | No conforming run exists yet |
| Workload profiles exercised at rate | hot + spread + skew + aged | hot only | 3 of 4 profiles never attempted at sustained rate |
| Topology | hosted, multi-node | single Docker host throughout | 100% gap — zero hosted evidence |
| Fault tolerance under load | survives broker/node loss at target rate | only tested on an idle/toy-scale system | 100% gap — never attempted |
| Real upstream intake feeding it | ~20,000 order commands/s | ~5,000/s clean ceiling via real HTTP intake | ~4x gap |
| Known correctness blockers | zero | two P1s shipped unfixed, plus the order-index gap | Must close first |

Any architecture sketch that doesn't change at least the last three rows
(topology, fault tolerance, upstream feed) is not actually a 10k/s-directed
design — it's a faster version of the same single-host diagnostic.

## Prerequisites — must land before this shape is worth building toward

In order, per the review's own recommended sequencing:

1. **Finding 1's two P1 fixes**, for real, with the negative tests already
   specified in the 2026-09-30 handoff.
2. **Finding 2's order-ID run-scoping fix** (pending the Option A/B decision
   — this sketch assumes Option A, run-scoped order IDs, since that's the
   assumption every other part of this sketch's indexing design depends on).
3. **Finding 3's real-infrastructure CI** (landing now, separately from this
   sketch).
4. **One clean, all-profile capacity run** on the *current* single-host
   topology, with 1-3 fixed, to establish an honest baseline before scaling
   out. Scaling a broken or unproven single lane horizontally just
   multiplies the problem.

Nothing below should start until 1-3 are closed; step 4 is the gate for
starting the lane-scaling work in this sketch.

## Proposed shape, part by part

### 1. One shared topic-identity registry, not an asymmetric one-off

Today, `CalcifySourceRegistration` binds and validates a UUID for exactly one
topic (source) in Postgres; the verified and output topics have no
equivalent (Finding 1's P1-B) and Phase 1's commitment/verified topics have
no identity binding at all. Generalize this into one `CalcifyTopicRegistry`
that every Calcify stage uses for every topic it depends on — source,
commitment, verified, and resolved/output alike — with the same
`(generation, topicName) -> topicId` binding and the same fail-closed
mismatch behavior `CalcifySourceRegistration` already has. This removes the
asymmetry that caused P1-B by construction, rather than patching the
asymmetry in one more place.

### 2. One shared fail-closed consumer guard

Today there are at least three different half-measures: `BrokerVenueSourceReader`'s
real `beginningOffsets()` check (source only), the misleadingly-named
`ResolverConsumerGate` (lane-fault pause/resume, not retention), and Phase
1's three consumers with no guard at all. Build one `CalcifyOffsetGuard`
component — `auto.offset.reset=none` plus an `onPartitionsAssigned` check
that `beginning <= checkpoint <= end` before releasing the partition for
consumption — and have every Calcify consumer (extractor, verifier, receipt,
resolver's verified-topic consumer) use the same one. This is Finding 1 and
Finding 5's remediation, generalized into infrastructure instead of two
separate point fixes.

### 3. Run-scoped order index as a precondition, not an assumption

The resolver's local accepted-order index already keys on
`(generation, orderId)` (`CalcifyResolverProcessor.orderKey`). Once the
matching engine's order-ID index is actually run-scoped (prerequisite 2
above), extend the resolver's key to include `runId` explicitly too, so the
resolver's own index inherits the real guarantee instead of an accidental
one. This is a small, mechanical follow-on once the engine side lands.

### 4. Name the worker-runtime decision that already got made implicitly

The original Phase 2 architecture review posed an explicit, never-closed
choice: **Candidate A** (a custom verified-led worker with its own
consumer/checkpoint/recovery machinery) versus **Candidate B** (a Kafka
Streams two-input topology joining source and verified as co-partitioned
inputs). What actually got built is neither, cleanly: it's Kafka Streams
managing state/changelog/output transactionally over a **single** input
(verified links), with a **custom, non-Streams-managed sequential reader**
(`BrokerVenueSourceReader`) pulled in by the processor on demand to walk the
source topic only as far as each verified link requires. Call this
**Candidate C** and write it up as the actual accepted design in
`docs/DECISIONS.md` (it doesn't have a decision entry today) — retroactively
documenting what was built, with its real tradeoffs:

- It inherits Streams' EOS transaction boundary for free (this review found
  that boundary to be genuinely solid — Finding 8), which Candidate A would
  have had to build by hand.
- It avoids Candidate B's "unbounded pending state while the verifier lags"
  risk, because the source reader only ever reads as far as a verified link
  already demands.
- It does **not** get Candidate A's smallest-possible normal-path state for
  free — the custom source reader is still a second, non-Streams-managed
  moving part whose crash/recovery story has to be reasoned about
  separately from the Streams-managed half (this is exactly where Finding
  1's P1-A lives: the seam between the two).
- The standby/recovery-worker handoff research the original review flagged
  as a follow-up (warm standbys needing a continuously replicated order
  index) is *partially* answered by Streams' own standby-replica mechanism
  (`NUM_STANDBY_REPLICAS_CONFIG`, already set to 1 by default) for the
  managed-state half, but says nothing about the custom source reader's
  half — a promoted standby still has to reconstruct `BrokerVenueSourceReader`
  state (which is cheap, since it's just a cursor position, not its own
  durable store) but this has never been tested under a live standby
  promotion specifically, only under cold-restart recovery.

Recommendation: keep Candidate C (it's a reasonable design and rebuilding it
as pure A or B now would be wasted work), but close the one real gap it has
— prove standby promotion under load, not just cold restart — as part of
the validation ladder below, and write the decision down so the next person
doesn't have to reverse-engineer which candidate got built.

### 5. Lane-scaling model to actually reach 10,000/s

Single-lane evidence (once correctness-fixed) is somewhere around the
10,000-10,600/s range already seen in the best (still gate-failing) runs —
call the real, re-measured number after prerequisites land **R_lane**. The
scaling model is horizontal: Kafka Streams already assigns one task per
partition, so reaching the target is `partitions >= ceil(10,000 / R_lane) +
headroom`, each partition independently owned and independently
fault-tolerant (a poisoned or failed lane doesn't block the others — this is
already true today, per the fault-suffix design in
`CalcifyResolverProcessor`). The two things this model actually needs that
don't exist yet:

- **A real multi-partition, multi-task measurement** — every sustained run
  to date is one hot lane. Horizontal scaling assumptions (linear-ish
  scaling across independent lanes) need to be *measured*, not assumed,
  especially for shared-resource contention (RocksDB block cache budgeted
  per task, broker-side partition count effects on batching/compression
  efficiency).
- **Upstream intake that can actually feed multiple lanes at once** — the
  ~4x gap between real intake (~5,000/s) and the ~20,000/s upstream commands
  that 10,000 trades/s needs is not a Calcify-side problem at all; it's a
  matching-engine/ingress capacity question (D-041's hosted Kafka-compatible
  producer path), orthogonal to everything else in this sketch, and it needs
  its own capacity work before Calcify's downstream number means anything
  end to end.

### 6. Validation ladder — the actual sequence of runs needed before "production-ready" means anything

Four runs, each a precondition for the next, none of which exist today in a
form that counts:

1. **Clean single-lane, all-profile, correctness-fixed run.** Hot, spread,
   skew, aged-state, one lane, prerequisites 1-3 done. This replaces the
   never-completed four-profile qualification from the original Phase 2
   plan.
2. **Multi-lane hosted run.** Genuinely multi-node (not three containers on
   one Docker Engine), real upstream intake (not synthetic fixture
   injection), partition count from the step-5 model, sustained at target
   rate.
3. **Fault-injection-under-load run.** Kill a broker and a resolver process
   *while step 2's load is actively flowing at or near target rate* and
   measure correctness (no duplicate/lost output) and recovery time. This
   exact combination — failure during active high-rate load, not on an idle
   system — has never been attempted for Calcify at any phase, and it's the
   single most important missing proof for anything called
   "production-ready."
4. **Aged-state run.** Same as step 2/3 but against a store that's been
   accumulating accepted-order facts for a realistic run lifetime, to
   validate the no-TTL index design's actual memory/disk growth and cache
   hit-rate assumptions against real numbers instead of the illustrative
   estimates in the original Piece 6.

## What this sketch deliberately does not decide

This is a shape for the matching/resolution plumbing only. It says nothing
about, and should not be read as implicitly answering, the Calcify decision
register's still-fully-open items — CAL-01 (product/obligation semantics),
CAL-05 (financial finality — what actually acknowledges settlement), CAL-06
(read contracts), CAL-07 (recovery/trust policy beyond what's sketched
above), CAL-08 (final physical storage authority), CAL-10 (cutover). Per the
review's Finding 9, closing at least CAL-05 and a draft of CAL-06 before
investing further in this plumbing is worth deciding explicitly, not by
default.

## Suggested sequencing, end to end

1. Prerequisites 1-3 (correctness + CI).
2. Validation step 1 (clean single-lane baseline) — this is also the
   "decide if Candidate C stays" checkpoint.
3. Write up Candidate C and the topic-registry/offset-guard generalizations
   as an actual `docs/DECISIONS.md` entry (D-0XX), since by this point
   they're proven, not speculative.
4. Revisit Finding 9: close CAL-05/CAL-06 drafts before continuing, or
   explicitly decide to keep betting on infrastructure-first.
5. Upstream intake capacity work (D-041 hosted path) in parallel — it gates
   validation steps 2-4 regardless of how fast the plumbing work above goes.
6. Validation steps 2-4 (multi-lane hosted, fault-under-load, aged-state),
   in that order, each gated on the previous passing cleanly.
7. Only then is "10,000/s, production-ready" an honest claim rather than a
   best-single-run number.
