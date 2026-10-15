# Calcify capacity profile: the hot instrument

Date: 2026-10-14. Status: **design with measurements; decisions taken under the owner's delegation of 2026-10-14 (not a pivot), recorded here so they can be overturned.** Measured on a shared 10-core laptop in short single runs (CAL-FIN-P16 to P19); no capacity claim.

## The question
The capacity profile shards by instrument: the platform routes an instrument to one command partition (`StreamCommandEnvelopeBuilder.partition(run, session, instrument, count)`), one matching engine lane matches its book, the venue event goes to the same partition, the resolver writes the resolved context there, and that partition is one finance lane (one ledger, one Streams thread, one projection). Instruments spread over lanes, so uniform flow scales with the lane count. A **hot instrument** is the case where one instrument carries a large share of the trades: its lane carries that share alone, and sharding by instrument cannot spread it. The arena contract names the single hot-instrument variant without giving it a rate. This note answers: how hot can an instrument be before finance is the limit, what limits it, what can be done cheaply, and what the design is if one instrument must be split.

## The budget law
With a hottest lane carrying the fraction `s` of all trades and a lane ceiling `C` (trades per second one lane sustains), the total is at most `C / s`. At the contract's 10,000 booked trades per second the hottest lane must satisfy `s x 10,000 <= C`.

## What was measured
Four lanes, 64 instruments, resolved-context source, 300,000 trades, 200 accounts, pre-generated; `CAP_HOT_SHARE` sends that fraction of all trades to one instrument and the rest uniformly (CAL-FIN-P19).

| Trades on the hot instrument | Hot lane's share of all trades | Total projected decisions/s | Total booked trades/s |
| --- | --- | --- | --- |
| none (uniform) | 30 percent | 34,742 | 17,371 |
| 50 percent | 60 percent | 23,120 | 11,560 |
| **100 percent** | 100 percent | **16,393** | **8,197** |

- **One lane ceiling** (everything on one instrument, so a small state): about 16.4k decisions/s, **8.2k trades/s**. With 64 instruments' state in the one lane it is lower, 13.2k decisions/s (6.6k trades/s). The projection keeps pace with the processor in both (it finishes within one second of it), so the limit is the **processor thread**, not Postgres.
- **At the contract's 10,000 trades/s the hot lane may carry up to about 65 to 80 percent of the trades** (6.6k to 8.2k per lane). Half the flow on one instrument is inside that budget with room to spare (23.1k decisions/s measured against the 20k need).
- **Where a lane's processor time goes** (one lane, 64 instruments, 600,000 decisions, 13.3k decisions/s, about 75 microseconds each): ledger `decide` 37 (the incremental state commitment's hashing is most of it), store puts including head and meta 31 (about eleven changelog puts per booked trade), protobuf encode 4, decode 2.5, entity bytes 2, forward 1.
- **What did not help.** A caching store (repeated puts to hot position keys coalesce at the commit): put time fell 13 percent but the run was not faster (12.4k against 13.3k, inside the noise); not shipped. A bare decision table (no indexes or constraints): no change at one lane, because the projection is not the limit there. Both were run, not argued.

## What actually limits a single hot instrument
In order along the path: (1) the **matching engine's one book**, one thread, price-time priority: it cannot be split, and its single-lane rate at a hot instrument has not been measured (the compose-stack load generator uses one HTTP client and tops out near 1.4k orders/s, so it cannot reach it; a hot-instrument run with the Go simulator's load tool is the measurement); (2) the **finance lane**: 8.2k trades/s today on this laptop; (3) the projection, which keeps pace. Finance is the limit only if the engine's hot book exceeds about 8k trades/s. Nothing measured says it does.

## Options
**A. Accept the skew and make it visible (chosen, now).** Keep one lane per instrument group; state the skew budget (above); fail visibly. The lane stops on any unusable or refused captured input (built), and each lane's progress can be read from its checkpoint; export of per-lane lag is still to do (below).

**B. Raise the lane ceiling (chosen, in this order, each gated by its own measurement).**
1. *Lagged commitment on a second thread* (design 5.6): decision `s` carries the commitment of an earlier state, the hashing runs beside the deciding thread. Measured on the stand-in processor (CAL-FIN-P15): 12.9k to 17.9k inputs/s, **x1.39**. It removes about a third of `decide`. Cost: the decision chain's meaning changes (a lagged commitment, an undo ring, the halt rule of 5.6); it is the design the owner deferred behind measurement, and this note supplies the measurement that says a hot lane needs it only above about 8k trades/s.
2. *Fewer store puts per trade:* head and meta every K records instead of every record (restore derives them from the last decisions; design 5.6 already reads the last `2K - 1`), saving two of about eleven puts. Small, no semantic cost beyond the restore rule.
3. *A faster core:* the measured EPYC droplet ran one lane 1.35 to 1.55 times a Xeon of the same count; a hot instrument is a single-thread problem, so it buys directly.

Together these plausibly give 1.5 to 1.8 times the lane, that is roughly 12k to 15k trades/s for one instrument: above any rate the single book is likely to sustain.

**C. Split one instrument across finance workers (designed, not built; trigger below).** The facts that constrain any split:
- A booked trade changes four position rows: cash and shares of the buyer and of the seller. Two trades of the same instrument that share an account conflict on that account's rows, so a split by trade needs either **balances partitioned across the workers** or **cross-worker atomic moves**.
- *C1, sub-lanes by account hash with partitioned balances:* route a trade of the hot instrument to sub-lane `hash(buyer) mod k`. Each participant holds a separate cash and inventory balance per sub-lane (the S1 cash split again, now for shares too). A trade fails with INSUFFICIENT in its sub-lane although the participant is rich elsewhere. It needs a rebalance control between sub-lanes and an admission rule (credit before matching) that reserves per sub-lane. Implementable on the existing ledger with no new ledger code (sub-lane = another lane whose instrument set is the same instrument), but it changes gameplay semantics and needs the rebalance control, which is not built.
- *C2, capture and settlement split:* capturing (the obligation and its action key) touches no position and can be sharded by execution hash; settlement touches two accounts and cannot, so the position updates remain the serial part and nothing is gained.
- *C3, cross-worker two-phase moves* (pending then post, as in TigerBeetle's linked transfers): correct for any workload, the most design and latency, and a coordinator.

**D. Do nothing about the book.** If the engine's single book is the limit, finance work above is wasted; measure the book first.

## Decisions (defaults; overturn by editing)
1. **A now.** The skew budget is a stated property of the profile: the hottest lane may carry up to 65 percent of the trades at 10,000 trades/s on the measured hardware. Nothing else is built for the hot instrument yet.
2. **B1 is the first lever, and it is built only when a measurement shows a lane above its ceiling at the target** (hot share above about 65 percent, or a slower host). Until then it stays an option. B2 and B3 are free to adopt when convenient.
3. **C is not built.** Trigger: the engine's hot book is measured above the finance lane's ceiling after B1 to B3. Then build C1, with the rebalance control as its first piece, because without it a sub-lane can strand a participant.
4. **Measure the book before any of it:** one hot instrument through the real stack with a load generator that can exceed 8k trades/s (the Go `calcify-load` tool), reading the engine's own per-lane stats.

## To do
Per-lane lag and progress export (decisions processed, projected, oldest unprojected age) so a hot lane shows itself before it falls behind; the engine hot-book measurement; B1 only on the trigger above.
