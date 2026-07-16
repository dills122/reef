import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const { runBotStrategyScenarioV1 } = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/strategy-runner.ts")).href);
const { createRecordingVenueTransportV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-client.ts")).href
);

await assertMultiSymbolStrategyRun();
await assertLiveReadClientsStrategyRun();
await assertStrategyBotRandomAdvancesAndReplays();
await assertPolicyBlockedStrategyRunDoesNotSendOrApply();

console.log("bot SDK strategy runner checks passed");

async function assertMultiSymbolStrategyRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/multi-symbol-strategy-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotStrategyScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "multi-symbol-strategy",
      actorId: "actor-multi-symbol-strategy",
      historicalBars: {
        AAPL: bars("AAPL", [
          100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
          100, 100, 100, 100, 100, 80, 85, 90, 92, 95,
        ]),
        MSFT: bars("MSFT", [
          200, 200, 200, 200, 200, 200, 200, 200, 200, 200,
          200, 200, 200, 200, 200, 220, 215, 212, 210, 205,
        ]),
      },
      ticks: fixture.ticks.map((tick) => ({
        ...tick,
        marketSnapshots: {
          ...tick.marketSnapshots,
          MSFT: {
            instrumentId: "MSFT",
            asOf: tick.occurredAt,
            bidPrice: 211,
            askPrice: 213,
            midPrice: 212,
            lastPrice: 212,
          },
          NVDA: {
            instrumentId: "NVDA",
            asOf: tick.occurredAt,
            bidPrice: 499,
            askPrice: 501,
            midPrice: 500,
            lastPrice: 500,
          },
          TSLA: {
            instrumentId: "TSLA",
            asOf: tick.occurredAt,
            bidPrice: 249,
            askPrice: 251,
            midPrice: 250,
            lastPrice: 250,
          },
        },
      })),
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.signalsGenerated, 2);
  assert.equal(report.orderActionsProposed, 2);
  assert.equal(report.dataCalls, 2);
  assert.equal(report.ticks[0].signals.length, 2);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(transport.requests.length, 2);
}

async function assertLiveReadClientsStrategyRun() {
  const transport = createRecordingVenueTransportV1(202);
  const readClients = {
    marketData: {
      async snapshot(instrumentId) {
        assert.equal(instrumentId, "AAPL");
        return { ok: true, value: { instrumentId, asOf: "2026-07-06T00:00:00Z", midPrice: 124 } };
      },
      async snapshots() {
        throw new Error("not used");
      },
    },
    orders: {
      async current() {
        return {
          ok: true,
          value: [{
            orderId: "live-strategy-open-1",
            instrumentId: "AAPL",
            side: "BUY",
            quantity: 5,
            remainingQuantity: 5,
            limitPrice: 123,
            status: "OPEN",
          }],
        };
      },
      async history() {
        return { ok: true, value: [] };
      },
    },
  };

  const report = await runBotStrategyScenarioV1({
    BotClass: LiveReadProbeBot,
    fixture: {
      ...fixture,
      ticks: fixture.ticks.slice(0, 1).map((tick) => ({ ...tick, marketSnapshots: {} })),
      initialOrders: [],
    },
    venueTransport: transport,
    readClients,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.dataCalls, 2);
  assert.equal(report.ticks[0].venueCommands.length, 1);
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "124000000000");
  assert.equal(report.finalOrders.length, 1);
  assert.equal(report.finalOrders[0].orderId, "live-strategy-open-1");
}

async function assertStrategyBotRandomAdvancesAndReplays() {
  const first = await runBotStrategyScenarioV1({ BotClass: RandomStrategyProbeBot, fixture });
  const second = await runBotStrategyScenarioV1({ BotClass: RandomStrategyProbeBot, fixture });
  const differentRun = await runBotStrategyScenarioV1({
    BotClass: RandomStrategyProbeBot,
    fixture: { ...fixture, runId: `${fixture.runId}-different` },
  });

  const firstValues = randomReasons(first);
  assert.equal(first.status, "completed");
  assert.equal(firstValues.length, 3);
  assert.equal(new Set(firstValues).size, firstValues.length);
  assert.deepEqual(firstValues, randomReasons(second));
  assert.notDeepEqual(firstValues, randomReasons(differentRun));
}

async function assertPolicyBlockedStrategyRunDoesNotSendOrApply() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/test-fixtures/bad-bots/too-many-orders-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotStrategyScenarioV1({
    BotClass: module.default,
    fixture,
    venueTransport: transport,
  });

  assert.equal(report.status, "do_not_merge");
  assert.ok(report.issues.some((issue) => issue.code === "max_order_actions_per_tick_exceeded"));
  assert.equal(report.ticks[0].venueCommands.length, 0);
  assert.equal(report.finalOrders.length, 0);
  assert.equal(transport.requests.length, 0);
}

class LiveReadProbeBot {
  strategies = [];
  async onStart() {}
  async onStop() {}
  async onSignal() {
    return [];
  }
  async onTick(ctx) {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    const current = await ctx.orders.current();
    if (!snapshot.ok || !current.ok) {
      return [ctx.actions.noop("live data unavailable")];
    }
    return [ctx.orders.placeLimit({ instrumentId: "AAPL", side: "BUY", quantity: 1, limitPrice: snapshot.value.midPrice })];
  }
}

class RandomStrategyProbeBot {
  strategies = [];
  async onStart() {}
  async onStop() {}
  async onSignal() {
    return [];
  }
  async onTick(ctx) {
    return [ctx.actions.noop(`random:${ctx.random.integer(0, 1_000_000_000)}`)];
  }
}

function randomReasons(report) {
  return report.ticks.map((tick) => tick.actions[0]?.reason);
}

function bars(instrumentId, closes) {
  return closes.map((close, index) => ({
    instrumentId,
    start: `2026-07-04T14:${String(index).padStart(2, "0")}:00.000Z`,
    end: `2026-07-04T14:${String(index + 1).padStart(2, "0")}:00.000Z`,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
    volume: 1000 + index,
  }));
}
