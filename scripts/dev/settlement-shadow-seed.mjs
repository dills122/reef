#!/usr/bin/env node
// Fund the fixed materializer fixture before its first trade reaches shadow settlement.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

const [fixturePath, runId] = process.argv.slice(2);
if (!fixturePath || !runId || !/^[A-Za-z0-9_-]+$/.test(runId)) {
  throw new Error("usage: settlement-shadow-seed.mjs FIXTURE_YAML RUN_ID");
}
const fixture = readFileSync(fixturePath, "utf8");
function ids(pattern) {
  return [...new Set([...fixture.matchAll(pattern)].map((match) => match[1]))];
}
const actors = ids(/^\s+- actorId: ([A-Za-z0-9_-]+)\s*$/gm);
const instruments = ids(/^\s+instrumentId: ([A-Za-z0-9_-]+)\s*$/gm);
if (actors.length !== 5 || instruments.length === 0) {
  throw new Error(`unexpected settlement fixture: ${actors.length} actors, ${instruments.length} instruments`);
}
const quote = (value) => `'${value}'`; // IDs are restricted to [A-Za-z0-9_-] above.
const rows = [];
for (const actor of actors) {
  const participant = `${actor}-participant`;
  const account = `${actor}-account`;
  for (const [assetType, assetId] of [["CASH", "USD"], ...instruments.map((id) => ["SECURITY", id])]) {
    rows.push(`(${[`${runId}-${actor}-${assetType}-${assetId}`, runId, "instant-post-trade-v1",
      `benchmark-${runId}`, `opening-${actor}`, participant, account, assetType, assetId,
      "1000000000000000000000000"].map(quote).join(", ")}, now())`);
  }
}
const sql = `BEGIN;
INSERT INTO settlement.resource_positions
  (resource_position_id, scenario_run_id, post_trade_profile_id, correlation_id,
   causation_id, participant_id, account_id, asset_type, asset_id, quantity, occurred_at)
VALUES ${rows.join(",\n")}
ON CONFLICT (resource_position_id) DO NOTHING;
COMMIT;`;
const result = spawnSync("docker", ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml",
  "--profile", "postmatch", "exec", "-T", "settlement-postgres", "psql", "-X", "-q",
  "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef"],
{ input: sql, encoding: "utf8", maxBuffer: 1024 * 1024 });
if (result.status !== 0) throw new Error(result.stderr?.trim() || result.error?.message || "settlement seed failed");
console.log(`seeded settlement openings: ${actors.length} actors, ${instruments.length} instruments, ${rows.length} positions`);
