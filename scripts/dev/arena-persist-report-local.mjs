import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { composeArgs } from "./lib/compose-utils.mjs";

const args = parseArgs(process.argv.slice(2));
const reportPath = args.report;
const outPath = args.out ?? reportPath;
const actorId = args.actorId ?? process.env.ADMIN_ACTOR_ID ?? "admin-cli";
const service = args.service ?? "platform-api";
const dryRun = args.dryRun === true || args["dry-run"] === true;
const arenaAdminApiToken = args.adminApiToken ?? args["admin-api-token"] ?? process.env.ARENA_ADMIN_API_TOKEN ?? "";

if (!reportPath) {
  console.error("usage: node scripts/dev/arena-persist-report-local.mjs --report=<arena-report.json> [--out=<updated-report.json>] [--dry-run]");
  process.exit(1);
}

const report = JSON.parse(readFileSync(reportPath, "utf8"));
const mode = report.mode ?? {};
const rosterBinding = report.rosterBinding ?? {};
for (const field of ["admissionWindowId", "rosterSnapshotId", "rosterSnapshotHash"]) {
  if (typeof rosterBinding[field] !== "string" || rosterBinding[field].length === 0) {
    throw new Error(`report.rosterBinding.${field} is required`);
  }
}
const correlationId = `${report.runId}-local-persist`;
const botById = new Map((report.sessionReports ?? []).map((session) => [session.bot?.botId, session.bot]).filter(([botId]) => botId));
const botVersions = (report.botResults ?? []).map((result) => ({ botId: result.botId, versionId: result.versionId }));
const operations = [];

for (const result of report.botResults ?? []) {
  const bot = botById.get(result.botId) ?? {};
  operations.push(request("POST", "/admin/v1/arena/bots", {
    botId: result.botId,
    fileName: bot.entryPath ?? `${result.botId}.ts`,
    name: result.botId,
    publisher: bot.role === "competitor" ? "Reef Local Competitor" : "Reef Built-In",
    email: "arena-local@reef.local",
    actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
  operations.push(request("POST", "/admin/v1/arena/bot-versions", {
    botId: result.botId,
    versionId: result.versionId,
    sourceHash: bot.artifact?.manifest?.sourceHash ?? `sha256:${result.botId}-source`,
    artifactHash: bot.artifact?.manifest?.artifactHash ?? `sha256:${result.botId}-artifact`,
    sdkVersion: "1.5.0",
    apiVersion: "v1",
    dependencyManifestHash: `sha256:${result.botId}-deps`,
    actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
  for (const [status, reason] of [
    ["submitted", "local arena report submitted"],
    ["checks-passed", "local arena report checks passed"],
    ["approved", "local arena report approved"],
  ]) {
    operations.push(request("POST", "/admin/v1/arena/bot-versions/transition", {
      botId: result.botId,
      versionId: result.versionId,
      status,
      reason,
      actorId,
      correlationId,
    }, { allowInvalidTransition: true }));
  }
}

operations.push(request("POST", "/admin/v1/arena/runs", {
  runId: report.runId,
  modeId: mode.modeId,
  scenarioId: mode.scenarioId ?? `${mode.modeId}-scenario`,
  seed: Number(mode.seed ?? 0),
  policyVersion: mode.riskPolicyVersion ?? "arena-risk-v0",
  admissionWindowId: rosterBinding.admissionWindowId,
  rosterSnapshotId: rosterBinding.rosterSnapshotId,
  rosterSnapshotHash: rosterBinding.rosterSnapshotHash,
  seedSetHash: mode.seedSetHash ?? report.policyEnvelope?.seedSetHash,
  actorProfileVersion: mode.actorProfileCatalogVersion,
  actorProfileHash: mode.actorProfileCatalogHash,
  riskPolicyHash: mode.riskPolicyHash ?? report.policyEnvelope?.riskPolicyHash,
  policyEnvelopeHash: report.policyEnvelopeHash,
  scoringPolicyVersion: mode.scoringPolicyVersion,
  scoringPolicyHash: mode.scoringPolicyHash ?? report.policyEnvelope?.scoringPolicyHash,
  economicPolicyVersion: mode.economicPolicyVersion,
  economicPolicyHash: mode.economicPolicyHash ?? report.policyEnvelope?.economicPolicyHash,
  botVersions,
  actorId,
  correlationId,
}, { allowAlreadyExists: true }));

operations.push(request("POST", "/admin/v1/arena/runs/status", {
  runId: report.runId,
  status: "running",
  actorId,
  correlationId,
}, { allowInvalidTransition: true }));

for (const result of report.botResults ?? []) {
  operations.push(request("POST", "/admin/v1/arena/run-bot-results", {
    runId: report.runId,
    botId: result.botId,
    versionId: result.versionId,
    scoringPolicyVersion: mode.scoringPolicyVersion,
    scoringPolicyHash: mode.scoringPolicyHash ?? report.policyEnvelope?.scoringPolicyHash,
    policyEnvelopeHash: report.policyEnvelopeHash,
    finalEquity: result.score,
    realizedPnl: result.score - 1_000_000,
    maxDrawdown: result.disqualified ? 250_000 : 0,
    actionsProposed: result.actionsProposed,
    orderActionsProposed: result.venueCommands,
    dataCalls: result.dataCalls ?? 0,
    signalsGenerated: 0,
    disqualified: result.disqualified,
    scoreEligible: result.scoreEligible,
    publicLeaderboard: result.publicLeaderboard,
    actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
}

for (const event of report.enforcementEvents ?? []) {
  operations.push(request("POST", "/admin/v1/arena/run-enforcement-events", {
    runId: report.runId,
    botId: event.botId,
    versionId: event.versionId,
    decision: event.decision,
    reasonCode: event.reasonCode,
    reason: event.reason,
    policyVersion: event.policyVersion,
    countersJson: JSON.stringify(event.counters ?? {}),
    actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
}

operations.push(request("POST", "/admin/v1/arena/runs/status", {
  runId: report.runId,
  status: "completed",
  actorId,
  correlationId,
}, { allowInvalidTransition: true }));

const rawResults = request("GET", `/admin/v1/arena/run-bot-results?runId=${encodeURIComponent(report.runId)}&actorId=${encodeURIComponent(actorId)}`);
const rawEnforcementEvents = request("GET", `/admin/v1/arena/run-enforcement-events?runId=${encodeURIComponent(report.runId)}&actorId=${encodeURIComponent(actorId)}`);
const leaderboard = request(
  "GET",
  `/admin/v1/arena/leaderboard?modeId=${encodeURIComponent(mode.modeId)}&scoringPolicyVersion=${encodeURIComponent(mode.scoringPolicyVersion)}&limit=50&actorId=${encodeURIComponent(actorId)}`,
);
const leaderboardEntry = leaderboard.body?.entries?.find((entry) => entry.runId === report.runId);
const expectsLeaderboardEntry = (report.botResults ?? []).some((result) => result.scoreEligible && result.publicLeaderboard && !result.disqualified);
if (!dryRun && expectsLeaderboardEntry && leaderboardEntry === undefined) {
  throw new Error(`arena leaderboard missing run ${report.runId}: ${JSON.stringify(leaderboard.body)}`);
}

report.persistence = {
  enabled: true,
  skipped: false,
  mode: dryRun ? "dry-run" : "docker-compose-loopback",
  service,
  operations,
  rawResults,
  rawEnforcementEvents,
  leaderboard,
  leaderboardEntry,
};

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, `${JSON.stringify(report, null, 2)}\n`);
console.log(`arena report persisted locally: ${resolve(outPath)}`);
console.log(`mode=${report.persistence.mode} operations=${operations.length} leaderboardEntry=${leaderboardEntry?.botId ?? "none"}`);

function request(method, path, payload, options = {}) {
  if (dryRun) {
    return {
      path,
      method,
      statusCode: 200,
      ok: true,
      dryRun: true,
      requestPayload: payload,
      body: dryRunBody(path, payload),
    };
  }

  const result = curlRequest(method, path, payload);
  let parsed = parseCurlResponse(result, method, path);
  const { statusCode, text, body } = parsed;
  if (statusCode >= 200 && statusCode < 300) {
    return { path, method, statusCode, ok: true, body };
  }
  const encoded = JSON.stringify(body);
  if (options.allowAlreadyExists && encoded.includes("already exists")) {
    return { path, method, statusCode, ok: true, ignored: "already_exists", body };
  }
  if (options.allowInvalidTransition && (encoded.includes("invalid bot version transition") || encoded.includes("invalid arena run transition"))) {
    return { path, method, statusCode, ok: true, ignored: "invalid_transition", body };
  }
  throw new Error(`arena local persist ${method} ${path} failed (${statusCode}): ${text}`);
}

function curlRequest(method, path, payload) {
  const command = composeArgs([
    "exec",
    "-T",
    service,
    "curl",
    "-sS",
    "-w",
    "\n%{http_code}",
    "-X",
    method,
    "-H",
    "Content-Type: application/json",
    "-H",
    `X-Reef-Actor-Id: ${actorId}`,
    "-H",
    `X-Correlation-Id: ${correlationId}`,
  ]);
  if (arenaAdminApiToken.trim() !== "") {
    command.push("-H", `Authorization: Bearer ${arenaAdminApiToken}`);
  }
  if (method === "POST") {
    command.push("--data-binary", "@-");
  }
  command.push(`http://127.0.0.1:8080${path}`);
  const result = spawnSync("docker", command, {
    cwd: new URL("../../", import.meta.url).pathname,
    encoding: "utf8",
    input: method === "POST" ? JSON.stringify(payload) : undefined,
  });
  return result;
}

function parseCurlResponse(result, method, path) {
  if (result.status !== 0) {
    throw new Error(`docker compose exec curl failed for ${method} ${path}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`);
  }
  const lines = result.stdout.trimEnd().split("\n");
  const statusCode = Number(lines.pop());
  const text = lines.join("\n");
  const body = safeJson(text);
  return { statusCode, text, body };
}

function dryRunBody(path, payload) {
  if (path.includes("/leaderboard")) {
    const entry = (report.leaderboard ?? [])[0];
    return { status: "ok", entries: entry ? [{ ...entry, runId: report.runId, modeId: mode.modeId, scoringPolicyVersion: mode.scoringPolicyVersion }] : [] };
  }
  if (path.includes("/run-bot-results")) {
    return { status: "ok", results: report.botResults ?? [] };
  }
  if (path.includes("/run-enforcement-events")) {
    return { status: "ok", events: report.enforcementEvents ?? [] };
  }
  return { status: "ok", payload };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

function parseArgs(values) {
  const parsed = {};
  for (const value of values) {
    if (!value.startsWith("--")) continue;
    const index = value.indexOf("=");
    if (index === -1) {
      parsed[value.slice(2)] = true;
    } else {
      parsed[value.slice(2, index)] = value.slice(index + 1);
    }
  }
  return parsed;
}
