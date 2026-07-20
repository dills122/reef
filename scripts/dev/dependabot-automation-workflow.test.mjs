import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const dependabot = await readFile(new URL("../../.github/dependabot.yml", import.meta.url), "utf8");
const autoMerge = await readFile(new URL("../../.github/workflows/dependabot-auto-merge.yml", import.meta.url), "utf8");
const ci = await readFile(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8");

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
assert.match(dependabot, /dependency-name: "typescript"\n        versions:\n          - ">=7"/);

assert.match(autoMerge, /on:\n  workflow_run:/);
assert.match(autoMerge, /workflows:\n      - CI\n    types:\n      - completed/);
assert.doesNotMatch(autoMerge, /pull_request_target:/);
assert.match(autoMerge, /contents: write/);
assert.match(autoMerge, /issues: write/);
assert.match(autoMerge, /pull-requests: write/);
assert.match(autoMerge, /github\.event\.workflow_run\.event == 'pull_request'/);
assert.match(autoMerge, /github\.event\.workflow_run\.conclusion == 'success'/);
assert.match(autoMerge, /github\.event\.workflow_run\.actor\.login == 'dependabot\[bot\]'/);
assert.match(autoMerge, /github\.repository == 'dills122\/reef'/);
assert.match(autoMerge, /current_head=.*gh pr view/);
assert.match(autoMerge, /test "\$current_head" = "\$CI_HEAD_SHA"/);
assert.match(autoMerge, /merge_state=.*mergeStateStatus/);
assert.match(autoMerge, /if \[ "\$merge_state" = 'BEHIND' \]; then/);
assert.match(autoMerge, /gh pr comment "\$PR_NUMBER" --repo "\$GH_REPO" --body '@dependabot rebase'/);
assert.ok(
  autoMerge.indexOf("@dependabot rebase") < autoMerge.indexOf('gh pr merge "$PR_NUMBER"'),
  "stale Dependabot branches must rebase and rerun CI before auto-merge is enabled",
);
assert.match(autoMerge, /gh pr merge "\$PR_NUMBER" --repo "\$GH_REPO" --auto --squash/);
assert.doesNotMatch(autoMerge, /actions\/checkout/);
assert.doesNotMatch(autoMerge, /secrets\./);

assert.match(ci, /dependency-alignment:/);
assert.match(ci, /make check-dependency-alignment/);
assert.equal((ci.match(/run: go mod tidy -diff/g) ?? []).length, 2);
assert.match(ci, /kotlin-stock-data:/);
assert.match(ci, /run: \.\/gradlew --no-daemon test/);
assert.match(ci, /docs-site:/);
assert.match(ci, /run: npm ci --include=optional/);
assert.match(ci, /run: npm run typecheck/);
assert.match(ci, /github\.event_name == 'push' \|\| github\.event\.pull_request\.user\.login == 'dependabot\[bot\]'/);
assert.match(ci, /name: deploy-receiver\n            context: infra\/hetzner-core\/server\/deploy-receiver/);
assert.match(ci, /infrastructure-config:/);
assert.match(ci, /tofu fmt -check -recursive infra/);
assert.equal((ci.match(/-lockfile=readonly/g) ?? []).length, 2);
assert.match(ci, /name: Create placeholder hosted Compose env files/);
assert.match(ci, /for name in db postgres-admin postgres-analytics openbao matching-engine platform-runtime caddy simulator/);
assert.match(ci, /docker compose -f infra\/hetzner-core\/server\/docker-compose\.yml/);

console.log("dependabot automation workflow guard checks passed");
