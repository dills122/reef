import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import http from "node:http";
import https from "node:https";
import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const repoRoot = new URL("../../", import.meta.url).pathname;
const { runtimeUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const suffix = `${Date.now()}`;
const actorId = env("ADMIN_ACTOR_ID", "admin-cli");
const botId = env("DEV_ARENA_RUN_RESULT_SMOKE_BOT_ID", `arena-result-bot-${suffix}`);
const versionId = env("DEV_ARENA_RUN_RESULT_SMOKE_VERSION_ID", "v1");
const runId = env("DEV_ARENA_RUN_RESULT_SMOKE_RUN_ID", `arena-result-run-${suffix}`);
const modeId = env("DEV_ARENA_RUN_RESULT_SMOKE_MODE_ID", "hosted-sim-smoke");
const scoringPolicyVersion = env("DEV_ARENA_RUN_RESULT_SMOKE_SCORING_POLICY_VERSION", "score-v1");
const finalEquity = Number(env("DEV_ARENA_RUN_RESULT_SMOKE_FINAL_EQUITY", "1025000"));
const replacementFinalEquity = finalEquity + 1500;
const arenaAdminApiToken = env("ARENA_ADMIN_API_TOKEN", "");

async function request(method, path, payload = undefined, internalRoute = false) {
  const response = await requestJson(`${runtimeUrl}${path}`, method, payload, internalRoute);
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
  let response = await request(method, path, payload);
  if (canFallbackToInternal(path, response)) {
    response = await request(method, internalArenaPath(path), payload, true);
  }
  assertArenaAdminConfigured(response, `${method} ${path}`);
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${method} ${path} failed (${response.status}): ${response.text}`);
  }
  return response;
}

async function transitionVersion(status, reason) {
  await expectOk("POST", "/admin/v1/arena/bot-versions/transition", {
    botId,
    versionId,
    status,
    reason,
    actorId,
    correlationId: `arena-result-smoke-${suffix}`,
  });
}

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

await expectOk("POST", "/admin/v1/arena/bots", {
  botId,
  fileName: `${botId}.ts`,
  name: "Arena Result Smoke Bot",
  publisher: "Reef Smoke",
  email: "arena-result-smoke@example.com",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await expectOk("POST", "/admin/v1/arena/bot-versions", {
  botId,
  versionId,
  sourceHash: `sha256:source-${suffix}`,
  artifactHash: `sha256:artifact-${suffix}`,
  sdkVersion: "1.5.0",
  apiVersion: "v1",
  dependencyManifestHash: `sha256:deps-${suffix}`,
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await transitionVersion("submitted", "result ingestion smoke submitted");
await transitionVersion("checks-passed", "result ingestion smoke checks passed");
await transitionVersion("approved", "result ingestion smoke approved");

await expectOk("POST", "/admin/v1/arena/runs", {
  runId,
  modeId,
  scenarioId: "hosted-summary-smoke",
  seed: 42,
  policyVersion: "policy-v1",
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
await expectOk("POST", "/admin/v1/arena/runs/status", {
  runId,
  status: "completed",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});

const summaryDir = mkdtempSync(join(tmpdir(), "reef-arena-result-smoke-"));
const summaryPath = join(summaryDir, "summary.json");
writeFileSync(summaryPath, JSON.stringify({
  approvalStatus: "approved_for_merge",
  runId,
  actionsProposed: 12,
  orderActionsProposed: 8,
  dataCalls: 20,
  signalsGenerated: 4,
}, null, 2));

const ingest = spawnSync("node", [
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

const retryIngest = spawnSync("node", [
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

function canFallbackToInternal(path, response) {
  const host = new URL(runtimeUrl).hostname;
  const loopback = host === "127.0.0.1" || host === "localhost" || host === "::1";
  if (!loopback || arenaAdminApiToken.trim() !== "" || !path.startsWith("/admin/v1/arena/")) return false;
  return response.status === 404 ||
    response.status === 401 ||
    (response.status === 503 && response.text.includes("ARENA_ADMIN_API_TOKEN"));
}

function internalArenaPath(path) {
  return path.replace("/admin/v1/arena/", "/internal/admin/arena/");
}

function requestJson(url, method, payload = undefined, internalRoute = false) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const request = transport.request(parsed, {
      method,
      headers: {
        "content-type": "application/json",
        ...(arenaAdminApiToken.trim() !== "" && !internalRoute ? { Authorization: `Bearer ${arenaAdminApiToken}` } : {}),
        ...(internalRoute ? { "X-Reef-Internal-Route": "true" } : {}),
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
