import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const provision = await readFile(new URL("../../.github/workflows/bot-submission-provision.yml", import.meta.url), "utf8");
const approval = await readFile(new URL("../../.github/workflows/bot-submission-invite-approve.yml", import.meta.url), "utf8");

assert.doesNotMatch(provision, /pull_request_target/);
assert.match(provision, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(provision, /Record fork submission admission/);
assert.match(provision, /workflow_dispatch:/);
assert.match(provision, /inputs\.pr_number \|\| github\.event\.workflow_run\.pull_requests\[0\]\.number/);
assert.match(provision, /steps\.admission\.outputs\.state == 'invite_approved'/);
assert.match(provision, /steps\.admission\.outputs\.state != 'invite_approved'/);
assert.match(provision, /Mark non-bot branch provisioning status/);
assert.match(provision, /description=Not a bot submission/);

assert.doesNotMatch(approval, /pull_request_target/);
assert.match(approval, /workflow_dispatch:/);
assert.match(approval, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(approval, /permission.*maintain\|admin/);
assert.match(approval, /requested SHA is not the current PR head/);
assert.match(approval, /approverActorId/);
assert.match(approval, /APPROVER_ACTOR_ID="user-gh-\$\{actor_id\}"/);
assert.match(approval, /Dispatch trusted provisioning/);
assert.match(approval, /gh workflow run bot-submission-provision\.yml/);

console.log("bot submission invite workflow guard checks passed");
