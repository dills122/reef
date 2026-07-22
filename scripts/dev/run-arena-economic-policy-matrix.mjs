import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const ECONOMIC_POLICY_ENTRIES = [
  { id: "zero-fee", economicPolicyVersion: "preview-zero-fee-v1" },
  { id: "balanced-fee", economicPolicyVersion: "preview-balanced-fee-v1" },
  { id: "liquidity-subsidy", economicPolicyVersion: "preview-liquidity-subsidy-v1" },
];

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const options = parseArgs(process.argv.slice(2));
  const manifest = options.validateExisting
    ? validateExistingEconomicPolicyMatrix(options)
    : runEconomicPolicyMatrix(options);
  console.log(JSON.stringify(manifest, null, 2));
  if (manifest.status !== "pass") process.exitCode = 1;
}

export function runEconomicPolicyMatrix(options = {}) {
  const bindings = validateBindings(readJson(requiredOption(options.bindings, "--bindings")));
  const outDir = resolve(options.outDir ?? "reports/arena-economic-policy-matrix/latest");
  mkdirSync(outDir, { recursive: true });
  const records = [];

  for (const entry of ECONOMIC_POLICY_ENTRIES) {
    const binding = bindings.entries[entry.economicPolicyVersion];
    const reportPath = join(outDir, `${entry.id}.report.json`);
    const logPath = join(outDir, `${entry.id}.runner.log`);
    const args = buildArenaRunArgs(entry, binding, reportPath, options);
    const spawned = spawnSync(options.runtime ?? "bun", args, {
      cwd: options.cwd ?? repoRoot(),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      maxBuffer: 16 * 1024 * 1024,
    });
    const log = [spawned.stdout, spawned.stderr].filter(Boolean).join("\n").trim();
    writeFileSync(logPath, `${log}${log.length > 0 ? "\n" : ""}`);
    const report = spawned.status === 0 ? readJson(reportPath) : null;
    records.push({
      id: entry.id,
      economicPolicyVersion: entry.economicPolicyVersion,
      binding,
      reportPath,
      logPath,
      exitCode: spawned.status ?? 1,
      error: spawned.status === 0 ? "" : log,
      report,
    });
    if (spawned.status !== 0) break;
  }

  return writeMatrixManifest(records, options, outDir);
}

export function validateExistingEconomicPolicyMatrix(options = {}) {
  const bindings = validateBindings(readJson(requiredOption(options.bindings, "--bindings")));
  const outDir = resolve(options.outDir ?? "reports/arena-economic-policy-matrix/latest");
  const records = ECONOMIC_POLICY_ENTRIES.map((entry) => {
    const binding = bindings.entries[entry.economicPolicyVersion];
    const reportPath = join(outDir, `${entry.id}.report.json`);
    const logPath = join(outDir, `${entry.id}.runner.log`);
    const report = existsSync(reportPath) ? readJson(reportPath) : null;
    return {
      id: entry.id,
      economicPolicyVersion: entry.economicPolicyVersion,
      binding,
      reportPath,
      logPath,
      exitCode: report === null ? 1 : 0,
      error: report === null ? `missing existing report: ${reportPath}` : "",
      report,
    };
  });
  return writeMatrixManifest(records, options, outDir);
}

function writeMatrixManifest(records, options, outDir) {
  const validation = validateMatrixReports(records);
  const manifest = {
    schemaVersion: "reef.arena.economicPolicyMatrix.v1",
    generatedAt: new Date().toISOString(),
    runner: "scripts/dev/run-arena-economic-policy-matrix.mjs",
    status: validation.status,
    outDir,
    mode: options.mode ?? "packages/scenario-definitions/arena/equity-sprint.v1.json",
    entries: records.map(({ report, ...record }) => ({
      ...record,
      summary: report === null ? null : reportSummary(report),
    })),
    validation,
  };
  const manifestPath = join(outDir, "manifest.json");
  writeJson(manifestPath, manifest);
  return { ...manifest, manifestPath };
}

export function buildArenaRunArgs(entry, binding, reportPath, options = {}) {
  const args = [
    "scripts/dev/arena-local-tick-run.mjs",
    `--run-id=${binding.runId}`,
    `--bot-version-suffix=${binding.botVersionSuffix ?? binding.runId}`,
    `--venue-session-id=${binding.venueSessionId}`,
    `--economic-policy-version=${entry.economicPolicyVersion}`,
    `--mode=${options.mode ?? "packages/scenario-definitions/arena/equity-sprint.v1.json"}`,
    `--extra-bots=${options.extraBots ?? "builtin-npc-taker-aapl"}`,
    `--compartment=${options.compartment ?? "ses"}`,
    `--submit-mode=${options.submitMode ?? "live"}`,
    "--report-shape=compact",
    `--out=${reportPath}`,
    `--admission-window-id=${binding.admissionWindowId}`,
    `--roster-snapshot-id=${binding.rosterSnapshotId}`,
    `--roster-snapshot-hash=${binding.rosterSnapshotHash}`,
    "--persist-results",
    "--require-roster-binding",
    "--require-economic-reconciliation",
  ];
  if (options.durationSeconds !== undefined) args.push(`--duration-seconds=${options.durationSeconds}`);
  if (options.tickIntervalMs !== undefined) args.push(`--tick-interval-ms=${options.tickIntervalMs}`);
  if (options.venueUrl) args.push(`--venue-url=${options.venueUrl}`);
  if (options.arenaAdminUrl) args.push(`--arena-admin-url=${options.arenaAdminUrl}`);
  if (options.seedReference !== false) args.push("--seed-reference");
  if (options.requireProjectionDrain !== false) args.push("--require-projection-drain");
  if (options.projectionDrainTimeoutMs !== undefined) args.push(`--projection-drain-timeout-ms=${options.projectionDrainTimeoutMs}`);
  if (options.skipProjectorPreflight === true) args.push("--skip-projector-preflight");
  return args;
}

export function validateBindings(bindings) {
  if (bindings?.schemaVersion !== "reef.arena.economicPolicyMatrixBindings.v1") {
    throw new Error("bindings schemaVersion must be reef.arena.economicPolicyMatrixBindings.v1");
  }
  if (bindings.entries === null || typeof bindings.entries !== "object" || Array.isArray(bindings.entries)) {
    throw new Error("bindings.entries must be an object keyed by economic policy version");
  }
  const identityFields = ["runId", "admissionWindowId", "rosterSnapshotId", "venueSessionId"];
  for (const entry of ECONOMIC_POLICY_ENTRIES) {
    const binding = bindings.entries[entry.economicPolicyVersion];
    if (binding === null || typeof binding !== "object") {
      throw new Error(`missing binding for ${entry.economicPolicyVersion}`);
    }
    for (const field of identityFields) {
      if (typeof binding[field] !== "string" || binding[field].length === 0) {
        throw new Error(`${entry.economicPolicyVersion}.${field} is required`);
      }
    }
    if (!/^sha256:[a-f0-9]{64}$/.test(binding.rosterSnapshotHash ?? "")) {
      throw new Error(`${entry.economicPolicyVersion}.rosterSnapshotHash must be a canonical sha256 digest`);
    }
    if (binding.botVersionSuffix !== undefined && (typeof binding.botVersionSuffix !== "string" || binding.botVersionSuffix.length === 0)) {
      throw new Error(`${entry.economicPolicyVersion}.botVersionSuffix must be a non-empty string when provided`);
    }
  }
  for (const field of identityFields) {
    const values = ECONOMIC_POLICY_ENTRIES.map((entry) => bindings.entries[entry.economicPolicyVersion][field]);
    if (new Set(values).size !== values.length) throw new Error(`matrix bindings must use distinct ${field} values`);
  }
  return bindings;
}

export function validateMatrixReports(records) {
  const errors = [];
  if (records.length !== ECONOMIC_POLICY_ENTRIES.length) {
    errors.push(`expected ${ECONOMIC_POLICY_ENTRIES.length} completed entries, received ${records.length}`);
  }
  const comparable = [];
  for (const record of records) {
    const report = record.report;
    if (record.exitCode !== 0 || report === null) {
      errors.push(`${record.id}: runner failed with exit code ${record.exitCode}`);
      continue;
    }
    if (report.runId !== record.binding.runId) errors.push(`${record.id}: report runId does not match binding`);
    if (!["completed", "completed_with_warnings"].includes(report.status)) {
      errors.push(`${record.id}: report status is ${report.status}`);
    }
    if (report.mode?.economicPolicyVersion !== record.economicPolicyVersion) errors.push(`${record.id}: economic policy mismatch`);
    if (report.mode?.venueSessionId !== record.binding.venueSessionId) errors.push(`${record.id}: venue session mismatch`);
    if (report.rosterBinding?.admissionWindowId !== record.binding.admissionWindowId
      || report.rosterBinding?.rosterSnapshotId !== record.binding.rosterSnapshotId
      || report.rosterBinding?.rosterSnapshotHash !== record.binding.rosterSnapshotHash) {
      errors.push(`${record.id}: roster binding mismatch`);
    }
    const reconciliation = report.economicReconciliation;
    if (reconciliation?.status !== "pass" || reconciliation.complete !== true || reconciliation.supported !== true) {
      errors.push(`${record.id}: economic reconciliation did not pass completely`);
    }
    if (Number(report.executionSummary?.fillCount ?? 0) <= 0) errors.push(`${record.id}: no live fills were recorded`);
    if (Number(report.executionSummary?.byRole?.UNSPECIFIED?.fillCount ?? 0) > 0) {
      errors.push(`${record.id}: unspecified execution roles remain`);
    }
    comparable.push(report);
  }
  if (comparable.length > 1) {
    const fixedFacts = comparable.map(comparisonFacts);
    for (const field of Object.keys(fixedFacts[0])) {
      const values = fixedFacts.map((facts) => JSON.stringify(facts[field]));
      if (new Set(values).size !== 1) errors.push(`fixed comparison fact changed across policies: ${field}`);
    }
  }
  return {
    status: errors.length === 0 ? "pass" : "fail",
    errors,
    requiredPolicies: ECONOMIC_POLICY_ENTRIES.map((entry) => entry.economicPolicyVersion),
    completedPolicies: records.filter((record) => record.exitCode === 0).map((record) => record.economicPolicyVersion),
  };
}

export function parseArgs(argv) {
  const options = {};
  for (const arg of argv) {
    if (arg.startsWith("--bindings=")) options.bindings = arg.slice("--bindings=".length);
    else if (arg.startsWith("--out-dir=")) options.outDir = arg.slice("--out-dir=".length);
    else if (arg.startsWith("--mode=")) options.mode = arg.slice("--mode=".length);
    else if (arg.startsWith("--extra-bots=")) options.extraBots = arg.slice("--extra-bots=".length);
    else if (arg.startsWith("--runtime=")) options.runtime = arg.slice("--runtime=".length);
    else if (arg.startsWith("--submit-mode=")) options.submitMode = arg.slice("--submit-mode=".length);
    else if (arg.startsWith("--compartment=")) options.compartment = arg.slice("--compartment=".length);
    else if (arg.startsWith("--duration-seconds=")) options.durationSeconds = positiveNumber(arg.slice("--duration-seconds=".length));
    else if (arg.startsWith("--tick-interval-ms=")) options.tickIntervalMs = positiveNumber(arg.slice("--tick-interval-ms=".length));
    else if (arg.startsWith("--venue-url=")) options.venueUrl = arg.slice("--venue-url=".length);
    else if (arg.startsWith("--arena-admin-url=")) options.arenaAdminUrl = arg.slice("--arena-admin-url=".length);
    else if (arg.startsWith("--projection-drain-timeout-ms=")) options.projectionDrainTimeoutMs = positiveNumber(arg.slice("--projection-drain-timeout-ms=".length));
    else if (arg === "--no-seed-reference") options.seedReference = false;
    else if (arg === "--no-require-projection-drain") options.requireProjectionDrain = false;
    else if (arg === "--skip-projector-preflight") options.skipProjectorPreflight = true;
    else if (arg === "--validate-existing") options.validateExisting = true;
    else throw new Error(`unsupported argument: ${arg}`);
  }
  return options;
}

function reportSummary(report) {
  const reconciliation = report.economicReconciliation ?? {};
  return {
    runId: report.runId,
    status: report.status,
    venueSessionId: report.mode?.venueSessionId,
    economicPolicyVersion: report.mode?.economicPolicyVersion,
    economicPolicyHash: report.mode?.economicPolicyHash,
    policyEnvelopeHash: report.policyEnvelopeHash,
    rosterSnapshotHash: report.rosterBinding?.rosterSnapshotHash,
    fillCount: report.executionSummary?.fillCount ?? 0,
    executionRoles: report.executionSummary?.byRole ?? {},
    reconciliationStatus: reconciliation.status,
    reconciliationHash: reconciliation.reconciliationHash,
    feesPaid: reconciliation.ledgers?.competition?.feesPaid ?? 0,
    rebatesReceived: reconciliation.ledgers?.competition?.rebatesReceived ?? 0,
    facilityCashDelta: reconciliation.economicFacility?.cashDelta ?? 0,
    commandAccountingGap: report.commandAccounting?.accountingGap,
    healthStatus: report.healthSummary?.status,
    healthWarnings: report.healthSummary?.failures ?? [],
  };
}

function comparisonFacts(report) {
  return {
    modeId: report.mode?.modeId,
    seed: report.mode?.seed,
    seedSetHash: report.mode?.seedSetHash,
    scoringPolicyHash: report.mode?.scoringPolicyHash,
    riskPolicyHash: report.mode?.riskPolicyHash,
    actorProfileCatalogHash: report.mode?.actorProfileCatalogHash,
    botIds: (report.botResults ?? []).map((result) => result.botId).sort(),
    ticks: report.totals?.ticks,
    fillCount: report.executionSummary?.fillCount,
    filledQuantity: report.executionSummary?.filledQuantity,
    filledNotional: report.executionSummary?.filledNotional,
    avgFillPrice: report.executionSummary?.avgFillPrice,
    byInstrument: report.executionSummary?.byInstrument,
    byRole: report.executionSummary?.byRole,
  };
}

function requiredOption(value, option) {
  if (typeof value !== "string" || value.length === 0) throw new Error(`${option} is required`);
  return value;
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
  if (!Number.isFinite(parsed) || parsed <= 0) throw new Error(`expected a positive number, received ${value}`);
  return parsed;
}
