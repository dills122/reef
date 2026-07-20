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
- Reef/Arena packaging separation: Reef-only artifact, routes, migrations, Compose profile, failure isolation, and P1 canonical equivalence are promoted; Arena is an optional artifact and overlay.
- Settlement evidence: scenario facts API, obligation materializer, instant-post-trade instructions/attempts, cash/security leg outcomes, append-only ledger proof, `SETTLED` finality facts, break/repair paths, and replayable balance/proof reads.
- `stock-data` seed service: seed-once Tiingo/fake provider boundary, normalized snapshots, batch seed hash, Postgres or in-memory persistence, and `/v1/seed-snapshots`.
- Bot SDK live read clients for market data, bars, own orders, and data availability; hosted/local reports can distinguish `fixture` versus `live` read mode.
- Bot Arena local gates: persisted positive/negative smoke, static operator reports, and shared-time multi-instrument simulation proof with 5 active symbols and 18 bots.
- Bot Arena hosted/control-plane slices: GitHub OAuth and Admin DB identity, public landing/leaderboard, owner-scoped bot config, container-isolated PR qualification, trusted OpenBao provisioning, and post-merge registry sync. A same-repository smoke bot passed the complete internal submission lifecycle.
- Invite admission: fork PRs persist as `pending_invite_review`; trusted maintainer approval binds immutable GitHub identity and exact head SHA before dispatching base-branch provisioning. Local workflow tests cover pending, approval, changed-SHA, and non-bot behavior.

## Still Planned

- Rich platform UI and operator workflows.
- Remaining post-trade depth and operator UX beyond the implemented allocation/confirmation/affirmation, clearing/novation, obligation/instruction/attempt, ledger, break/repair, and exception-queue facts.
- Dedicated broad `account` schema and possible future `market_data` extraction beyond the current runtime-backed read slice.
- Broader analytics facts, dashboards, and reports beyond initial run export.
- Named external-account proof of fork submission through approval,
  provisioning, merge, registry sync, owner config, eligibility, and a recorded
  preview run. The code path exists, but the preview is not yet open or
  self-service.
- Persisted `T-72h`/`T-48h`/`T-24h` eligibility and roster-lock enforcement,
  broader hosted hostile-code execution at scale, replay UI, and final scoring/economic policies.
- Longer or higher-rate full-projection soaks. Full projection passed a short `5k/60s` gate, but write amplification remains high and is not yet promoted to the `10k` venue-core materializer claim.
- Broader scenario campaigns beyond the now-promoted local P1/P2 locks and replay evidence.

## Active Architecture Direction

The high-throughput path is organized around one promise: if Reef says `202 Accepted`, the configured durable ingress producer acknowledged the command. After that, the matching engine consumes commands by partition, publishes durable venue event batches, and Postgres catches up asynchronously. The direct-stream plus venue-event-materializer path is the canonical `10k` venue-core baseline; full projection has a separate short `5k/60s` green gate, and the next scaling concern is lowering projection write amplification before longer or higher-rate promotion. Speed work must not weaken audit, replay, or acceptance semantics.

The first deterministic scenarios now have promoted target stories. P1 proves a hidden-cross trade lifecycle and P2 proves a settlement break and repair path, with direct-stream replay/checksum evidence kept separate from projection-freshness claims.

## Learn More

- `docs/CURRENT_STATUS.md` — the full, most current snapshot (source of truth for this page)
- `docs/BOT_ARENA_RELEASE_READINESS.md` — current external-submission release gate
- `docs/REEF_BOT_ARENA_SEPARATION_PROMOTION.md` — promoted standalone Reef/Arena boundary evidence
- `docs/BOT_ARENA_INVITE_PREVIEW_SPRINT.md` — active fork-preview and recorded-run work
- `docs/DECISIONS.md` — accepted architecture decisions
- `docs/WORK_PLAN.md` — active execution ladder
- `docs/SCENARIO_CONTRACTS.md` and `docs/SCENARIO_ASSERTION_PLAN.md` — first-wave scenario gates
- `docs/SETTLEMENT_EXCEPTION_FACTS.md` and `docs/SETTLEMENT_CLEARING_STRATEGY.md` — current and target settlement slices
