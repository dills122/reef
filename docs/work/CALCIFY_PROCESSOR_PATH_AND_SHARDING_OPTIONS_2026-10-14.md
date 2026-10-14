# Calcify capacity profile: processor-path savings, sharding options and hardware (analysis, 2026-10-14)

Status: analysis written after the end-to-end spikes ([CAL-FIN-P4 and P5](../THROUGHPUT_BASELINES.md), PR 536). **Not a decision and not a design**; it sets out what each option costs and what the evidence does and does not show, so the owner can choose. Written autonomously while the owner was away; nothing here was run beyond the tests named.

## 1. What was measured

The whole chain on one lane (real envelope parse, history chain and encodings, O(touched) ledger with the commitment on a second thread, persistent store, tailer, set-based projector, Postgres) runs at 11.4k to 13.3k inputs/s and projects 10.4k to 12.1k decisions/s: 1.6x to 1.9x short of the 20k decisions/s that 10,000 booked trades/s needs. About 68 microseconds per record are spent inside `process` (capture envelope parse 18 and content-digest check 16 per capture record, store puts 20, history entry 10, decide 9, output 7) plus 15 to 25 outside it. Sharded into disjoint-account lanes on a machine loaded by other work (load average 8 to 50), the median projected rate was 11.1k, 15.3k and 17.5k decisions/s at 1, 2 and 3 lanes. The chain costs about 290 to 330 microseconds of CPU per decision across the JVM, Redpanda and Postgres. All of this is on one shared laptop with a prototype ledger.

## 2. Savings inside one lane

| Lever | Measured | Semantic cost | Assessment |
| --- | --- | --- | --- |
| Reuse the input digest and the decision proto bytes (digest once, one `toProto`) | Included in the "free" variant, not separable; expected 3 to 5 microseconds per record | None | Do it; pure engineering. |
| Cached pointer store for `head` and `meta` (a second store with caching on, so repeated writes to the same two keys coalesce to one changelog record per commit) | "Free" variant showed no clear gain on the loaded host (medians 11.7k against 11.1k at one lane, within the spread) | None: EOS commits both stores atomically with the input offsets, and the caching only coalesces writes inside a commit interval | Cheap to adopt, gain unproven; the earlier "lean" variant (write `head` and `meta` every K records) saved about 8 microseconds in a quiet single-lane run but needs restore to treat the history tail as authoritative (see below). Prefer the cached store. |
| Capture content-digest check (16 microseconds per capture record, about 8 per record) | Verifier thread variant: no clear gain here (one more thread on a saturated host); the lean variant that skips it entirely reached 15.1k to 16.6k together with the head/meta change | Skipping loses detection of an envelope whose `contentDigest` does not match its content. Kafka's CRC covers transport and the topic ACL already restricts the writer (proven in S2); the digest is an unkeyed integrity value, so a malicious or buggy writer that writes a consistent digest passes either way. A verifier thread keeps detection but moves it after the decision: a mismatch halts the processor with up to a queue's worth of decisions already issued, the same posture as the audit in design 5.5 | Verifier thread is the conservative option; skipping needs an owner decision on whether the check defends against anything the ACL does not. |
| Capture envelope parse (18 microseconds per capture record) | Not reduced | None | Inherent to protobuf of about 1.2 KB; the envelope carries members the finance lane ignores (only `READY_EXECUTION` members map). A narrower envelope for the finance topic would help but changes the capture contract. |

On `head` and `meta` written every K records: restore today reads `meta` for the history position and chain head and `head` for the trusted last decision. If they lag the entity table by up to K-1 records, restore must derive them from the last history entry and the decision log instead (history entries are still written every record). Design 5.6's restore already reads the last `2K - 1` decisions, so the same scan could serve both, but it is a restore-spec change, not a free tweak.

None of the in-lane levers is large enough alone: together the best measured single lane was 15k to 16.6k inputs/s, short of 20k.

## 3. Sharding (design question D8)

A shard is an independent binding: its own topics, ledger, history, decision log, projection and digest chain. The spike's best case used disjoint accounts. The real constraint is that a booked trade debits and credits the cash and the security accounts of two participants.

Context from the arena contract: 100 participants, 64 instruments in the uniform profile and a single-hot-instrument variant, "credit before matching" with a prefunded pool (credit policy v1).

| Option | How trades map | Cost | Fit |
| --- | --- | --- | --- |
| **S1. Shard by instrument, per-shard cash sub-accounts** | Each instrument shard holds the security positions for its instrument and a cash sub-account per participant, funded by control inputs from the participant's main cash | No cross-shard trade at all; every shard is the existing single-lane design. Cash is partitioned, so a participant can hit INSUFFICIENT_CASH in one shard while rich in another unless a rebalancer moves cash; the credit policy's reserve-before-matching can allocate the sub-budgets (that is where the cash is decided), so the allocation rule belongs to the credit policy, not the ledger. The read model sums sub-accounts per participant. The hot-instrument profile defeats it (one shard carries all the work). | Natural for the 64-instrument profile; needs the credit policy owner. |
| **S2. Shard by participant** | A trade between participants in different shards needs both shards to change | Two-phase (pending then post, or linked transfers as in TigerBeetle) between shards: added latency, a coordinator or an ordered cross-shard log, partial-failure rules. | Correct for any workload but the most design and the most risk. |
| **S3. Central balance service, sharded trade capture** | Capture and order handling shard freely, balances stay in one ledger | Moves the bottleneck to the balance service, which is the lane measured here. | Does not help the binding term. |
| **S4. One lane, cheaper path** | Same as today | Section 2 levers plus a faster store path; measured ceiling about 15k to 16.6k on this host. | May suffice on dedicated hardware (section 4) but has no headroom beyond the target. |

Recommendation (to be confirmed by the owner): **prototype S1 on top of the existing spike**, because it reuses everything built and measured so far and turns the open design question into a credit-policy question (how cash is split across shards). Do not start it until the owner has said that the arena profile may carry per-instrument cash budgets; the hot-instrument variant remains unaddressed under S1.

## 4. Hardware

At 290 to 330 microseconds of CPU per decision, 20,000 decisions/s needs about 6 to 6.6 cores of this machine for the whole chain, load generator and Docker VM overhead included; the shared host delivered 15k to 19k decisions/s at three lanes because other work held the load average at 14 to 50. A dedicated host with 8 to 16 cores for the finance chain (Redpanda, Postgres and the processors) is therefore plausible for 20k/s with three shards, but this is arithmetic from CPU time, not a measurement on such a host.
The repo has the tooling for a short dedicated run (`infra/do-benchmark`, `scripts/dev/do-benchmark-host.sh`, a DigitalOcean droplet with a JDK needed for these tests). **I did not run it:** it costs money, needs the owner's approval, and the earlier live-recording checklist noted the harness does not install a JDK or sync the checkout. A measured run on a quiet dedicated host (one lane, then three) is the single most informative next measurement.

## 5. Suggested next steps

1. Owner: choose between S1 (prototype per-instrument cash sub-accounts) and staying on one lane while the section 2 levers are adopted; confirm whether the hot-instrument profile matters.
2. Owner: approve one dedicated-host measurement (cost and time-boxed), or keep working on the shared laptop and accept the spread.
3. Engineering with no decision needed: reuse the input digest and proto bytes, adopt the cached pointer store, and re-measure on the dedicated host; decide the digest-check option after reading the result.
4. Keep the commitment work (sharded trie, LtHash, K, undo ring) parked: the commitment is not the binding term on this evidence (CAL-FIN-P4).
