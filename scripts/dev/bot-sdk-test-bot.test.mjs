import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname;

const approved = runTester(
  "packages/bot-sdk/examples/technical-indicator-strategy-bot.ts",
  "packages/bot-sdk/fixtures/aapl-technical-indicator.json",
);
assert.equal(approved.status, 0, `approved bot tester failed\nstdout:\n${approved.stdout}\nstderr:\n${approved.stderr}`);
const approvedReport = JSON.parse(approved.stdout);
assert.equal(approvedReport.approvalStatus, "approved_for_merge");
assert.equal(approvedReport.artifact.approvedPackages[0].name, "trading-signals");
assert.equal(approvedReport.signalsGenerated, 3);
assert.equal(approvedReport.orderActionsProposed, 3);

const blocked = runTester("packages/bot-sdk/test-fixtures/bad-bots/too-many-orders-bot.ts", "packages/bot-sdk/fixtures/aapl-multi-tick.json");
assert.notEqual(blocked.status, 0, "blocked bot tester should exit nonzero");
const blockedReport = JSON.parse(blocked.stdout);
assert.equal(blockedReport.approvalStatus, "do_not_merge");
assert.ok(blockedReport.issues.some((issue) => issue.code === "max_order_actions_per_tick_exceeded"));

const unapprovedImport = runTester(
  "packages/bot-sdk/test-fixtures/bad-bots/unapproved-package-bot.ts",
  "packages/bot-sdk/fixtures/aapl-multi-tick.json",
);
assert.notEqual(unapprovedImport.status, 0, "unapproved package bot tester should exit nonzero");
const unapprovedImportReport = JSON.parse(unapprovedImport.stdout);
assert.equal(unapprovedImportReport.approvalStatus, "do_not_merge");
assert.equal(unapprovedImportReport.phase, "artifact_build");
assert.match(unapprovedImportReport.issues[0].message, /left-pad/);

const syncHang = runTester(
  "packages/bot-sdk/test-fixtures/bad-bots/sync-hanging-bot.ts",
  "packages/bot-sdk/fixtures/aapl-multi-tick.json",
  ["--wall-timeout-ms=250"],
);
assert.notEqual(syncHang.status, 0, "sync hanging bot tester should exit nonzero");
const syncHangReport = JSON.parse(syncHang.stdout);
assert.equal(syncHangReport.approvalStatus, "do_not_merge");
assert.ok(syncHangReport.issues.some((issue) => issue.code === "hosted_worker_timeout"));

console.log("bot SDK tester checks passed");

function runTester(botPath, fixturePath, extraArgs = []) {
  return spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-test-bot.mjs",
      botPath,
      fixturePath,
      "--summary-only",
      ...extraArgs,
    ],
    { cwd: repoRoot, encoding: "utf8" },
  );
}
