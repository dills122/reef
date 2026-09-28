#!/usr/bin/env node
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function checkCandidateArtifacts(directory) {
  const failures = [];
  const loadReports = readdirSync(directory)
    .filter((name) => /^venue-event-materializer-stress-rate-.*\.json$/.test(name));
  if (loadReports.length !== 1) failures.push("candidate run requires exactly one measured load report");
  for (const [name, valid] of [
    ["postmatch-stage-summary.json", (data) => data.status === "pass" &&
      data.inLoadIntervalCount >= 3 && data.loadWindows?.length === 1],
    ["postmatch-candidate-age-summary.json", (data) => data.status === "pass" &&
      data.partitions?.length === 16 && data.expectedWindowCount > 0],
    ["settlement-candidate-cohort.json", (data) => data.status === "pass" &&
      data.scope === "closed-retained-source-cohort"],
    ["postmatch-candidate-read-probe.json", (data) => data.summary?.readGatePassed === true &&
      data.summary?.marketAgeGates?.p95Ms === 5000 &&
      data.workload?.durationSeconds === 300],
  ]) {
    let data;
    try { data = JSON.parse(readFileSync(resolve(directory, name), "utf8")); }
    catch { failures.push(`missing or invalid ${name}`); continue; }
    if (!valid(data)) failures.push(`candidate gate failed: ${name}`);
  }
  return failures;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const directory = process.argv[2];
  if (!directory) throw new Error("usage: postmatch-candidate-artifact-check.mjs REPORT_DIR");
  const failures = checkCandidateArtifacts(resolve(directory));
  for (const failure of failures) console.error(failure);
  console.log(`post-match candidate artifact gates: ${failures.length ? "fail" : "pass"}`);
  if (failures.length) process.exitCode = 1;
}
