import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl, engineUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "300"));

async function postJson(url, payload, headers = {}) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...headers,
    },
    signal: AbortSignal.timeout(5000),
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`POST ${url} failed (${response.status}): ${text}`);
  }
  return await response.text();
}

console.log("waiting for matching-engine health...");
await waitForHttp(`${engineUrl}/health`, waitTimeout);
console.log("waiting for platform-runtime health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

console.log("seeding reference data...");
await postJson(`${runtimeUrl}/reference/instruments`, {
  instrumentId: "AAPL",
  symbol: "AAPL",
  assetClass: "US_EQ",
  currency: "USD",
});
await postJson(`${runtimeUrl}/reference/participants`, {
  participantId: "participant-1",
  name: "Participant 1",
});
await postJson(`${runtimeUrl}/reference/accounts`, {
  accountId: "account-1",
  participantId: "participant-1",
  accountType: "HOUSE",
});

console.log("submitting via /api/v1 boundary...");
const submitResponse = await postJson(
  `${runtimeUrl}/api/v1/orders/submit`,
  {
    commandId: "smoke-cmd-submit-1",
    traceId: "smoke-trace-1",
    causationId: "smoke-causation-1",
    correlationId: "smoke-correlation-1",
    actorId: "smoke-user",
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
if (!submitResponse.includes('"accepted"')) {
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
    occurredAt: "2026-05-01T13:00:10Z",
    orderId: "smoke-ord-1",
  },
  { "X-Client-Id": "local-smoke-client", "Idempotency-Key": "smoke-cancel-1" },
);
if (!cancelResponse.includes('"accepted"')) {
  throw new Error(`smoke failure: cancel did not return accepted payload: ${cancelResponse}`);
}

console.log("smoke passed");
