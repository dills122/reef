import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../..", import.meta.url));
const scriptExts = new Set([".js", ".mjs", ".sh", ".ts"]);
const nodeCheckExts = new Set([".js", ".mjs"]);
const shellCheckExts = new Set([".sh"]);
const skipDirs = new Set([
  ".git",
  ".gradle",
  ".idea",
  ".codex",
  ".claude",
  "build",
  "coverage",
  "dist",
  "node_modules",
  "reports",
]);

const scriptFiles = walk("scripts")
  .filter((path) => scriptExts.has(extension(path)))
  .sort();

const failures = [];
const syntaxChecked = [];
const shellChecked = [];
const syntaxSkipped = [];

for (const path of scriptFiles) {
  if (nodeCheckExts.has(extension(path))) {
    runCheck(process.execPath, ["--check", path], `${path} JS syntax`);
    syntaxChecked.push(path);
    continue;
  }
  if (shellCheckExts.has(extension(path))) {
    runCheck("bash", ["-n", path], `${path} shell syntax`);
    shellChecked.push(path);
    continue;
  }
  syntaxSkipped.push(path);
}

const repoFiles = walk(".")
  .filter((path) => path !== ".")
  .filter((path) => isReadableTextCandidate(path))
  .sort();
const repoText = new Map();
for (const path of repoFiles) {
  try {
    repoText.set(path, readFileSync(path, "utf8"));
  } catch {
    // Permission-restricted local agent/plugin files are intentionally ignored.
  }
}

const controlFiles = [
  "Makefile",
  "package.json",
  ...walk(".github").filter((path) => /\.(ya?ml)$/.test(path)),
].filter((path) => existsSync(path));
const controlText = controlFiles.map((path) => repoText.get(path) ?? readFileSync(path, "utf8")).join("\n");

const unreferencedEntrypoints = [];
const unwiredTests = [];

for (const path of scriptFiles) {
  if (path.includes("/lib/")) continue;
  if (path.includes("/db/migrations/")) continue;

  if (path.endsWith(".test.mjs")) {
    if (!controlText.includes(path)) {
      unwiredTests.push(path);
    }
    continue;
  }

  if (!hasReference(path)) {
    unreferencedEntrypoints.push(path);
  }
}

if (unreferencedEntrypoints.length > 0) {
  failures.push([
    "script entrypoints without a Makefile/package/CI/docs/import reference:",
    ...unreferencedEntrypoints.map((path) => `  - ${path}`),
  ].join("\n"));
}

if (unwiredTests.length > 0) {
  failures.push([
    "script tests not wired into Makefile, package.json, or CI:",
    ...unwiredTests.map((path) => `  - ${path}`),
  ].join("\n"));
}

const rawInternalArenaScriptCallers = scriptFiles
  .filter((path) => !path.includes("/db/migrations/"))
  .filter((path) => path !== "scripts/dev/script-surface-check.mjs")
  .filter((path) => (repoText.get(path) ?? "").includes("/internal/admin/arena"))
  .sort();
if (rawInternalArenaScriptCallers.length > 0) {
  failures.push([
    "script callers must use /admin/v1/arena/... instead of raw /internal/admin/arena/...:",
    ...rawInternalArenaScriptCallers.map((path) => `  - ${path}`),
  ].join("\n"));
}

const rawInternalRiskScriptCallers = scriptFiles
  .filter((path) => !path.includes("/db/migrations/"))
  .filter((path) => path !== "scripts/dev/script-surface-check.mjs")
  .filter((path) => {
    const text = repoText.get(path) ?? "";
    return [
      "/internal/admin/account-risk",
      "/internal/admin/circuit-breakers",
      "/internal/admin/price-collars",
      "/internal/boundary/account-risk",
      "/internal/boundary/circuit-breakers",
      "/internal/boundary/price-collars",
    ].some((route) => text.includes(route));
  })
  .sort();
if (rawInternalRiskScriptCallers.length > 0) {
  failures.push([
    "script callers must use /admin/v1/risk/... instead of raw risk-control /internal/... routes:",
    ...rawInternalRiskScriptCallers.map((path) => `  - ${path}`),
  ].join("\n"));
}

const rawInternalSettlementScriptCallers = scriptFiles
  .filter((path) => !path.includes("/db/migrations/"))
  .filter((path) => path !== "scripts/dev/script-surface-check.mjs")
  .filter((path) => (repoText.get(path) ?? "").includes("/internal/admin/settlement/"))
  .sort();
if (rawInternalSettlementScriptCallers.length > 0) {
  failures.push([
    "script callers must use /admin/v1/settlement/... instead of raw /internal/admin/settlement/...:",
    ...rawInternalSettlementScriptCallers.map((path) => `  - ${path}`),
  ].join("\n"));
}

checkWorkflowSecurity();
checkArenaRunnerTimeoutContainment();

if (failures.length > 0) {
  console.error(failures.join("\n\n"));
  process.exit(1);
}

console.log("script surface check passed");
console.log(`  scripts=${scriptFiles.length}`);
console.log(`  nodeSyntaxChecked=${syntaxChecked.length}`);
console.log(`  shellSyntaxChecked=${shellChecked.length}`);
if (syntaxSkipped.length > 0) {
  console.log(`  syntaxSkipped=${syntaxSkipped.join(", ")}`);
}
console.log(`  controlFiles=${controlFiles.join(", ")}`);

function hasReference(scriptPath) {
  const localReference = `./${scriptPath.split("/").pop()}`;
  for (const [path, text] of repoText) {
    if (path === scriptPath) continue;
    if (text.includes(scriptPath) || text.includes(localReference)) return true;
  }
  return false;
}

function runCheck(cmd, args, label) {
  const result = spawnSync(cmd, args, {
    cwd: repoRoot,
    encoding: "utf8",
  });
  if (result.status === 0) return;
  failures.push([
    `${label} failed with status ${result.status ?? "signal"}`,
    result.stdout.trim(),
    result.stderr.trim(),
  ].filter(Boolean).join("\n"));
}

function checkWorkflowSecurity() {
  const botSubmission = textFile(".github/workflows/bot-submission.yml");
  const botProvision = textFile(".github/workflows/bot-submission-provision.yml");
  const registrySync = textFile(".github/workflows/bot-registry-sync.yml");
  const publishImage = textFile(".github/workflows/publish-image.yml");

  requireNotIncludes(
    ".github/workflows/bot-submission.yml",
    botSubmission,
    "ARENA_ADMIN_API_TOKEN",
    "pull_request bot-submission workflow must not receive hosted admin API tokens",
  );
  requireNotIncludes(
    ".github/workflows/bot-submission.yml",
    botSubmission,
    "id-token: write",
    "pull_request bot-submission workflow must not mint GitHub OIDC tokens",
  );
  requireIncludes(
    ".github/workflows/bot-submission.yml",
    botSubmission,
    "::error::Bot files changed",
    "off-convention bot changes must fail instead of warning only",
  );
  requireIncludes(
    ".github/workflows/bot-submission.yml",
    botSubmission,
    "exit 1",
    "off-convention bot changes must fail instead of warning only",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "workflow_run:",
    "hosted bot provisioning must run from the trusted workflow_run workflow",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "statuses: write",
    "trusted provisioning workflow must be able to publish the required branch-protection status",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "bot-submission-commit-status.mjs",
    "trusted provisioning workflow must publish the registry-diff-and-provision commit status",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "ref: ${{ github.event.repository.default_branch }}",
    "trusted provisioning workflow must checkout base-branch scripts",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    'HEAD_REPOSITORY" != "$GITHUB_REPOSITORY"',
    "trusted provisioning workflow must reject forked PR workflow runs",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "bots/add/*) flow=\"add\"",
    "trusted provisioning workflow must classify bot add branches before provisioning",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "bots/update/*) flow=\"update\"",
    "trusted provisioning workflow must classify bot update branches before provisioning",
  );
  requireIncludes(
    ".github/workflows/bot-submission-provision.yml",
    botProvision,
    "bots/remove/*) flow=\"remove\"",
    "trusted provisioning workflow must classify bot remove branches before provisioning",
  );
  requireNotIncludes(
    ".github/workflows/bot-registry-sync.yml",
    registrySync,
    'requested="${{ inputs.manifest_path',
    "workflow_dispatch manifest_path must not be interpolated directly into shell",
  );
  requireIncludes(
    ".github/workflows/bot-registry-sync.yml",
    registrySync,
    "REQUESTED_MANIFEST: ${{ inputs.manifest_path || '' }}",
    "workflow_dispatch manifest_path must be passed through environment and validated",
  );
  requireNotIncludes(
    ".github/workflows/bot-registry-sync.yml",
    registrySync,
    'printf \'%s\' "${{ steps.manifests.outputs.paths }}"',
    "manifest list output must not be interpolated directly into shell",
  );
  requireNotIncludes(
    ".github/workflows/publish-image.yml",
    publishImage,
    ":${{ inputs.tag }}",
    "workflow_dispatch image tag must not be interpolated directly into shell output",
  );
  requireIncludes(
    ".github/workflows/publish-image.yml",
    publishImage,
    "IMAGE_TAG: ${{ inputs.tag }}",
    "workflow_dispatch image tag must be passed through environment and validated",
  );
  requireIncludes(
    ".github/workflows/publish-image.yml",
    publishImage,
    "image tag must be 1-128 chars",
    "workflow_dispatch image tag must be bounded before use",
  );
}

function checkArenaRunnerTimeoutContainment() {
  const runnerScripts = [
    "scripts/dev/arena-runner-pool-smoke.mjs",
    "scripts/dev/arena-runner-tick-smoke.mjs",
    "scripts/dev/arena-local-tick-run.mjs",
  ];

  for (const path of runnerScripts) {
    const text = textFile(path);
    requireIncludes(path, text, "request(message, timeoutMs = 10000)", "runner worker requests must have bounded execution time");
    requireIncludes(path, text, "this.pending.delete(id);", "timed-out runner requests must be removed from the pending map");
    requireIncludes(path, text, 'this.child.kill("SIGKILL")', "timed-out runner workers must be terminated");
    requireIncludes(path, text, "timed out after ${timeoutMs}ms", "timed-out runner requests must return a bounded timeout error");
  }
}

function requireIncludes(path, text, needle, message) {
  if (text.includes(needle)) return;
  failures.push(`${path}: ${message}`);
}

function requireNotIncludes(path, text, needle, message) {
  if (!text.includes(needle)) return;
  failures.push(`${path}: ${message}`);
}

function textFile(path) {
  return repoText.get(path) ?? (existsSync(path) ? readFileSync(path, "utf8") : "");
}

function walk(start) {
  if (!existsSync(start)) return [];
  const out = [];
  visit(start);
  return out.map(toPosix);

  function visit(path) {
    const stat = statSync(path);
    if (stat.isDirectory()) {
      const name = path.split(sep).pop();
      if (name && skipDirs.has(name)) return;
      for (const entry of readdirSync(path).sort()) {
        visit(`${path}${sep}${entry}`);
      }
      return;
    }
    if (stat.isFile()) out.push(path);
  }
}

function isReadableTextCandidate(path) {
  return /(^Makefile$|\.md$|\.json$|\.ya?ml$|\.toml$|\.mjs$|\.js$|\.ts$|\.sh$|\.kt$|\.go$)$/.test(path);
}

function extension(path) {
  const dot = path.lastIndexOf(".");
  return dot === -1 ? "" : path.slice(dot);
}

function toPosix(path) {
  return relative(repoRoot, path).split(sep).join("/");
}
