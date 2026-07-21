import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-result-ingest-"));
const summaryPath = join(dir, "summary.json");

writeFileSync(
  summaryPath,
  JSON.stringify({
    approvalStatus: "approved_for_merge",
    runId: "run-1",
    scoringPolicyHash: "sha256:1111111111111111111111111111111111111111111111111111111111111111",
    policyEnvelopeHash: "sha256:2222222222222222222222222222222222222222222222222222222222222222",
    actionsProposed: 12,
    orderActionsProposed: 8,
    dataCalls: 20,
    signalsGenerated: 4,
  }),
);

const result = spawnSync(
  "node",
  [
    "scripts/dev/arena-ingest-bot-run-result.mjs",
    `--summary=${summaryPath}`,
    "--bot-id=bot-1",
    "--version-id=v1",
    "--scoring-policy-version=score-v1",
    "--final-equity=1025000",
    "--realized-pnl=25000",
    "--max-drawdown=1000",
    "--dry-run",
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, result.stderr);
const payload = JSON.parse(result.stdout);
assert.equal(payload.runId, "run-1");
assert.equal(payload.botId, "bot-1");
assert.equal(payload.versionId, "v1");
assert.equal(payload.scoringPolicyVersion, "score-v1");
assert.equal(payload.scoringPolicyHash, "sha256:1111111111111111111111111111111111111111111111111111111111111111");
assert.equal(payload.policyEnvelopeHash, "sha256:2222222222222222222222222222222222222222222222222222222222222222");
assert.equal(payload.finalEquity, 1025000);
assert.equal(payload.realizedPnl, 25000);
assert.equal(payload.maxDrawdown, 1000);
assert.equal(payload.actionsProposed, 12);
assert.equal(payload.orderActionsProposed, 8);
assert.equal(payload.dataCalls, 20);
assert.equal(payload.signalsGenerated, 4);
assert.equal(payload.disqualified, false);

console.log("arena bot result ingestion payload checks passed");
