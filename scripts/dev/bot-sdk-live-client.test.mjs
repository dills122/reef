import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const {
  createLiveMarketDataClientV1,
  createLiveHistoricalDataClientV1,
  createLiveOwnOrdersReadClientV1,
  createLiveBotContextV1,
} = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/live-client.ts")).href);

function fakeFetch(routes) {
  return async (url) => {
    for (const [pattern, body] of routes) {
      if (pattern.test(url)) {
        return { status: 200, async text() { return JSON.stringify(body); } };
      }
    }
    return { status: 404, async text() { return JSON.stringify({ error: "not found" }); } };
  };
}

// snapshot: bid+ask present -> midPrice averaged, lastPrice from tape.
// Prices are stored venue-wide as fixed-point nanos (price_nanos = price_dollars * 1e9);
// bestBidPrice "100250000000" is $100.25.
{
  const fetchImpl = fakeFetch([
    [/market-data\/snapshots\/AAPL/, { snapshot: { bestBidPrice: "100250000000", bestAskPrice: "102250000000", updatedAt: "2026-07-06T00:00:00Z" } }],
    [/market-data\/trades\/AAPL/, { trades: [{ price: "101250000000" }] }],
  ]);
  const client = createLiveMarketDataClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const result = await client.snapshot("AAPL");
  assert.equal(result.ok, true);
  assert.equal(result.value.bidPrice, 100.25);
  assert.equal(result.value.askPrice, 102.25);
  assert.equal(result.value.midPrice, 101.25);
  assert.equal(result.value.lastPrice, 101.25);
}

// snapshot: no bid/ask/trades at all -> NOT_FOUND denial
{
  const fetchImpl = fakeFetch([
    [/market-data\/snapshots\/MSFT/, { snapshot: { bestBidPrice: "", bestAskPrice: "" } }],
    [/market-data\/trades\/MSFT/, { trades: [] }],
  ]);
  const client = createLiveMarketDataClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const result = await client.snapshot("MSFT");
  assert.equal(result.ok, false);
  assert.equal(result.denial.code, "NOT_FOUND");
}

// intraday bars: numeric conversion from platform's string OHLCV, prices in nanos.
// volume is plain OrderQuantity.units (unscaled), unlike OHLC price fields.
{
  const fetchImpl = fakeFetch([
    [/market-data\/bars\/AAPL/, { bars: [{ start: "2026-07-06T00:00:00Z", end: "2026-07-06T00:01:00Z", open: "100000000000", high: "105000000000", low: "99000000000", close: "104000000000", volume: "50" }] }],
  ]);
  const client = createLiveHistoricalDataClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const result = await client.intradayBars({ instrumentId: "AAPL", interval: "1m", start: "2026-07-06T00:00:00Z", end: "2026-07-06T00:02:00Z" });
  assert.equal(result.ok, true);
  assert.equal(result.value.length, 1);
  assert.equal(result.value[0].open, 100);
  assert.equal(result.value[0].close, 104);
  assert.equal(result.value[0].volume, 50);
}

// own orders: status mapping CANCELLED -> CANCELED, scoped read, limitPrice in nanos
{
  const fetchImpl = fakeFetch([
    [/orders\/current/, { orders: [{ orderId: "o1", instrumentId: "AAPL", side: "BUY", quantityUnits: "10", remainingQuantityUnits: "10", limitPrice: "100000000000", status: "OPEN" }] }],
    [/orders\/history/, { orders: [
      { orderId: "o1", instrumentId: "AAPL", side: "BUY", quantityUnits: "10", remainingQuantityUnits: "10", limitPrice: "100000000000", status: "OPEN" },
      { orderId: "o2", instrumentId: "AAPL", side: "SELL", quantityUnits: "5", remainingQuantityUnits: "0", limitPrice: "101000000000", status: "CANCELLED" },
    ] }],
  ]);
  const client = createLiveOwnOrdersReadClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const current = await client.current();
  assert.equal(current.ok, true);
  assert.equal(current.value.length, 1);
  assert.equal(current.value[0].status, "OPEN");
  assert.equal(current.value[0].limitPrice, 100);

  const history = await client.history();
  assert.equal(history.ok, true);
  assert.equal(history.value.length, 2);
  assert.equal(history.value[1].status, "CANCELED");
  assert.equal(history.value[1].limitPrice, 101);
}

// createLiveBotContextV1 wires safe.cancel against live current-orders read
{
  const fetchImpl = fakeFetch([
    [/orders\/current/, { orders: [{ orderId: "o1", instrumentId: "AAPL", side: "BUY", quantityUnits: "10", remainingQuantityUnits: "10", limitPrice: "100000000000", status: "OPEN" }] }],
  ]);
  const ctx = createLiveBotContextV1({
    baseUrl: "http://venue",
    participantId: "p1",
    fetch: fetchImpl,
    policy: { tickIntervalMs: 1000, minTickIntervalMs: 100, maxOrderActionsPerTick: 10, maxDataCallsPerTick: 10, maxTradeCommandsPerSecond: 10 },
  });
  const cancelResult = await ctx.orders.safe.cancel({ orderId: "o1", instrumentId: "AAPL" });
  assert.equal(cancelResult.ok, true);
  assert.equal(cancelResult.value.type, "cancel_order");

  const unknownCancel = await ctx.orders.safe.cancel({ orderId: "does-not-exist", instrumentId: "AAPL" });
  assert.equal(unknownCancel.ok, false);
  assert.equal(unknownCancel.denial.code, "NOT_FOUND");
}

console.log("bot SDK live client checks passed");
