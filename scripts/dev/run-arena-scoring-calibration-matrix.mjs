import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { compareArenaScoringCalibration } from "./compare-arena-scoring-calibration.mjs";

const DEFAULT_ENTRIES = [
  {
    id: "equity-sprint-noise",
    mode: "packages/scenario-definitions/arena/equity-sprint.v1.json",
    description: "single-competitor sprint with benign NPC noise",
  },
  {
    id: "equity-multi-toxic",
    mode: "packages/scenario-definitions/arena/equity-multi-local.v1.json",
    description: "multi-asset arena with aggressive toxic-momentum NPC takers",
  },
];

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const options = parseArgs(process.argv.slice(2));
  const manifest = runCalibrationMatrix(options);
  console.log(JSON.stringify(manifest, null, 2));
}

export function runCalibrationMatrix(options = {}) {
  const entries = options.entries ?? DEFAULT_ENTRIES;
  const outDir = resolve(options.outDir ?? "artifacts/arena-scoring-calibration");
  mkdirSync(outDir, { recursive: true });

  const reports = [];
  for (const entry of entries) {
    const reportPath = join(outDir, `${entry.id}.json`);
    const result = runArenaEntry(entry, reportPath, options);
    reports.push({
      id: entry.id,
      mode: entry.mode,
      description: entry.description ?? "",
      reportPath,
      status: result.status,
      exitCode: result.exitCode,
      error: result.error,
      summary: result.report === null ? null : reportSummary(result.report),
    });
    if (result.status !== "completed" || result.exitCode !== 0) {
      break;
    }
  }

  const comparisons = compareReports(reports, outDir);
  const manifest = {
    schemaVersion: "reef.arena.scoringCalibrationMatrix.v1",
    generatedAt: new Date().toISOString(),
    runner: "scripts/dev/run-arena-scoring-calibration-matrix.mjs",
    outDir,
    submitMode: options.submitMode ?? "dry-run",
    reportShape: "compact",
    entries: reports,
    comparisons,
  };
  const manifestPath = join(outDir, "manifest.json");
  writeJson(manifestPath, manifest);
  return { ...manifest, manifestPath };
}

export function buildArenaRunArgs(entry, reportPath, options = {}) {
  const args = [
    "scripts/dev/arena-local-tick-run.mjs",
    `--compartment=${options.compartment ?? "vm"}`,
    `--submit-mode=${options.submitMode ?? "dry-run"}`,
    `--mode=${entry.mode}`,
    "--report-shape=compact",
    `--out=${reportPath}`,
  ];
  if (options.durationSeconds !== undefined) args.push(`--duration-seconds=${options.durationSeconds}`);
  if (options.tickIntervalMs !== undefined) args.push(`--tick-interval-ms=${options.tickIntervalMs}`);
  if (options.venueUrl) args.push(`--venue-url=${options.venueUrl}`);
  if (options.arenaAdminUrl) args.push(`--arena-admin-url=${options.arenaAdminUrl}`);
  if (options.seedReference === true) args.push("--seed-reference");
  if (options.requireProjectionDrain === true) args.push("--require-projection-drain");
  if (options.projectionDrainTimeoutMs !== undefined) args.push(`--projection-drain-timeout-ms=${options.projectionDrainTimeoutMs}`);
  return args;
}

export function compareReports(reports, outDir) {
  const completed = reports.filter((entry) => entry.status === "completed" && entry.exitCode === 0);
  if (completed.length < 2) return [];
  const baseline = completed[0];
  return completed.slice(1).map((candidate) => {
    const baseReport = readJson(baseline.reportPath);
    const candidateReport = readJson(candidate.reportPath);
    const comparison = compareArenaScoringCalibration(baseReport, candidateReport);
    const comparisonPath = join(outDir, `${baseline.id}__vs__${candidate.id}.comparison.json`);
    writeJson(comparisonPath, comparison);
    return {
      baseId: baseline.id,
      candidateId: candidate.id,
      comparisonPath,
      topComponentMove: comparison.topComponentMove,
      addedFlags: comparison.dataQuality.addedFlags,
      removedFlags: comparison.dataQuality.removedFlags,
      publicScoreStillUnchanged: comparison.dataQuality.publicScoreStillUnchanged,
    };
  });
}

function runArenaEntry(entry, reportPath, options) {
  const args = buildArenaRunArgs(entry, reportPath, options);
  const spawned = spawnSync(options.runtime ?? "bun", args, {
    cwd: options.cwd ?? repoRoot(),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (spawned.status !== 0) {
    return {
      status: "failed",
      exitCode: spawned.status ?? 1,
      error: [spawned.stdout, spawned.stderr].filter(Boolean).join("\n").trim(),
      report: null,
    };
  }
  const report = readJson(reportPath);
  return {
    status: report.status ?? "completed",
    exitCode: 0,
    error: "",
    report,
  };
}

function reportSummary(report) {
  return {
    runId: report.runId ?? "",
    status: report.status ?? "",
    modeId: report.mode?.modeId ?? "",
    policyEnvelopeHash: report.policyEnvelopeHash ?? "",
    eligibleCompetitors: report.scoringCalibration?.eligibility?.eligibleCompetitors ?? 0,
    npcDifficultyBuckets: report.scoringCalibration?.difficultyContext?.npcDifficultyBuckets ?? [],
    difficultyMultiplier: report.scoringCalibration?.difficultyContext?.difficultyMultiplier ?? null,
    shadowScoreAvg: report.scoringCalibration?.scoreDistribution?.shadowScore?.avg ?? null,
    flags: report.scoringCalibration?.dataQuality?.flags ?? [],
  };
}

export function parseArgs(argv) {
  const options = {};
  for (const arg of argv) {
    if (arg.startsWith("--out-dir=")) options.outDir = arg.slice("--out-dir=".length);
    else if (arg.startsWith("--submit-mode=")) options.submitMode = arg.slice("--submit-mode=".length);
    else if (arg.startsWith("--compartment=")) options.compartment = arg.slice("--compartment=".length);
    else if (arg.startsWith("--duration-seconds=")) options.durationSeconds = positiveNumber(arg.slice("--duration-seconds=".length));
    else if (arg.startsWith("--tick-interval-ms=")) options.tickIntervalMs = positiveNumber(arg.slice("--tick-interval-ms=".length));
    else if (arg.startsWith("--venue-url=")) options.venueUrl = arg.slice("--venue-url=".length);
    else if (arg.startsWith("--arena-admin-url=")) options.arenaAdminUrl = arg.slice("--arena-admin-url=".length);
    else if (arg.startsWith("--projection-drain-timeout-ms=")) options.projectionDrainTimeoutMs = positiveNumber(arg.slice("--projection-drain-timeout-ms=".length));
    else if (arg === "--seed-reference") options.seedReference = true;
    else if (arg === "--require-projection-drain") options.requireProjectionDrain = true;
  }
  return options;
}

function repoRoot() {
  return resolve(dirname(fileURLToPath(import.meta.url)), "../..");
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function positiveNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}
