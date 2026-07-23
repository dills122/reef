import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./bot-submission-pr-comment.mjs", import.meta.url));
const result = spawnSync(process.execPath, [script], {
  encoding: "utf8",
  env: {
    ...process.env,
    BOT_SUBMISSION_PR_COMMENT_DRY_RUN: "1",
    GITHUB_REPOSITORY: "dills122/reef",
    PR_NUMBER: "307",
    GITHUB_TOKEN: "test-token",
    BOT_ID: "noodle-invite-smoke",
    PROVISION_FLOW: "add",
    SUBMITTER_IDENTITY: "noodle-ventures",
    PROVISION_RESULT: "pending",
    SUBMISSION_HEAD_SHA: "6edd41cf3a7e5384e15fef20d2823746b909ef8f",
    GITHUB_RUN_ID: "29968689112",
  },
});

assert.equal(result.status, 0, result.stderr);
assert.match(result.stdout, /Fork submission recorded and pending invite review/);
assert.match(result.stdout, /Bot Submission Invite Approval workflow/);
assert.match(result.stdout, /6edd41cf3a7e5384e15fef20d2823746b909ef8f/);
assert.doesNotMatch(result.stdout, /Secret slice:/);
assert.doesNotMatch(result.stdout, /test-token/);

console.log("bot submission PR comment checks passed");
