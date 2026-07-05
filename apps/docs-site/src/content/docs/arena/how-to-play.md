---
title: How To Play
description: Player-facing walkthrough of a Bot Arena run — capital, data, actions, scoring.
banner:
  content: Reflects the current design target, not a live product. Public bot submissions are not open yet.
---

## The Shape Of A Run

You author one bot as a TypeScript class. The Reef harness owns scheduling and calls your bot on each tick; your bot returns proposed actions; the harness validates, rates, risk-checks, and only then turns approved actions into real venue commands.

1. Each run starts with a fixed capital base, risk limits, and a visible-data policy — same starting conditions for every bot in a fair-comparison mode.
2. The simulator drives a seeded, deterministic market. Same seed → same market conditions, every time.
3. On each tick, your bot sees a curated snapshot (see [What Your Bot Can See](#what-your-bot-can-see)) and returns 0+ actions.
4. Actions pass `schema validation -> arena permission/risk policy -> rate limit -> venue command gateway`. Rejections are first-class outcomes and can affect scoring.
5. At the end of the run, results feed a leaderboard for that game mode.

## What Your Bot Can See

Allowed: simulation timestamp/phase, game mode + visible parameters, instrument reference data, your own cash/equity/positions/holds, your own open orders and fills, public top-of-book/limited depth/last trade as the mode allows, current risk limits, remaining session time.

Not allowed: direct database or network access, internal event logs, future scenario schedule, other bots' private state, raw scoring internals, hidden matching-engine state.

## What Your Bot Can Do

Initial action set: submit limit order, submit market order (mode-permitting), modify order, cancel order, cancel all own open orders, no-op. See [Bot SDK Reference](/arena/bot-sdk-reference/) for the exact `ctx.orders` API.

## Scoring (Initial)

Final equity is the first headline leaderboard metric — easy to understand, fun to compete on. It will not be the only one: realized/unrealized PnL, max drawdown, return volatility, order-to-trade ratio, fill rate, invalid-action count, and consistency across seeds are all tracked from day one and will surface as richer leaderboards as the arena matures.

## Fairness & Safety Rules

- Same starting cash, same market snapshot, same instruments, same duration, same fee/slippage model within a competition.
- Per-bot rate limits on ticks, data calls, and trade actions; price collars, position/notional limits, and self-trade prevention apply.
- Repeated invalid actions or rule violations can freeze, quarantine, or ban a bot version — operators can disable a bot mid-run.

## Learn More

- [Bot SDK Quickstart](/arena/bot-sdk-quickstart/) — write your first bot
- `docs/BOT_ARENA_PLAN.md` — full fairness, scoring, and safety rules under design
