import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import http from "node:http";
import https from "node:https";
import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const repoRoot = new URL("../../", import.meta.url).pathname;
const { runtimeUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const suffix = `${Date.now()}`;
const actorId = env("ADMIN_ACTOR_ID", "admin-cli");
const botId = env("DEV_ARENA_RUN_RESULT_SMOKE_BOT_ID", "");
const versionId = env("DEV_ARENA_RUN_RESULT_SMOKE_VERSION_ID", "v1");
const runId = env("DEV_ARENA_RUN_RESULT_SMOKE_RUN_ID", `arena-result-run-${suffix}`);
const modeId = env("DEV_ARENA_RUN_RESULT_SMOKE_MODE_ID", "hosted-sim-smoke");
const scenarioId = env("DEV_ARENA_RUN_RESULT_SMOKE_SCENARIO_ID", "hosted-summary-smoke");
const seed = Number(env("DEV_ARENA_RUN_RESULT_SMOKE_SEED", "42"));
const riskPolicyVersion = env("DEV_ARENA_RUN_RESULT_SMOKE_RISK_POLICY_VERSION", "policy-v1");
const scoringPolicyVersion = env("DEV_ARENA_RUN_RESULT_SMOKE_SCORING_POLICY_VERSION", "score-v1");
const admissionWindowId = env("DEV_ARENA_RUN_RESULT_SMOKE_ADMISSION_WINDOW_ID", "");
const rosterSnapshotId = env("DEV_ARENA_RUN_RESULT_SMOKE_ROSTER_SNAPSHOT_ID", "");
const rosterSnapshotHash = env("DEV_ARENA_RUN_RESULT_SMOKE_ROSTER_SNAPSHOT_HASH", "");
if ([botId, admissionWindowId, rosterSnapshotId, rosterSnapshotHash].some((value) => value.length === 0)) {
  throw new Error("run-result smoke requires an existing locked-roster bot and admission window via DEV_ARENA_RUN_RESULT_SMOKE_BOT_ID, DEV_ARENA_RUN_RESULT_SMOKE_ADMISSION_WINDOW_ID, DEV_ARENA_RUN_RESULT_SMOKE_ROSTER_SNAPSHOT_ID, and DEV_ARENA_RUN_RESULT_SMOKE_ROSTER_SNAPSHOT_HASH");
}
const seedSetHash = env("DEV_ARENA_RUN_RESULT_SMOKE_SEED_SET_HASH", `sha256:${"5".repeat(64)}`);
const actorProfileVersion = env("DEV_ARENA_RUN_RESULT_SMOKE_ACTOR_PROFILE_VERSION", "actors-v1");
const actorProfileHash = env("DEV_ARENA_RUN_RESULT_SMOKE_ACTOR_PROFILE_HASH", `sha256:${"6".repeat(64)}`);
const riskPolicyHash = env("DEV_ARENA_RUN_RESULT_SMOKE_RISK_POLICY_HASH", `sha256:${"7".repeat(64)}`);
const scoringPolicyHash = env(
  "DEV_ARENA_RUN_RESULT_SMOKE_SCORING_POLICY_HASH",
  "sha256:d87133eca6c0a4994fd0aa30af3108b72ac679955128f14e64335417358dd15a",
);
const economicPolicyVersion = env("DEV_ARENA_RUN_RESULT_SMOKE_ECONOMIC_POLICY_VERSION", "preview-zero-fee-v1");
const economicPolicyHash = env(
  "DEV_ARENA_RUN_RESULT_SMOKE_ECONOMIC_POLICY_HASH",
  "sha256:27dd4a5b641465079a3137ee9c97ae3c370d8ea6027f6b1663b502e7b86dff29",
);
const policyEnvelopeHash = env(
  "DEV_ARENA_RUN_RESULT_SMOKE_POLICY_ENVELOPE_HASH",
  `sha256:${createHash("sha256")
    .update(`${modeId}:${scoringPolicyVersion}:${scoringPolicyHash}:${economicPolicyVersion}:${economicPolicyHash}`)
    .digest("hex")}`,
);
const finalEquity = Number(env("DEV_ARENA_RUN_RESULT_SMOKE_FINAL_EQUITY", "1025000"));
const replacementFinalEquity = finalEquity + 1500;
const arenaAdminApiToken = env("ARENA_ADMIN_API_TOKEN", "");

async function request(method, path, payload = undefined) {
  const response = await requestJson(`${runtimeUrl}${path}`, method, payload);
  return { status: response.status, text: response.text, json: parseJson(response.text) };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch (_error) {
    return {};
  }
}

function assertArenaAdminConfigured(response, operation) {
  if (response.status === 503 && String(response.text).includes("arena admin service unavailable")) {
    throw new Error(`${operation} requires PLATFORM_ARENA_ADMIN_ENABLED=1 on the runtime service`);
  }
}

async function expectOk(method, path, payload = undefined) {
  const response = await request(method, path, payload);
  assertArenaAdminConfigured(response, `${method} ${path}`);
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${method} ${path} failed (${response.status}): ${response.text}`);
  }
  return response;
}

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

await expectOk("POST", "/admin/v1/arena/runs", {
  runId,
  modeId,
  scenarioId,
  seed,
  policyVersion: riskPolicyVersion,
  admissionWindowId,
  rosterSnapshotId,
  rosterSnapshotHash,
  seedSetHash,
  actorProfileVersion,
  actorProfileHash,
  riskPolicyHash,
  policyEnvelopeHash,
  scoringPolicyVersion,
  scoringPolicyHash,
  economicPolicyVersion,
  economicPolicyHash,
  botVersions: [{ botId, versionId }],
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await expectOk("POST", "/admin/v1/arena/runs/status", {
  runId,
  status: "running",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
const summaryDir = mkdtempSync(join(tmpdir(), "reef-arena-result-smoke-"));
const summaryPath = join(summaryDir, "summary.json");
writeFileSync(summaryPath, JSON.stringify({
  approvalStatus: "approved_for_merge",
  runId,
  policyEnvelopeHash,
  scoringPolicyHash,
  actionsProposed: 12,
  orderActionsProposed: 8,
  dataCalls: 20,
  signalsGenerated: 4,
}, null, 2));

const ingest = spawnSync("bun", [
  "scripts/dev/arena-ingest-bot-run-result.mjs",
  `--summary=${summaryPath}`,
  `--bot-id=${botId}`,
  `--version-id=${versionId}`,
  `--scoring-policy-version=${scoringPolicyVersion}`,
  `--final-equity=${finalEquity}`,
  "--realized-pnl=25000",
  "--max-drawdown=1000",
  `--runtime-url=${runtimeUrl}`,
  `--actor-id=${actorId}`,
  `--correlation-id=arena-result-smoke-${suffix}`,
], { cwd: repoRoot, encoding: "utf8" });
if (ingest.status !== 0) {
  throw new Error(`arena result ingestion failed (${ingest.status}): ${ingest.stdout}${ingest.stderr}`);
}

const retryIngest = spawnSync("bun", [
  "scripts/dev/arena-ingest-bot-run-result.mjs",
  `--summary=${summaryPath}`,
  `--bot-id=${botId}`,
  `--version-id=${versionId}`,
  `--scoring-policy-version=${scoringPolicyVersion}`,
  `--final-equity=${replacementFinalEquity}`,
  "--realized-pnl=26500",
  "--max-drawdown=750",
  `--runtime-url=${runtimeUrl}`,
  `--actor-id=${actorId}`,
  `--correlation-id=arena-result-smoke-retry-${suffix}`,
], { cwd: repoRoot, encoding: "utf8" });
if (retryIngest.status !== 0) {
  throw new Error(`arena result retry ingestion failed (${retryIngest.status}): ${retryIngest.stdout}${retryIngest.stderr}`);
}

const rawResults = await expectOk(
  "GET",
  `/admin/v1/arena/run-bot-results?runId=${encodeURIComponent(runId)}&actorId=${encodeURIComponent(actorId)}`,
);
const rawResult = rawResults.json.results?.find((candidate) => candidate.botId === botId && candidate.versionId === versionId);
if (!rawResult) {
  throw new Error(`expected raw result for ${runId}/${botId}/${versionId}, got ${rawResults.text}`);
}
if (rawResults.json.results?.filter((candidate) => candidate.botId === botId && candidate.versionId === versionId).length !== 1) {
  throw new Error(`expected one replaced raw result for ${runId}/${botId}/${versionId}, got ${rawResults.text}`);
}
if (rawResult.finalEquity !== replacementFinalEquity) {
  throw new Error(`expected raw finalEquity ${replacementFinalEquity}, got ${rawResult.finalEquity}`);
}
if (rawResult.realizedPnl !== 26500 || rawResult.maxDrawdown !== 750) {
  throw new Error(`expected retry result metrics to replace first ingest, got ${rawResults.text}`);
}
if (rawResult.scoringPolicyHash !== scoringPolicyHash || rawResult.policyEnvelopeHash !== policyEnvelopeHash) {
  throw new Error(`expected result policy locks to match the accepted run, got ${rawResults.text}`);
}

await expectOk("POST", "/admin/v1/arena/runs/status", {
  runId,
  status: "completed",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});

const leaderboard = await expectOk(
  "GET",
  `/admin/v1/arena/leaderboard?modeId=${encodeURIComponent(modeId)}&scoringPolicyVersion=${encodeURIComponent(scoringPolicyVersion)}&limit=10&actorId=${encodeURIComponent(actorId)}`,
);
const entry = leaderboard.json.entries?.find((candidate) => candidate.runId === runId && candidate.botId === botId);
if (!entry) {
  throw new Error(`expected leaderboard entry for ${runId}/${botId}, got ${leaderboard.text}`);
}
if (entry.finalEquity !== replacementFinalEquity) {
  throw new Error(`expected finalEquity ${replacementFinalEquity}, got ${entry.finalEquity}`);
}

console.log("arena run result ingestion smoke passed");
console.log(JSON.stringify({ botId, versionId, runId, modeId, scoringPolicyVersion, finalEquity: replacementFinalEquity }, null, 2));

function requestJson(url, method, payload = undefined) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const request = transport.request(parsed, {
      method,
      headers: {
        "content-type": "application/json",
        ...(arenaAdminApiToken.trim() !== "" ? { Authorization: `Bearer ${arenaAdminApiToken}` } : {}),
        "X-Reef-Actor-Id": actorId,
        "X-Correlation-Id": `arena-result-smoke-${suffix}`,
        ...(body === undefined ? {} : { "content-length": Buffer.byteLength(body) }),
      },
    }, (response) => {
      let text = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { text += chunk; });
      response.on("end", () => {
        resolve({ status: response.statusCode, text });
      });
    });
    request.on("error", reject);
    if (body !== undefined) request.write(body);
    request.end();
  });
}
