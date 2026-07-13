import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);

await assertHostedScenarioRunsInCompartment();
await assertHostedScenarioUsesLiveReadClients();
await assertHostedScenarioReportsDataAvailabilityDenial();
await assertDeniedNetworkSourceDoesNotEvaluate();
await assertSandboxRejectsAmbientGlobalProbe();
await assertHostedTickTimeoutReturnsDoNotMerge();

console.log("bot SDK hosted runner checks passed");

async function assertHostedScenarioRunsInCompartment() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class HostedMarketMaker extends ReefBotV1 {
  static metadata = {
    name: "hosted-market-maker",
    publisher: "Reef",
    email: "bots@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  };

  async onTick(ctx) {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    if (!snapshot.ok) return [ctx.actions.noop("missing snapshot")];
    return [ctx.orders.placeLimit({ instrumentId: "AAPL", side: "BUY", quantity: 1, limitPrice: snapshot.value.midPrice - 1 })];
  }
};`;

  const report = await hostedRunner.runHostedBotScenarioV1({
    source,
    fileName: "hosted-market-maker.js",
    fixture,
    compartmentFactory: createVmCompartmentFactory(),
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.orderActionsProposed, 3);
  assert.equal(report.dataCalls, 3);
  assert.equal(report.issues.length, 0);
}

async function assertHostedScenarioUsesLiveReadClients() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class HostedLiveReadBot extends ReefBotV1 {
  static metadata = {
    name: "hosted-live-read-bot",
    publisher: "Reef",
    email: "bots@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  };

  async onTick(ctx) {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    const current = await ctx.orders.current();
    if (!snapshot.ok || !current.ok) return [ctx.actions.noop("live data unavailable")];
    return [ctx.orders.placeLimit({ instrumentId: "AAPL", side: "BUY", quantity: 1, limitPrice: snapshot.value.midPrice })];
  }
};`;

  const currentOrders = [{
    orderId: "hosted-live-open-1",
    instrumentId: "AAPL",
    side: "BUY",
    quantity: 5,
    remainingQuantity: 5,
    limitPrice: 122,
    status: "OPEN",
  }];
  const report = await hostedRunner.runHostedBotScenarioV1({
    source,
    fileName: "hosted-live-read-bot.js",
    fixture: {
      ...fixture,
      ticks: fixture.ticks.slice(0, 1).map((tick) => ({ ...tick, marketSnapshots: {} })),
      initialOrders: [],
    },
    compartmentFactory: createVmCompartmentFactory(),
    readMode: "live",
    readClients: {
      marketData: {
        async snapshot(instrumentId) {
          assert.equal(instrumentId, "AAPL");
          return { ok: true, value: { instrumentId, asOf: "2026-07-06T00:00:00Z", midPrice: 125 } };
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
    },
    dataAvailabilityClient: {
      async availability() {
        return {
          ok: true,
          value: {
            generatedAt: "2026-07-06T00:00:00Z",
            source: "venue-event-batch",
            projections: [],
            surfaces: [{
              name: "currentOrders",
              endpoint: "/api/v1/orders/current",
              source: "runtime.orders + runtime.order_lifecycle_state",
              freshness: "dirty-tracked lifecycle projection",
              scope: "participant-own-orders",
              requiredQuery: ["participantId"],
              optionalQuery: ["instrumentId", "limit"],
              projectionName: "runtime-normalized-venue-outcomes",
              lag: 0,
              lastPartitionSequence: 1,
              lastUpdatedAt: "2026-07-06T00:00:00Z",
              notes: "",
            }],
          },
        };
      },
    },
  });

  assert.equal(report.status, "completed");
  assert.equal(report.readMode, "live");
  assert.equal(report.dataAvailability.surfaces[0].name, "currentOrders");
  assert.equal(report.dataCalls, 2);
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "125000000000");
  assert.equal(report.finalOrders[0].orderId, "hosted-live-open-1");
}

async function assertHostedScenarioReportsDataAvailabilityDenial() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class HostedNeverRunsBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("never")]; } };`;

  const report = await hostedRunner.runHostedBotScenarioV1({
    source,
    fileName: "hosted-never-runs-bot.js",
    fixture,
    compartmentFactory: createVmCompartmentFactory(),
    readMode: "live",
    dataAvailabilityClient: {
      async availability() {
        return { ok: false, denial: { code: "TEMPORARILY_UNAVAILABLE", message: "availability offline" } };
      },
    },
  });

  assert.equal(report.status, "do_not_merge");
  assert.equal(report.readMode, "live");
  assert.ok(report.issues.some((issue) => issue.code === "data_availability_denial"));
  assert.equal(report.ticksRun, 0);
}

async function assertDeniedNetworkSourceDoesNotEvaluate() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
fetch("https://example.com");
module.exports.default = class BadNetworkBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("never")]; } };`;

  const result = await hostedRunner.loadHostedBotClassV1({
    source,
    fileName: "bad-network-bot.js",
    compartmentFactory: createThrowingCompartmentFactory(),
  });

  assert.equal(result.ok, false);
  assert.ok(result.issues.some((issue) => issue.code === "sandbox_denied_global"));
}

async function assertSandboxRejectsAmbientGlobalProbe() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
const processKey = "pro" + "cess";
if (typeof globalThis[processKey] !== "undefined") throw new Error("ambient runtime leaked into hosted compartment");
module.exports.default = class HostedNoAmbientBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("ok")]; } };`;

  const result = await hostedRunner.loadHostedBotClassV1({
    source,
    fileName: "hosted-no-process-bot.js",
    compartmentFactory: createVmCompartmentFactory(),
  });

  assert.equal(result.ok, false);
  assert.ok(result.issues.some((issue) => issue.code === "sandbox_denied_global"));
}

async function assertHostedTickTimeoutReturnsDoNotMerge() {
  const source = `
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class HostedHangingBot extends ReefBotV1 {
  static metadata = {
    name: "hosted-hanging-bot",
    publisher: "Reef",
    email: "bots@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  };

  async onTick() {
    return new Promise(() => undefined);
  }
};`;

  const report = await hostedRunner.runHostedBotScenarioV1({
    source,
    fileName: "hosted-hanging-bot.js",
    fixture,
    compartmentFactory: createVmCompartmentFactory(),
    executionLimits: { tickTimeoutMs: 5 },
  });

  assert.equal(report.status, "do_not_merge");
  assert.ok(report.issues.some((issue) => issue.code === "hosted_execution_failed"));
  assert.match(report.issues[0].message, /onTick timed out/);
}

function createVmCompartmentFactory() {
  return {
    create(options) {
      return {
        evaluate(source) {
          const context = vm.createContext(Object.freeze({ ...options.endowments }));
          return new vm.Script(source, { filename: options.name }).runInContext(context, { timeout: 1000 });
        },
      };
    },
  };
}

function createThrowingCompartmentFactory() {
  return {
    create() {
      return {
        evaluate() {
          throw new Error("source with policy violations should not be evaluated");
        },
      };
    },
  };
}
