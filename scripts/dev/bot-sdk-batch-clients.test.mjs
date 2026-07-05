import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const { createFixtureBotContextV1, defaultBotRuntimePolicyV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/harness.ts")).href
);

const counters = { dataCalls: 0, dataCallsThisTick: 0 };
const denials = [];
const ctx = createFixtureBotContextV1({
  policy: defaultBotRuntimePolicyV1,
  denials,
  counters,
  fixtureData: {
    marketSnapshots: {
      AAPL: { instrumentId: "AAPL", asOf: "2026-07-04T14:30:00.000Z", midPrice: 100 },
      MSFT: { instrumentId: "MSFT", asOf: "2026-07-04T14:30:00.000Z", midPrice: 200 },
    },
    historicalBars: {
      AAPL: [{ instrumentId: "AAPL", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z", open: 99, high: 101, low: 98, close: 100, volume: 1000 }],
      MSFT: [{ instrumentId: "MSFT", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z", open: 199, high: 201, low: 198, close: 200, volume: 1000 }],
    },
  },
});

const snapshots = await ctx.marketData.snapshots(["AAPL", "MSFT"]);
assert.equal(snapshots.ok, true);
assert.equal(snapshots.value.AAPL.midPrice, 100);
assert.equal(snapshots.value.MSFT.midPrice, 200);
assert.equal(counters.dataCalls, 1);

const bars = await ctx.historical.intradayBarsBatch([
  { instrumentId: "AAPL", interval: "1m", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z" },
  { instrumentId: "MSFT", interval: "1m", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z" },
]);
assert.equal(bars.ok, true);
assert.equal(bars.value.AAPL.length, 1);
assert.equal(bars.value.MSFT.length, 1);
assert.equal(counters.dataCalls, 2);

const cachedBars = await ctx.historical.intradayBarsBatch([
  { instrumentId: "AAPL", interval: "1m", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z" },
  { instrumentId: "MSFT", interval: "1m", start: "2026-07-04T14:00:00.000Z", end: "2026-07-04T14:01:00.000Z" },
]);
assert.equal(cachedBars.ok, true);
assert.equal(cachedBars.cached, true);
assert.equal(counters.dataCalls, 2);
assert.equal(denials.length, 0);

console.log("bot SDK batch client checks passed");
