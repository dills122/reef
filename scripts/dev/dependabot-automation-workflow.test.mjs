import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const dependabot = await readFile(new URL("../../.github/dependabot.yml", import.meta.url), "utf8");
const autoMerge = await readFile(new URL("../../.github/workflows/dependabot-auto-merge.yml", import.meta.url), "utf8");

const expectedDomainSchedules = new Map([
  ["matching-engine", "monday"],
  ["simulator", "tuesday"],
  ["jvm-services", "wednesday"],
  ["javascript-apps", "thursday"],
  ["infrastructure", "friday"],
]);

for (const [group, day] of expectedDomainSchedules) {
  assert.match(
    dependabot,
    new RegExp(`  ${group}:\\n    schedule:\\n      interval: "weekly"\\n      day: "${day}"\\n      time: "06:00"\\n      timezone: "America/Toronto"`),
  );
  assert.match(dependabot, new RegExp(`multi-ecosystem-group: "${group}"`));
}

for (const directory of [
  "/",
  "/apps/arena-admin",
  "/apps/docs-site",
  "/infra/do-benchmark",
  "/infra/hetzner-core/server",
  "/infra/hetzner-core/server/deploy-receiver",
  "/infra/hetzner-core/tofu",
  "/packages/bot-sdk",
  "/services/arena-control-plane",
  "/services/matching-engine",
  "/services/platform-runtime",
  "/services/simulator",
  "/services/stock-data",
]) {
  assert.match(dependabot, new RegExp(`"${directory.replaceAll("/", "\\/")}"`));
}

assert.match(dependabot, /package-ecosystem: "github-actions"/);
assert.match(dependabot, /day: "friday"\n      time: "18:00"/);
assert.match(dependabot, /repository-automation:\n        patterns:\n          - "\*"/);

assert.match(autoMerge, /on:\n  pull_request:/);
assert.doesNotMatch(autoMerge, /pull_request_target:/);
assert.match(autoMerge, /contents: write/);
assert.match(autoMerge, /pull-requests: write/);
assert.match(autoMerge, /github\.event\.pull_request\.user\.login == 'dependabot\[bot\]'/);
assert.match(autoMerge, /github\.repository == 'dills122\/reef'/);
assert.match(autoMerge, /gh pr merge --auto --squash "\$PR_URL"/);
assert.doesNotMatch(autoMerge, /actions\/checkout/);
assert.doesNotMatch(autoMerge, /secrets\./);

console.log("dependabot automation workflow guard checks passed");
