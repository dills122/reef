import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname;
const examplesDir = join(repoRoot, "packages/bot-sdk/examples");
const badBotsDir = join(repoRoot, "packages/bot-sdk/test-fixtures/bad-bots");
const { qualifyBotV1, defaultBotRuntimePolicyV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/harness.ts")).href
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
  assert.equal(report.ticksRun, 5, `${fileName} must complete qualification ticks`);
}

await assertBadBot("forbidden-api-bot.ts", ["hosted_api_forbidden"]);
await assertBadBot("invalid-metadata-bot.ts", ["invalid_bot_name", "invalid_bot_version", "invalid_email"]);
await assertBadBot("too-many-orders-bot.ts", ["max_order_actions_per_tick_exceeded"]);

const designDoc = readFileSync(join(repoRoot, "docs/BOT_SDK_DESIGN.md"), "utf8");
assert.match(designDoc, /minimum allowed tick interval: `250ms`/);
assert.match(designDoc, /default tick interval: `500ms`/);
assert.match(designDoc, /max individual order actions per tick: `10`/);
assert.match(designDoc, /configuration-driven/);

console.log("bot SDK contract checks passed");

async function assertBadBot(fileName, expectedIssueCodes) {
  const filePath = join(badBotsDir, fileName);
  const source = readFileSync(filePath, "utf8");
  const module = await import(pathToFileURL(filePath).href);
  const report = await qualifyBotV1({
    fileName,
    source,
    BotClass: module.default,
    registryFileNames: [fileName],
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
