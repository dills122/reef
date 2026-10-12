# Calcify Finite P3: Option-B Determinism Spike Result (S1-B2)

Date: 2026-10-11. Timebox: 2h. Test: `services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/finance/FinanceOrderingSpikeTest.kt`.
Stacked on the wire-contract PR (`FinanceWire`, `calcify_finance.proto`).

## Question
Can a financial processor that consumes two input topics (CAPTURE and CONTROL) and positions each input in **its own
transactional history** produce results that depend only on that history, so that cross-topic arrival order never makes a
restore or replay ambiguous (O3 packet section 5, item 3)?

## What was built
A Processor API topology (`TopologyTestDriver`) with two sources, one processor, a `history` key-value store (position ->
`FinanceInputV1` bytes), and a `decisions` sink. For each consumed input the processor appends it at the next history
position, decides it with the O3a `FinanceLedger`, and forwards the `FinanceDecisionV1`. Restore replays the recorded history
through the pure ledger.

## Results (all pass)
| Claim | Evidence |
| --- | --- |
| Two arrival interleavings of the same logical inputs are both valid | Interleaving A funds then captures then settles (4 decisions, SETTLED); B captures, settles early (INSUFFICIENT_CASH), funds, then retries (5 decisions, SETTLED). Both chains verify. |
| Each interleaving is reproducible from its own recorded history | Replaying the stored history through a fresh ledger yields the identical `Decision` list for A and for B; A and B checksums differ, as they should. |
| Restore uses history, not re-consumption | A second driver preloaded only with the history store, then fed one more input, yields the same four checksums as an uninterrupted run. |
| Redelivery is idempotent | Re-feeding the same input yields no second decision (action-key dedup), while the redelivery still takes a history position. |

## Verdict
**GO for option B at the ordering/determinism level.** Order is defined by the history; the pure ledger makes every replay
byte-identical.

## Limits (not proven here; must be proven on the real broker in O3b)
1. `TopologyTestDriver` has no transactions: the claim that the consumer offsets, the history append and the decision
   publication commit atomically (so a crash between consume and record loses and duplicates nothing) rests on Kafka
   exactly-once semantics and needs a real-broker crash test (kill between poll and commit).
2. Restore here is a lazy replay on first input; real Streams restores the changelog before `init()`. The O3b processor must
   restore before processing and bind the cut `(captureOffset, controlOffset, historySequence, chain)` per the binding v2 spec.
3. History position uses `approximateNumEntries()` on an in-memory store, adequate for a single-threaded test; O3b must use an
   explicit persisted counter.
4. Redelivered inputs consume a history position. That is deterministic, but the history-record budget (296) must count them or
   O3b must dedup before positioning; this is an O3b design decision.

## Recommendation
Proceed with option B. Carry limits 1-4 into the O3b design review as acceptance items.
