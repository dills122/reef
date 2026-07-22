import assert from "node:assert/strict";

import { createSmokeIdentity, newSmokeExecutionId } from "./lib/smoke-identity.mjs";

const first = createSmokeIdentity("test-1");
const second = createSmokeIdentity("test-2");

assert.equal(first.runId, "smoke-run-test-1");
assert.equal(first.orderId, "smoke-ord-test-1");
assert.notEqual(first.submitCommandId, second.submitCommandId);
assert.notEqual(first.cancelIdempotencyKey, second.cancelIdempotencyKey);
assert.match(newSmokeExecutionId(), /^[a-z0-9]+-[0-9a-f]{8}$/);
assert.throws(() => createSmokeIdentity("unsafe/value"), /letters, numbers/);

console.log("smoke identity tests ok");
