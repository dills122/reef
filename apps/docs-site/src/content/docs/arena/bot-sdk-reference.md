---
title: Bot SDK Reference
description: Full BotContextV1 API surface, action mapping, config, and sandbox rules.
---

## Reads

```ts
await ctx.orders.current();
await ctx.orders.history({ instrumentId: "AAPL", limit: 50 });
await ctx.marketData.snapshot("AAPL");
await ctx.marketData.snapshots(["AAPL", "MSFT", "NVDA"]);
await ctx.historical.intradayBars({ instrumentId: "AAPL", interval: "1m", start, end });
await ctx.historical.intradayBarsBatch([{ instrumentId: "AAPL", interval: "1m", start, end }]);
```

Read clients return structured `BotResultV1<T>` values — rate limits, policy denials, stale data, and temporary unavailability come back as denials (`{ ok: false, ... }`) rather than throwing, so bots can adapt without crashing. Historical windows may be cached client-side since they don't change after finalization; live/current reads respect freshness/TTL/rate-limit policy.

## Order Actions

```ts
ctx.orders.placeLimit({ instrumentId, side, quantity, limitPrice });
ctx.orders.placeMarket({ instrumentId, side, quantity });
ctx.orders.modify({ orderId, instrumentId, quantity, limitPrice });
ctx.orders.cancel({ orderId, instrumentId });
ctx.orders.cancelAll(instrumentId?);
```

Safe helpers read the bot's own projected orders before proposing a cancel/modify, and reject unknown, terminal, mismatched-instrument, or fully-filled orders locally so stale actions don't waste venue capacity:

```ts
const cancel = await ctx.orders.safe.cancel({ orderId, instrumentId });
if (cancel.ok) return [cancel.value];
```

Order write methods return proposed `BotActionV1` values — hosted bots never submit directly.

## Config

```ts
const maxInventory = ctx.config.number("maxInventory");
const strategyName = ctx.config.optionalString("strategyName");
```

Private bot config loads during preflight/start from OpenBao, is validated by the platform, and is exposed in memory as `ctx.config`. Config is immutable for a run; TypeScript types are ergonomics only — OpenBao + runtime validation is the enforcement boundary.

## Venue Command Mapping

The SDK's `toVenueCommandRequestsV1` helper (adapter-side, not bot-visible) maps proposed actions to `/api/v1` requests:

| Bot action | Venue route |
|---|---|
| `submit_limit` | `POST /api/v1/orders/submit` |
| `modify_order` | `POST /api/v1/orders/modify` |
| `cancel_order` | `POST /api/v1/orders/cancel` |

Currently denied at the adapter (not yet supported): `submit_market` (validation only accepts `LIMIT` today), `cancel_all` (must be expanded into individual own-order cancels before submission).

## Sandbox Restrictions (Hosted V1)

No external network access, no direct filesystem access, no `setTimeout`/`setInterval`, no child processes or worker threads, no direct Node built-in imports, no native modules, no dynamic dependency installation, no arbitrary Reef HTTP/gRPC/WebSocket clients. Clock and randomness are only available through `ctx.clock` / `ctx.random` for determinism.

## Approved Dependencies

Bot source may import from an approved allowlist, bundled into the hosted artifact and scanned post-bundling:

- `trading-signals@7.4.3`
- `simple-statistics@7.9.3`
- `decimal.js@10.6.0`
- `lodash-es@4.18.1`

## Registration Checks

Unique filename, required metadata, basic email syntax, semver-like version, forbidden-import/hosted-API scan, typecheck against pinned SDK version, confined-runtime instantiation, deterministic qualification simulation, deterministic `sha256:<hex>` source hash. Failing bots are marked `do_not_merge` with stable issue codes.

## Learn More

- `docs/BOT_SDK_DESIGN.md` — full SDK design contract (source for this page)
- `docs/BOT_SDK_APPROVED_PACKAGES.md` — approval rules for dependencies
- `docs/BOT_SDK_HOSTED_RUNTIME.md` — hosted sandbox direction
- [Bot SDK Quickstart](/arena/bot-sdk-quickstart/) — minimal working example
