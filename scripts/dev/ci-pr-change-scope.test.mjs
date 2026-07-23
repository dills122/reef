import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { classifyPullRequestScope } from "./ci-pr-change-scope.mjs";

const exactFiles = [
  { filename: "bots/noodle-invite-smoke/bot.json", status: "added" },
  { filename: "bots/noodle-invite-smoke/index.ts", status: "added" },
];

assert.deepEqual(
  classifyPullRequestScope({
    headRef: "bots/add/noodle-invite-smoke",
    pages: [exactFiles],
    expectedChangedFiles: exactFiles.length,
  }),
  {
    botOnly: true,
    runFullCi: false,
    flow: "add",
    botName: "noodle-invite-smoke",
    reason: "exact_bot_directory_only",
  },
);

for (const input of [
  { headRef: "feature/noodle", pages: [exactFiles], expectedChangedFiles: exactFiles.length, reason: "branch_not_bot_submission" },
  { headRef: "bots/add/noodle-invite-smoke", pages: [[]], expectedChangedFiles: 0, reason: "no_changed_files" },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [[...exactFiles, { filename: ".github/workflows/ci.yml" }]],
    expectedChangedFiles: exactFiles.length + 1,
    reason: "path_outside_submitted_bot",
  },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [[...exactFiles, { filename: "bots/other-bot/index.ts" }]],
    expectedChangedFiles: exactFiles.length + 1,
    reason: "path_outside_submitted_bot",
  },
  {
    headRef: "bots/update/noodle-invite-smoke",
    pages: [[{
      filename: "bots/noodle-invite-smoke/index.ts",
      previous_filename: "packages/bot-sdk/index.ts",
    }]],
    expectedChangedFiles: 1,
    reason: "path_outside_submitted_bot",
  },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [[{ filename: "bots/noodle-invite-smoke/../other-bot/index.ts" }]],
    expectedChangedFiles: 1,
    reason: "path_outside_submitted_bot",
  },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [[{ status: "added" }]],
    expectedChangedFiles: 1,
    reason: "invalid_changed_file_metadata",
  },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [exactFiles],
    expectedChangedFiles: exactFiles.length + 1,
    reason: "incomplete_changed_file_list",
  },
  {
    headRef: "bots/add/noodle-invite-smoke",
    pages: [[exactFiles[0], exactFiles[0]]],
    expectedChangedFiles: 2,
    reason: "duplicate_changed_file_metadata",
  },
]) {
  const result = classifyPullRequestScope(input);
  assert.equal(result.botOnly, false);
  assert.equal(result.runFullCi, true);
  assert.equal(result.reason, input.reason);
}

const ci = await readFile(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8");
const botSubmission = await readFile(new URL("../../.github/workflows/bot-submission.yml", import.meta.url), "utf8");

assert.match(ci, /change-scope:/);
assert.match(ci, /ref: \$\{\{ github\.event\.pull_request\.base\.sha \}\}/);
assert.match(ci, /if \[ ! -f scripts\/dev\/ci-pr-change-scope\.mjs \]; then/);
assert.match(ci, /echo "run-full-ci=true"/);
assert.match(ci, /\} >> "\$GITHUB_OUTPUT"/);
assert.match(ci, /gh api --paginate --slurp "repos\/\$\{GITHUB_REPOSITORY\}\/pulls\/\$\{PR_NUMBER\}\/files"/);
assert.match(ci, /node scripts\/dev\/ci-pr-change-scope\.mjs \/tmp\/reef-pr-files\.json/);
assert.match(ci, /EXPECTED_CHANGED_FILES: \$\{\{ github\.event\.pull_request\.changed_files/);

const jobs = ci.slice(ci.indexOf("\njobs:\n") + "\njobs:\n".length);
const jobNames = [...jobs.matchAll(/^  ([a-z0-9-]+):$/gm)].map((match) => match[1]);
const fullCiJobs = jobNames.filter((name) => name !== "change-scope");
assert.ok(fullCiJobs.length >= 15, "expected the repository-wide CI job matrix");
for (const [index, jobName] of jobNames.entries()) {
  if (jobName === "change-scope") continue;
  const start = jobs.indexOf(`  ${jobName}:`);
  const nextJobName = jobNames[index + 1];
  const end = nextJobName ? jobs.indexOf(`  ${nextJobName}:`, start + 1) : jobs.length;
  const block = jobs.slice(start, end);
  assert.match(block, /\n    needs: change-scope\n/, `${jobName} must depend on change-scope`);
  assert.match(
    block,
    /\n    if: .*needs\.change-scope\.outputs\.run-full-ci == 'true'/,
    `${jobName} must skip only when the trusted classifier proves bot-only scope`,
  );
}

assert.match(botSubmission, /pull-requests: read/);
assert.match(botSubmission, /Validate exact bot-only change scope/);
assert.match(botSubmission, /ref: \$\{\{ github\.event\.pull_request\.base\.sha \}\}/);
assert.match(botSubmission, /steps\.scope\.outputs\.bot-only != 'true'/);
assert.match(botSubmission, /EXPECTED_CHANGED_FILES: \$\{\{ github\.event\.pull_request\.changed_files/);

console.log("CI pull request change-scope checks passed");
