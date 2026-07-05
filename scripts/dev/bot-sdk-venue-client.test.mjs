import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const { createRecordingVenueTransportV1, sendVenueCommandRequestsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-client.ts")).href
);

const requests = [
  {
    method: "POST",
    route: "/api/v1/orders/submit",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": "idem-1",
    },
    body: {
      commandId: "cmd-1",
      traceId: "trace-1",
      correlationId: "corr-1",
      actorId: "bot-actor",
      occurredAt: "2026-07-04T14:30:00.000Z",
      orderId: "order-1",
      instrumentId: "AAPL",
      participantId: "participant-1",
      accountId: "account-1",
      side: "BUY",
      orderType: "LIMIT",
      quantityUnits: "10",
      limitPrice: "99.5",
      currency: "USD",
      timeInForce: "DAY",
    },
  },
];

const acceptedTransport = createRecordingVenueTransportV1(202);
const accepted = await sendVenueCommandRequestsV1(requests, acceptedTransport);
assert.equal(accepted.ok, true);
assert.equal(accepted.value.length, 1);
assert.equal(accepted.value[0].status, 202);
assert.equal(acceptedTransport.requests.length, 1);
assert.equal(acceptedTransport.requests[0].headers["Idempotency-Key"], "idem-1");

const rejectedTransport = createRecordingVenueTransportV1(429);
const rejected = await sendVenueCommandRequestsV1(requests, rejectedTransport);
assert.equal(rejected.ok, false);
assert.equal(rejected.denial.code, "TEMPORARILY_UNAVAILABLE");

console.log("bot SDK venue client checks passed");
