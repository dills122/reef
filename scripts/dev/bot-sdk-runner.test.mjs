import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const { runBotScenarioV1 } = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/runner.ts")).href);
const { createRecordingVenueTransportV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-client.ts")).href
);

await assertSimpleMarketMakerRun();
await assertLiveReadClientsRun();
await assertLifecycleSafeMarketMakerRun();
await assertRefreshingMarketMakerHealthyNoopRun();
await assertRefreshingMarketMakerStaleRefreshRun();
await assertConfigurablePassiveStrategyRestingOrderRun();
await assertAggressiveTakerRun();
await assertAggressiveTakerWarmupRun();
await assertPolicyBlockedRunDoesNotSendOrApply();

console.log("bot SDK scenario runner checks passed");

async function assertSimpleMarketMakerRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/simple-market-maker.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({ BotClass: module.default, fixture, venueTransport: transport });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.actionsProposed, 6);
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.dataCalls, 3);
  assert.equal(report.issues.length, 0);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueResponses.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "99500000000");
  assert.equal(report.ticks[1].venueCommands[0].body.limitPrice, "100000000000");
  assert.equal(report.ticks[2].venueCommands[1].body.limitPrice, "101250000000");
  assert.equal(report.finalOrders.length, 6);
  assert.equal(transport.requests.length, 6);
}

async function assertLiveReadClientsRun() {
  const transport = createRecordingVenueTransportV1(202);
  const currentOrders = [{
    orderId: "live-open-1",
    instrumentId: "AAPL",
    side: "BUY",
    quantity: 5,
    remainingQuantity: 5,
    limitPrice: 122,
    status: "OPEN",
  }];
  const readClients = {
    marketData: {
      async snapshot(instrumentId) {
        assert.equal(instrumentId, "AAPL");
        return { ok: true, value: { instrumentId, asOf: "2026-07-06T00:00:00Z", midPrice: 123 } };
      },
      async snapshots() {
        throw new Error("not used");
      },
    },
    orders: {
      async current() {
        return { ok: true, value: currentOrders };
      },
      async history() {
        return { ok: true, value: currentOrders };
      },
    },
  };

  const report = await runBotScenarioV1({
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
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "123000000000");
  assert.equal(report.finalOrders.length, 1);
  assert.equal(report.finalOrders[0].orderId, "live-open-1");
}

async function assertLifecycleSafeMarketMakerRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/lifecycle-safe-market-maker.ts")).href);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "lifecycle-safe-market-maker",
      actorId: "actor-lifecycle-safe-market-maker",
    },
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.actionsProposed, 4);
  assert.equal(report.orderActionsProposed, 2);
  assert.equal(report.dataCalls, 4);
  assert.equal(report.issues.length, 0);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[1].venueCommands.length, 0);
  assert.equal(report.ticks[1].actions[0].type, "noop");
  assert.equal(report.ticks[2].venueCommands.length, 0);
  assert.equal(report.finalOrders.length, 2);
}

class LiveReadProbeBot {
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

async function assertRefreshingMarketMakerHealthyNoopRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/refreshing-market-maker.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "refreshing-market-maker",
      actorId: "actor-refreshing-market-maker",
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.orderActionsProposed, 2);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.ticks[1].venueCommands.length, 0);
  assert.equal(report.ticks[1].actions[0].type, "noop");
  assert.equal(report.ticks[2].venueCommands.length, 0);
  assert.equal(report.ticks[2].actions[0].type, "noop");
  assert.equal(report.finalOrders.length, 2);
  assert.equal(transport.requests.length, 2);
}

async function assertRefreshingMarketMakerStaleRefreshRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/refreshing-market-maker.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "refreshing-market-maker",
      actorId: "actor-refreshing-market-maker",
      config: {
        ...fixture.config,
        quoteTtlMs: 400,
        maxCancelsPerTick: 2,
      },
      ticks: [
        ...fixture.ticks,
        {
          ...fixture.ticks[2],
          occurredAt: "2026-07-04T14:30:01.500Z",
          marketSnapshots: {
            AAPL: {
              ...fixture.ticks[2].marketSnapshots.AAPL,
              asOf: "2026-07-04T14:30:01.500Z",
            },
          },
        },
      ],
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 4);
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.ticks[1].venueCommands.length, 0);
  assert.equal(report.ticks[2].venueCommands.length, 2);
  assert.equal(report.ticks[2].venueCommands[0].route, "/api/v1/orders/cancel");
  assert.equal(report.ticks[3].venueCommands.length, 2);
  assert.equal(report.ticks[3].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.finalOrders.length, 2);
  assert.equal(transport.requests.length, 6);
}

async function assertAggressiveTakerRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/configurable-aggressive-taker-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "configurable-aggressive-taker-bot",
      actorId: "actor-configurable-aggressive-taker-bot",
      config: {
        instrumentId: "AAPL",
        side: "ALTERNATE",
        orderSize: 1,
        crossOffset: 0.05,
      },
      ticks: fixture.ticks.slice(0, 2),
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 2);
  assert.equal(report.orderActionsProposed, 1);
  assert.equal(report.ticks[0].venueCommands[0].body.side, "BUY");
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "100550000000");
  assert.equal(report.ticks[1].venueCommands.length, 0);
  assert.equal(report.ticks[1].actions[0].type, "noop");
  assert.equal(report.ticks[1].actions[0].reason, "taker order already active");
  assert.equal(transport.requests.length, 1);
}

async function assertAggressiveTakerWarmupRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/configurable-aggressive-taker-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "configurable-aggressive-taker-bot",
      actorId: "actor-configurable-aggressive-taker-bot",
      config: {
        instrumentId: "AAPL",
        side: "BUY",
        orderSize: 1,
        crossOffset: 0.05,
        startAfterTicks: 1,
      },
      ticks: fixture.ticks.slice(0, 2),
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.orderActionsProposed, 1);
  assert.equal(report.ticks[0].venueCommands.length, 0);
  assert.equal(report.ticks[0].actions[0].type, "noop");
  assert.equal(report.ticks[0].actions[0].reason, "waiting for liquidity warmup");
  assert.equal(report.ticks[1].venueCommands.length, 1);
  assert.equal(report.ticks[1].venueCommands[0].body.side, "BUY");
  assert.equal(transport.requests.length, 1);
}

async function assertConfigurablePassiveStrategyRestingOrderRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/configurable-passive-strategy-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "configurable-passive-strategy-bot",
      actorId: "actor-configurable-passive-strategy-bot",
      config: {
        instrumentId: "AAPL",
        side: "BUY",
        orderSize: 1,
        priceOffset: 0.1,
      },
      ticks: fixture.ticks.slice(0, 2),
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 2);
  assert.equal(report.orderActionsProposed, 1);
  assert.equal(report.ticks[0].venueCommands.length, 1);
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "99900000000");
  assert.equal(report.ticks[1].venueCommands.length, 0);
  assert.equal(report.ticks[1].actions[0].type, "noop");
  assert.equal(report.ticks[1].actions[0].reason, "passive order already resting");
  assert.equal(transport.requests.length, 1);
}

async function assertPolicyBlockedRunDoesNotSendOrApply() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/test-fixtures/bad-bots/too-many-orders-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotScenarioV1({
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
