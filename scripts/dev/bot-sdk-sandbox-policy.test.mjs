import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const {
  reefBotApprovedPackagesV1,
  reefBotHostedSourceSandboxPolicyV1,
  scanBotSourceForSandboxViolationsV1,
} = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/sandbox-policy.ts")).href
);
const approvedPackagesJson = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/approved-packages.v1.json"), "utf8"));

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

const inertText = `
  // fetch("https://example.com");
  const message = "setTimeout and process are words here";
  const payload = { fetch: "field-name-only" };
`;
assert.deepEqual(scanBotSourceForSandboxViolationsV1(inertText), []);

const approvedPackageSource = 'import { RSI } from "trading-signals";\nexport default class Example {}';
assert.ok(reefBotApprovedPackagesV1.some((pkg) => pkg.name === "trading-signals" && pkg.version === "7.4.3"));
assert.deepEqual(scanBotSourceForSandboxViolationsV1(approvedPackageSource, reefBotHostedSourceSandboxPolicyV1), []);
assert.ok(scanBotSourceForSandboxViolationsV1(approvedPackageSource).some((violation) => violation.pattern === "trading-signals"));
assert.ok(
  scanBotSourceForSandboxViolationsV1('import { sum } from "left-pad";', reefBotHostedSourceSandboxPolicyV1).some(
    (violation) => violation.pattern === "left-pad",
  ),
);
assert.equal(approvedPackagesJson.schemaVersion, "reef.bot.approvedPackages.v1");
assert.deepEqual(
  approvedPackagesJson.packages.map(packageIdentity),
  reefBotApprovedPackagesV1.map(packageIdentity),
);

console.log("bot SDK sandbox policy checks passed");

function packageIdentity(pkg) {
  return {
    name: pkg.name,
    version: pkg.version,
    license: pkg.license,
    status: pkg.status,
  };
}
