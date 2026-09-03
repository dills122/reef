import { pathToFileURL } from "node:url";

export const FULL_CI_JOBS = [
  "dependency-alignment",
  "proto-governance",
  "go-matching-engine",
  "go-simulator",
  "kotlin-platform-runtime",
  "kotlin-stock-data",
  "reef-arena-separation",
  "node-dev-tooling",
  "docs-site",
  "bot-sdk",
  "arena-admin",
  "scenario-replay",
  "container-builds",
  "infrastructure-config",
  "go-vulnerability-scan",
  "postgres-schema-placement",
];

export function validateRequiredResults(needs, { scenarioReplayRequired }) {
  if (!needs || typeof needs !== "object" || Array.isArray(needs)) {
    throw new TypeError("CI_NEEDS_JSON must be a JSON object");
  }

  const failures = [];
  const scope = needs["change-scope"];
  if (scope?.result !== "success") {
    failures.push(`change-scope=${scope?.result ?? "missing"}`);
  }

  const runFullCi = scope?.outputs?.["run-full-ci"] === "true";
  for (const jobName of FULL_CI_JOBS) {
    const result = needs[jobName]?.result ?? "missing";
    const replayMaySkip = jobName === "scenario-replay" && !scenarioReplayRequired;
    const accepted = runFullCi
      ? result === "success" || (replayMaySkip && result === "skipped")
      : result === "success" || result === "skipped";
    if (!accepted) failures.push(`${jobName}=${result}`);
  }

  if (failures.length > 0) {
    throw new Error(`Required CI jobs did not pass: ${failures.join(", ")}`);
  }

  return { runFullCi, scenarioReplayRequired };
}

function main() {
  const needs = JSON.parse(process.env.CI_NEEDS_JSON ?? "null");
  const result = validateRequiredResults(needs, {
    scenarioReplayRequired: process.env.CI_SCENARIO_REPLAY_REQUIRED === "true",
  });
  console.log(
    `Required CI results accepted (full=${result.runFullCi}, replay-required=${result.scenarioReplayRequired})`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
