import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const suffix = `${Date.now()}`;
const botId = env("DEV_ARENA_BOT_RISK_SMOKE_BOT_ID", `arena-risk-bot-${suffix}`);
const versionId = env("DEV_ARENA_BOT_RISK_SMOKE_VERSION_ID", "v1");
const actorId = env("ADMIN_ACTOR_ID", "admin-cli");
const runId = env("DEV_ARENA_BOT_RISK_SMOKE_RUN_ID", `arena-risk-smoke-${suffix}`);
const venueSessionId = env("DEV_ARENA_BOT_RISK_SMOKE_VENUE_SESSION_ID", `arena-risk-session-${suffix}`);
const traderActorId = "arena-risk-smoke-user";

async function request(method, path, payload = undefined, headers = {}) {
  const response = await fetch(`${runtimeUrl}${path}`, {
    method,
    headers: {
      "content-type": "application/json",
      ...headers,
    },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const text = await response.text();
  return { status: response.status, text, json: parseJson(text) };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch (_error) {
    return {};
  }
}

async function expectOk(method, path, payload = undefined, headers = {}) {
  const response = await request(method, path, payload, headers);
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${method} ${path} failed (${response.status}): ${response.text}`);
  }
  return response;
}

function assertArenaAdminConfigured(response, operation) {
  if (response.status === 503 && String(response.text).includes("arena admin service unavailable")) {
    throw new Error(`${operation} requires PLATFORM_ARENA_ADMIN_ENABLED=1 on the runtime service`);
  }
}

async function seedReferenceData() {
  const internalRouteHeaders = { "X-Reef-Internal-Route": "true" };
  await expectOk("POST", "/reference/instruments", {
    instrumentId: "AAPL",
    symbol: "AAPL",
    assetClass: "US_EQ",
    currency: "USD",
  }, internalRouteHeaders);
  await expectOk("POST", "/reference/participants", {
    participantId: "participant-1",
    name: "Participant 1",
  }, internalRouteHeaders);
  await expectOk("POST", "/reference/accounts", {
    accountId: "account-1",
    participantId: "participant-1",
    accountType: "HOUSE",
  }, internalRouteHeaders);
  await expectOk("POST", "/auth/roles", {
    roleId: "order_trader",
    permissions: "order.submit,order.cancel,order.modify",
  }, internalRouteHeaders);
  await expectOk("POST", "/auth/actor-roles", {
    actorId: traderActorId,
    roleId: "order_trader",
  }, internalRouteHeaders);
}

async function registerArenaBot() {
  const bot = await request("POST", "/internal/admin/arena/bots", {
    botId,
    fileName: `${botId}.ts`,
    name: "Arena Risk Smoke Bot",
    publisher: "Reef Smoke",
    email: "arena-risk-smoke@example.com",
    actorId,
    correlationId: `arena-risk-smoke-${suffix}`,
  });
  assertArenaAdminConfigured(bot, "arena bot registration");
  if (bot.status !== 200 && bot.status !== 409) {
    throw new Error(`arena bot registration failed (${bot.status}): ${bot.text}`);
  }

  const version = await request("POST", "/internal/admin/arena/bot-versions", {
    botId,
    versionId,
    sourceHash: `sha256:source-${suffix}`,
    artifactHash: `sha256:artifact-${suffix}`,
    sdkVersion: "1.5.0",
    apiVersion: "v1",
    dependencyManifestHash: `sha256:deps-${suffix}`,
    actorId,
    correlationId: `arena-risk-smoke-${suffix}`,
  });
  assertArenaAdminConfigured(version, "arena bot version registration");
  if (version.status !== 200 && version.status !== 409) {
    throw new Error(`arena bot version registration failed (${version.status}): ${version.text}`);
  }
}

async function quarantineBotVersion() {
  const response = await request("POST", "/internal/admin/arena/bot-versions/transition", {
    botId,
    versionId,
    status: "quarantined",
    reason: "arena risk smoke",
    actorId,
    correlationId: `arena-risk-smoke-${suffix}`,
  });
  assertArenaAdminConfigured(response, "arena bot version transition");
  if (response.status !== 200) {
    throw new Error(`arena bot version quarantine failed (${response.status}): ${response.text}`);
  }
}

async function submitBotOrder() {
  return request(
    "POST",
    "/api/v1/orders/submit",
    {
      commandId: `cmd-arena-risk-${suffix}`,
      traceId: `trace-arena-risk-${suffix}`,
      causationId: `cause-arena-risk-${suffix}`,
      correlationId: `corr-arena-risk-${suffix}`,
      actorId: traderActorId,
      runId,
      venueSessionId,
      occurredAt: "2026-07-05T13:00:00Z",
      orderId: `ord-arena-risk-${suffix}`,
      instrumentId: "AAPL",
      participantId: "participant-1",
      accountId: "account-1",
      side: "BUY",
      orderType: "LIMIT",
      quantityUnits: "100",
      limitPrice: "150250000000",
      currency: "USD",
      timeInForce: "DAY",
      botId,
      botVersion: versionId,
    },
    {
      "X-Client-Id": `bot:${botId}`,
      "Idempotency-Key": `idem-arena-risk-${suffix}`,
    },
  );
}

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);
await seedReferenceData();
await registerArenaBot();
await quarantineBotVersion();

const rejected = await submitBotOrder();
if (rejected.status !== 403 || rejected.json.code !== "BOT_DISABLED") {
  throw new Error(`expected BOT_DISABLED rejection, got ${rejected.status}: ${rejected.text}`);
}

console.log("arena bot risk smoke passed");
console.log(JSON.stringify({ botId, versionId, runId, code: rejected.json.code }, null, 2));
