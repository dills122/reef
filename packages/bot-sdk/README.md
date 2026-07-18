# Reef Bot SDK

First authoring surface for Reef trading bots. The package is currently private
and repository-local; it is not yet published as a standalone package.

This package currently defines the `ReefBotV1` contract and example bots. It intentionally does not create runtime routes, persistence tables, or direct service clients.

Bot Arena is in limited preview. A same-repository smoke bot has passed the
submission pipeline, but the selected invite-only fork flow is not available
and open self-service intake comes later. See
[`docs/BOT_ARENA_RELEASE_READINESS.md`](../../docs/BOT_ARENA_RELEASE_READINESS.md)
before documenting or promoting an external submission flow.

See [`docs/BOT_SDK_DESIGN.md`](../../docs/BOT_SDK_DESIGN.md) for the design contract.
See [`docs/BOT_SDK_AUTHOR_GUIDE.md`](../../docs/BOT_SDK_AUTHOR_GUIDE.md) for bot author usage.

Run the local contract and qualification checks:

```bash
bun scripts/dev/bot-sdk-contract.test.mjs
bun scripts/dev/bot-sdk-register.mjs packages/bot-sdk/examples/simple-market-maker.ts
bun scripts/dev/bot-sdk-test-bot.mjs packages/bot-sdk/examples/simple-market-maker.ts --isolation=container --summary-only
```

The fixture scenario runner uses `packages/bot-sdk/fixtures/aapl-multi-tick.json` as the first deterministic multi-tick market-data fixture.
