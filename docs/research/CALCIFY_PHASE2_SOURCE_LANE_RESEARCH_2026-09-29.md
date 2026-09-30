# Calcify Phase 2 source-lane coverage research

Status: scoped code research; no architecture change approved or implementation made.
Decision owner: Calcify design review with product/matching ownership.
Question: Can a Phase 2 resolver always find both accepted order facts by scanning only the venue-event partition named by a verified trade commitment?

## Conclusion

**No unconditional same-partition guarantee exists in current matching code.** Intake routes commands by `(runId, venueSessionId, instrumentId)`, while matching keeps one book by `(venueSessionId, instrumentId)` and shares that book across partition processors in one engine process. A cross-run match is possible in the matching service. For a concrete pair of run IDs, intake routes the two orders to different partitions; the trade's venue batch is published on the crossing command's partition. Its resting order fact can therefore be on another partition. A Phase 2 index local to only the trade partition would miss it.

This is a matching scope/routing decision, not merely a choice between SQL and a faster key-value store. Avoid treating the initial Phase 2 forward-scan design as universal until the intended run/session matching boundary is explicit and enforced.

## Method and source versions

- Read codebase-memory graph for routing, matcher, publisher, replay, and Calcify Phase 1 symbols; checked index coverage on cited paths. `reef-main` graph at `2026-09-29T02:43:34Z` reported no recorded coverage gaps for examined source files. Phase 1 graph reported changed metadata for `CalcifyPipeline.kt`, so Phase 1 conclusions use direct `git show` of `origin/codex/calcify-phase1` at `684ac5e986ca9373222f74e59ff58ce9855e1917`.
- Main source was `origin/master` at `56b882d4cfbc7b78be27ddc63e7c99ae594a6b27`, reviewed from planning branch `27ec9a8a`. No matching or Calcify source edits were retained.
- Ran a temporary focused Go test in `services/matching-engine/internal/app`, then removed its file. Command: `go test ./internal/app -run '^TestCalcifyResearchCrossRunMatch$' -count=1 -v`. Fixture: buy `run-a`, sell `run-b`, both `session-1`/`AAPL`, distinct participant/account/order IDs, crossing quantity and price. Both submissions were accepted and sell result contained one trade. Test passed in 0.00s. This proves service behavior for the fixture, not a full broker/API deployment.
- Calculated routing from exact Kotlin SHA-256/first-8-byte signed-mask/modulus formula: at four partitions `run-a` maps to 0 and `run-b` to 1; at sixteen partitions they map to 12 and 9. This is a calculation from code, not a broker observation.

## Evidence

| ID | Kind | Finding | Source |
| --- | --- | --- | --- |
| E1 | Documented fact | Intake requires run, session, instrument, and hashes `runId|venueSessionId|instrumentId` for command partition. Builder does not validate a run-to-session ownership relation. | [`StreamCommandContracts.kt`](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/StreamCommandContracts.kt) |
| E2 | Documented fact | One matching `Service` is passed to every Kafka partition processor owned by an engine process. Book key contains session and instrument, not run; `submitOrder` uses that book. | [`main.go`](../../services/matching-engine/cmd/matching-engine/main.go), [`runner.go`](../../services/matching-engine/internal/streamdirect/runner.go), [`service.go`](../../services/matching-engine/internal/app/service.go) |
| E3 | Observation | Temporary two-order Go fixture produced a trade across `run-a` and `run-b` in one service. | Test command and fixture above; output recorded above. |
| E4 | Documented fact | Venue batch records ordered command outcomes and publishes explicitly to its processor's partition. Trade has buy/sell order IDs, but no run, participant, or account IDs. | [`processor.go`](../../services/matching-engine/internal/streamdirect/processor.go), [`kafka.go`](../../services/matching-engine/internal/streamdirect/kafka.go), [`order.go`](../../services/matching-engine/internal/domain/order.go) |
| E5 | Inference | With E1–E4 and the calculated partitions, a cross-run resting order can be recorded on partition 0 while trade is recorded on crossing order's partition 1. Trade-partition-only lookup then misses the resting order. Exact broker path was not executed. | E1–E4. |
| E6 | Documented fact | `acceptedOrderFact` may be attached even to a rejected `SubmitOrder` with complete command fields. Phase 2 must index only successful accepted outcomes, not every non-null `acceptedOrder`. | [`processor.go`](../../services/matching-engine/internal/streamdirect/processor.go) |
| E7 | Documented fact/inference | Engine restores committed commands one partition at a time into shared service. If one book can contain commands from multiple partitions, cross-partition live interleaving is not represented by a single partition order; replay outcome may differ. This failure mode needs a dedicated fixture. | [`runner.go`](../../services/matching-engine/internal/streamdirect/runner.go), [`processor.go`](../../services/matching-engine/internal/streamdirect/processor.go), [`kafka.go`](../../services/matching-engine/internal/streamdirect/kafka.go). [Kafka documents order only within one partition](https://kafka.apache.org/26/streams/architecture/). |
| E8 | Documented fact | Phase 1 extractor emits links in source-batch trade order to corresponding partition, and verifier publishes passing links to same partition transactionally. Stub verification checks link shape and partition, not source-order monotonicity or order ownership. | [`CalcifyContract.kt` on Phase 1 branch](https://github.com/dills122/reef/blob/684ac5e986ca9373222f74e59ff58ce9855e1917/services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyContract.kt), [`CalcifyPipeline.kt` on Phase 1 branch](https://github.com/dills122/reef/blob/684ac5e986ca9373222f74e59ff58ce9855e1917/services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyPipeline.kt) |

## Decision options

| Direction | Effect on Phase 2 local lookup | Cost or risk |
| --- | --- | --- |
| Make matching run-scoped: book and order ownership include run, consistent with present routing key | Both orders in a valid trade should share run/session/instrument and lane; forward-scan index becomes viable after proof | Changes matching semantics and recovery/snapshot keys; needs product approval and focused compatibility/replay tests |
| Require one run per venue session and enforce it at admission/lifecycle | Present book and routing keys align for accepted commands without changing book structure | Valid only if venue sessions are truly run-exclusive; enforcement and historical data behavior need proof |
| Intend venue-session-wide matching across runs: route solely by session/instrument | Book and routing keys align, including cross-run trades | Changes routing and partition history; run isolation and cutover become explicit design questions |
| Keep current cross-partition matching and compensate in Phase 2 | Needs cross-partition order-fact distribution or global state, not a simple local index | Does not itself solve matching replay/order ambiguity; high complexity and storage/latency risk at 10k commitments/s |

**Recommendation for discussion:** decide whether trades may cross runs within a venue session. If not, enforce run isolation at matching boundary (book key or run-exclusive session admission), then prove same-lane source coverage. If yes, align routing with book scope before designing Phase 2 lookup. Do not add a cross-partition join as a quiet post-match workaround for an ambiguous matching authority.

## Unknowns and next gate

- Full API and arena admission paths were not exhaustively audited for a global run/session uniqueness rule. The stream envelope builder and matching service do not enforce one; an existing external rule could narrow reachable traffic. Verify operationally before calling this a production incident.
- Exact broker end-to-end cross-run fixture, ownership rebalance, and replay divergence were not run. These are next narrow proofs after intended matching scope is decided.
- Source retention and Phase 2 index recovery remain separate open issues. A local index must either rebuild from complete available source or from a durable changelog/snapshot; this research does not pick a state-store implementation.
- Phase 1 verifier's normal path preserves per-partition order by construction, but a separately produced or corrupted verified link is not checked for monotonic source positions. Phase 2 must validate its own input sequence and source identity.

Gate before resolver implementation: accepted product/matching scope, enforced routing/book invariant, fixture covering resting order in earlier batch and both source partitions, replay equality, and one-lane order-fact coverage. Then confirm index key/lifetime and output contract.
