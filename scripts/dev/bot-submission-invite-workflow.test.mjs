import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolveTrustedPullRequest } from "./bot-submission-resolve-pr.mjs";

const provision = await readFile(new URL("../../.github/workflows/bot-submission-provision.yml", import.meta.url), "utf8");
const approval = await readFile(new URL("../../.github/workflows/bot-submission-invite-approve.yml", import.meta.url), "utf8");
const nonBotStatus = await readFile(new URL("../../.github/workflows/bot-submission-non-bot-status.yml", import.meta.url), "utf8");
const submission = await readFile(new URL("../../.github/workflows/bot-submission.yml", import.meta.url), "utf8");
const registrySync = await readFile(new URL("../../.github/workflows/bot-registry-sync.yml", import.meta.url), "utf8");
const arenaGateway = await readFile(
  new URL("../../services/arena-control-plane/src/main/kotlin/com/reef/arena/controlplane/api/ArenaAdminGateway.kt", import.meta.url),
  "utf8",
);

assert.match(submission, /if: github\.event\.pull_request\.user\.login != 'dependabot\[bot\]'/);

assert.doesNotMatch(provision, /pull_request_target/);
assert.match(provision, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(provision, /Record fork submission admission/);
assert.match(provision, /workflow_dispatch:/);
assert.match(provision, /pr_number:\n        description: Approved fork bot-submission PR number\n        required: true/);
assert.doesNotMatch(provision, /github\.event\.workflow_run\.pull_requests\[0\]\.number != ''/);
assert.match(provision, /EVENT_PR_NUMBER: \$\{\{ github\.event\.workflow_run\.pull_requests\[0\]\.number \|\| '' \}\}/);
assert.match(provision, /WORKFLOW_HEAD_REPOSITORY: \$\{\{ github\.event\.workflow_run\.head_repository\.full_name \|\| '' \}\}/);
assert.match(provision, /WORKFLOW_HEAD_BRANCH: \$\{\{ github\.event\.workflow_run\.head_branch \|\| '' \}\}/);
assert.match(provision, /WORKFLOW_HEAD_SHA: \$\{\{ github\.event\.workflow_run\.head_sha \|\| '' \}\}/);
assert.match(provision, /EXPECTED_BASE_REPOSITORY: dills122\/reef/);
assert.match(provision, /gh api --method GET "repos\/\$\{EXPECTED_BASE_REPOSITORY\}\/pulls"/);
assert.match(provision, /-f state=open/);
assert.match(provision, /-f "head=\$\{head_owner\}:\$\{WORKFLOW_HEAD_BRANCH\}"/);
assert.match(provision, /PR_NUMBER: \$\{\{ steps\.resolve-pr\.outputs\.pr-number \}\}/);
assert.match(provision, /Persist trusted pull request number/);
assert.match(provision, /echo "PR_NUMBER=\$PR_NUMBER" >> "\$GITHUB_ENV"/);
assert.match(provision, /steps\.admission\.outputs\.state == 'invite_approved'/);
assert.match(provision, /steps\.admission\.outputs\.state != 'invite_approved'/);
assert.match(provision, /Comment invite review pending/);
assert.match(provision, /PROVISION_RESULT: pending/);
assert.match(provision, /SUBMISSION_HEAD_SHA: \$\{\{ steps\.metadata\.outputs\.head-sha \}\}/);
assert.match(provision, /SUBMISSION_REPOSITORY: \$\{\{ github\.repository \}\}/);
assert.match(provision, /SUBMISSION_PR_NUMBER: \$\{\{ steps\.resolve-pr\.outputs\.pr-number \}\}/);
assert.match(
  provision,
  /if: steps\.openbao-provision\.outputs\['exit-code'\] != '' && steps\.openbao-provision\.outputs\['exit-code'\] == '0'/,
);
assert.match(provision, /startsWith\(github\.event\.workflow_run\.head_branch, 'bots\/add\/'\)/);
assert.match(provision, /startsWith\(github\.event\.workflow_run\.head_branch, 'bots\/update\/'\)/);
assert.match(provision, /startsWith\(github\.event\.workflow_run\.head_branch, 'bots\/remove\/'\)/);

const workflowRun = {
  eventName: "workflow_run",
  dispatchPrNumber: "",
  eventPrNumber: "",
  workflowHeadRepository: "noodle-ventures/reef",
  workflowHeadBranch: "bots/add/noodle-invite-smoke",
  workflowHeadSha: "6edd41cf3a7e5384e15fef20d2823746b909ef8f",
  expectedBaseRepository: "dills122/reef",
};
const exactPr = {
  number: 307,
  state: "open",
  head: {
    ref: workflowRun.workflowHeadBranch,
    sha: workflowRun.workflowHeadSha,
    repo: { full_name: workflowRun.workflowHeadRepository },
  },
  base: { repo: { full_name: workflowRun.expectedBaseRepository } },
};

assert.equal(resolveTrustedPullRequest({ ...workflowRun, candidates: [exactPr] }), "307");
assert.equal(resolveTrustedPullRequest({
  ...workflowRun,
  eventPrNumber: "307",
  candidates: exactPr,
}), "307");
assert.equal(resolveTrustedPullRequest({
  eventName: "workflow_dispatch",
  dispatchPrNumber: "307",
}), "307");

assert.throws(
  () => resolveTrustedPullRequest({ ...workflowRun, candidates: [] }),
  /expected exactly one open pull request.*found 0/,
);
assert.throws(
  () => resolveTrustedPullRequest({
    ...workflowRun,
    candidates: [exactPr, { ...exactPr, number: 308 }],
  }),
  /expected exactly one open pull request.*found 2/,
);
for (const mismatchedPr of [
  { ...exactPr, head: { ...exactPr.head, repo: { full_name: "attacker/reef" } } },
  { ...exactPr, head: { ...exactPr.head, ref: "bots/add/other" } },
  { ...exactPr, head: { ...exactPr.head, sha: "0000000000000000000000000000000000000000" } },
  { ...exactPr, base: { repo: { full_name: "attacker/reef" } } },
]) {
  assert.throws(
    () => resolveTrustedPullRequest({ ...workflowRun, candidates: [mismatchedPr] }),
    /expected exactly one open pull request.*found 0/,
  );
}
assert.throws(
  () => resolveTrustedPullRequest({
    ...workflowRun,
    eventPrNumber: "308",
    candidates: exactPr,
  }),
  /does not exactly match trusted run metadata/,
);
assert.throws(
  () => resolveTrustedPullRequest({
    eventName: "workflow_dispatch",
    dispatchPrNumber: "",
  }),
  /requires a valid pr_number/,
);

const resolveIndex = provision.indexOf("Resolve trusted pull request number");
for (const downstreamStep of [
  "Resolve current pull request metadata",
  "Record fork submission admission",
  "Mark provisioning status pending",
  "- name: OpenBao provisioning",
]) {
  assert.ok(resolveIndex < provision.indexOf(downstreamStep), `PR resolution must precede ${downstreamStep}`);
}

assert.match(nonBotStatus, /pull_request_target:/);
assert.match(nonBotStatus, /pull-requests: read/);
assert.match(nonBotStatus, /statuses: write/);
assert.match(nonBotStatus, /pulls\/\$\{PR_NUMBER\}\/files/);
assert.match(nonBotStatus, /grep -q '\^bots\/'/);
assert.match(nonBotStatus, /statuses\/\$\{HEAD_SHA\}/);
assert.match(nonBotStatus, /context=registry-diff-and-provision/);
assert.match(nonBotStatus, /Dependabot PR; bot checks not applicable/);
assert.doesNotMatch(nonBotStatus, /actions\/checkout/);
assert.doesNotMatch(nonBotStatus, /id-token: write/);
assert.doesNotMatch(nonBotStatus, /secrets\./);

assert.doesNotMatch(approval, /pull_request_target/);
assert.match(approval, /workflow_dispatch:/);
assert.match(approval, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(registrySync, /pull-requests: read/);
assert.match(registrySync, /GITHUB_TOKEN: \$\{\{ github\.token \}\}/);
assert.match(
  registrySync,
  /grep -Fxq 'scripts\/dev\/bot-submission-register-merged\.mjs'[\s\S]+git ls-files 'bots\/\*\/bot\.json'/,
);
assert.match(
  approval,
  /jobs:\n  approve:\n    runs-on: ubuntu-latest\n    permissions:\n      actions: write\n      contents: read\n      pull-requests: read/,
);
assert.match(approval, /permission.*maintain\|admin/);
assert.match(approval, /requested SHA is not the current PR head/);
assert.match(approval, /approverActorId/);
assert.match(approval, /APPROVER_ACTOR_ID="user-gh-\$\{actor_id\}"/);
assert.match(approval, /ADMIN_API_TOKEN: \$\{\{ secrets\.ADMIN_API_TOKEN \}\}/);
assert.match(approval, /authorization: Bearer \$\{ADMIN_API_TOKEN\}/);
assert.doesNotMatch(approval, /ARENA_ADMIN_API_TOKEN/);
assert.match(approval, /Dispatch trusted provisioning/);
assert.match(approval, /gh workflow run bot-submission-provision\.yml/);
assert.match(
  arenaGateway,
  /adminRoute\("\/admin\/v1\/arena\/submission-admissions\/approve"[^\n]+"admin", adminTokens, operatorRoles\)/,
);

console.log("bot submission invite workflow guard checks passed");
