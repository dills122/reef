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

async function transitionVersion(status, reason) {
  await expectOk("POST", "/internal/admin/arena/bot-versions/transition", {
    botId,
    versionId,
    status,
    reason,
    actorId,
    correlationId: `arena-result-smoke-${suffix}`,
  });
}

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

await expectOk("POST", "/internal/admin/arena/bots", {
  botId,
  fileName: `${botId}.ts`,
  name: "Arena Result Smoke Bot",
  publisher: "Reef Smoke",
  email: "arena-result-smoke@example.com",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await expectOk("POST", "/internal/admin/arena/bot-versions", {
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

await expectOk("POST", "/internal/admin/arena/runs", {
  runId,
  modeId,
  scenarioId: "hosted-summary-smoke",
  seed: 42,
  policyVersion: "policy-v1",
  botVersions: [{ botId, versionId }],
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await expectOk("POST", "/internal/admin/arena/runs/status", {
  runId,
  status: "running",
  actorId,
  correlationId: `arena-result-smoke-${suffix}`,
});
await expectOk("POST", "/internal/admin/arena/runs/status", {
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

const rawResults = await expectOk(
  "GET",
  `/internal/admin/arena/run-bot-results?runId=${encodeURIComponent(runId)}&actorId=${encodeURIComponent(actorId)}`,
);
const rawResult = rawResults.json.results?.find((candidate) => candidate.botId === botId && candidate.versionId === versionId);
if (!rawResult) {
  throw new Error(`expected raw result for ${runId}/${botId}/${versionId}, got ${rawResults.text}`);
}
if (rawResult.finalEquity !== finalEquity) {
  throw new Error(`expected raw finalEquity ${finalEquity}, got ${rawResult.finalEquity}`);
}

const leaderboard = await expectOk(
  "GET",
  `/internal/admin/arena/leaderboard?modeId=${encodeURIComponent(modeId)}&scoringPolicyVersion=${encodeURIComponent(scoringPolicyVersion)}&limit=10&actorId=${encodeURIComponent(actorId)}`,
);
const entry = leaderboard.json.entries?.find((candidate) => candidate.runId === runId && candidate.botId === botId);
if (!entry) {
  throw new Error(`expected leaderboard entry for ${runId}/${botId}, got ${leaderboard.text}`);
}
if (entry.finalEquity !== finalEquity) {
  throw new Error(`expected finalEquity ${finalEquity}, got ${entry.finalEquity}`);
}

console.log("arena run result ingestion smoke passed");
console.log(JSON.stringify({ botId, versionId, runId, modeId, scoringPolicyVersion, finalEquity }, null, 2));

function requestJson(url, method, payload = undefined) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const request = transport.request(parsed, {
      method,
      headers: {
        "content-type": "application/json",
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
