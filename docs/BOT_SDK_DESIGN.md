# Bot SDK Design

## Purpose

Define the first public Bot SDK contract for Reef bot authors.

The SDK must let users build useful trading bots while preserving Reef's core constraints:

- bots use the same venue command paths as manual users and simulator actors
- hosted bot code is untrusted
- bot decisions are replayable, inspectable, and rate limited
- arena rules and limits are configuration-driven
- public bot APIs are versioned so breaking changes can be isolated

This document describes the initial `ReefBotV1` design. It does not add new runtime routes or storage contracts.

For bot-author-facing usage, see [`BOT_SDK_AUTHOR_GUIDE.md`](./BOT_SDK_AUTHOR_GUIDE.md).
For hosted sandbox direction, see [`BOT_SDK_HOSTED_RUNTIME.md`](./BOT_SDK_HOSTED_RUNTIME.md).
For package approval rules, see [`BOT_SDK_APPROVED_PACKAGES.md`](./BOT_SDK_APPROVED_PACKAGES.md).

## Current Implementation Map

- `packages/bot-sdk/src/index.ts` owns the public V1 authoring types and `ReefBotV1` base class.
- `packages/bot-sdk/src/harness.ts` owns registration, metadata, registry, fixture context, and qualification checks.
- `packages/bot-sdk/src/runner.ts` owns deterministic tick execution and scenario reports.
- `packages/bot-sdk/src/hosted-runner.ts` owns hosted bundle loading through a SES-compatible compartment interface.
- `packages/bot-sdk/src/venue-adapter.ts` maps approved bot actions to venue command requests.
- `packages/bot-sdk/src/venue-client.ts` owns adapter-controlled HTTP transport and recording transport helpers.
- `packages/bot-sdk/src/venue-preflight.ts` describes live venue seed requirements for fixture-backed smoke runs.

## V1 Authoring Model

Each bot starts as one TypeScript file. The file exports one default class that extends `ReefBotV1`.

The filename must be unique across submitted bots. The metadata `name` does not need to match the filename.

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
  };

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    if (!snapshot.ok) {
      return [];
    }

    return [
      ctx.actions.submitLimit({
        instrumentId: "AAPL",
        side: "BUY",
        quantity: 10,
        limitPrice: snapshot.value.midPrice - 1,
      }),
    ];
  }
}
```

## Metadata

Required metadata:

- `name`
- `publisher`
- `email`
- `version`
- `sdkVersion`
- `botApiVersion`

Optional metadata:

- `description`
- `tags`
- `repository`
- `license`
- `homepage`

The registration harness performs basic email syntax validation. During PR-based publishing, the harness should also record the git author email as a secondary audit signal, but metadata remains the bot author's declared contact.

Bot authors should bump their bot `version` whenever they change bot behavior. Existing bots only need code changes for SDK/API major-version breaks. Minor and patch SDK updates should preserve compatibility.

## Lifecycle

`ReefBotV1` uses a harness-owned tick lifecycle:

- `onStart(ctx)` runs once after metadata, config, scanning, and preflight succeed.
- `onTick(ctx)` runs on the configured schedule and returns proposed actions.
- `onStop(ctx)` runs once during graceful shutdown.

Hosted v1 bots must not create their own timers, background loops, or transport clients. Future HFT or event-driven bots should use a separate runtime mode or a future major bot API version rather than weakening the v1 tick contract.

## Scheduling And Limits

All limits are configuration-driven by arena/runtime policy.

Initial default policy:

- default tick interval: `500ms`
- minimum allowed tick interval: `250ms`
- max individual order actions per tick: `10`
- all API clients have per-bot call-rate limits
- batch order limits count each child order, not just the batch wrapper

Bot metadata may later expose scheduling preferences, but the runtime owns the final schedule.

## Action Model

Hosted bots return proposed actions from `onTick`; they do not submit directly to Reef.

The harness validates, rates, scores, and audits actions before translating them into normal venue commands.
Bot authors use the order client to construct these actions:

```ts
return [
  ctx.orders.placeLimit({
    instrumentId: "AAPL",
    side: "BUY",
    quantity: 10,
    limitPrice: 99.5,
  }),
];
```

`ctx.actions` remains a low-level factory, but examples should prefer `ctx.orders` because it is the single author-facing order API surface.

Initial actions:

- submit limit order
- submit market order when the arena mode allows it
- modify order
- cancel order
- cancel all own open orders
- no-op

Each accepted action must pass:

```text
schema validation -> arena permission/risk policy -> rate limit -> venue command gateway
```

Rejected actions are first-class bot outcomes and may affect qualification, freeze, ban, or scoring decisions.

## Data Surface

V1 is pull/call based with rate limits.

Allowed read surfaces:

- own current orders
- own previous orders
- own fills and executions where projected
- tightly scoped public market snapshots
- aggregate and intraday market indicators
- historical data exposed by approved SDK clients
- current runtime policy and limits visible to the bot

Disallowed surfaces:

- direct database access
- direct network access
- matching-engine internals
- future scenario schedule
- other bots' private state
- raw event logs unless exposed through a versioned public policy

Historical responses may be cached client-side because historical windows do not change after finalization. Live/current reads must respect freshness, TTL, and rate-limit policy.

## SDK Clients

`BotContextV1` exposes approved clients. Bot code must not create its own Reef HTTP, gRPC, WebSocket, or database clients.

Initial order client:

- `ctx.orders.current()`
- `ctx.orders.history({ instrumentId, limit })`
- `ctx.orders.placeLimit(order)`
- `ctx.orders.placeMarket(order)`
- `ctx.orders.modify(order)`
- `ctx.orders.cancel(order)`
- `ctx.orders.cancelAll(instrumentId?)`
- `ctx.orders.safe.modify(order)`
- `ctx.orders.safe.cancel(order)`

Initial market and historical clients:

- `ctx.marketData.snapshot(instrumentId)`
- `ctx.historical.intradayBars({ instrumentId, interval, start, end })`

Read clients return structured `BotResultV1<T>` values. Rate limits, policy denials, stale data, and temporary unavailability should be returned as denials so bots can adapt without crashing.

Order write methods return proposed `BotActionV1` values in hosted mode. A later local-development adapter may translate those actions to live `/api/v1` calls, but hosted qualification must keep the proposal/validation boundary.

Safe order helpers read the bot's own projected orders before proposing cancel/modify actions. They reject unknown, terminal, mismatched-instrument, or fully filled orders locally so stale lifecycle actions do not waste venue capacity or harm bot scoring.

## Venue Adapter

The SDK sidecar includes a pure mapping helper, `toVenueCommandRequestsV1`, for adapter/orchestrator code. It converts proposed bot actions into normal `/api/v1` command request objects with:

- command ID
- trace ID
- idempotency key
- actor ID
- run ID
- venue session ID
- bot ID and bot version
- participant/account context
- instrument routing metadata where available

The helper does not execute HTTP calls. Hosted bots still return proposed actions; adapter/orchestrator code decides whether approved actions become platform commands.

`sendVenueCommandRequestsV1` is the adapter-owned send step. It accepts mapped command requests and a transport implementation. Bot code never receives the transport and never creates its own network client.

The deterministic runner is dry-run by default. When passed a venue transport, it sends generated commands through the adapter-owned client and records venue responses on each tick report.

Before live submission, `validateVenuePreflightV1` reports the fixture's required venue seed state: instruments, participant, account, actor role binding, and venue session. The preflight report is an adapter/orchestrator contract; it does not seed runtime data by itself.

Current mapping support:

- `submit_limit` -> `/api/v1/orders/submit`
- `modify_order` -> `/api/v1/orders/modify`
- `cancel_order` -> `/api/v1/orders/cancel`

Current structured denials:

- `submit_market`, because current `/api/v1` validation only accepts `LIMIT`
- `cancel_all`, because the orchestrator must expand it into individual own-order cancels before venue submission

## Private Runtime Config

Private bot config is loaded during preflight/start from OpenBao, validated by the platform, and then made available in memory through `ctx.config`.

The SDK may expose config descriptors for author ergonomics and registration UI, but TypeScript types are not enforcement. Runtime validation and OpenBao storage are the enforcement boundary.

Config values are immutable for a run.

`resolveBotRuntimeConfigV1` defines the runner-side preflight contract:

- descriptors identify a config key, `OpenBao` provider, secret path, required flag, and optional value type
- the runner fetches secret values before bot startup through a platform-owned secret provider
- resolved values are frozen and passed into the bot context config
- missing required values, provider mismatches, invalid keys, and type mismatches fail preflight
- bot source never receives the secret provider, secret path fetch capability, or network access

## Sandbox Policy

Hosted v1 bots are untrusted.

Minimum hosted restrictions:

- no external network access
- no direct filesystem access in v1
- no `setTimeout` or `setInterval`
- no child processes
- no direct Node built-in imports
- no native modules
- no dynamic dependency installation
- no arbitrary Reef HTTP/gRPC clients
- deterministic clock through `ctx.clock`
- deterministic randomness only through `ctx.random`
- per-decision wall-time timeout
- CPU and memory budgets enforced outside the JS runtime
- bounded logs, state, API calls, and action counts

SES compartments are a good JavaScript-level confinement layer, but hosted safety also requires process/container isolation, OS-level resource limits, blocked outbound network, restricted module loading, scanner gates, and operator kill switches.

## Dependencies

V1.5 keeps runtime dependency access closed, but bot source may import exact packages from the approved allowlist:

- `trading-signals@7.4.3`
- `simple-statistics@7.9.3`
- `decimal.js@10.6.0`
- `lodash-es@4.18.1`

Approved packages are bundled into the hosted artifact, scanned after bundling, and recorded in the artifact manifest. SES execution still receives no package loader, filesystem access, network access, or dynamic dependency installation.

## Registration And Qualification

The registration harness should:

1. verify unique filename
2. parse and validate metadata
3. check basic email syntax and record git author email when available
4. reject forbidden imports and hosted-mode APIs
5. typecheck against the pinned SDK version
6. instantiate the default bot class in a confined runtime
7. run `onStart`, controlled ticks, and `onStop`
8. enforce configured API/action/trade-per-second limits
9. run a deterministic qualification simulation
10. compute a deterministic `sha256:<hex>` source hash
11. emit a registration report

The sidecar implementation starts with:

- `bun scripts/dev/bot-sdk-register.mjs <bot-file.ts>` for single-bot registration and mock qualification
- `bun scripts/dev/bot-sdk-run.mjs <bot-file.ts> [fixture.json]` for deterministic multi-tick scenario runs
- `bun scripts/dev/bot-sdk-contract.test.mjs` for SDK examples and contract checks
- `bun scripts/dev/bot-sdk-venue-adapter.test.mjs` for fixture-to-venue-command smoke checks
- `bun scripts/dev/bot-sdk-venue-client.test.mjs` for adapter-owned venue client checks
- `bun scripts/dev/bot-sdk-runner.test.mjs` for deterministic multi-tick scenario runner checks
- `bun scripts/dev/bot-sdk-sandbox-policy.test.mjs` for hosted-mode source policy checks
- `bun scripts/dev/bot-sdk-preflight.test.mjs` for live venue preflight contract checks
- `packages/bot-sdk/bot-registry.example.json` as the first local registry fixture, with filename, bot ID, owner, publisher, approved version, status, and optional artifact hash
- `packages/bot-sdk/test-fixtures/bad-bots/` for rejected-bot fixtures covering metadata, hosted API, and action-limit failures

A bot that exceeds trade/sec limits during qualification must be marked `do_not_merge` until fixed. Repeated live violations can freeze the bot and then ban that bot version or submitter according to arena policy.

When a registry entry has `artifactHash`, it must match the computed source hash. Draft entries may omit `artifactHash` until the publish/approval flow records the immutable artifact.

## Persistence And Leaderboards

Persistent arena storage is the source of truth for:

- bot identity and public metadata
- bot versions and artifact hashes
- validation and qualification results
- run metadata
- public analytics exported from simulations
- leaderboard source facts

Repository README or GitHub Pages leaderboards should be generated read models from persistent facts, not the source of truth.

## Future Runtime Modes

`ReefBotV1` is deliberately conservative. It is designed for safe public authoring and deterministic arena qualification, not HFT.

Future modes can add event-driven or lower-latency behavior after the platform has stronger sandboxing, throughput evidence, replay guarantees, and scoring controls.
