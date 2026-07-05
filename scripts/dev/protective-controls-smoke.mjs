import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const runId = env("DEV_PROTECTIVE_CONTROLS_SMOKE_RUN_ID", "protective-controls-smoke");
const venueSessionId = env("DEV_PROTECTIVE_CONTROLS_SMOKE_VENUE_SESSION_ID", "protective-controls-session");
const suffix = `${Date.now()}`;
const clientId = "protective-controls-smoke-client";

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

function isAccepted(response) {
  const status = String(response.json.status ?? "").toLowerCase();
  return response.status === 202 || status === "accepted" || response.json.accepted === true;
}

async function submitOrder(label, extra = {}) {
  return request(
    "POST",
    "/api/v1/orders/submit",
    {
      commandId: `protective-${label}-${suffix}`,
      traceId: `protective-trace-${label}-${suffix}`,
      causationId: `protective-causation-${label}-${suffix}`,
      correlationId: `protective-correlation-${label}-${suffix}`,
      actorId: "protective-smoke-user",
      runId,
      venueSessionId,
      occurredAt: "2026-07-04T13:00:00Z",
      orderId: `protective-ord-${label}-${suffix}`,
      instrumentId: "AAPL",
      participantId: "participant-1",
      accountId: "account-1",
      side: "BUY",
      orderType: "LIMIT",
      quantityUnits: "100",
      limitPrice: "150250000000",
      currency: "USD",
      timeInForce: "DAY",
      ...extra,
    },
    {
      "X-Client-Id": clientId,
      "Idempotency-Key": `protective-${label}-${suffix}`,
    },
  );
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
    actorId: "protective-smoke-user",
    roleId: "order_trader",
  }, internalRouteHeaders);
}

async function setAccountRisk(scopeType, scopeId, decision, reason) {
  return expectOk("POST", "/internal/admin/account-risk/controls", {
    scopeType,
    scopeId,
    decision,
    reason,
    actorId: "protective-controls-smoke",
    correlationId: `protective-controls-smoke-${suffix}`,
  });
}

async function setBreaker(scopeType, scopeId, action, reason) {
  return expectOk("POST", "/internal/admin/circuit-breakers", {
    scopeType,
    scopeId,
    action,
    reason,
    actorId: "protective-controls-smoke",
    correlationId: `protective-controls-smoke-${suffix}`,
  });
}

console.log("waiting for platform-api health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

console.log("checking protective control admin endpoints...");
const controls = await request("GET", "/internal/boundary/account-risk/controls");
if (controls.status !== 200) {
  throw new Error(`account-risk controls endpoint unavailable (${controls.status}): ${controls.text}`);
}

console.log("seeding reference data...");
await seedReferenceData();

console.log("disabling bot and expecting pre-acceptance reject...");
await setAccountRisk("bot", "protective-bot", "disabled-bot", "smoke disabled");
const disabledBot = await submitOrder("disabled-bot", { botId: "protective-bot" });
if (disabledBot.status !== 403 || disabledBot.json.code !== "BOT_DISABLED") {
  throw new Error(`expected BOT_DISABLED 403, got ${disabledBot.status}: ${disabledBot.text}`);
}

console.log("clearing bot and expecting accepted command...");
await setAccountRisk("bot", "protective-bot", "allow", "smoke cleared");
const allowedBot = await submitOrder("allowed-bot", { botId: "protective-bot" });
if (!isAccepted(allowedBot)) {
  throw new Error(`expected accepted command after bot clear, got ${allowedBot.status}: ${allowedBot.text}`);
}

console.log("tripping instrument breaker and expecting pre-acceptance reject...");
await setBreaker("instrument", "AAPL", "trip", "smoke halt");
const trippedInstrument = await submitOrder("tripped-instrument");
if (trippedInstrument.status !== 503 || trippedInstrument.json.code !== "COMMAND_CIRCUIT_BREAKER_TRIPPED") {
  throw new Error(`expected breaker 503, got ${trippedInstrument.status}: ${trippedInstrument.text}`);
}

console.log("resetting instrument breaker and expecting accepted command...");
await setBreaker("instrument", "AAPL", "reset", "smoke cleared");
const resetInstrument = await submitOrder("reset-instrument");
if (!isAccepted(resetInstrument)) {
  throw new Error(`expected accepted command after breaker reset, got ${resetInstrument.status}: ${resetInstrument.text}`);
}

console.log("protective controls smoke passed");
