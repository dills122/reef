---
title: Bot SDK Quickstart
description: Write, register, and run a ReefBotV1 trading bot.
---

Reef bots are TypeScript classes extending `ReefBotV1`. Bots are tick-based: the harness owns scheduling, calls your bot, validates proposed actions, and only then turns approved actions into venue commands. Bots never create their own Reef clients, timers, or network connections in hosted mode.

## Minimal Bot

```ts
import { ReefBotV1, type BotContextV1, type BotActionV1 } from "@reef/bot-sdk";

export default class ExampleBot extends ReefBotV1 {
  static override metadata = {
    name: "example-bot",
    publisher: "Example Publisher",
    email: "bot-author@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    return [
      ctx.orders.placeLimit({
        instrumentId: "AAPL",
        side: "BUY",
        quantity: 10,
        limitPrice: snapshot.value.midPrice - 1,
      }),
    ];
  }
}
```

Required metadata: `name`, `publisher`, `email`, `version`, `sdkVersion`, `botApiVersion`. Optional: `description`, `tags`, `repository`, `license`, `homepage`.

## Lifecycle

- `onStart(ctx)` — runs once after metadata, config, scanning, and preflight succeed
- `onTick(ctx)` — runs on the configured schedule, returns proposed actions
- `onStop(ctx)` — runs once during graceful shutdown

## Local Checks

```bash
bun scripts/dev/bot-sdk-contract.test.mjs
bun scripts/dev/bot-sdk-register.mjs packages/bot-sdk/examples/simple-market-maker.ts
bun scripts/dev/bot-sdk-run.mjs packages/bot-sdk/examples/simple-market-maker.ts
bun scripts/dev/bot-sdk-test-bot.mjs packages/bot-sdk/examples/technical-indicator-strategy-bot.ts packages/bot-sdk/fixtures/aapl-technical-indicator.json --summary-only
```

`bot-sdk-test-bot.mjs` is the pre-merge hosted-simulation gate: it builds the hosted artifact, scans approved imports, runs the bot through SES against a fixture market, and exits nonzero when the bot should be marked `do_not_merge`. Confirm the report says `approved_for_merge` before submitting.

## Default Limits

- default tick interval `500ms`, minimum `250ms`
- max 10 individual order actions per tick (a two-sided quote counts as 2)
- max 20 data calls per tick
- max 10 trade commands per second

All limits are runtime configuration, not API constants — they can change per arena/run policy.

## Examples In The Repo

- `packages/bot-sdk/examples/simple-market-maker.ts` — minimal quoting bot
- `packages/bot-sdk/examples/refreshing-market-maker.ts` — cancel/replace using `ctx.orders.safe`
- `packages/bot-sdk/examples/multi-symbol-strategy-bot.ts` — multi-instrument Bollinger/momentum signals
- `packages/bot-sdk/examples/technical-indicator-strategy-bot.ts` — approved-package (`trading-signals`) example

## Learn More

- `docs/BOT_SDK_AUTHOR_GUIDE.md` — full author guide (source for this page)
- [Bot SDK Reference](../bot-sdk-reference/) — full `ctx` API surface and sandbox rules
- `docs/BOT_SDK_LIVE_SMOKE.md` — submitting generated commands against a local Reef stack
