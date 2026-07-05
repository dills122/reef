import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const { scanBotSourceForSandboxViolationsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/sandbox-policy.ts")).href
);

const allowed = readFileSync(join(repoRoot, "packages/bot-sdk/examples/hello-bot.ts"), "utf8");
assert.deepEqual(scanBotSourceForSandboxViolationsV1(allowed), []);

const denied = `
  import fs from "node:fs";
  setTimeout(() => undefined, 1);
  fetch("https://example.com");
  const socket = new WebSocket("wss://example.com");
  const fs = require("node:fs");
  const dynamic = require(moduleName);
`;
const violations = scanBotSourceForSandboxViolationsV1(denied);
const codes = violations.map((violation) => violation.code);
assert.ok(codes.includes("sandbox_denied_import"));
assert.ok(codes.includes("sandbox_denied_global"));
assert.ok(violations.some((violation) => violation.pattern === "fetch"));
assert.ok(violations.some((violation) => violation.pattern === "setTimeout"));
assert.ok(violations.some((violation) => violation.pattern === "node:fs"));
assert.ok(codes.includes("sandbox_dynamic_require"));

console.log("bot SDK sandbox policy checks passed");
