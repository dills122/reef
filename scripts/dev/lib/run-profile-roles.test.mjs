import assert from "node:assert/strict";
import { expectedRolesForRunProfile } from "./run-profile-roles.mjs";

runTest("control room materializer profile treats projectors as optional", () => {
  const roles = expectedRolesForRunProfile("materializer-soak");
  assert.deepEqual(roles, {
    workers: "stopped or disabled",
    materializers: "online",
    projectors: "optional unless read-model freshness is being measured",
  });
});

runTest("stress metadata expected roles keep stable string shape", () => {
  for (const profile of ["stream-ack", "materializer-soak", "direct-nodb", "custom"]) {
    const roles = expectedRolesForRunProfile(profile);
    assert.deepEqual(Object.keys(roles).sort(), ["materializers", "projectors", "workers"]);
    assert.equal(typeof roles.workers, "string");
    assert.equal(typeof roles.materializers, "string");
    assert.equal(typeof roles.projectors, "string");
  }
});

function runTest(name, fn) {
  try {
    fn();
  } catch (error) {
    console.error(`not ok - ${name}`);
    throw error;
  }
  console.log(`ok - ${name}`);
}
