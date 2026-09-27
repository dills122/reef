import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";

test("settlement diagnostic rejects a missing dedicated database before stack startup", () => {
  const result = spawnSync(process.execPath, ["scripts/dev/venue-event-materializer-stress.mjs"], {
    encoding: "utf8",
    env: { ...process.env, REEF_DO_POSTMATCH_SETTLEMENT_DIAGNOSTIC: "1",
      REEF_SETTLEMENT_POSTGRES_MIGRATIONS: "0" },
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /settlement diagnostic requires REEF_SETTLEMENT_POSTGRES_MIGRATIONS=1/);
  assert.doesNotMatch(result.stderr, /docker|settlement-shadow-seed/);
});
