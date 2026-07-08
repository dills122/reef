import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";
import http from "node:http";
import https from "node:https";

loadDotEnv();
const { runtimeUrl, engineUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const internalRouteHeaders = { "X-Reef-Internal-Route": "true" };
const smokeRunId = env("DEV_SMOKE_RUN_ID", "smoke-run-1");
const smokeVenueSessionId = env("DEV_SMOKE_VENUE_SESSION_ID", "smoke-session-1");

async function postJson(url, payload, headers = {}) {
  const response = await requestJson("POST", url, payload, headers, 5000);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`POST ${url} failed (${response.statusCode}): ${response.body}`);
  }
  return response.body;
}

function isAcceptedResponse(body) {
  try {
    const parsed = JSON.parse(body);
    return String(parsed.status ?? "").toLowerCase() === "accepted" || parsed.accepted === true || parsed.accepted != null;
  } catch (_error) {
    return body.includes('"accepted"');
  }
}

function requestJson(method, url, payload, headers = {}, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    let parsed;
    try {
      parsed = new URL(url);
    } catch (error) {
      reject(error);
      return;
    }
    const body = JSON.stringify(payload);
    const transport = parsed.protocol === "https:" ? https : http;
    const req = transport.request(parsed, {
      method,
      timeout: timeoutMs,
      headers: {
        "content-type": "application/json",
        "content-length": Buffer.byteLength(body),
        ...headers,
      },
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        resolve({
          statusCode: res.statusCode ?? 0,
          body: data,
        });
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

console.log("waiting for matching-engine health...");
await waitForHttp(`${engineUrl}/health`, waitTimeout);
console.log("waiting for platform-api health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

console.log("seeding reference data...");
await postJson(`${runtimeUrl}/reference/instruments`, {
  instrumentId: "AAPL",
  symbol: "AAPL",
  assetClass: "US_EQ",
  currency: "USD",
}, internalRouteHeaders);
await postJson(`${runtimeUrl}/reference/participants`, {
  participantId: "participant-1",
  name: "Participant 1",
}, internalRouteHeaders);
await postJson(`${runtimeUrl}/reference/accounts`, {
  accountId: "account-1",
  participantId: "participant-1",
  accountType: "HOUSE",
}, internalRouteHeaders);
await postJson(`${runtimeUrl}/auth/roles`, {
  roleId: "order_trader",
  permissions: "order.submit,order.cancel,order.modify",
}, internalRouteHeaders);
await postJson(`${runtimeUrl}/auth/actor-roles`, {
  actorId: "smoke-user",
  roleId: "order_trader",
}, internalRouteHeaders);

console.log("submitting via /api/v1 boundary...");
const submitResponse = await postJson(
  `${runtimeUrl}/api/v1/orders/submit`,
  {
    commandId: "smoke-cmd-submit-1",
    traceId: "smoke-trace-1",
    causationId: "smoke-causation-1",
    correlationId: "smoke-correlation-1",
    actorId: "smoke-user",
    runId: smokeRunId,
    venueSessionId: smokeVenueSessionId,
    occurredAt: "2026-05-01T13:00:00Z",
    orderId: "smoke-ord-1",
    instrumentId: "AAPL",
    participantId: "participant-1",
    accountId: "account-1",
    side: "BUY",
    orderType: "LIMIT",
    quantityUnits: "100",
    limitPrice: "150250000000",
    currency: "USD",
    timeInForce: "DAY",
  },
  { "X-Client-Id": "local-smoke-client", "Idempotency-Key": "smoke-submit-1" },
);
if (!isAcceptedResponse(submitResponse)) {
  throw new Error(`smoke failure: submit did not return accepted payload: ${submitResponse}`);
}

console.log("canceling via /api/v1 boundary...");
const cancelResponse = await postJson(
  `${runtimeUrl}/api/v1/orders/cancel`,
  {
    commandId: "smoke-cmd-cancel-1",
    traceId: "smoke-trace-2",
    causationId: "smoke-causation-2",
    correlationId: "smoke-correlation-2",
    actorId: "smoke-user",
    runId: smokeRunId,
    venueSessionId: smokeVenueSessionId,
    occurredAt: "2026-05-01T13:00:10Z",
    orderId: "smoke-ord-1",
    instrumentId: "AAPL",
    reason: "smoke cancel",
  },
  { "X-Client-Id": "local-smoke-client", "Idempotency-Key": "smoke-cancel-1" },
);
if (!isAcceptedResponse(cancelResponse)) {
  throw new Error(`smoke failure: cancel did not return accepted payload: ${cancelResponse}`);
}

console.log("smoke passed");
