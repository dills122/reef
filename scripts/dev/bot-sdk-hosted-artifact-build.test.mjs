import "ses";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });

const repoRoot = new URL("../../", import.meta.url).pathname;
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-artifact-"));
const artifactPath = join(outDir, "simple-market-maker.bundle.js");
const manifestPath = join(outDir, "simple-market-maker.bundle.manifest.json");
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);
const { scanBotSourceForSandboxViolationsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/sandbox-policy.ts")).href
);

const build = spawnSync(
  "bun",
  [
    "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
    "packages/bot-sdk/examples/simple-market-maker.ts",
    `--out=${artifactPath}`,
    `--manifest-out=${manifestPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(build.status, 0, `artifact build failed\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);

const artifact = readFileSync(artifactPath, "utf8");
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
assert.equal(manifest.schemaVersion, "reef.bot.hostedArtifact.v1");
assert.equal(manifest.entryPath, "packages/bot-sdk/examples/simple-market-maker.ts");
assert.match(manifest.sourceHash, /^sha256:[a-f0-9]{64}$/);
assert.match(manifest.artifactHash, /^sha256:[a-f0-9]{64}$/);
assert.equal(artifact.includes("__reefBotSdk"), true);
assert.equal(artifact.includes("import "), false);
assert.equal(artifact.includes("export default"), false);
assert.deepEqual(scanBotSourceForSandboxViolationsV1(artifact), []);

const report = await hostedRunner.runHostedBotScenarioV1({
  source: artifact,
  fileName: "simple-market-maker.bundle.js",
  fixture,
});

assert.equal(report.status, "completed");
assert.equal(report.ticksRun, 3);
assert.equal(report.orderActionsProposed, 6);
assert.equal(report.dataCalls, 3);
assert.equal(report.ticks[0].venueCommands.length, 2);

console.log("bot SDK hosted artifact build checks passed");
