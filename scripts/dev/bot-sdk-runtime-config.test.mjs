import assert from "node:assert/strict";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const { resolveBotRuntimeConfigV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/runtime-config.ts")).href
);

const provider = {
  provider: "OpenBao",
  async readSecret(path) {
    return {
      "kv/bots/sample/v1": { maxInventory: 100, strategyEnabled: true },
      "kv/bots/sample/v1/name": "mean-reversion",
    }[path];
  },
};

const resolved = await resolveBotRuntimeConfigV1(
  [
    {
      key: "maxInventory",
      provider: "OpenBao",
      secretPath: "kv/bots/sample/v1",
      required: true,
      valueType: "number",
    },
    {
      key: "strategyEnabled",
      provider: "OpenBao",
      secretPath: "kv/bots/sample/v1",
      required: true,
      valueType: "boolean",
    },
    {
      key: "strategyName",
      provider: "OpenBao",
      secretPath: "kv/bots/sample/v1/name",
      required: true,
      valueType: "string",
    },
    {
      key: "optionalLimit",
      provider: "OpenBao",
      secretPath: "kv/bots/sample/v1/missing",
      required: false,
      valueType: "number",
    },
  ],
  provider,
);

assert.deepEqual(resolved.values, {
  maxInventory: 100,
  strategyEnabled: true,
  strategyName: "mean-reversion",
});
assert.equal(Object.isFrozen(resolved.values), true);

await assert.rejects(
  () =>
    resolveBotRuntimeConfigV1(
      [
        {
          key: "missingRequired",
          provider: "OpenBao",
          secretPath: "kv/bots/sample/v1/missing",
          required: true,
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
          key: "maxInventory",
          provider: "OpenBao",
          secretPath: "kv/bots/sample/v1",
          required: true,
          valueType: "string",
        },
      ],
      provider,
    ),
  /Bot runtime config maxInventory must be string/,
);

console.log("bot SDK runtime config checks passed");
