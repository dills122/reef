import { spawn } from "node:child_process";
import http from "node:http";
import https from "node:https";
import { runStackUp } from "./lib/dev-stack-profiles.mjs";
import { env, loadDotEnv, setDefault, setValue, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const smokeId = env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_ID", `materializer-smoke-${Date.now()}`);
const commandId = `cmd-${smokeId}`;
const orderId = `ord-${smokeId}`;
const traceId = `trace-${smokeId}`;
const instrumentId = `AAPL-${smokeId}`;
const participantId = `participant-${smokeId}`;
const accountId = `account-${smokeId}`;
const runtimeUrl = env("RUNTIME_BASE_URL", `http://127.0.0.1:${env("REEF_PLATFORM_API_HOST_PORT", "8080")}`);
const engineUrl = env("ENGINE_BASE_URL", `http://127.0.0.1:${env("REEF_MATCHING_ENGINE_HOST_PORT", "8081")}`);
const materializerUrl = env(
  "VENUE_EVENT_MATERIALIZER_BASE_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091")}`,
);
const readApiUrl = env(
  "DEV_VENUE_EVENT_MATERIALIZER_READ_API_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084")}`,
);
const readApiHeaders = {
  "X-Client-Id": "materializer-smoke-client",
  "X-Actor-Id": "materializer-smoke-user",
  "X-Participant-Id": participantId,
};
const waitTimeoutSeconds = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "120"));
const materializerTimeoutMs = Number(env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_TIMEOUT_MS", "60000"));
const materializerPollMs = Number(env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_POLL_MS", "1000"));
const expectOrderRead = env("DEV_VENUE_EVENT_MATERIALIZER_EXPECT_ORDER_ROW", "1") !== "0";
const assertReplayIdempotency = env("DEV_VENUE_EVENT_MATERIALIZER_ASSERT_REPLAY_IDEMPOTENCY", "1") !== "0";
const projectionName = env("DEV_VENUE_EVENT_MATERIALIZER_PROJECTION_NAME", `runtime-normalized-venue-outcomes-${smokeId}`);
const marketDataProjectionName = env("DEV_VENUE_EVENT_MATERIALIZER_MARKET_DATA_PROJECTION_NAME", `market-data-top-of-book-${smokeId}`);
const depthProjectionName = env("DEV_VENUE_EVENT_MATERIALIZER_DEPTH_PROJECTION_NAME", `market-data-depth-${smokeId}`);
const streamToken = smokeId.replace(/[^A-Za-z0-9_-]/g, "_");

setValue("STREAM_ACK_LOG_PROVIDER", "redpanda");
setDefault("STREAM_ACK_COMMAND_STREAM", `REEF_MATERIALIZER_SMOKE_COMMANDS_${streamToken.toUpperCase()}`);
setDefault("STREAM_ACK_SUBJECT_PREFIX", `reef.materializer.smoke.${streamToken}.cmd.v1`);
setDefault("STREAM_ACK_PARTITION_COUNT", "4");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..3");
setDefault("MATCHING_ENGINE_EVENT_STREAM", `REEF_MATERIALIZER_SMOKE_VENUE_EVENTS_${streamToken.toUpperCase()}`);
setDefault("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", `reef.materializer.smoke.${streamToken}.venue.events.v1`);
setDefault("VENUE_EVENT_MATERIALIZER_TOPIC", env("MATCHING_ENGINE_EVENT_STREAM"));
setDefault("VENUE_EVENT_MATERIALIZER_GROUP_ID", `reef-venue-event-materializer-${smokeId}`);
setValue("VENUE_EVENT_MATERIALIZER_ENABLED", "true");
setValue("PLATFORM_INTERNAL_HTTP_MODE", "enabled");
setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "50");
setDefault("VENUE_EVENT_MATERIALIZER_POLL_MS", "10");
setDefault("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", "200");
setDefault("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["redpanda", "venue-event-materializer"]));
setValue("DEV_STREAM_DIRECT_NODB_PROJECTOR_ENABLED", "1");
setValue("STREAM_ACK_PROJECTION_SOURCE", "venue-event-batch");
setValue("STREAM_ACK_PROJECTION_NAME", projectionName);
setValue("STREAM_ACK_PROJECTION_EVENT_STREAM", env("MATCHING_ENGINE_EVENT_STREAM"));
setDefault("STREAM_ACK_PROJECTOR_BATCH_SIZE", "1000");
setDefault("STREAM_ACK_PROJECTOR_POLL_MS", "10");
setValue("ORDER_LIFECYCLE_PROJECTOR_ENABLED", "true");
setDefault("ORDER_LIFECYCLE_PROJECTOR_BATCH_SIZE", "1000");
setDefault("ORDER_LIFECYCLE_PROJECTOR_POLL_MS", "10");
setValue("MARKET_DATA_PROJECTOR_ENABLED", "true");
setValue("MARKET_DATA_PROJECTOR_PROJECTION_NAME", marketDataProjectionName);
setValue("MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME", projectionName);
setDefault("MARKET_DATA_PROJECTOR_BATCH_SIZE", "1000");
setDefault("MARKET_DATA_PROJECTOR_POLL_MS", "10");

console.log("starting Redpanda direct-stream materializer smoke stack...");
if (env("DEV_VENUE_EVENT_MATERIALIZER_SKIP_STACK_UP", "0") === "1") {
  console.log("skipping stack startup; using existing materializer smoke stack");
} else {
  await runStackUp("stream-direct-nodb");
}

console.log("waiting for platform-api, matching-engine, materializer, and read projector health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${materializerUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${readApiUrl}/health`, waitTimeoutSeconds);

console.log("seeding API reference/auth state...");
await seedReferenceData();

const beforeStats = await getJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
const beforeMaterialized = Number(beforeStats.metrics?.materialized ?? 0);

console.log(`submitting ${commandId} through stream-ack API...`);
const submit = await postJson(
  `${runtimeUrl}/api/v1/orders/submit`,
  {
    commandId,
    traceId,
    causationId: `cause-${smokeId}`,
    correlationId: `corr-${smokeId}`,
    actorId: "materializer-smoke-user",
    runId: smokeId,
    venueSessionId: `session-${smokeId}`,
    occurredAt: "2026-07-04T18:00:00Z",
    orderId,
    instrumentId,
    participantId,
    accountId,
    side: "BUY",
    orderType: "LIMIT",
    quantityUnits: "100",
    limitPrice: "150250000000",
    currency: "USD",
    timeInForce: "DAY",
  },
  { "X-Client-Id": "materializer-smoke-client", "Idempotency-Key": `idem-${smokeId}` },
);
assertAccepted(submit);

console.log("waiting for canonical command outcome row...");
const outcome = await waitForCanonicalOutcome(commandId);
console.log("waiting for background projection and read APIs...");
const readApiProof = await waitForProjectedReadApis(outcome);
const afterStats = await getJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
const afterMaterialized = Number(afterStats.metrics?.materialized ?? 0);
if (afterMaterialized <= beforeMaterialized) {
  throw new Error(`materializer stats did not advance: before=${beforeMaterialized} after=${afterMaterialized}`);
}

let replayIdempotencyProof = null;
if (assertReplayIdempotency) {
  console.log("rewinding projection watermark and proving idempotent replay produces no duplicate read-model rows...");
  replayIdempotencyProof = await assertProjectionReplayIdempotent(outcome);
}

console.log("venue event materializer smoke passed");
console.log(JSON.stringify({
  smokeId,
  commandId,
  batchId: outcome.batch_id,
  resultStatus: outcome.result_status,
  materializedDelta: afterMaterialized - beforeMaterialized,
  readApiProof,
  replayIdempotencyProof,
  projectionName,
  marketDataProjectionName,
}, null, 2));

async function seedReferenceData() {
  const internal = { "X-Reef-Internal-Route": "true" };
  await postJson(`${runtimeUrl}/reference/instruments`, {
    instrumentId,
    symbol: instrumentId,
    assetClass: "US_EQ",
    currency: "USD",
  }, internal);
  await postJson(`${runtimeUrl}/reference/participants`, {
    participantId,
    name: "Materializer Smoke Participant",
  }, internal);
  await postJson(`${runtimeUrl}/reference/accounts`, {
    accountId,
    participantId,
    accountType: "HOUSE",
  }, internal);
  await postJson(`${runtimeUrl}/auth/roles`, {
    roleId: "order_trader",
    permissions: "order.submit,order.cancel,order.modify",
  }, internal);
  await postJson(`${runtimeUrl}/auth/actor-roles`, {
    actorId: "materializer-smoke-user",
    roleId: "order_trader",
  }, internal);
}

async function waitForCanonicalOutcome(id) {
  const started = Date.now();
  let last = "";
  while (Date.now() - started < materializerTimeoutMs) {
    const rows = await queryRuntimeRows(`
      SELECT batch_id, command_id, result_status, partition_id, stream_sequence
      FROM runtime.canonical_command_outcomes
      WHERE command_id = '${sqlLiteral(id)}'
      LIMIT 1
    `, ["batch_id", "command_id", "result_status", "partition_id", "stream_sequence"]);
    if (rows.length > 0) return rows[0];
    last = `no canonical outcome for ${id}`;
    await sleep(materializerPollMs);
  }
  throw new Error(`timeout waiting for canonical outcome (${last})`);
}

async function waitForProjectedReadApis(outcome) {
  const partition = Number(outcome.partition_id);
  if (!Number.isFinite(partition)) {
    throw new Error(`canonical outcome did not include a numeric partition_id: ${JSON.stringify(outcome)}`);
  }
  const streamSequence = Number(outcome.stream_sequence);
  if (!Number.isFinite(streamSequence)) {
    throw new Error(`canonical outcome did not include a numeric stream_sequence: ${JSON.stringify(outcome)}`);
  }
  const started = Date.now();
  let last = "";
  while (Date.now() - started < materializerTimeoutMs) {
    try {
      return await proveProjectedReadApisOnce(outcome, partition, streamSequence);
    } catch (ex) {
      last = ex.message || String(ex);
      await sleep(materializerPollMs);
    }
  }
  throw new Error(`timeout waiting for projected read APIs (${last})`);
}

async function proveProjectedReadApisOnce(outcome, partition, streamSequence) {
  const submitRows = await queryProjectionRows(`
    SELECT command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at
    FROM runtime.submit_results
    WHERE command_id = '${sqlLiteral(outcome.command_id)}'
  `, ["command_id", "result_type", "event_id", "order_id", "engine_order_id", "code", "reason", "occurred_at"]);
  if (submitRows.length !== 1) {
    throw new Error(`expected one projected submit_result row for ${outcome.command_id}, got ${submitRows.length}`);
  }
  if (submitRows[0].result_type !== outcome.result_status) {
    throw new Error(`projected submit_result type mismatch: ${JSON.stringify(submitRows[0])}`);
  }

  const eventRows = await queryProjectionRows(`
    SELECT event_id, event_type, order_id, trace_id, producer
    FROM runtime.runtime_events
    WHERE trace_id = '${sqlLiteral(outcome.command_id)}'
    ORDER BY sequence_number
  `, ["event_id", "event_type", "order_id", "trace_id", "producer"]);
  if (eventRows.length !== 1) {
    throw new Error(`expected one projected runtime_event row for ${outcome.command_id}, got ${eventRows.length}`);
  }
  if (eventRows[0].producer !== "venue-event-batch-projector") {
    throw new Error(`projected runtime_event producer mismatch: ${JSON.stringify(eventRows[0])}`);
  }

  if (expectOrderRead) {
    const orderRows = await queryProjectionRows(`
      SELECT order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force
      FROM runtime.orders
      WHERE order_id = '${sqlLiteral(orderId)}'
    `, ["order_id", "instrument_id", "participant_id", "account_id", "side", "order_type", "quantity_units", "limit_price", "currency", "time_in_force"]);
    if (orderRows.length !== 1) {
      throw new Error(`expected one projected order row for ${orderId}, got ${orderRows.length}`);
    }
    const orderRow = orderRows[0];
    if (
      orderRow.instrument_id !== instrumentId ||
      orderRow.participant_id !== participantId ||
      orderRow.account_id !== accountId ||
      orderRow.quantity_units !== "100"
    ) {
      throw new Error(`projected order row mismatch: ${JSON.stringify(orderRow)}`);
    }
  }

  const watermarkRows = await queryProjectionRows(`
    SELECT projection_name, partition_id, last_partition_seq, last_error
    FROM runtime.projection_watermarks
    WHERE projection_name = '${sqlLiteral(projectionName)}'
      AND partition_id = ${partition}
  `, ["projection_name", "partition_id", "last_partition_seq", "last_error"]);
  if (watermarkRows.length !== 1 || watermarkRows[0].last_error) {
    throw new Error(`projection watermark was not clean: ${JSON.stringify(watermarkRows)}`);
  }
  if (Number(watermarkRows[0].last_partition_seq) < streamSequence) {
    throw new Error(`projection watermark behind: ${JSON.stringify(watermarkRows[0])}`);
  }

  const lifecycleRows = await queryProjectionRows(`
    SELECT order_id, instrument_id, participant_id, remaining_quantity_units, filled_quantity_units, status, limit_price
    FROM runtime.order_lifecycle_state
    WHERE order_id = '${sqlLiteral(orderId)}'
  `, ["order_id", "instrument_id", "participant_id", "remaining_quantity_units", "filled_quantity_units", "status", "limit_price"]);
  if (lifecycleRows.length !== 1) {
    throw new Error(`expected one order_lifecycle_state row for ${orderId}, got ${lifecycleRows.length}`);
  }
  const lifecycle = lifecycleRows[0];
  if (
    lifecycle.instrument_id !== instrumentId ||
    lifecycle.participant_id !== participantId ||
    lifecycle.remaining_quantity_units !== "100" ||
    lifecycle.filled_quantity_units !== "0" ||
    lifecycle.status !== "OPEN" ||
    lifecycle.limit_price !== "150250000000"
  ) {
    throw new Error(`projected order_lifecycle_state mismatch: ${JSON.stringify(lifecycle)}`);
  }

  const currentOrders = await getJson(
    `${readApiUrl}/api/v1/orders/current?participantId=${encodeURIComponent(participantId)}&instrumentId=${encodeURIComponent(instrumentId)}&limit=10`,
    readApiHeaders,
  );
  assertOwnOrderResponse("currentOrders", currentOrders, { openOnly: true });

  const orderHistory = await getJson(
    `${readApiUrl}/api/v1/orders/history?participantId=${encodeURIComponent(participantId)}&instrumentId=${encodeURIComponent(instrumentId)}&limit=10`,
    readApiHeaders,
  );
  assertOwnOrderResponse("orderHistory", orderHistory, { openOnly: false });

  const snapshot = await getJson(
    `${readApiUrl}/api/v1/market-data/snapshots/${encodeURIComponent(instrumentId)}?projectionName=${encodeURIComponent(marketDataProjectionName)}`,
    readApiHeaders,
  );
  if (snapshot.snapshot?.bestBidPrice !== "150250000000" || snapshot.snapshot?.bestBidQuantity !== "100") {
    throw new Error(`market-data snapshot mismatch: ${JSON.stringify(snapshot)}`);
  }

  const depth = await getJson(
    `${readApiUrl}/api/v1/market-data/depth/${encodeURIComponent(instrumentId)}?levels=5&projectionName=${encodeURIComponent(depthProjectionName)}&sourceProjectionName=${encodeURIComponent(projectionName)}`,
    readApiHeaders,
  );
  if (depth.depth?.bidLevels?.[0]?.price !== "150250000000" || depth.depth?.bidLevels?.[0]?.quantity !== "100") {
    throw new Error(`market-data depth mismatch: ${JSON.stringify(depth)}`);
  }

  const availability = await getJson(
    `${readApiUrl}/api/v1/data/availability?venueProjectionName=${encodeURIComponent(projectionName)}&marketDataProjectionName=${encodeURIComponent(marketDataProjectionName)}&source=venue-event-batch`,
    readApiHeaders,
  );
  const surfaces = Array.isArray(availability.surfaces) ? availability.surfaces : [];
  assertAvailabilitySurface(surfaces, {
    name: "currentOrders",
    source: ["runtime.orders + runtime.order_lifecycle_state", "runtime.order_lifecycle_state"],
    freshness: "dirty-tracked lifecycle projection",
    scope: "participant-own-orders",
    requiredQuery: ["participantId"],
  });
  assertAvailabilitySurface(surfaces, {
    name: "marketDataDepth",
    source: "runtime.order_lifecycle_state",
    freshness: "read-time bounded aggregation",
    scope: "public-market-data",
    optionalQuery: ["levels", "projectionName", "sourceProjectionName"],
  });

  const projectorStatus = await getJson(`${readApiUrl}/internal/projector/status`);
  const lifecycleStatus = await getJson(`${readApiUrl}/internal/order-lifecycle/projector/status`);
  const marketDataStatus = await getJson(`${readApiUrl}/internal/market-data/projector/status`);

  return {
    canonicalProjected: projectorStatus.metrics?.projected ?? 0,
    lifecycleCycles: lifecycleStatus.metrics?.cycles ?? 0,
    lifecycleProcessedRows: lifecycleStatus.metrics?.processedRows ?? 0,
    marketDataCycles: marketDataStatus.metrics?.cycles ?? 0,
    marketDataProcessedRows: marketDataStatus.metrics?.processedRows ?? 0,
    lifecycleStatus: lifecycle.status,
    currentOrders: currentOrders.orders.length,
    orderHistory: orderHistory.orders.length,
    bestBidPrice: snapshot.snapshot.bestBidPrice,
    depthBidLevels: depth.depth.bidLevels.length,
    availabilitySurfaces: surfaces.length,
    readApiUrl,
  };
}

/**
 * Gate 5 (WORK_PLAN.md "Run short local durable gates before any long soak"):
 * "projection replay/idempotency applies no duplicate read-model rows."
 *
 * The venue-event-batch projector (STREAM_ACK_PROJECTOR_ENABLED=true) is already
 * running continuously against this stack. This rewinds the live projection
 * watermark for this command's partition below its stream_sequence, so the
 * running projector background loop naturally re-processes the same canonical
 * command outcome it already projected, then asserts the projected read-model
 * row counts for this command/order are unchanged (still exactly one row each)
 * instead of duplicated, and that the watermark re-advances past the outcome.
 */
async function assertProjectionReplayIdempotent(outcome) {
  const partition = Number(outcome.partition_id);
  const streamSequence = Number(outcome.stream_sequence);
  if (!Number.isFinite(partition) || !Number.isFinite(streamSequence)) {
    throw new Error(`canonical outcome missing numeric partition/sequence for replay idempotency check: ${JSON.stringify(outcome)}`);
  }

  const before = await readProjectedRowCounts(outcome.command_id);

  await runProjectionPsql(`
    UPDATE runtime.projection_watermarks
    SET last_partition_seq = 0, last_error = ''
    WHERE projection_name = '${sqlLiteral(projectionName)}'
      AND partition_id = ${partition}
  `);

  const started = Date.now();
  let last = "";
  while (Date.now() - started < materializerTimeoutMs) {
    const watermarkRows = await queryProjectionRows(`
      SELECT last_partition_seq
      FROM runtime.projection_watermarks
      WHERE projection_name = '${sqlLiteral(projectionName)}'
        AND partition_id = ${partition}
    `, ["last_partition_seq"]);
    if (watermarkRows.length === 1 && Number(watermarkRows[0].last_partition_seq) >= streamSequence) {
      break;
    }
    last = `watermark not yet re-advanced past ${streamSequence}: ${JSON.stringify(watermarkRows)}`;
    await sleep(materializerPollMs);
  }
  if (Date.now() - started >= materializerTimeoutMs) {
    throw new Error(`timeout waiting for projector to replay rewound watermark (${last})`);
  }

  const after = await readProjectedRowCounts(outcome.command_id);
  for (const key of ["submitResults", "runtimeEvents", "orders"]) {
    if (before[key] !== after[key]) {
      throw new Error(`projection replay produced duplicate ${key} rows for ${outcome.command_id}: before=${before[key]} after=${after[key]}`);
    }
    if (after[key] !== 1) {
      throw new Error(`expected exactly one ${key} row for ${outcome.command_id} after replay, got ${after[key]}`);
    }
  }
  return { commandId: outcome.command_id, partition, streamSequence, before, after };
}

async function readProjectedRowCounts(commandId) {
  const submitResults = await queryProjectionRows(`
    SELECT COUNT(*) AS count FROM runtime.submit_results WHERE command_id = '${sqlLiteral(commandId)}'
  `, ["count"]);
  const runtimeEvents = await queryProjectionRows(`
    SELECT COUNT(*) AS count FROM runtime.runtime_events WHERE trace_id = '${sqlLiteral(commandId)}'
  `, ["count"]);
  const orders = await queryProjectionRows(`
    SELECT COUNT(*) AS count FROM runtime.orders WHERE order_id = '${sqlLiteral(orderId)}'
  `, ["count"]);
  return {
    submitResults: Number(submitResults[0]?.count ?? 0),
    runtimeEvents: Number(runtimeEvents[0]?.count ?? 0),
    orders: Number(orders[0]?.count ?? 0),
  };
}

function assertOwnOrderResponse(label, response, expected) {
  if (response.participantId !== participantId) {
    throw new Error(`${label} participant mismatch: ${JSON.stringify(response)}`);
  }
  const allowedSources = new Set(["runtime.orders + runtime.order_lifecycle_state", "runtime.order_lifecycle_state"]);
  if (!allowedSources.has(response.meta?.source)) {
    throw new Error(`${label} source mismatch: ${JSON.stringify(response.meta)}`);
  }
  if (response.meta?.freshness !== "dirty-tracked lifecycle projection") {
    throw new Error(`${label} freshness mismatch: ${JSON.stringify(response.meta)}`);
  }
  if (response.meta?.scope !== "participant") {
    throw new Error(`${label} scope mismatch: ${JSON.stringify(response.meta)}`);
  }
  if (response.meta?.openOnly !== expected.openOnly) {
    throw new Error(`${label} openOnly mismatch: ${JSON.stringify(response.meta)}`);
  }
  const orders = Array.isArray(response.orders) ? response.orders : [];
  const order = orders.find((candidate) => candidate.orderId === orderId);
  if (!order) {
    throw new Error(`${label} missing order ${orderId}: ${JSON.stringify(response)}`);
  }
  if (
    order.orderId !== orderId ||
    order.instrumentId !== instrumentId ||
    order.side !== "BUY" ||
    order.remainingQuantityUnits !== "100" ||
    order.limitPrice !== "150250000000" ||
    order.status !== "OPEN"
  ) {
    throw new Error(`${label} order mismatch: ${JSON.stringify(order)}`);
  }
}

function assertAvailabilitySurface(surfaces, expected) {
  const surface = surfaces.find((candidate) => candidate.name === expected.name);
  if (!surface) {
    throw new Error(`missing availability surface ${expected.name}: ${JSON.stringify(surfaces)}`);
  }
  for (const field of ["freshness", "scope"]) {
    if (surface[field] !== expected[field]) {
      throw new Error(`availability ${expected.name} ${field} mismatch: ${JSON.stringify(surface)}`);
    }
  }
  const expectedSources = Array.isArray(expected.source) ? expected.source : [expected.source];
  if (!expectedSources.includes(surface.source)) {
    throw new Error(`availability ${expected.name} source mismatch: ${JSON.stringify(surface)}`);
  }
  for (const [field, values] of Object.entries({
    requiredQuery: expected.requiredQuery ?? [],
    optionalQuery: expected.optionalQuery ?? [],
  })) {
    const actual = Array.isArray(surface[field]) ? surface[field] : [];
    for (const value of values) {
      if (!actual.includes(value)) {
        throw new Error(`availability ${expected.name} missing ${field}=${value}: ${JSON.stringify(surface)}`);
      }
    }
  }
}

async function queryRuntimeRows(sql, columns) {
  const output = await runRuntimePsql(sql);
  return parsePsqlRows(output, columns);
}

async function queryProjectionRows(sql, columns) {
  const output = await runProjectionPsql(sql);
  return parsePsqlRows(output, columns);
}

function parsePsqlRows(output, columns) {
  return output
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => Object.fromEntries(line.split("\t").map((value, index) => [columns[index], value ?? ""])));
}

async function runRuntimePsql(sql) {
  return runPsql("postgres", sql);
}

async function runProjectionPsql(sql) {
  return runPsql("projection-postgres", sql);
}

async function runPsql(service, sql) {
  const output = env("DEV_VENUE_EVENT_MATERIALIZER_PSQL_RUNNER", "compose") === "kubectl"
    ? await runCapture("kubectl", [
      ...kubectlContextArgs(),
      "-n",
      env("KUBE_NAMESPACE", "reef-local"),
      "exec",
      "-i",
      `statefulset/${service}`,
      "--",
      "psql",
      "-U",
      env("DEV_VENUE_EVENT_MATERIALIZER_DB_USER", "reef"),
      "-d",
      env("DEV_VENUE_EVENT_MATERIALIZER_DB_NAME", "reef"),
      "-At",
      "-F",
      "\t",
      "-c",
      sql,
    ])
    : await runCapture("docker", [
      "compose",
      "exec",
      "-T",
      service,
      "psql",
      "-U",
      env("DEV_VENUE_EVENT_MATERIALIZER_DB_USER", "reef"),
      "-d",
      env("DEV_VENUE_EVENT_MATERIALIZER_DB_NAME", "reef"),
      "-At",
      "-F",
      "\t",
      "-c",
      sql,
    ]);
  return output;
}

function kubectlContextArgs() {
  const context = env("KUBE_CONTEXT", "");
  return context ? ["--context", context] : [];
}

async function getJson(url, headers = {}) {
  const body = await getText(url, headers);
  return JSON.parse(body || "{}");
}

async function getText(url, headers = {}) {
  const response = await request("GET", url, null, headers, 5000);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`GET ${url} failed (${response.statusCode}): ${response.body}`);
  }
  return response.body;
}

async function postJson(url, payload, headers = {}) {
  const response = await request("POST", url, payload, headers, 5000);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`POST ${url} failed (${response.statusCode}): ${response.body}`);
  }
  return response.body;
}

function request(method, url, payload, headers = {}, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const body = payload == null ? "" : JSON.stringify(payload);
    const transport = parsed.protocol === "https:" ? https : http;
    const internalHeaders = parsed.pathname.startsWith("/internal/")
      ? { "X-Reef-Internal-Route": "true" }
      : {};
    const req = transport.request(parsed, {
      method,
      timeout: timeoutMs,
      headers: {
        ...(payload == null ? {} : {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        }),
        ...internalHeaders,
        ...headers,
      },
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        resolve({ statusCode: res.statusCode ?? 0, body: data });
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

function runCapture(cmd, args, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      reject(new Error(`${cmd} ${args.join(" ")} timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}: ${stderr || stdout}`));
      }
    });
  });
}

function assertAccepted(body) {
  const parsed = JSON.parse(body);
  const accepted = String(parsed.status ?? "").toLowerCase() === "accepted" || parsed.accepted === true;
  if (!accepted) {
    throw new Error(`submit did not return accepted payload: ${body}`);
  }
}

function sqlLiteral(value) {
  return String(value).replaceAll("'", "''");
}

function appendProfiles(raw, additions) {
  const profiles = new Set(
    String(raw ?? "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
  );
  for (const addition of additions) {
    profiles.add(addition);
  }
  return [...profiles].join(",");
}
