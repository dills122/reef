---
title: Current Status
description: What is actually built versus planned, as of the current implementation snapshot.
banner:
  content: This page is the fastest-changing page on the site. It reflects a point-in-time snapshot, not a live status feed.
---

Reef is past skeleton stage. You can run the local stack, submit orders, drive scenarios, inspect command status, read market data, and follow the first settlement evidence path. The UI and full post-trade workflow are still early, so this page keeps the marketing honest: built means implemented; planned means target shape.

## Built Today

Core venue path:

- Kotlin runtime with `/api/v1` submit, cancel, modify, and order lifecycle paths.
- Boundary checks for idempotency, auth/rate-limit hooks, abuse protection, command capture, and command status lookup.
- Go matching engine with hidden-book matching, partial fills, multi-match, cancel, modify, HTTP/gRPC, and direct-stream paths.
- Protobuf order-execution contracts shared across Kotlin and Go.

Inspection and replay:

- Runtime query surfaces for orders, trades, events, trace timelines, public trade tape, intraday bars, own-order reads, and data-availability inventory.
- Explicit runtime, boundary, auth, admin, orchestration, analytics, and command-log schemas through local migrations.
- Go simulator/load tester with persona sessions, deterministic replay checks, stress reports, local intake/materializer/projection gates, and DigitalOcean benchmark automation.
- Docker-first setup, reset, smoke, stress, replay, venue-event replay, and benchmark automation.
- Redpanda/Kafka-compatible direct-stream ingestion plus durable venue event batch materialization is the canonical `10k` venue-core baseline after the July 12 `soak-5m` DigitalOcean gate.
- Kafka-compatible publish retries handle retriable broker callback/send failures inside the existing publish-ack timeout; `202 Accepted` still requires durable producer acknowledgement.

Early product slices:

- Arena control plane: bot registry, bot-version approval lifecycle, operator decisions, run records, and bot-originated order flow through real venue risk checks.
- Settlement evidence: scenario facts API, obligation materializer, instant-post-trade instructions/attempts, cash/security leg outcomes, append-only ledger proof, `SETTLED` finality facts, break/repair paths, and replayable balance/proof reads.
- `stock-data` seed service: seed-once Tiingo/fake provider boundary, normalized snapshots, batch seed hash, Postgres or in-memory persistence, and `/v1/seed-snapshots`.
- Bot SDK live read clients for market data, bars, own orders, and data availability; hosted/local reports can distinguish `fixture` versus `live` read mode.
- Bot Arena local gates: persisted positive/negative smoke, static operator reports, and shared-time multi-instrument simulation proof with 5 active symbols and 18 bots.

## Still Planned

- Rich platform UI and operator workflows.
- Full post-trade lifecycle: allocation, confirmation, affirmation, clearing, novation, netting, and exception UI.
- Dedicated broad `account` schema and possible future `market_data` extraction beyond the current runtime-backed read slice.
- Broader analytics facts, dashboards, and reports beyond initial run export.
- Public bot submission flow, hosted sandbox execution at scale, production leaderboard service, and full scoring policies.
- Projection/read-model freshness under the same durable venue-event load. A short July 12 `2.5k rps` DigitalOcean projection gate passed; it is not yet the same claim as the `10k` venue-core materializer baseline.
- Complete scenario locking for `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`, even though local live assertions now exist for both paths.

## Active Architecture Direction

The high-throughput path is organized around one promise: if Reef says `202 Accepted`, the configured durable ingress producer acknowledged the command. After that, the matching engine consumes commands by partition, publishes durable venue event batches, and Postgres catches up asynchronously. The direct-stream plus venue-event-materializer path is now the canonical `10k` venue-core baseline; the active promotion work is proving projection/read-model freshness and replay under that same durable load. Speed work must not weaken audit, replay, or acceptance semantics.

The first deterministic scenarios now have clear target stories. P1 proves a hidden-cross trade lifecycle. P2 proves a settlement break and repair path. The assertion surfaces exist; the remaining work is making live gates prove those stories consistently from platform facts.

## Learn More

- `docs/CURRENT_STATUS.md` — the full, most current snapshot (source of truth for this page)
- `docs/DECISIONS.md` — accepted architecture decisions
- `docs/WORK_PLAN.md` — active execution ladder
- `docs/SCENARIO_CONTRACTS.md` and `docs/SCENARIO_ASSERTION_PLAN.md` — first-wave scenario gates
- `docs/SETTLEMENT_EXCEPTION_FACTS.md` and `docs/SETTLEMENT_CLEARING_STRATEGY.md` — current and target settlement slices
