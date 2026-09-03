import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";

const workflowDirectory = new URL("../../.github/workflows/", import.meta.url);

async function readWorkflow(name) {
  return readFile(new URL(name, workflowDirectory), "utf8");
}

function jobBlocks(workflow) {
  const lines = workflow.split("\n");
  const jobsIndex = lines.findIndex((line) => line === "jobs:");
  assert.notEqual(jobsIndex, -1, "workflow must define jobs");

  const blocks = new Map();
  let currentName;
  let currentLines = [];

  for (const line of lines.slice(jobsIndex + 1)) {
    if (/^\S/.test(line)) break;
    const match = line.match(/^  ([a-zA-Z0-9_-]+):\s*$/);
    if (match) {
      if (currentName) blocks.set(currentName, currentLines.join("\n"));
      currentName = match[1];
      currentLines = [line];
    } else if (currentName) {
      currentLines.push(line);
    }
  }
  if (currentName) blocks.set(currentName, currentLines.join("\n"));
  return blocks;
}

function assertArtifactUploadsAlways(workflow, workflowName) {
  const uploadSteps = workflow.match(/      - name: Upload[^\n]*\n[\s\S]*?(?=\n      - name:|\n  [a-zA-Z0-9_-]+:|$)/g) ?? [];
  for (const step of uploadSteps.filter((candidate) => candidate.includes("actions/upload-artifact@"))) {
    assert.match(step, /\n        if: always\(\)/, `${workflowName} artifact uploads must run after failures`);
  }
}

const [ci, stress, autoMerge, materializer] = await Promise.all([
  readWorkflow("ci.yml"),
  readWorkflow("throughput-stress.yml"),
  readWorkflow("dependabot-auto-merge.yml"),
  readWorkflow("materializer-10k-gate.yml"),
]);

assert.match(
  stress,
  /^env:\n  ADMIN_API_TOKEN: local-admin$/m,
  "both stress lanes must receive the same ephemeral admin credential",
);
assert.equal(
  (stress.match(/DEV_STRESS_SUCCESS_GUARDRAIL_METRIC: valid-intent/g) ?? []).length,
  2,
  "both lifecycle stress lanes must gate valid intents instead of expected business rejects",
);
const runtimeDb = jobBlocks(stress).get("runtime-db");
assert.ok(runtimeDb, "runtime-db job must exist");
assert.match(runtimeDb, /name: Start DB-backed runtime stack[\s\S]*run: JS_RUNTIME=node make dev-up/);
assert.ok(
  runtimeDb.indexOf("Start DB-backed runtime stack") < runtimeDb.indexOf("Run db-backed stress iteration 1"),
  "DB-backed stack must start before load generation",
);
assert.match(stress, /report-health:\n[\s\S]*needs:\n      - runtime-nodb\n      - runtime-db/);
assert.match(stress, /report-health:\n[\s\S]*permissions:\n      issues: write/);
assert.match(stress, /if: \$\{\{ always\(\) && github\.event_name == 'schedule' \}\}/);

assert.match(ci, /on:\n[\s\S]*?  workflow_dispatch:/);
assert.match(
  jobBlocks(ci).get("scenario-replay"),
  /github\.event_name == 'workflow_dispatch'/,
  "manual full-CI runs must exercise scenario replay",
);
assert.match(ci, /^permissions:\n  contents: read$/m);
assert.match(ci, /concurrency:\n  group: ci-/);
assert.match(ci, /cancel-in-progress: \$\{\{ github\.event_name == 'pull_request' \}\}/);
assert.doesNotMatch(ci, /arduino\/setup-protoc@/, "proto setup must not depend on a deprecated Node runtime");
assert.match(ci, /protobuf\/releases\/download\/v33\.2\/protoc-33\.2-linux-x86_64\.zip/);
assert.match(ci, /b24b53f87c151bfd48b112fe4c3a6e6574e5198874f38036aff41df3456b8caf/);
const goVulnerabilityScan = jobBlocks(ci).get("go-vulnerability-scan");
assert.doesNotMatch(ci, /golang\/govulncheck-action@/, "vulnerability scanner install must be version-pinned");
assert.match(goVulnerabilityScan, /go-version: '1\.26\.x'/);
assert.match(goVulnerabilityScan, /cache-dependency-path: \$\{\{ matrix\.workdir \}\}\/go\.sum/);
assert.match(goVulnerabilityScan, /go install golang\.org\/x\/vuln\/cmd\/govulncheck@v1\.7\.0/);
assert.match(goVulnerabilityScan, /run: govulncheck \.\/\.\.\./);
const ciRequired = jobBlocks(ci).get("ci-required");
assert.ok(ciRequired, "CI must expose one stable required-check fan-in job");
for (const requiredJob of ["change-scope", ...fullCiJobNames()]) {
  assert.match(ciRequired, new RegExp(`      - ${requiredJob.replaceAll("-", "\\-")}`));
}
assert.match(ciRequired, /if: always\(\)/);
assert.match(ciRequired, /run: node scripts\/ci\/check-required-results\.mjs/);
assertArtifactUploadsAlways(ci, "CI");

assert.match(autoMerge, /^permissions:\n[\s\S]*?  actions: write$/m);
assert.match(autoMerge, /gh pr merge "\$PR_NUMBER" --repo "\$GH_REPO" --squash --match-head-commit "\$CI_HEAD_SHA"/);
assert.doesNotMatch(autoMerge, /gh pr merge[^\n]* --auto /);
assert.match(autoMerge, /gh workflow run ci\.yml --repo "\$GH_REPO" --ref "\$BASE_BRANCH"/);
assert.match(autoMerge, /pulls\/\$PR_NUMBER\/files/);
for (const workflow of ["container-images.yml", "docs-site.yml", "admin-ui-deploy.yml"]) {
  assert.match(
    autoMerge,
    new RegExp(`gh workflow run ${workflow.replace(".", "\\.")} --repo "\\$GH_REPO" --ref "\\$BASE_BRANCH"`),
  );
}

assert.match(materializer, /  schedule:\n    - cron:/);
assert.match(materializer, /ARGS="\$\{\{ inputs\.command \|\| 'plan' \}\}"/);

function fullCiJobNames() {
  return [
    "dependency-alignment",
    "proto-governance",
    "go-matching-engine",
    "go-simulator",
    "kotlin-platform-runtime",
    "kotlin-stock-data",
    "reef-arena-separation",
    "node-dev-tooling",
    "docs-site",
    "bot-sdk",
    "arena-admin",
    "scenario-replay",
    "container-builds",
    "infrastructure-config",
    "go-vulnerability-scan",
    "postgres-schema-placement",
  ];
}

const workflowNames = (await readdir(workflowDirectory))
  .filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"))
  .sort();

for (const workflowName of workflowNames) {
  const workflow = await readWorkflow(workflowName);
  const workflowPrefix = workflow.slice(0, workflow.indexOf("\njobs:\n"));
  assert.match(workflowPrefix, /^permissions:/m, `${workflowName} must declare least-privilege defaults`);

  for (const [jobName, block] of jobBlocks(workflow)) {
    assert.match(block, /^    timeout-minutes: \d+$/m, `${workflowName}:${jobName} must have a timeout`);
  }

  for (const match of workflow.matchAll(/uses:\s+([\w.-]+\/[\w.-]+)@([^\s#]+)/g)) {
    assert.match(
      match[2],
      /^[0-9a-f]{40}$/,
      `${workflowName} must pin ${match[1]} to an immutable full commit SHA`,
    );
  }

  for (const match of workflow.matchAll(/uses:\s+actions\/setup-go@[0-9a-f]{40}([\s\S]*?)(?=\n\s+- (?:uses|name):|$)/g)) {
    assert.match(match[1], /cache-dependency-path:/, `${workflowName} setup-go must cache the nested module`);
  }
}

console.log("CI workflow hardening guard checks passed");
