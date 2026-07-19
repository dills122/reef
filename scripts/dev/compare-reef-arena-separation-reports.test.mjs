import assert from "node:assert/strict";
import { compareReefArenaSeparationReports } from "./compare-reef-arena-separation-reports.mjs";

const proof = {
  mode: "live",
  pass: true,
  pathId: "P1_GOLDEN_HIDDEN_CROSS_T1",
  seed: 424242,
  assertions: [{ id: "p1-trades", status: "pass", expected: "2", observed: "2", proofSource: "/trades" }],
  projectionLag: [{ projection: "runtime", lag: "0", watermark: "0", measuredAt: "2026-07-19T00:00:00Z" }],
  visibilityTimeline: {
    publicDepthHiddenRestingExposed: false,
    publicDepthChecks: [{ phase: "before", instrumentId: "XYZ", price: "100", hiddenRestingQuantityVisible: false, statusCode: 404, observed: "safe", checkedAt: "2026-07-19T00:00:00Z" }],
  },
};

const equivalent = compareReefArenaSeparationReports(
  { ...proof, scenarioRunId: "reef" },
  { ...proof, scenarioRunId: "arena", assertions: [...proof.assertions].reverse() },
);
assert.equal(equivalent.status, "pass");
assert.equal(equivalent.deterministicHash.matched, true);

const changed = compareReefArenaSeparationReports(proof, {
  ...proof,
  assertions: [{ ...proof.assertions[0], observed: "3" }],
});
assert.equal(changed.status, "fail");
assert.match(changed.failures.join("\n"), /evidence differs/);

console.log("Reef/Arena separation report comparison checks passed");
