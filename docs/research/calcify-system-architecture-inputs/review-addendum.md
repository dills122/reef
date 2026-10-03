# Calcify RFC — Adversarial Review Addendum

**Date:** October 2, 2026  
**Status:** proposed corrections and acceptance gates, not an accepted ADR.  
**Reviewed baseline:** `0f4617a8b251a6fed5648b60c38e1c34837bc922`.  
**Targets unchanged:** 10,000 trades/s for 600 seconds; 7,500 trades/s for 900 seconds, preserving the declared replay contract.

## Executive decision

Carry forward the direction, and adopt Codex's proposal to bring a bounded financial-domain proof forward. Do not treat agreement between the reviews as verification of the completed architecture.

Keep immutable execution facts, explicit financial ownership, durable admission, short physical processing paths, complete financial decisions, and independent SQL views. Log-authoritative financial results remain a candidate to prove, not a fact that follows from selecting Kafka Streams.

## 1. Source identity is a contract conflict, not just a missing field

At the reviewed commit, `contracts/proto/README.md` explicitly says Calcify uses `(sourceGeneration, orderId)` per lane and rejects new acceptances reusing an ID even after matcher terminal retention. It also explicitly says the matcher supports `(runId, orderId)` and reuse across runs. The two interfaces do not admit the same input population.

`CalcifyResolverProcessor` matches its narrower contract: `orderKey` omits run; both trade-side lookups use it; `mergeAcceptance` rejects changed acceptance facts. This is fail-closed behavior, but it means a matcher-valid workload can stop Calcify.

Required decision:

- Support run-scoped acceptance identity and define its lifetime end to end.
- For the initial slice, either prohibit reuse of an internal order identity for the entire run and enforce this upstream, or support an explicit authoritative acceptance incarnation.
- Do not infer run from whichever accepted-order row happens to match an ID.
- Do not overwrite acceptance history with the latest reused ID or amended economics.
- If reuse is supported, process outcomes sequentially or retain a temporal/incarnation index. An all-acceptances-first batch pass followed by bare-ID trade lookup is unsafe under that new contract.
- Stable business identities cannot depend on broker batch cuts if independent seeded reconstruction is promised.

Tests: collocated runs with identical IDs; terminal reuse; reuse within one source batch; delayed old fill; modification causing a fill; restoration of all cases.

## 2. Define `decide` and `evolve`, not just `decide`

The RFC's decision object includes state changes, but its proposed durable envelope only lists events, postings, versions, intents, and disposition. It does not yet prove that all future-decision-relevant state is reconstructible.

The authoritative contract should establish:

```text
decision = decide(state, orderedInput, immutablePolicy)
nextState = evolve(state, decision)
commit(nextState changelog, decision, consumed input position)
```

`evolve` must reconstruct balances, reservations, obligations, pending workflows, attempts, logical time, policy activation, due-work state, external-effect state, and business deduplication state. Complete domain events are sufficient when their evolution rules cover these changes; duplicating opaque RocksDB implementation bytes is not required.

A settled journal alone is insufficient: cash 100 with reservation 80 and cash 100 with reservation 0 have identical settled journal balances but different outcomes for a new spend of 60.

Cold recovery must restore a certified consistent cut and resume from its exact next inputs, including staged-but-unfinished work. It must not reset a live processor and re-emit historical settlements to existing output topics.

Alternative: specify deterministic reconstruction from retained admissions plus exact executable versions and comparison against existing decisions. Do not call a result log independently authoritative for working-state reconstruction without demonstrating that relationship.

## 3. Business atomicity must precede broker atomicity

The kernel must validate the whole financial decision before mutating managed state. A typed insufficient-resource result must not leave earlier provisional debits in state.

Do not copy a generic `catch -> persist fault -> request commit` pattern into financial code after mutations. Kafka can atomically commit an internally inconsistent combination of state and a failure record if the application asks it to.

Expected business failure: produce a complete typed decision with only the changes allowed by policy. Unexpected implementation/storage/serialization failure: abort the uncommitted transaction, stop/recover through the framework, and do not convert the failure into an ordinary completed business action.

Tests must inject failure after each mutation/serialization/forward boundary, not only kill the process immediately before or after a normal commit. Include several decisions batched in one transaction.

## 4. Causal gates require safety, liveness, and a stated isolation limit

A dependency is not merely 'same source slice'. Specify the actual scope required by the financial action: order incarnation, prior fills, reservation effects, applicable revisions, allocation state, or policy-defined source order.

Where a validated manifest proves that a delayed fact belongs only to domain A, B may advance if all of B's own dependencies and arbitration rules are satisfied. Where routing, identity, or slice coverage cannot be trusted, do not pretend the corruption can safely be isolated to A.

A source batch, Kafka partition, Streams thread, and financial domain are different failure boundaries. Sharing any of them can constrain isolation. Version 1 may deliberately pause a whole source lane; describe and test that limit instead of promising unconditional healthy-domain progress.

Liveness requirement: reaching the pending-memory bound must not prevent the gate from reading the dependencies or seal required to drain it. Bound slice fanout/bytes and prove a progress path for closure/dependency records. A full queue plus a closure marker later on the same paused input is a deterministic deadlock.

Consumer pause is not upstream throttling. Specify a separate ingress-health signal, hysteresis, reaction budget, and reserved capacity for already accepted/matched work. Existing executions cannot be dropped during overload.

Do not require consecutive integer Kafka offsets or infer trade counts from offset distance: transaction markers and aborted messages create legitimate gaps. Use explicit application membership/sequence coverage.

## 5. Determinism is a closed-loop contract

Freeze distinct claims:

1. Recovery of an existing committed history.
2. Recompute the same decisions from the same ordered admissions.
3. Recreate the same simulation from a seed.

The third additionally requires reproducible strategy observations, logical ticks, participant ordering, closure frontiers, external responses, and source outcomes. A deterministic financial reducer cannot repair different trading decisions that arose because bots observed different projection progress.

Internal continuations also need order. Consider admitted `ClockAdvanced` followed by `FundAccount`: if the clock starts two due settlement attempts, a CPU-budget yield must not let funding overtake the second attempt in one run but not another.

Choose an explicit rule: complete a logical work phase before admitting later state-changing inputs, or retain a canonical continuation/admission order. A wall-clock callback may schedule execution but must not silently choose financial order.

Test invariance under processor yield budgets, source transaction batch sizes, legal input delivery interleavings, restore, and projection scheduling. Full seed-only invariance cannot be claimed until the simulator/source side satisfies its part of the contract.

## 6. Closed financial domains need a capacity escape criterion

Under a single-owner/no-cross-domain-coordination design, domain membership is transitively closed over atomic financial operations. A trade between A and B joins their resources; another between B and C connects C too. A broad market may therefore form one domain, not many account-sized domains.

One isolated run per domain is a reasonable first experiment. It is not a proof of horizontal scale within one busy market. Define the maximum intended hot-domain workload and state age. Fixed routing and one approved application namespace must fence active writers during takeover; no concurrent legacy/new writers.

Failure of the hot-domain proof should trigger a measured runtime/storage decision, not unplanned instrument/account sharding. A single owner is a chosen implementation of serialization, not a universal requirement that excludes transactional database designs.

## 7. Recovery guarantees must state their failure model

Current Redpanda documentation explicitly excludes transactional writes and consumer offsets from ordinary write caching. The earlier blanket caching criticism should be withdrawn for those paths; pin the deployed version and test it.

Do not extend the normal broker-transaction guarantee to administrative topic deletion or arbitrary disaster recovery. The current Redpanda transaction page says deleted partitions may be removed from an in-flight transaction and that remote recovery does not guarantee transaction atomicity.

Separate:

- ordinary process/broker loss with registered histories intact;
- missing local state with compatible changelog intact;
- missing changelog with a verified complete reconstruction source;
- cluster/topic recovery with coordinated, certified history cuts;
- missing required history, which must refuse continuation.

Restrict deletion/recreation of active authoritative topics. A periodic UUID check is not an atomic cross-topic fencing protocol. A compacted working-state changelog is not a complete financial audit archive.

## 8. Archive implementation can be optional; history coverage cannot

Adopt Codex's correction to the initial sequence. An isolated bounded run can rely on sufficiently retained broker history without an archive service.

Define maximum run lifetime, unresolved-work lifetime, post-close replay window, maximum outage/catch-up allowance, time and byte retention limits, and safety margin. Shared topics must cover the oldest dependency of all active runs. A source acceptance can remain necessary long after it was produced.

For a promise beyond broker retention, use a verified archive or another explicitly authoritative complete history source. Do not delete history merely because a run stopped producing trades. State retirement and historical record deletion are separate decisions.

## 9. Terminal verification disposition is not financial discharge

A duplicate pointer may be recognized as duplicate. An unresolvable or contradictory source fact may require quarantine or lane failure. A valid executed trade that cannot clear or settle remains an execution plus an exception/pending obligation.

No terminal status at the source gateway may silently discard that financial liability. Coverage can close a processing attempt without establishing settlement. Define these dispositions explicitly before adding verification policy beyond the current structural stub.

## 10. Revised proof sequence

### Track A: baseline contracts and source repairs

Close run/acceptance identity, bootstrap/history semantics, and deterministic-input assumptions. Keep fixes separate from speculative source prefetch or broad lifecycle expansion. Pure kernel experimentation can proceed against complete explicit fixtures in parallel; it does not establish live source correctness.

### Proof 1: pure kernel and reconstruction

Use a closed constrained domain, exact units, a declared minimal instant profile, ordered funding, complete decisions, and an independent small oracle. Include success, both insufficiency cases, retries, same-ID conflicts, new attempts after repair, cold-state reconstruction, and the clock-continuation case.

### Proof 2: real broker transactional runtime

Use the production adapter and pinned Redpanda/client versions, real durability settings, and independent read-committed observers. Test failure after state mutation, before forwarding, before commit, after commit acknowledgment ambiguity, and stale-owner promotion. Reconstruct without local state; separately test the declared missing-changelog recovery path. Keep existing output history intact during recovery tests.

### Proof 3: first usable vertical slice

Connect real venue facts through resolution/admission, the financial runtime, one transactionally materialized SQL trade/account bundle, existing API adapters, and one repair path. Test projection crash after SQL commit before broker checkpoint. Source/lifecycle dependencies and a history-retention manifest are required; a full archive product is not.

### Proof 4: the hot-domain capacity decision

Measure one hot financial domain under real shared-account contention, realistic churn and aged state, actual journal/event payloads, required API reads, and injected recovery. Keep declared latency/freshness objectives fixed. Distinguish component qualification from the full 10k/600s and 7.5k/900s target.

Then expand realistic workflows, netting, partial settlement, integrations, archive, and operations section by section. Do not claim those modules complete because their event names exist in a spike.

## Sources inspected

Repository references are at the pinned SHA above:

- `contracts/proto/README.md`: conflicting order-identity lifetimes.
- `services/matching-engine/internal/app/order_index.go`: run-scoped index and terminal reservation removal.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyResolverProcessor.kt`: lookups, state retention, synchronous drain, fault-commit pattern.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/MatchContextResolver.kt`: acceptance merge and batch order processing.
- `services/platform-runtime/src/main/java/com/reef/platform/calcify/ResolverConsumerGate.java`: missing-checkpoint branch.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/BrokerVenueSourceReader.kt`: synchronous source and metadata calls.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyPipeline.kt`: per-source-record extractor transaction.
- `docs/work/CALCIFY_DISCOVERY.md`: optional archive and bounded replay contract; physical authority remains a decision.
- Original attachment `reef-calcify-system-design-rfc.md`: proposed contracts, phase sequence, retirement, and result envelope.

Primary platform documentation checked October 2, 2026:

- Kafka Streams concepts: https://kafka.apache.org/43/streams/core-concepts/
- Kafka Streams architecture: https://kafka.apache.org/43/streams/architecture/
- Kafka Processor API: https://kafka.apache.org/43/streams/developer-guide/processor-api/
- Kafka reset tool: https://kafka.apache.org/43/streams/developer-guide/app-reset-tool/
- Kafka consumer API, transactional reads and offset gaps: https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html
- Apache KIP-447, per-thread EOS producer and fencing: https://cwiki.apache.org/confluence/display/KAFKA/KIP-447%3A+Producer+scalability+for+exactly+once+semantics
- Redpanda write caching: https://docs.redpanda.com/streaming/current/develop/manage-topics/config-topics/
- Redpanda transactions and recovery limitations: https://docs.redpanda.com/streaming/current/develop/transactions/

No deployed Reef capacity, full integration correctness, or regulatory/real-money settlement qualification is inferred from these references or the accompanying models.
