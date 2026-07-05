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

console.log("bot SDK tester checks passed");

function runTester(botPath, fixturePath) {
  return spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-test-bot.mjs",
      botPath,
      fixturePath,
      "--summary-only",
    ],
    { cwd: repoRoot, encoding: "utf8" },
  );
}
