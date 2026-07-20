---
title: Bot Arena Overview
description: The work-in-progress trading-bot game built on top of Reef's simulation control plane.
banner:
  content: Limited-preview stage. Fork admission and trusted approval are implemented, but the named external-account E2E is still required; open self-service intake is later.
---

Bot Arena is the game layer on top of Reef. Bots compete in deterministic simulated markets, but their orders still enter the same venue as everyone else. That keeps the arena more interesting than a backtest: bot behavior can change liquidity, fills, and outcomes for other bots.

## The Idea

Each bot starts with capital, risk limits, visible market data, and an allowed action set. The simulator drives seeded market conditions and background participants. Bots observe the public snapshot, choose orders, and compete under a scoring mode.

Competitor bots are not passive observers. Their orders enter the simulated venue, affect liquidity and price formation, and influence other bots' outcomes. Alongside them:

- operator-controlled market-maker bots (public behavior)
- operator-controlled background-traffic bots
- optional built-in benchmark bots for calibration

Current local modes cover equity-sprint-style runs and multi-instrument liquidity/provider tuning. Planned modes include a momentum challenge, market-making trial, low-drawdown/survival league, execution-quality challenge, and an aggregate season across modes.

## Pipeline

```text
bot artifact -> sandbox runtime -> Bot SDK interface -> arena runtime protocol
  -> arena adapter/orchestrator -> schema validation -> arena risk gate
  -> venue command gateway -> platform runtime -> matching engine
  -> events, read models, analytics, leaderboard
```

The matching engine does not need special arena behavior. The arena adapter turns bot actions into normal venue commands with actor identity, command IDs, idempotency keys, trace/causation IDs, run ID, and bot version metadata.

## Where Things Stand

Built:

- `ReefBotV1` SDK contract and TypeScript authoring model (see [Bot SDK Quickstart](../bot-sdk-quickstart/))
- Registration/qualification harness, deterministic tick runner, SES-compatible hosted runner, and opt-in live read clients for market data, bars, own orders, and data availability
- Arena control-plane registry: bot identity, bot versions, artifact hashes, approval lifecycle (`draft` → `submitted` → `checks_passed` → `approved` → `active` / `suspended` / `quarantined` / `banned` / `archived`), operator decisions, run records
- Bot-originated orders can flow through the real venue command boundary with bot client identity and pre-acceptance risk checks (a quarantined bot version is rejected before order acceptance)
- Local positive/negative persisted smoke gates, static operator report rendering, report-index rendering, hosted/local report `readMode` evidence, and a shared-time multi-instrument simulation proof with 5 active symbols and 18 bots
- GitHub PR validation, container-isolated fixture qualification, trusted OpenBao provisioning, post-merge registry sync, GitHub OAuth/Admin DB identity, owner-scoped bot config, and a deployed public leaderboard; the same-repository smoke bot has passed this lifecycle end to end
- A separate Arena control-plane artifact and Compose overlay, with Reef-only route absence/storage independence and Arena-enabled route/persistence gates
- Fork submissions persist as `pending_invite_review`; SHA-bound maintainer approval automatically dispatches a trusted base-branch provisioning workflow without executing fork code

Not ready for open release:

- Sandboxed execution at scale connected to a dedicated arena runtime protocol (gRPC/protobuf)
- Production-grade modular game-mode loading, final scoring policies, and replay UI
- Named external-account lifecycle proof for the implemented fork workflow, plus denial/update/remove drills
- External contributor onboarding, persisted cutoff/roster enforcement, and a first public scored run on the deployed leaderboard

The chosen next release is an invite-only, fork-based preview. The trusted
handoff is implemented, and Reef/Arena separation is promoted. External
submissions remain unadvertised until a named external account completes the
flow and the cutoff, roster, hosted-run, replay, and scoring evidence is green.

## Learn More

- `docs/REEF_BOT_ARENA_SEPARATION_PROMOTION.md` — promoted standalone Reef and explicit Arena-overlay evidence
- `docs/BOT_ARENA_INVITE_PREVIEW_SPRINT.md` — active fork admission, run cutoffs, policy, and recorded E2E campaign
- `docs/BOT_ARENA_RELEASE_READINESS.md` — current launch call, blockers, and go/no-go gates
- `docs/BOT_ARENA_PLAN.md` and `docs/BOT_ARENA_DO_SIMULATION_SOAK_CHECKLIST.md` — full product concept, rollout phases, and latest local gate evidence
- `docs/BOT_SDK_DESIGN.md` — SDK contract detail
- [How The Game Works](../how-the-game-works/) — architecture/pipeline detail on this site
- [How To Play](../how-to-play/) — player-facing walkthrough
