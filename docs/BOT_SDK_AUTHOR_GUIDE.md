# Bot SDK Author Guide

## Start Here

Reef bots are TypeScript classes that extend `ReefBotV1`.

V1 bots are tick-based. The Reef harness owns scheduling, calls your bot, validates proposed actions, and only then can approved actions become venue commands.

Bots do not create their own Reef clients, network connections, timers, or background loops in hosted mode.

## File Shape

Create one TypeScript file with a unique filename:

```ts
import { ReefBotV1, type BotActionV1, type BotContextV1 } from "@reef/bot-sdk";

export default class MyBot extends ReefBotV1 {
  static override metadata = {
    name: "my-bot",
    publisher: "My Publisher",
    email: "me@example.com",
    version: "1.0.0",
    sdkVersion: "1.0.0",
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

## Required Metadata

- `name`: lowercase bot name, 3-64 characters, using letters, numbers, dots, underscores, or hyphens
- `publisher`: person, organization, or team publishing the bot
- `email`: basic contact email
- `version`: bot version, semver-like, for example `1.0.0`
- `sdkVersion`: SDK version targeted by the bot
- `botApiVersion`: `v1`

Optional metadata can include `description`, `tags`, `repository`, `license`, and `homepage`.

## Lifecycle

Available lifecycle methods:

- `onStart(ctx)`: optional setup hook
- `onTick(ctx)`: required decision hook
- `onStop(ctx)`: optional shutdown hook

`onTick` returns proposed actions. It should not directly call Reef HTTP APIs.

## SDK APIs

Order reads:

```ts
await ctx.orders.current();
await ctx.orders.history({ instrumentId: "AAPL", limit: 50 });
```

Order actions:

```ts
ctx.orders.placeLimit({ instrumentId: "AAPL", side: "BUY", quantity: 10, limitPrice: 99.5 });
ctx.orders.placeMarket({ instrumentId: "AAPL", side: "SELL", quantity: 10 });
ctx.orders.modify({ orderId: "order-1", instrumentId: "AAPL", quantity: 5, limitPrice: 100 });
ctx.orders.cancel({ orderId: "order-1", instrumentId: "AAPL" });
ctx.orders.cancelAll("AAPL");
```

Safe order actions:

```ts
const cancel = await ctx.orders.safe.cancel({ orderId: "order-1", instrumentId: "AAPL" });
if (cancel.ok) {
  return [cancel.value];
}
```

Market data:

```ts
await ctx.marketData.snapshot("AAPL");
```

Historical data:

```ts
await ctx.historical.intradayBars({
  instrumentId: "AAPL",
  interval: "1m",
  start: "2026-07-04T14:00:00.000Z",
  end: "2026-07-04T15:00:00.000Z",
});
```

Private config:

```ts
const maxInventory = ctx.config.number("maxInventory");
const strategyName = ctx.config.optionalString("strategyName");
```

Private config is loaded before the run, stored outside bot source, and exposed in memory through `ctx.config`.

## Limits

Current defaults are runtime configuration, not API constants:

- default tick interval: `500ms`
- minimum tick interval: `250ms`
- max individual order actions per tick: `10`
- max data calls per tick: `20`
- max trade commands per second: `10`

A batch counts by individual child orders. For example, a two-sided quote counts as two order actions.

## Qualification

Run local checks:

```bash
bun scripts/dev/bot-sdk-contract.test.mjs
bun scripts/dev/bot-sdk-register.mjs packages/bot-sdk/examples/simple-market-maker.ts
```

Registration checks:

- unique filename registry
- required metadata
- basic email syntax
- semver-like versions
- forbidden hosted APIs
- lifecycle shape
- deterministic fixture qualification
- configured action and API limits

Failed bots are marked `do_not_merge` with stable issue codes.

## Hosted Restrictions

Hosted v1 bots may not use:

- external network access
- direct filesystem access
- `setTimeout` or `setInterval`
- child processes
- worker threads
- direct Node built-in imports
- direct Reef HTTP, gRPC, or WebSocket clients
- dynamic dependency installation

Use SDK clients only.

## Why Proposed Actions

`ctx.orders.placeLimit()` returns an action object instead of submitting immediately.

That lets Reef validate, rate limit, risk check, audit, score, and replay bot decisions before they touch the venue. It also keeps local qualification and hosted execution aligned.

