import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const {
  createLiveMarketDataClientV1,
  createLiveHistoricalDataClientV1,
  createLiveOwnOrdersReadClientV1,
  createLiveDataAvailabilityClientV1,
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
  const requestedUrls = [];
  const routeFetch = fakeFetch([
    [/orders\/current/, { orders: [{ orderId: "o1", instrumentId: "AAPL", side: "BUY", quantityUnits: "10", remainingQuantityUnits: "10", limitPrice: "100000000000", status: "OPEN" }] }],
    [/orders\/history/, { orders: [
      { orderId: "o1", instrumentId: "AAPL", side: "BUY", quantityUnits: "10", remainingQuantityUnits: "10", limitPrice: "100000000000", status: "OPEN" },
      { orderId: "o2", instrumentId: "AAPL", side: "SELL", quantityUnits: "5", remainingQuantityUnits: "0", limitPrice: "101000000000", status: "CANCELLED" },
    ] }],
  ]);
  const fetchImpl = async (url) => {
    requestedUrls.push(url);
    return routeFetch(url);
  };
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

  const filteredHistory = await client.history({ instrumentId: "AAPL", limit: 1 });
  assert.equal(filteredHistory.ok, true);
  assert.equal(filteredHistory.value.length, 1);
  assert.match(requestedUrls.at(-1), /orders\/history\?participantId=p1&instrumentId=AAPL&limit=1/);
}

// own orders: malformed numeric fields do not leak NaN into bot decisions
{
  const fetchImpl = fakeFetch([
    [/orders\/current/, { orders: [{ orderId: "bad-qty", instrumentId: "AAPL", side: "BUY", quantityUnits: "not-a-number", remainingQuantityUnits: undefined, limitPrice: "", status: "UNKNOWN" }] }],
  ]);
  const client = createLiveOwnOrdersReadClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const current = await client.current();
  assert.equal(current.ok, true);
  assert.equal(current.value.length, 1);
  assert.equal(current.value[0].quantity, 0);
  assert.equal(current.value[0].remainingQuantity, 0);
  assert.equal(current.value[0].limitPrice, undefined);
  assert.equal(current.value[0].status, "REJECTED");
}

// data availability: exposes read source/freshness and projection lag
{
  const fetchImpl = fakeFetch([
    [/data\/availability/, {
      generatedAt: "2026-07-06T00:00:00Z",
      source: "venue-event-batch",
      projections: [{
        projectionName: "runtime-normalized-venue-outcomes",
        role: "canonical venue outcome projection",
        projectedCount: 10,
        lag: 2,
        watermarks: [{
          projectionName: "runtime-normalized-venue-outcomes",
          partition: 0,
          lastPartitionSequence: 8,
          canonicalMaxPartitionSequence: 10,
          lag: 2,
          updatedAt: "2026-07-06T00:00:01Z",
          lastError: "",
        }],
      }],
      surfaces: [{
        name: "tradeTape",
        endpoint: "/api/v1/market-data/trades/{instrumentId}",
        source: "runtime.trades",
        freshness: "durable fact rows",
        scope: "public-market-data",
        requiredQuery: [],
        optionalQuery: ["limit", "before"],
        projectionName: "runtime-normalized-venue-outcomes",
        lag: 2,
        lastPartitionSequence: 8,
        lastUpdatedAt: "2026-07-06T00:00:01Z",
        notes: "",
      }],
    }],
  ]);
  const client = createLiveDataAvailabilityClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const result = await client.availability();
  assert.equal(result.ok, true);
  assert.equal(result.value.source, "venue-event-batch");
  assert.equal(result.value.projections[0].lag, 2);
  assert.equal(result.value.projections[0].watermarks[0].lastPartitionSequence, 8);
  assert.equal(result.value.surfaces[0].name, "tradeTape");
  assert.equal(result.value.surfaces[0].freshness, "durable fact rows");
  assert.equal(result.value.surfaces[0].scope, "public-market-data");
  assert.deepEqual(result.value.surfaces[0].optionalQuery, ["limit", "before"]);
}

// data availability: malformed numeric fields normalize to zero instead of NaN
{
  const fetchImpl = fakeFetch([
    [/data\/availability/, {
      generatedAt: "2026-07-06T00:00:00Z",
      source: "venue-event-batch",
      projections: [{
        projectionName: "runtime-normalized-venue-outcomes",
        role: "canonical venue outcome projection",
        projectedCount: "not-a-number",
        lag: "also-bad",
        watermarks: [{
          projectionName: "runtime-normalized-venue-outcomes",
          partition: "bad",
          lastPartitionSequence: "bad",
          canonicalMaxPartitionSequence: "bad",
          lag: "bad",
          updatedAt: "2026-07-06T00:00:01Z",
          lastError: "",
        }],
      }],
      surfaces: [{
        name: "tradeTape",
        endpoint: "/api/v1/market-data/trades/{instrumentId}",
        source: "runtime.trades",
        freshness: "durable fact rows",
        scope: "public-market-data",
        requiredQuery: [],
        optionalQuery: [],
        lag: "bad",
        lastPartitionSequence: "bad",
      }],
    }],
  ]);
  const client = createLiveDataAvailabilityClientV1({ baseUrl: "http://venue", participantId: "p1", fetch: fetchImpl });
  const result = await client.availability();
  assert.equal(result.ok, true);
  assert.equal(result.value.projections[0].projectedCount, 0);
  assert.equal(result.value.projections[0].lag, 0);
  assert.equal(result.value.projections[0].watermarks[0].partition, 0);
  assert.equal(result.value.surfaces[0].lag, 0);
  assert.equal(result.value.surfaces[0].lastPartitionSequence, 0);
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
