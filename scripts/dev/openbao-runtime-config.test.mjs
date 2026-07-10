import assert from "node:assert/strict";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { createOpenBaoRuntimeSecretProvider, openBaoKvV2DataPath, runtimeConfigPreflightReport } from "./lib/openbao-runtime-config.mjs";

const repoRoot = new URL("../../", import.meta.url).pathname;
const { resolveBotRuntimeConfigV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/runtime-config.ts")).href
);
const { runBotScenarioV1 } = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/runner.ts")).href);

assert.equal(openBaoKvV2DataPath("secret/bots/local/builtin-mm-simple"), "secret/data/bots/local/builtin-mm-simple");
assert.equal(openBaoKvV2DataPath("secret/data/bots/local/builtin-mm-simple"), "secret/data/bots/local/builtin-mm-simple");

const fetchCalls = [];
const provider = createOpenBaoRuntimeSecretProvider({
  baoAddr: "http://openbao.test",
  token: "runtime-token",
  fetchImpl: async (url, options) => {
    fetchCalls.push({ url, token: options?.headers?.["X-Vault-Token"] });
    assert.equal(options?.headers?.["X-Vault-Token"], "runtime-token");
    if (url === "http://openbao.test/v1/secret/data/bots/local/working-bot") {
      return jsonResponse(200, { data: { data: { instrumentId: "AAPL", orderSize: 3, spread: 0.25, enabled: true } } });
    }
    if (url === "http://openbao.test/v1/secret/data/bots/local/blob-bot") {
      return jsonResponse(200, {
        data: {
          data: {
            config_schema: "reef.bot-config.v1",
            config_json: JSON.stringify({
              instrumentId: "MSFT",
              orderSize: 7,
              nested: { ignoredByRuntimeDescriptors: true },
            }),
          },
        },
      });
    }
    if (url === "http://openbao.test/v1/secret/data/bots/local/type-mismatch") {
      return jsonResponse(200, { data: { data: { orderSize: "three" } } });
    }
    return jsonResponse(404, { errors: ["missing"] });
  },
});

await assert.rejects(
  () =>
    resolveBotRuntimeConfigV1(
      [
        {
          key: "missingRequired",
          provider: "OpenBao",
          secretPath: "secret/bots/local/missing",
          required: true,
          valueType: "string",
        },
      ],
      provider,
    ),
  /Missing required bot runtime config missingRequired/,
);

await assert.rejects(
  () =>
    resolveBotRuntimeConfigV1(
      [
        {
          key: "orderSize",
          provider: "OpenBao",
          secretPath: "secret/bots/local/type-mismatch",
          required: true,
          valueType: "number",
        },
      ],
      provider,
    ),
  /Bot runtime config orderSize must be number/,
);

const descriptors = [
  {
    key: "instrumentId",
    provider: "OpenBao",
    secretPath: "secret/bots/local/working-bot",
    required: true,
    valueType: "string",
  },
  {
    key: "orderSize",
    provider: "OpenBao",
    secretPath: "secret/bots/local/working-bot",
    required: true,
    valueType: "number",
  },
  {
    key: "spread",
    provider: "OpenBao",
    secretPath: "secret/bots/local/working-bot",
    required: true,
    valueType: "number",
  },
  {
    key: "enabled",
    provider: "OpenBao",
    secretPath: "secret/bots/local/working-bot",
    required: true,
    valueType: "boolean",
  },
];
const resolved = await resolveBotRuntimeConfigV1(descriptors, provider);
assert.deepEqual(resolved.values, {
  enabled: true,
  instrumentId: "AAPL",
  orderSize: 3,
  spread: 0.25,
});

const blobResolved = await resolveBotRuntimeConfigV1(
  [
    {
      key: "instrumentId",
      provider: "OpenBao",
      secretPath: "secret/bots/local/blob-bot",
      required: true,
      valueType: "string",
    },
    {
      key: "orderSize",
      provider: "OpenBao",
      secretPath: "secret/bots/local/blob-bot",
      required: true,
      valueType: "number",
    },
  ],
  provider,
);
assert.deepEqual(blobResolved.values, {
  instrumentId: "MSFT",
  orderSize: 7,
});

const report = runtimeConfigPreflightReport(provider, descriptors, resolved.values);
assert.deepEqual(report, {
  provider: "OpenBao",
  descriptorCount: 4,
  resolvedKeys: ["enabled", "instrumentId", "orderSize", "spread"],
});
const reportJson = JSON.stringify(report);
assert.equal(reportJson.includes("secretPath"), false);
assert.equal(reportJson.includes("secret/bots"), false);
assert.equal(reportJson.includes("AAPL"), false);
assert.equal(reportJson.includes("0.25"), false);

const seen = {};
class ConfigReadingBot {
  async onStart(ctx) {
    seen.instrumentId = ctx.config.string("instrumentId");
    seen.orderSize = ctx.config.number("orderSize");
    seen.spread = ctx.config.number("spread");
    seen.enabled = ctx.config.boolean("enabled");
  }

  async onTick(ctx) {
    return [ctx.actions.noop(ctx.config.string("instrumentId"))];
  }

  async onSignal() {
    return [];
  }

  async onStop() {}
}

const run = await runBotScenarioV1({
  BotClass: ConfigReadingBot,
  fixture: {
    scenarioId: "openbao-runtime-config-test",
    runId: "openbao-runtime-config-test-run",
    venueSessionId: "session-1",
    actorId: "actor-1",
    participantId: "participant-1",
    accountId: "account-1",
    botId: "working-bot",
    botVersion: "v1",
    correlationId: "corr-1",
    config: resolved.values,
    ticks: [
      {
        occurredAt: "2026-07-04T14:30:00.000Z",
        marketSnapshots: {
          AAPL: { instrumentId: "AAPL", asOf: "2026-07-04T14:30:00.000Z", midPrice: 100 },
        },
      },
    ],
  },
});

assert.equal(run.status, "completed");
assert.deepEqual(seen, {
  enabled: true,
  instrumentId: "AAPL",
  orderSize: 3,
  spread: 0.25,
});
assert.equal(run.ticks[0].actions[0].reason, "AAPL");
assert.ok(fetchCalls.some((call) => call.url === "http://openbao.test/v1/secret/data/bots/local/working-bot"));

console.log("OpenBao runtime config checks passed");

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status <= 299,
    status,
    async json() {
      return payload;
    },
  };
}
