---
title: Bot Arena Overview
description: The work-in-progress trading-bot game built on top of Reef's simulation control plane.
banner:
  content: Limited-preview stage. Same-repository testing works; the selected invite-only fork flow is not available yet, and open self-service intake is later.
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

Not ready for open release:

- Sandboxed execution at scale connected to a dedicated arena runtime protocol (gRPC/protobuf)
- Production-grade modular game-mode loading, final scoring policies, and replay UI
- Fork-safe external submission: trusted provisioning currently rejects forked PRs, and repository protection does not yet enforce the required human approval or trusted provisioning status
- External contributor onboarding, a named external-user lifecycle smoke, and a first public scored run on the deployed leaderboard

The chosen next release is an invite-only, fork-based preview. It still needs a
maintainer-gated trusted handoff that never gives PR-controlled code hosted
credentials or OIDC permission, so external submissions remain closed today.
Before that intake work begins, the next sprint separates Arena implementation,
routes, persistence, and deployment from the standalone Reef artifact and
proves both profiles against the same canonical venue facts.

## Learn More

- `docs/REEF_BOT_ARENA_SEPARATION_SPRINT.md` — next sprint for standalone Reef and the explicit Arena overlay
- `docs/BOT_ARENA_INVITE_PREVIEW_SPRINT.md` — subsequent fork admission, run cutoffs, policy, and recorded E2E campaign
- `docs/BOT_ARENA_RELEASE_READINESS.md` — current launch call, blockers, and go/no-go gates
- `docs/BOT_ARENA_PLAN.md` and `docs/BOT_ARENA_DO_SIMULATION_SOAK_CHECKLIST.md` — full product concept, rollout phases, and latest local gate evidence
- `docs/BOT_SDK_DESIGN.md` — SDK contract detail
- [How The Game Works](../how-the-game-works/) — architecture/pipeline detail on this site
- [How To Play](../how-to-play/) — player-facing walkthrough
