import assert from "node:assert/strict";
import { normalizeAdminAccessCollections } from "../../apps/arena-admin/src/lib/admin-access-normalize.ts";

const userWithoutArenaOwnerships = normalizeAdminAccessCollections({
  reefUserId: "user-gh-308171859",
  roles: [{ roleId: "participant" }],
});

assert.deepEqual(userWithoutArenaOwnerships.roles, [{ roleId: "participant" }]);
assert.deepEqual(userWithoutArenaOwnerships.botOwnerships, []);

const userWithoutCollections = normalizeAdminAccessCollections({
  reefUserId: "user-gh-15662762",
});

assert.deepEqual(userWithoutCollections.roles, []);
assert.deepEqual(userWithoutCollections.botOwnerships, []);

console.log("arena admin access normalization checks passed");
