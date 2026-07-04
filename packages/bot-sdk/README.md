# Reef Bot SDK

First public authoring surface for Reef trading bots.

This package currently defines the `ReefBotV1` contract and example bots. It intentionally does not create runtime routes, persistence tables, or direct service clients.

See [`docs/BOT_SDK_DESIGN.md`](../../docs/BOT_SDK_DESIGN.md) for the design contract.
See [`docs/BOT_SDK_AUTHOR_GUIDE.md`](../../docs/BOT_SDK_AUTHOR_GUIDE.md) for bot author usage.

Run the local contract and qualification checks:

```bash
bun scripts/dev/bot-sdk-contract.test.mjs
bun scripts/dev/bot-sdk-register.mjs packages/bot-sdk/examples/simple-market-maker.ts
```
