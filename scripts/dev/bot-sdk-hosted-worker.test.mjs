import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname;
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-worker-"));
const artifactPath = join(outDir, "simple-market-maker.bundle.js");
const hangingArtifactPath = join(outDir, "sync-hanging-bot.bundle.js");

const build = spawnSync(
  "bun",
  [
    "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
    "packages/bot-sdk/examples/simple-market-maker.ts",
    `--out=${artifactPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(build.status, 0, `artifact build failed\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);

const run = spawnSync(
  "bun",
  ["scripts/dev/bot-sdk-hosted-worker-run.mjs", artifactPath, "packages/bot-sdk/fixtures/aapl-multi-tick.json"],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(run.status, 0, `hosted worker run failed\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}`);
const report = JSON.parse(run.stdout);
assert.equal(report.status, "completed");
assert.equal(report.orderActionsProposed, 6);

writeFileSync(
  hangingArtifactPath,
  `
while (true) {}
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class SyncHangingBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("never")]; } };
`,
);
const hangingRun = spawnSync(
  "bun",
  ["scripts/dev/bot-sdk-hosted-worker-run.mjs", hangingArtifactPath, "packages/bot-sdk/fixtures/aapl-multi-tick.json", "--wall-timeout-ms=250"],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(hangingRun.status, 1);
const hangingReport = JSON.parse(hangingRun.stdout);
assert.equal(hangingReport.status, "do_not_merge");
assert.ok(hangingReport.issues.some((issue) => issue.code === "hosted_worker_timeout"));

console.log("bot SDK hosted worker checks passed");
