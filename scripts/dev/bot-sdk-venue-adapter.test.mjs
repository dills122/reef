import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const botPath = join(repoRoot, "packages/bot-sdk/examples/simple-market-maker.ts");
const source = readFileSync(botPath, "utf8");
const sourceHash = `sha256:${createHash("sha256").update(source, "utf8").digest("hex")}`;
const botModule = await import(pathToFileURL(botPath).href);
const { qualifyBotV1, createFixtureBotContextV1, defaultBotRuntimePolicyV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/harness.ts")).href
);
const { expandCancelAllActionsV1, toVenueCommandRequestsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-adapter.ts")).href
);

const fixtureData = {
  config: {
    instrumentId: "AAPL",
    orderSize: 10,
    spread: 1,
  },
  marketSnapshots: {
    AAPL: {
      instrumentId: "AAPL",
      asOf: "2026-07-04T14:30:00.000Z",
      bidPrice: 99.5,
      askPrice: 100.5,
      midPrice: 100,
      lastPrice: 100,
    },
  },
  currentOrders: [],
  orderHistory: [],
};

const qualification = await qualifyBotV1({
  fileName: "simple-market-maker.ts",
  source,
  sourceHash,
  BotClass: botModule.default,
  registryEntries: [
    {
      fileName: "simple-market-maker.ts",
      botId: "simple-market-maker",
      owner: "reef",
      publisher: "Reef Examples",
      approvedVersion: "1.0.0",
      status: "draft",
    },
  ],
  tickCount: 1,
  policy: defaultBotRuntimePolicyV1,
  fixtureData,
});

assert.equal(qualification.status, "accepted");

const bot = new botModule.default();
const actions = await bot.onTick(
  createFixtureBotContextV1({
    policy: defaultBotRuntimePolicyV1,
    fixtureData,
  }),
);

const commands = toVenueCommandRequestsV1(actions, {
  scenarioId: "scenario-1",
  runId: "run-1",
  runKind: "scenario",
  venueSessionId: "session-1",
  actorId: "bot-actor-1",
  participantId: "participant-1",
  accountId: "account-1",
  botId: "simple-market-maker",
  botVersion: "1.0.0",
  correlationId: "corr-1",
  occurredAt: "2026-07-04T14:30:00.000Z",
  commandIdPrefix: "cmd-simple-mm",
  traceIdPrefix: "trace-simple-mm",
  idempotencyKeyPrefix: "idem-simple-mm",
});

assert.equal(commands.ok, true);
assert.equal(commands.value.length, 2);

const [bid, ask] = commands.value;
assert.equal(bid.route, "/api/v1/orders/submit");
assert.equal(ask.route, "/api/v1/orders/submit");

assert.deepEqual(Object.keys(bid.body).sort(), [
  "accountId",
  "actorId",
  "botId",
  "botVersion",
  "commandId",
  "correlationId",
  "currency",
  "instrumentId",
  "limitPrice",
  "occurredAt",
  "orderId",
  "orderType",
  "participantId",
  "quantityUnits",
  "runId",
  "runKind",
  "scenarioId",
  "side",
  "timeInForce",
  "traceId",
  "venueSessionId",
]);
assert.equal(bid.headers["Idempotency-Key"], "idem-simple-mm-1");
assert.equal(ask.headers["Idempotency-Key"], "idem-simple-mm-2");
assert.equal(bid.headers["X-Client-Id"], "bot:simple-market-maker");
assert.equal(ask.headers["X-Client-Id"], "bot:simple-market-maker");
assert.equal(bid.body.commandId, "cmd-simple-mm-1");
assert.equal(ask.body.commandId, "cmd-simple-mm-2");
assert.equal(bid.body.scenarioId, "scenario-1");
assert.equal(ask.body.runKind, "scenario");
assert.equal(bid.body.traceId, "trace-simple-mm-1");
assert.equal(ask.body.traceId, "trace-simple-mm-2");
assert.equal(bid.body.side, "BUY");
assert.equal(ask.body.side, "SELL");
assert.equal(bid.body.quantityUnits, "10");
assert.equal(ask.body.quantityUnits, "10");
assert.equal(bid.body.limitPrice, "99500000000");
assert.equal(ask.body.limitPrice, "100500000000");
assert.equal(bid.body.orderType, "LIMIT");
assert.equal(ask.body.timeInForce, "DAY");
assert.equal(bid.body.botId, "simple-market-maker");
assert.equal(bid.body.botVersion, "1.0.0");
assert.equal(bid.body.runId, "run-1");
assert.equal(bid.body.venueSessionId, "session-1");

const expandedCancelAll = expandCancelAllActionsV1(
  [{ type: "cancel_all", instrumentId: "AAPL" }],
  [
    {
      orderId: "open-aapl",
      instrumentId: "AAPL",
      side: "BUY",
      quantity: 10,
      remainingQuantity: 10,
      limitPrice: 99,
      status: "OPEN",
    },
    {
      orderId: "filled-aapl",
      instrumentId: "AAPL",
      side: "SELL",
      quantity: 10,
      remainingQuantity: 0,
      limitPrice: 101,
      status: "FILLED",
    },
    {
      orderId: "open-msft",
      instrumentId: "MSFT",
      side: "BUY",
      quantity: 10,
      remainingQuantity: 10,
      limitPrice: 200,
      status: "OPEN",
    },
  ],
);
assert.equal(expandedCancelAll.length, 1);
assert.equal(expandedCancelAll[0].type, "cancel_order");
assert.equal(expandedCancelAll[0].order.orderId, "open-aapl");

const badSubmitPrice = toVenueCommandRequestsV1(
  [{ type: "submit_limit", order: { instrumentId: "AAPL", side: "BUY", quantity: 10, limitPrice: Number.NaN } }],
  {
    runId: "run-1",
    venueSessionId: "session-1",
    actorId: "bot-actor-1",
    participantId: "participant-1",
    accountId: "account-1",
    botId: "simple-market-maker",
    botVersion: "1.0.0",
    correlationId: "corr-1",
    occurredAt: "2026-07-04T14:30:00.000Z",
    commandIdPrefix: "cmd-simple-mm",
    traceIdPrefix: "trace-simple-mm",
    idempotencyKeyPrefix: "idem-simple-mm",
  },
);
assert.equal(badSubmitPrice.ok, false);
assert.equal(badSubmitPrice.denial.code, "NOT_ALLOWED");

const badModifyPrice = toVenueCommandRequestsV1(
  [{ type: "modify_order", order: { orderId: "open-aapl", instrumentId: "AAPL", quantity: 10, limitPrice: -1 } }],
  {
    runId: "run-1",
    venueSessionId: "session-1",
    actorId: "bot-actor-1",
    participantId: "participant-1",
    accountId: "account-1",
    botId: "simple-market-maker",
    botVersion: "1.0.0",
    correlationId: "corr-1",
    occurredAt: "2026-07-04T14:30:00.000Z",
    commandIdPrefix: "cmd-simple-mm",
    traceIdPrefix: "trace-simple-mm",
    idempotencyKeyPrefix: "idem-simple-mm",
  },
);
assert.equal(badModifyPrice.ok, false);
assert.equal(badModifyPrice.denial.code, "NOT_ALLOWED");

console.log("bot SDK venue adapter smoke checks passed");
