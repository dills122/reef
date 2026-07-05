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
await assertLifecycleSafeMarketMakerRun();
await assertRefreshingMarketMakerRun();
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
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "99.5");
  assert.equal(report.ticks[1].venueCommands[0].body.limitPrice, "100");
  assert.equal(report.ticks[2].venueCommands[1].body.limitPrice, "101.25");
  assert.equal(report.finalOrders.length, 6);
  assert.equal(transport.requests.length, 6);
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

async function assertRefreshingMarketMakerRun() {
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
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.ticks[1].venueCommands.length, 2);
  assert.equal(report.ticks[1].venueCommands[0].route, "/api/v1/orders/cancel");
  assert.equal(report.ticks[2].venueCommands.length, 2);
  assert.equal(report.ticks[2].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.finalOrders.length, 2);
  assert.equal(transport.requests.length, 6);
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
