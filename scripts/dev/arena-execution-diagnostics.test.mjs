import assert from "node:assert/strict";
import {
  enrichBotResultsWithExecutionDiagnostics,
  marketPriceValue,
  priceFromExecutionPrice,
  summarizeAdverseSelection,
  summarizeExecutionDiagnostics,
} from "./lib/arena-execution-diagnostics.mjs";

assert.equal(priceFromExecutionPrice("100000000000"), 100);
assert.equal(priceFromExecutionPrice("100.25"), 100.25);
assert.equal(marketPriceValue("101000000000"), 101);
assert.equal(marketPriceValue("101.5"), 101.5);

const fills = [
  {
    executionId: "exec-buy",
    instrumentId: "AAPL",
    side: "BUY",
    quantityUnits: "3",
    executionPrice: "100000000000",
  },
  {
    executionId: "exec-sell",
    instrumentId: "AAPL",
    side: "SELL",
    quantityUnits: "1",
    executionPrice: "101000000000",
  },
  {
    executionId: "exec-msft",
    instrumentId: "MSFT",
    side: "SELL",
    quantityUnits: "2",
    executionPrice: "200",
  },
];

const direct = summarizeExecutionDiagnostics(fills, { AAPL: 102, MSFT: 198 });
assert.equal(direct.executions.fillCount, 3);
assert.equal(direct.executions.buyFillCount, 1);
assert.equal(direct.executions.sellFillCount, 2);
assert.equal(direct.executions.filledQuantity, 6);
assert.equal(direct.executions.grossNotional, 801);
assert.equal(direct.executions.byInstrument.AAPL.netQuantity, 2);
assert.equal(direct.executions.byInstrument.MSFT.netQuantity, -2);
assert.equal(direct.pnl.cash, 201);
assert.equal(direct.pnl.inventoryValue, -192);
assert.equal(direct.pnl.total, 9);
assert.equal(direct.pnl.finalEquityDiagnostic, 1000009);
assert.deepEqual(direct.inventory.netQuantityByInstrument, { AAPL: 2, MSFT: -2 });
assert.deepEqual(direct.inventory.markPriceByInstrument, { AAPL: 102, MSFT: 198 });
assert.equal(direct.adverseSelection.available, false);

const adverseSelection = summarizeAdverseSelection([
  {
    executionId: "exec-adverse-buy",
    instrumentId: "AAPL",
    side: "BUY",
    quantityUnits: "2",
    executionPrice: "100",
    occurredAt: "2026-07-07T00:00:00.000Z",
  },
  {
    executionId: "exec-favorable-sell",
    instrumentId: "AAPL",
    side: "SELL",
    quantityUnits: "1",
    executionPrice: "101",
    occurredAt: "2026-07-07T00:00:00.000Z",
  },
], [{
  occurredAt: "2026-07-07T00:00:01.000Z",
  snapshots: [{ instrumentId: "AAPL", midPrice: 99 }],
}]);
assert.equal(adverseSelection.available, true);
assert.equal(adverseSelection.comparableFillCount, 2);
assert.equal(adverseSelection.adverseFillCount, 1);
assert.equal(adverseSelection.favorableFillCount, 1);
assert.equal(adverseSelection.adverseFillPct, 50);
assert.equal(adverseSelection.avgMarkoutBps, 49.009901);
assert.equal(adverseSelection.markoutNotional, 0);
assert.equal(adverseSelection.byInstrument.AAPL.adverseFillCount, 1);
assert.equal(adverseSelection.byInstrument.AAPL.favorableFillCount, 1);

const botResults = [{
  botId: "bot-a",
  tradingMetrics: {
    commands: { proposed: 1 },
    pnl: { available: false },
  },
}];
const healthSamples = [{
  occurredAt: "2026-07-07T00:00:01.000Z",
  snapshots: [
    { instrumentId: "AAPL", midPrice: 101 },
    { instrumentId: "MSFT", midPrice: 199 },
  ],
}];
const venueReadback = {
  ownOrders: [{
    botId: "bot-a",
    fills: { body: { fills } },
  }],
  snapshots: [{
    instrumentId: "AAPL",
    body: { snapshot: { instrumentId: "AAPL", bestBidPrice: "101000000000", bestAskPrice: "103000000000" } },
  }],
};

const enriched = enrichBotResultsWithExecutionDiagnostics(botResults, venueReadback, healthSamples, {
  fallbackInstruments: ["AAPL", "MSFT", "NVDA"],
});
assert.equal(enriched[0].tradingMetrics.executions.fillCount, 3);
assert.equal(enriched[0].tradingMetrics.pnl.total, 7);
assert.equal(enriched[0].tradingMetrics.inventory.markPriceByInstrument.AAPL, 102);
assert.equal(enriched[0].tradingMetrics.inventory.markPriceByInstrument.MSFT, 199);
assert.equal(enriched[0].tradingMetrics.adverseSelection.available, false);

const dryRun = enrichBotResultsWithExecutionDiagnostics(botResults, { skipped: true }, healthSamples);
assert.equal(dryRun, botResults);

console.log("arena execution diagnostics checks passed");
