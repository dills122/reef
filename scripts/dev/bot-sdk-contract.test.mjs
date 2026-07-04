import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname;
const examplesDir = join(repoRoot, "packages/bot-sdk/examples");
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

const designDoc = readFileSync(join(repoRoot, "docs/BOT_SDK_DESIGN.md"), "utf8");
assert.match(designDoc, /minimum allowed tick interval: `250ms`/);
assert.match(designDoc, /default tick interval: `500ms`/);
assert.match(designDoc, /max individual order actions per tick: `10`/);
assert.match(designDoc, /configuration-driven/);

console.log("bot SDK contract checks passed");
