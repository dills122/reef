import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const provision = await readFile(new URL("../../.github/workflows/bot-submission-provision.yml", import.meta.url), "utf8");
const approval = await readFile(new URL("../../.github/workflows/bot-submission-invite-approve.yml", import.meta.url), "utf8");

assert.doesNotMatch(provision, /pull_request_target/);
assert.match(provision, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(provision, /Record fork submission admission/);
assert.match(provision, /steps\.admission\.outputs\.state == 'invite_approved'/);
assert.match(provision, /steps\.admission\.outputs\.state != 'invite_approved'/);

assert.doesNotMatch(approval, /pull_request_target/);
assert.match(approval, /workflow_dispatch:/);
assert.match(approval, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
assert.match(approval, /permission.*maintain\|admin/);
assert.match(approval, /requested SHA is not the current PR head/);
assert.match(approval, /approverActorId/);
assert.match(approval, /APPROVER_ACTOR_ID="user-gh-\$\{actor_id\}"/);

console.log("bot submission invite workflow guard checks passed");
