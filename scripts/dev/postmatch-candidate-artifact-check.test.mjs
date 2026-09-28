import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { checkCandidateArtifacts } from "./postmatch-candidate-artifact-check.mjs";

test("offline candidate check requires every measured gate artifact", () => {
  const directory = mkdtempSync(join(tmpdir(), "reef-candidate-artifacts-"));
  const put = (name, data) => writeFileSync(join(directory, name), JSON.stringify(data));
  assert.match(checkCandidateArtifacts(directory).join(" "), /exactly one measured load report/);
  put("venue-event-materializer-stress-rate-10000.json", {});
  put("postmatch-stage-summary.json", { status: "pass", inLoadIntervalCount: 29,
    loadWindows: [{}] });
  put("postmatch-candidate-age-summary.json", { status: "pass", expectedWindowCount: 3,
    partitions: Array.from({ length: 16 }, (_, index) => index) });
  put("settlement-candidate-cohort.json", { status: "pass",
    scope: "closed-retained-source-cohort" });
  put("postmatch-candidate-read-probe.json", { summary: { readGatePassed: true,
    marketAgeGates: { p95Ms: 5000 } }, workload: { durationSeconds: 300 } });
  assert.deepEqual(checkCandidateArtifacts(directory), []);
  put("postmatch-candidate-read-probe.json", { summary: { readGatePassed: false } });
  assert.match(checkCandidateArtifacts(directory).join(" "), /read-probe/);
});
