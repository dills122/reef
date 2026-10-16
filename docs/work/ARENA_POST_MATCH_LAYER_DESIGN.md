# Arena post-match layer: a scorekeeper that folds the venue-event log

Date: 2026-10-16. Status: **design proposal for owner review; nothing is built. Direction set by the owner in chat on 2026-10-16: keep Calcify as it is for the simulator, and design a separate post-match layer that fits the bot arena.** Numbers are labelled measured or estimated.

## Why this exists
Calcify's post-match path (Phase 1 commitment extractor and verifier, Phase 2 managed resolver, capacity finance lanes, decisions topic, Postgres projection) is built for exactness: exactly-once decisions, hash-chained and committed state, RF3 durability. That is the right shape for a simulator that teaches how clearing and settlement work, and it is cheap enough to run locally. It is expensive for a game pod that exists for one tournament run: in the 5-minute 10k/s droplet run the stages after the matching engine took roughly 5 of the 16 cores (measured averages, cores of one CPU: resolver 0.9, capacity 1.3, boundary Postgres 2.5, plus the extractor and verifier, which are small) and the pod stored about 1.9 KB per trade in the broker and about 4.3 KB in Postgres.

The arena needs different things from post-match: per-bot results that score fairly, are replayable, and are cheap to produce. Everything else Calcify provides is optional there.

## What the arena needs from post-match
Taken from `docs/BOT_ARENA_SCORING_POLICY.md` and `docs/BOT_ARENA_MONETARY_POLICY.md` (both drafts):

1. **Per-bot cash and positions** from fills, with fees, rebates and penalties under the run's locked economic policy version.
2. **Headline score: final equity**, with open positions valued under the mode's declared rule (the scoring draft recommends liquidation value with a spread penalty for ranked runs).
3. **Risk metrics**: max drawdown (needs an equity time series), downside volatility, inventory concentration, gross exposure, turnover, realized versus unrealized split.
4. **Conduct metrics**: invalid-order rate, cancel/replace ratio, burstiness, repeated risk rejects. These come from the command stream, not from trades.
5. **Eligibility gates** that depend on post-match facts: accepted-command accounting gap, replay mismatch, missing run, policy or profile hashes.
6. **Determinism**: the same log, bot versions and policy versions give the same result, byte for byte.
7. **The existing result shape.** Analytics already projects a run export's `botResults[]` (`finalEquity`, `realizedPnl`, `maxDrawdown`, `failCount`, `commandCount`, `settlementScoreSummary`) in `BotRunPerformanceSummaryStore`. The arena layer should emit that shape so downstream ingestion does not change.

## What the arena does not need
- **Financial authority.** The matching engine has already matched; the layer records what happened and cannot refuse a settlement. Pre-trade risk gates belong at the venue boundary (D-045: bots use the normal command path with risk and abuse controls). A bot that ends with negative cash is a recorded risk-limit breach and, per policy, possibly a disqualification, not a rejected settlement. *Assumption to verify:* that the gateway enforces the pre-trade limits the scoring policy relies on; I have not checked that in code.
- **Exactly-once state machinery, changelogs, RF3 and hash-committed state.** The log is retained for the pod's life, and the result is a pure function of it.
- **A live Postgres projection.** Results are written once, at the end.

## Proposed design: the scorekeeper
A single small process per run, folding the venue-event log (`REEF_VENUE_EVENTS`, the engine's committed batches) into per-bot state.

**Inputs.** Accepted-order facts carry `participant_id` and `account_id`; a trade carries only `buy_order_id` and `sell_order_id` (`contracts/proto/order_execution.proto`, `calcify.proto`). The scorekeeper therefore keeps a small map from live order id to (account, side), filled from accepted-order events and dropped when an order is fully filled or cancelled. This replaces Phase 2's persistent resolver index: the map is bounded by open orders and needs no durability, because recovery replays the log.

**State.** Per bot: cash, per-instrument position and cost basis, fees, counters (orders, cancels, rejects), and an equity curve sampled on a deterministic axis. Size is bots times instruments, in the tens of kilobytes to megabytes. Nothing evicts and nothing spills.

**Cost per trade.** A map lookup, two position updates and a few counters, on the order of a microsecond; at 10k trades/s the fold itself is a small fraction of one core. Protobuf decoding and the Kafka consumer will cost more than the arithmetic. Estimate; to be measured in the spike.

**Recovery.** On crash, replay the log from offset zero. A run of 9 million trades is a matter of seconds to a minute of folding (estimate). There are no checkpoints, no changelog and no exactly-once producer. If the pod itself is lost the run is lost, which a disposable pod already accepts.

**Determinism and integrity.**
- The scorekeeper verifies each batch checksum as it reads (the engine already attaches `batch_checksum`; reusing the Phase 1 check is cheap) and records a rolling hash of the consumed log in the result.
- A replay gate: after the live fold, fold the exported log a second time offline and require an identical result hash. This is the "replay mismatch" eligibility gate made routine.
- An accounting gate: accepted orders counted in the log must equal the accepted count the intake acknowledged.
- Honest limit: this proves the result follows from the log; it does not prove the log is the engine's, beyond the batch checksums. A bot cannot touch the log (it only reaches the venue through the sandboxed path), and the pod is the trust boundary. Third-party verifiability comes from publishing the exported log and the result hash, not from online commitments.

**Time axis for drawdown.** Lanes are per instrument, and a bot's equity crosses instruments. Use the engine event timestamps and bucket the equity curve on a fixed wall-clock grid defined in the scoring policy (for example one sample per second of venue time), taking each lane's latest mark. The grid and the tie rules must be part of the scoring policy version so replays agree.

**Valuation.** Marks come from last-trade prices with a policy-defined haircut (spread penalty). A true liquidation value against the book needs the book at the end of the run, which the event log does not give directly. Options: (a) last trade with a haircut, available now; (b) the engine publishes a final book snapshot event at run end, a small engine change. Recommendation: start with (a), and treat (b) as a scoring-policy upgrade.

**Outputs.**
1. The run result: `botResults[]` in the existing shape, plus the result hash, the log hash and the policy versions.
2. An exported compressed copy of the event log, for audit and for the replay gate.
3. Optional equity curves and per-bot detail for replay UIs.
Both artifacts go to object storage (Cloudflare R2, already chosen in `docs/BOT_ARENA_PLAN.md`) and the result is pushed to the backbone's analytics API, which is the planned export step.

**Where conduct metrics come from.** Invalid orders, cancels and burstiness are in the command and outcome events of the same log (accepted, rejected, cancelled outcomes), so one fold produces them. Intake-level rejects that never reach the engine (rate limits, malformed requests) are not in the venue-event log and would come from the API's own counters; that gap is an open item.

## Cost comparison (estimates from the measured 5-minute run)

| Layer | Post-engine stages | Cores at 10k trades/s | Stored per trade |
| --- | --- | --- | --- |
| Calcify as built | extractor, verifier, resolver, capacity, decisions topic, projection Postgres | about 5 | about 1.9 KB broker plus about 4.3 KB Postgres |
| Calcify with the slim projection (PR 581) | same, Postgres writes reduced to positions and exceptions | about 2.5 to 3 (unmeasured) | Postgres small, broker unchanged |
| Scorekeeper (proposed) | one fold process, the venue-event topic only | well under 1 (unmeasured) | broker only; no Postgres per trade |

The pod's remaining cost at 10k/s would be the engine, the intake API and Redpanda, plus the bots. At the scale a game session is more likely to run (hundreds of trades per second) the pod could be a small droplet; that sizing needs the lower-rate measurements the earlier ladder did not take.

## Options considered
- **A. Keep Calcify for the arena.** Reuses everything and gives the strongest guarantees. Rejected as the default for a per-game pod: the guarantees exceed what scoring needs and the cost is several cores plus Postgres per run.
- **B. Calcify with the slim projection (PR 581).** A smaller change that still removes most of Postgres. Valid if the arena later wants Calcify's guarantees at lower cost. If the scorekeeper is adopted, B remains a simulator-side optimization and should not be a prerequisite for the arena.
- **C. The scorekeeper (this note).** Recommended for the arena: the smallest thing that meets the scoring policy's needs.

## Verification strategy: Calcify as the oracle
The scorekeeper's final cash and positions must equal Calcify's projected positions for the same run. The full-chain harness already produces both views from one venue-event log, so the cross-check costs one extra consumer and gives a strong correctness test before the scorekeeper is trusted. The oracle covers cash and positions only; equity valuation, drawdown and conduct metrics are tested against fixture logs with hand-computed answers.

## Open decisions
1. **Language and packaging.** Go (small binary, same toolchain as the engine, tens of megabytes) versus Kotlin in `platform-runtime` (reuses the protobuf classes and the analytics stores, larger footprint and slower start). The Phase 1 and Phase 2 stages are JVM stages of `platform-runtime`, so reuse of batch-checksum code favors Kotlin; footprint favors Go. Decide in the spike.
2. **Final book snapshot event** from the engine for liquidation valuation (option b above).
3. **Fee and rebate model.** The monetary policy defines maker and taker fees; where fees are applied (the engine, the intake, or the scorekeeper) is not settled in the code I read.
4. **Intake-level conduct counters** that never reach the venue-event log.
5. **Retiring B.** Whether to finish the slim projection profile, or park it behind the scorekeeper.

## Plan
1. **Spike (local, no cloud spend):** dump a venue-event log from a full-chain run, write the fold against it, measure events per second and memory, and check determinism by folding twice.
2. **Oracle check:** compare the fold's cash and positions with Calcify's projection for the same run.
3. **Result schema and policy fields**: lock the `botResults[]` mapping, the equity grid and the valuation rule into a scoring policy version.
4. **Harness profile:** a run-profile switch (`post_match = calcify | arena-scorekeeper | legacy`) so the same pod brings up either stack.
5. **Export step** (shared with the slim-projection plan): event log and result to object storage, result to analytics.
6. **Droplet verification** of a 10k/s run on the scorekeeper profile with the two-box harness; paid, needs the owner's explicit go with size and cost.

## What this does not claim
No scorekeeper number exists. The claims above about cost, fold speed and recovery time are estimates until the spike measures them. The design does not change the matching engine, the venue-event log or Calcify; it adds a second consumer path for a different purpose.
