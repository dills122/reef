import assert from "node:assert/strict";
import {
  ECONOMIC_POLICY_ENTRIES,
  buildArenaRunArgs,
  parseArgs,
  validateBindings,
  validateMatrixReports,
} from "./run-arena-economic-policy-matrix.mjs";

const options = parseArgs([
  "--bindings=/tmp/bindings.json",
  "--out-dir=/tmp/economic-matrix",
  "--mode=packages/scenario-definitions/arena/equity-sprint.v1.json",
  "--extra-bots=builtin-npc-taker-aapl",
  "--submit-mode=live",
  "--compartment=ses",
  "--duration-seconds=5",
  "--tick-interval-ms=250",
  "--venue-url=http://127.0.0.1:8080",
  "--arena-admin-url=http://127.0.0.1:8080",
  "--projection-drain-timeout-ms=30000",
  "--validate-existing",
]);
assert.equal(options.bindings, "/tmp/bindings.json");
assert.equal(options.durationSeconds, 5);
assert.equal(options.requireProjectionDrain, undefined);
assert.equal(options.validateExisting, true);

const bindings = validateBindings(bindingsFixture());
const entry = ECONOMIC_POLICY_ENTRIES[0];
const zeroBinding = bindings.entries[entry.economicPolicyVersion];
assert.deepEqual(buildArenaRunArgs(entry, zeroBinding, "/tmp/zero.report.json", options), [
  "scripts/dev/arena-local-tick-run.mjs",
  "--run-id=run-zero",
  "--bot-version-suffix=locked-zero",
  "--venue-session-id=matrix-zero",
  "--economic-policy-version=preview-zero-fee-v1",
  "--mode=packages/scenario-definitions/arena/equity-sprint.v1.json",
  "--extra-bots=builtin-npc-taker-aapl",
  "--compartment=ses",
  "--submit-mode=live",
  "--report-shape=compact",
  "--out=/tmp/zero.report.json",
  "--admission-window-id=window-zero",
  "--roster-snapshot-id=roster-zero",
  `--roster-snapshot-hash=sha256:${"1".repeat(64)}`,
  "--persist-results",
  "--require-roster-binding",
  "--require-economic-reconciliation",
  "--duration-seconds=5",
  "--tick-interval-ms=250",
  "--venue-url=http://127.0.0.1:8080",
  "--arena-admin-url=http://127.0.0.1:8080",
  "--seed-reference",
  "--require-projection-drain",
  "--projection-drain-timeout-ms=30000",
]);

assert.throws(
  () => validateBindings({ ...bindingsFixture(), schemaVersion: "wrong" }),
  /bindings schemaVersion/,
);
const missing = bindingsFixture();
delete missing.entries["preview-balanced-fee-v1"];
assert.throws(() => validateBindings(missing), /missing binding for preview-balanced-fee-v1/);
const duplicateSession = bindingsFixture();
duplicateSession.entries["preview-balanced-fee-v1"].venueSessionId = "matrix-zero";
assert.throws(() => validateBindings(duplicateSession), /distinct venueSessionId/);

const records = ECONOMIC_POLICY_ENTRIES.map((policy, index) => {
  const policyBinding = bindings.entries[policy.economicPolicyVersion];
  return {
    id: policy.id,
    economicPolicyVersion: policy.economicPolicyVersion,
    binding: policyBinding,
    exitCode: 0,
    report: reportFixture(policyBinding, policy.economicPolicyVersion, index),
  };
});
assert.deepEqual(validateMatrixReports(records), {
  status: "pass",
  errors: [],
  requiredPolicies: ECONOMIC_POLICY_ENTRIES.map((policy) => policy.economicPolicyVersion),
  completedPolicies: ECONOMIC_POLICY_ENTRIES.map((policy) => policy.economicPolicyVersion),
});

const unspecified = structuredClone(records);
unspecified[1].report.executionSummary.byRole.UNSPECIFIED = { fillCount: 1 };
assert.match(validateMatrixReports(unspecified).errors.join("\n"), /unspecified execution roles remain/);

const drifted = structuredClone(records);
drifted[2].report.executionSummary.fillCount = 99;
assert.match(validateMatrixReports(drifted).errors.join("\n"), /fixed comparison fact changed across policies: fillCount/);

console.log("arena economic policy matrix checks passed");

function bindingsFixture() {
  return {
    schemaVersion: "reef.arena.economicPolicyMatrixBindings.v1",
    entries: {
      "preview-zero-fee-v1": binding("zero", "1", { botVersionSuffix: "locked-zero" }),
      "preview-balanced-fee-v1": binding("balanced", "2"),
      "preview-liquidity-subsidy-v1": binding("subsidy", "3"),
    },
  };
}

function binding(id, digestCharacter, extra = {}) {
  return {
    runId: `run-${id}`,
    admissionWindowId: `window-${id}`,
    rosterSnapshotId: `roster-${id}`,
    rosterSnapshotHash: `sha256:${digestCharacter.repeat(64)}`,
    venueSessionId: `matrix-${id}`,
    ...extra,
  };
}

function reportFixture(bindingValue, economicPolicyVersion, index) {
  return {
    runId: bindingValue.runId,
    status: index === 1 ? "completed_with_warnings" : "completed",
    mode: {
      modeId: "equity-sprint",
      seed: 170707,
      venueSessionId: bindingValue.venueSessionId,
      seedSetHash: `sha256:${"4".repeat(64)}`,
      scoringPolicyHash: `sha256:${"5".repeat(64)}`,
      riskPolicyHash: `sha256:${"6".repeat(64)}`,
      actorProfileCatalogHash: `sha256:${"7".repeat(64)}`,
      economicPolicyVersion,
      economicPolicyHash: `sha256:${String(index + 7).repeat(64)}`,
    },
    rosterBinding: {
      admissionWindowId: bindingValue.admissionWindowId,
      rosterSnapshotId: bindingValue.rosterSnapshotId,
      rosterSnapshotHash: bindingValue.rosterSnapshotHash,
    },
    totals: { ticks: 12, venueCommands: 24, submittedCommands: 24 },
    commandAccounting: { accountingGap: 0 },
    executionSummary: {
      fillCount: 4,
      filledQuantity: 4,
      filledNotional: 400,
      avgFillPrice: 100,
      byInstrument: { AAPL: { fillCount: 4, filledQuantity: 4, filledNotional: 400, avgFillPrice: 100 } },
      byRole: { MAKER: { fillCount: 2 }, TAKER: { fillCount: 2 } },
    },
    economicReconciliation: {
      status: "pass",
      complete: true,
      supported: true,
      reconciliationHash: `sha256:${String(index + 1).repeat(64)}`,
      ledgers: { competition: { feesPaid: index, rebatesReceived: index } },
      economicFacility: { cashDelta: 0 },
    },
    botResults: [
      { botId: "builtin-mm-simple" },
      { botId: "builtin-npc-taker-aapl" },
    ],
  };
}
