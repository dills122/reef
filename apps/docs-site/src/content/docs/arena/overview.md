---
title: Bot Arena Overview
description: The work-in-progress trading-bot game built on top of Reef's simulation control plane.
banner:
  content: Planning/early-build stage. This is not an accepted final design — see Learn More for the living planning document.
---

Bot Arena is a tournament-style layer where submitted or built-in trading agents compete in deterministic simulated markets — while preserving Reef's core rules: bots use the same venue command paths as manual users, bot code is untrusted, runs are replayable and auditable, and game rules are config-driven, not hard-coded venue behavior.

## The Idea

Each bot starts with configured capital, risk limits, a visible-data policy, and an allowed action set. The simulator drives seeded market conditions and other participants. Bots observe an explicit public snapshot, emit order intents, and compete on mode-specific leaderboards.

Competitor bots are not passive backtest observers — their orders enter the same simulated venue, affect liquidity and price formation, and influence other bots' outcomes. Alongside them:

- operator-controlled market-maker bots (public behavior)
- operator-controlled background-traffic bots
- optional built-in benchmark bots for calibration

Planned game modes include an equity-sprint winner, momentum challenge, market-making trial, low-drawdown/survival league, execution-quality challenge, and an aggregate season across modes.

## Pipeline

```text
bot artifact -> sandbox runtime -> Bot SDK interface -> arena runtime protocol
  -> arena adapter/orchestrator -> schema validation -> arena risk gate
  -> venue command gateway -> platform runtime -> matching engine
  -> events, read models, analytics, leaderboard
```

The matching engine never knows whether an order came from a manual user, simulator persona, or arena bot — the arena adapter translates bot actions into normal venue commands with actor identity, command IDs, idempotency keys, trace/causation IDs, run ID, and bot version metadata.

## Where Things Stand

Built:

- `ReefBotV1` SDK contract and TypeScript authoring model (see [Bot SDK Quickstart](../bot-sdk-quickstart/))
- Registration/qualification harness, deterministic tick runner, SES-compatible hosted runner
- Arena control-plane registry: bot identity, bot versions, artifact hashes, approval lifecycle (`draft` → `submitted` → `checks_passed` → `approved` → `active` / `suspended` / `quarantined` / `banned` / `archived`), operator decisions, run records
- Bot-originated orders can flow through the real venue command boundary with bot client identity and pre-acceptance risk checks (a quarantined bot version is rejected before order acceptance)

Not yet built:

- Sandboxed execution at scale connected to a dedicated arena runtime protocol (gRPC/protobuf)
- Modular game-mode loading, scoring policies, leaderboards
- Public bot submission flow, UI (registry, run monitor, leaderboard, replay)

## Learn More

- `docs/BOT_ARENA_PLAN.md` — full product concept, rollout phases, open questions (this page's source)
- `docs/BOT_SDK_DESIGN.md` — SDK contract detail
- [How The Game Works](../how-the-game-works/) — architecture/pipeline detail on this site
- [How To Play](../how-to-play/) — player-facing walkthrough
