# Matching Engine Hardening Research

## Purpose

Capture external matching-engine patterns, Reef's current rank against them, and the follow-up hardening stream for the Go matching engine.

This document is scoped to matching-engine realism and production shape. It does not move Postgres materialization, API durable acceptance, or projection work back into the matching hot path.

## External Reference Shape

### Continuous FIFO Books

Coinbase Exchange describes a continuous first-come, first-serve book where orders execute in price-time priority as received by the matching engine. It also states that trades execute at the price of the resting order, not the taker limit price.

Reef status:

- implemented hidden limit book with price-time priority
- implemented resting-price execution
- tested same-price FIFO on buy and sell sides
- tested best price before older worse price

Reference:

- [Coinbase Exchange Matching Engine](https://docs.cdp.coinbase.com/exchange/concepts/matching-engine)

### Matching Algorithm Profiles

CME Globex documents multiple product-level matching algorithms: FIFO, FIFO with lead-market-maker treatment, allocation, pro-rata, configurable, threshold pro-rata, and institutional prioritization. Its FIFO description uses price and time as the criteria, and notes that priority is lost when an order changes in ways such as quantity increase or price change.

Reef status:

- current engine is FIFO only
- modify currently behaves as cancel/replace and resets priority
- no per-instrument `matchAlgorithm` profile yet
- pro-rata and LMM behavior should be deferred until FIFO plus controls are fully proven

Reference:

- [CME Supported Matching Algorithms](https://www.cmegroup.com/confluence/display/EPICSANDBOX/Supported%2BMatching%2BAlgorithms)

### Self-Trade Prevention

Coinbase documents self-trade prevention where two orders from the same user do not fill one another, with taker-side behavior options such as decrement-and-cancel, cancel oldest, cancel newest, and cancel both.

Reef status:

- participant/account metadata exists on submit command payloads
- engine order records do not yet retain participant/account identity
- engine currently allows self-crossing orders to match

Reference:

- [Coinbase Exchange Matching Engine: Self-Trade Prevention](https://docs.cdp.coinbase.com/exchange/concepts/matching-engine#self-trade-prevention)

### Single-Writer In-Memory Core

LMAX architecture writeups describe an in-memory, event-sourced processor with business logic on a single thread and IO kept outside that processor. The Disruptor paper focuses on avoiding write contention and queue overhead by separating sequencing, storage, and consumption concerns.

Reef status:

- accepted direction is shard-local in-memory hot book
- one book key is owned by one deterministic lane
- durable command/event logs surround the engine instead of synchronous DB writes in the hot path
- follow-up proof needed: snapshot plus replay plus checksum restore

References:

- [The LMAX Architecture](https://martinfowler.com/articles/lmax.html)
- [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/disruptor.html)

### Operational Resilience And Controls

SEC Regulation SCI is not a direct implementation template for Reef, but it is a useful production-readiness lens: capacity, integrity, resiliency, availability, security, corrective action, testing, review, and records all matter for market systems.

Reef status:

- local stress harnesses and durable-event materializer evidence exist
- engine-specific recovery/failover evidence is still incomplete
- engine needs more explicit integrity controls and operator-facing telemetry

Reference:

- [SEC Regulation SCI final rule](https://www.sec.gov/files/rules/final/2014/34-73639.pdf)

## Current Rank

Reef's matching engine is a strong prototype and early production-shaped venue core.

Strong areas:

- FIFO limit-book implementation is locally owned and deterministic.
- Ordered price levels, FIFO queues per level, and order-id unlinking match the expected hot-book shape.
- In-memory shard-local direction avoids database writes and cross-thread mutation in the matching decision path.
- Durable command log to engine to durable event batch boundary is aligned with event-sourced recovery patterns.
- Correctness corpus now covers FIFO, price priority, partial fills, cancel residuals, terminal state guards, non-crossing rest, instrument isolation, and resting-price execution.

Gaps before calling it production-ready:

- no self-trade prevention
- no explicit session state gates
- no engine-level price collar, max quantity, or notional controls
- no implemented snapshot/replay/checksum recovery proof
- no formal lifecycle conformance matrix
- no per-instrument matching algorithm profile
- no engine ownership-transfer or failover proof
- limited operator/debug telemetry from engine-local book state

## Recommended Pivot

Do not broaden algorithms yet.

Target this sequence first:

```text
FIFO
  + lifecycle conformance
  + amend priority rules
  + self-trade prevention
  + session gates
  + integrity controls
  + snapshot/replay checksum proof
```

Only after that should Reef add `matchAlgorithm` profiles such as pro-rata or LMM-style allocation.

## Branch Follow-Up Items

### 1. Recovery Proof

Goal:

- implement local book snapshot and restore evidence for one or more instruments
- prove `snapshot at N + replay N+1..M` produces the same full-book checksum as uninterrupted processing

Acceptance:

- unit tests cover snapshot restore and replay equivalence
- checksum mismatch fails closed
- snapshot metadata includes book identity, sequence/offset fields, engine version, and checksum
- no external state-store dependency is introduced

### 2. Lifecycle Conformance Matrix

Goal:

- encode FIX-like order lifecycle expectations as table tests for submit, cancel, and modify paths

Acceptance:

- all terminal-state cancel/modify paths reject with stable codes
- partial-fill modify and cancel behavior is explicit
- rejected commands do not mutate order state or book state
- tests cover missing/invalid command fields and unknown orders

### 3. Amend Priority Rules

Goal:

- make modify priority policy explicit and tested

Acceptance:

- price change resets priority
- quantity increase resets priority
- quantity decrease policy is documented and tested
- same-price cancel/replace behavior remains deterministic

### 4. Self-Trade Prevention

Goal:

- prevent same participant/account from trading with itself when opposing orders cross

Acceptance:

- engine order records retain participant/account identity
- default STP mode is deterministic and documented
- tests cover same-account and different-account crossing orders
- event output records whether STP cancelled, decremented, or rejected an order

### 5. Session State Gates

Goal:

- introduce deterministic venue-session state at the engine boundary

Acceptance:

- `OPEN` accepts normal matching
- `HALTED` rejects or prevents new matching according to a documented policy
- `CLOSED` rejects new order entry and lifecycle changes
- tests prove no book mutation on session-state rejection

### 6. Market Integrity Controls

Goal:

- add deterministic engine-level hard controls for obvious invalid market actions

Acceptance:

- max order quantity
- optional max notional
- optional price collar around configured reference price
- stable reject codes and no book mutation on rejects

### 7. Golden Replay Corpus

Goal:

- add replayable command fixtures with expected event batches and checksums

Acceptance:

- fixture runner executes command NDJSON or JSON
- expected outcomes and final checksum are asserted
- fixtures include partial fills, modify/cancel, self-trade prevention, and session gates as those features land

### 8. Engine Observability

Goal:

- expose minimal engine-local health and book integrity signals without leaking hot-book internals into normal market-data reads

Acceptance:

- per shard/partition processed sequence
- current batch id and ack lag
- open order count and price-level count per side
- checksum/debug endpoint or admin-only inspection path

