import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";

const repoRoot = new URL("../../", import.meta.url).pathname;
const examplesDir = join(repoRoot, "packages/bot-sdk/examples");
const badBotsDir = join(repoRoot, "packages/bot-sdk/test-fixtures/bad-bots");
const { qualifyBotV1, defaultBotRuntimePolicyV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/harness.ts")).href
);
const { toVenueCommandRequestsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-adapter.ts")).href
);
const requiredMetadataFields = ["name", "publisher", "email", "version", "sdkVersion", "botApiVersion"];
const forbiddenHostedApis = ["setTimeout", "setInterval", "fetch(", "http.", "https.", "child_process", "node:fs", "node:net"];

const emailPattern = /email:\s*["'][^@\s]+@[^@\s]+\.[^@\s]+["']/;

for (const fileName of readdirSync(examplesDir).filter((name) => name.endsWith(".ts"))) {
  const source = readFileSync(join(examplesDir, fileName), "utf8");

  assert.match(source, /extends\s+ReefBotV1/, `${fileName} must extend ReefBotV1`);
  assert.match(source, /static\s+override\s+metadata\s*=/, `${fileName} must define static metadata`);
  assert.match(source, /async\s+onTick\(/, `${fileName} must define onTick`);
  assert.match(source, emailPattern, `${fileName} must include a basic valid metadata email`);

  for (const field of requiredMetadataFields) {
    assert.match(source, new RegExp(`${field}:\\s*["']`), `${fileName} metadata missing ${field}`);
  }

  for (const forbiddenApi of forbiddenHostedApis) {
    assert.equal(source.includes(forbiddenApi), false, `${fileName} uses hosted-mode forbidden API ${forbiddenApi}`);
  }

  const registration = spawnSync("bun", ["scripts/dev/bot-sdk-register.mjs", `packages/bot-sdk/examples/${fileName}`], {
    cwd: repoRoot,
    encoding: "utf8",
  });

  assert.equal(
    registration.status,
    0,
    `${fileName} registration failed\nstdout:\n${registration.stdout}\nstderr:\n${registration.stderr}`,
  );

  const report = JSON.parse(registration.stdout);
  assert.equal(report.status, "accepted", `${fileName} must pass registration`);
  assert.match(report.sourceHash, /^sha256:[a-f0-9]{64}$/, `${fileName} must report source hash`);
  assert.equal(report.ticksRun, 5, `${fileName} must complete qualification ticks`);
}

await assertBadBot("forbidden-api-bot.ts", ["sandbox_denied_global"]);
await assertBadBot("invalid-metadata-bot.ts", ["invalid_bot_name", "invalid_bot_version", "invalid_email"]);
await assertBadBot("too-many-orders-bot.ts", ["max_order_actions_per_tick_exceeded"]);
assertVenueAdapter();

const designDoc = readFileSync(join(repoRoot, "docs/BOT_SDK_DESIGN.md"), "utf8");
assert.match(designDoc, /minimum allowed tick interval: `250ms`/);
assert.match(designDoc, /default tick interval: `500ms`/);
assert.match(designDoc, /max individual order actions per tick: `10`/);
assert.match(designDoc, /configuration-driven/);

console.log("bot SDK contract checks passed");

async function assertBadBot(fileName, expectedIssueCodes) {
  const filePath = join(badBotsDir, fileName);
  const source = readFileSync(filePath, "utf8");
  const sourceHash = `sha256:${createHash("sha256").update(source, "utf8").digest("hex")}`;
  const module = await import(pathToFileURL(filePath).href);
  const report = await qualifyBotV1({
    fileName,
    source,
    sourceHash,
    BotClass: module.default,
    registryEntries: [
      {
        fileName,
        botId: fileName.replace(/\.ts$/, ""),
        owner: "reef-test",
        publisher: "Reef Test Fixtures",
        approvedVersion: "1.0.0",
        status: "draft",
      },
    ],
    tickCount: 1,
    policy: defaultBotRuntimePolicyV1,
    fixtureData: {
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
    },
  });

  assert.equal(report.status, "do_not_merge", `${fileName} must be rejected`);
  const issueCodes = report.issues.map((issue) => issue.code);
  for (const expectedIssueCode of expectedIssueCodes) {
    assert.ok(
      issueCodes.includes(expectedIssueCode),
      `${fileName} missing expected issue ${expectedIssueCode}; got ${issueCodes.join(", ")}`,
    );
  }
}

function assertVenueAdapter() {
  const submit = toVenueCommandRequestsV1(
    [
      {
        type: "submit_limit",
        order: {
          instrumentId: "AAPL",
          side: "BUY",
          quantity: 10,
          limitPrice: 99.5,
          clientOrderId: "bot-order-1",
        },
      },
    ],
    venueAdapterContext(),
  );

  assert.equal(submit.ok, true);
  assert.equal(submit.value.length, 1);
  assert.equal(submit.value[0].route, "/api/v1/orders/submit");
  assert.equal(submit.value[0].headers["X-Client-Id"], "bot:bot-1");
  assert.equal(submit.value[0].headers["Idempotency-Key"], "idem-bot-1");
  assert.equal(submit.value[0].body.commandId, "cmd-bot-1");
  assert.equal(submit.value[0].body.orderType, "LIMIT");
  assert.equal(submit.value[0].body.quantityUnits, "10");
  assert.equal(submit.value[0].body.botId, "bot-1");
  assert.equal(submit.value[0].body.venueSessionId, "session-1");

  const override = toVenueCommandRequestsV1(
    [
      {
        type: "cancel_order",
        order: {
          orderId: "bot-order-1",
          instrumentId: "AAPL",
        },
      },
    ],
    { ...venueAdapterContext(), clientId: "configured-bot-client" },
  );

  assert.equal(override.ok, true);
  assert.equal(override.value[0].headers["X-Client-Id"], "configured-bot-client");

  const market = toVenueCommandRequestsV1(
    [
      {
        type: "submit_market",
        order: {
          instrumentId: "AAPL",
          side: "BUY",
          quantity: 10,
        },
      },
    ],
    venueAdapterContext(),
  );

  assert.equal(market.ok, false);
  assert.equal(market.denial.code, "NOT_ALLOWED");
}

function venueAdapterContext() {
  return {
    runId: "run-1",
    venueSessionId: "session-1",
    actorId: "actor-bot-1",
    participantId: "participant-bot-1",
    accountId: "account-bot-1",
    botId: "bot-1",
    botVersion: "1.0.0",
    correlationId: "corr-bot",
    occurredAt: "2026-07-04T14:30:00.000Z",
    commandIdPrefix: "cmd-bot",
    traceIdPrefix: "trace-bot",
    idempotencyKeyPrefix: "idem-bot",
  };
}
