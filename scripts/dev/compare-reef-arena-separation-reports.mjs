import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { reefPath, arenaPath, outputPath } = parseArgs(process.argv.slice(2));
  if (!reefPath || !arenaPath) {
    console.error("usage: bun scripts/dev/compare-reef-arena-separation-reports.mjs REEF_REPORT.json ARENA_REPORT.json [--out=COMPARISON.json]");
    process.exit(1);
  }
  const comparison = compareReefArenaSeparationReports(
    JSON.parse(readFileSync(reefPath, "utf8")),
    JSON.parse(readFileSync(arenaPath, "utf8")),
  );
  const rendered = `${JSON.stringify(comparison, null, 2)}\n`;
  if (outputPath) writeFileSync(outputPath, rendered);
  process.stdout.write(rendered);
  if (comparison.status !== "pass") process.exit(1);
}

export function compareReefArenaSeparationReports(reefReport, arenaReport) {
  const reefProof = deterministicProof(reefReport);
  const arenaProof = deterministicProof(arenaReport);
  const matched = stableJson(reefProof) === stableJson(arenaProof);
  const bothPassed = reefReport?.pass === true && arenaReport?.pass === true;
  const failures = [];
  if (!bothPassed) failures.push("both profile reports must pass");
  if (!matched) failures.push("deterministic scenario evidence differs");

  return {
    schemaVersion: "reef.arena.separationComparison.v1",
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    profiles: {
      reef: reportIdentity(reefReport),
      arena: reportIdentity(arenaReport),
    },
    deterministicHash: {
      reef: hashJson(reefProof),
      arena: hashJson(arenaProof),
      matched,
    },
    proof: reefProof,
  };
}

function deterministicProof(report) {
  return {
    mode: report?.mode ?? "",
    pass: report?.pass === true,
    pathId: report?.pathId ?? "",
    seed: report?.seed ?? null,
    assertions: [...(report?.assertions ?? [])]
      .map(({ id, status, expected, observed, proofSource }) => ({ id, status, expected, observed, proofSource }))
      .sort((left, right) => String(left.id).localeCompare(String(right.id))),
    projectionLag: [...(report?.projectionLag ?? [])]
      .map(({ projection, lag, watermark }) => ({ projection, lag, watermark }))
      .sort((left, right) => stableJson(left).localeCompare(stableJson(right))),
    visibilityTimeline: {
      publicDepthHiddenRestingExposed: report?.visibilityTimeline?.publicDepthHiddenRestingExposed === true,
      publicDepthChecks: [...(report?.visibilityTimeline?.publicDepthChecks ?? [])]
        .map(({ phase, instrumentId, price, hiddenRestingQuantityVisible, statusCode, observed }) => ({
          phase,
          instrumentId,
          price,
          hiddenRestingQuantityVisible: hiddenRestingQuantityVisible === true,
          statusCode,
          observed,
        }))
        .sort((left, right) => stableJson(left).localeCompare(stableJson(right))),
    },
  };
}

function reportIdentity(report) {
  return {
    scenarioRunId: report?.scenarioRunId ?? "",
    pathId: report?.pathId ?? "",
    pass: report?.pass === true,
  };
}

function parseArgs(args) {
  const paths = [];
  let outputPath = "";
  for (const arg of args) {
    if (arg.startsWith("--out=")) outputPath = arg.slice("--out=".length);
    else paths.push(arg);
  }
  return { reefPath: paths[0], arenaPath: paths[1], outputPath };
}

function hashJson(value) {
  return createHash("sha256").update(stableJson(value)).digest("hex");
}

function stableJson(value) {
  return JSON.stringify(sortValue(value));
}

function sortValue(value) {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, sortValue(child)]),
  );
}
