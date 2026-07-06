import { spawn } from "node:child_process";
import http from "node:http";
import https from "node:https";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const smokeId = env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_ID", `materializer-smoke-${Date.now()}`);
const commandId = `cmd-${smokeId}`;
const orderId = `ord-${smokeId}`;
const traceId = `trace-${smokeId}`;
const runtimeUrl = env("RUNTIME_BASE_URL", `http://127.0.0.1:${env("REEF_PLATFORM_API_HOST_PORT", "8080")}`);
const engineUrl = env("ENGINE_BASE_URL", `http://127.0.0.1:${env("REEF_MATCHING_ENGINE_HOST_PORT", "8081")}`);
const materializerUrl = env(
  "VENUE_EVENT_MATERIALIZER_BASE_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091")}`,
);
const waitTimeoutSeconds = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "120"));
const materializerTimeoutMs = Number(env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_TIMEOUT_MS", "60000"));
const materializerPollMs = Number(env("DEV_VENUE_EVENT_MATERIALIZER_SMOKE_POLL_MS", "1000"));
const expectOrderRead = env("DEV_VENUE_EVENT_MATERIALIZER_EXPECT_ORDER_ROW", "0") === "1";
const projectionName = env("DEV_VENUE_EVENT_MATERIALIZER_PROJECTION_NAME", `runtime-normalized-venue-outcomes-${smokeId}`);
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
setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "50");
setDefault("VENUE_EVENT_MATERIALIZER_POLL_MS", "10");
setDefault("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", "200");
setDefault("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["redpanda", "venue-event-materializer"]));

console.log("starting Redpanda direct-stream materializer smoke stack...");
await import("./stream-direct-nodb-up.mjs");

console.log("waiting for platform-api, matching-engine, and materializer health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${materializerUrl}/health`, waitTimeoutSeconds);

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
  { "X-Client-Id": "materializer-smoke-client", "Idempotency-Key": `idem-${smokeId}` },
);
assertAccepted(submit);

console.log("waiting for canonical command outcome row...");
const outcome = await waitForCanonicalOutcome(commandId);
console.log("projecting canonical command outcome into compact lifecycle rows...");
const projection = await projectCompactLifecycleRows(outcome);
const afterStats = await getJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
const afterMaterialized = Number(afterStats.metrics?.materialized ?? 0);
if (afterMaterialized <= beforeMaterialized) {
  throw new Error(`materializer stats did not advance: before=${beforeMaterialized} after=${afterMaterialized}`);
}

console.log("venue event materializer smoke passed");
console.log(JSON.stringify({
  smokeId,
  commandId,
  batchId: outcome.batch_id,
  resultStatus: outcome.result_status,
  materializedDelta: afterMaterialized - beforeMaterialized,
  projectedDelta: projection.projected,
  projectedReplayDelta: projection.replayed,
  projectionName,
}, null, 2));

async function seedReferenceData() {
  const internal = { "X-Reef-Internal-Route": "true" };
  await postJson(`${runtimeUrl}/reference/instruments`, {
    instrumentId: "AAPL",
    symbol: "AAPL",
    assetClass: "US_EQ",
    currency: "USD",
  }, internal);
  await postJson(`${runtimeUrl}/reference/participants`, {
    participantId: "participant-1",
    name: "Participant 1",
  }, internal);
  await postJson(`${runtimeUrl}/reference/accounts`, {
    accountId: "account-1",
    participantId: "participant-1",
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

async function projectCompactLifecycleRows(outcome) {
  const partition = Number(outcome.partition_id);
  if (!Number.isFinite(partition)) {
    throw new Error(`canonical outcome did not include a numeric partition_id: ${JSON.stringify(outcome)}`);
  }
  const streamSequence = Number(outcome.stream_sequence);
  if (!Number.isFinite(streamSequence)) {
    throw new Error(`canonical outcome did not include a numeric stream_sequence: ${JSON.stringify(outcome)}`);
  }
  await seedProjectionWatermark(partition, Math.max(0, streamSequence - 1));

  const projected = await projectUntilSubmitResult(outcome.command_id, partition);

  const submitRows = await queryRuntimeRows(`
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

  const eventRows = await queryRuntimeRows(`
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

  const replayed = Number(await queryRuntimeValue(`
    SELECT runtime.runtime_project_canonical_command_outcomes('${sqlLiteral(projectionName)}', 1000, ARRAY[${partition}])
  `));
  const replaySubmitRows = await queryRuntimeRows(`
    SELECT command_id
    FROM runtime.submit_results
    WHERE command_id = '${sqlLiteral(outcome.command_id)}'
  `, ["command_id"]);
  const replayEventRows = await queryRuntimeRows(`
    SELECT event_id
    FROM runtime.runtime_events
    WHERE trace_id = '${sqlLiteral(outcome.command_id)}'
  `, ["event_id"]);
  if (replaySubmitRows.length !== 1 || replayEventRows.length !== 1) {
    throw new Error(`projection replay duplicated target rows: submitRows=${replaySubmitRows.length} eventRows=${replayEventRows.length}`);
  }

  let orderResponse = null;
  if (expectOrderRead) {
    orderResponse = JSON.parse(await getText(`${runtimeUrl}/orders/${encodeURIComponent(orderId)}`));
    if (orderResponse.orderId !== orderId || orderResponse.instrumentId !== "AAPL") {
      throw new Error(`projected order read API did not expose expected order state: ${JSON.stringify(orderResponse)}`);
    }
  }

  const watermarkRows = await queryRuntimeRows(`
    SELECT projection_name, partition_id, last_partition_seq, last_error
    FROM runtime.projection_watermarks
    WHERE projection_name = '${sqlLiteral(projectionName)}'
      AND partition_id = ${partition}
  `, ["projection_name", "partition_id", "last_partition_seq", "last_error"]);
  if (watermarkRows.length !== 1 || watermarkRows[0].last_error) {
    throw new Error(`projection watermark was not clean: ${JSON.stringify(watermarkRows)}`);
  }

  return { projected, replayed, submitResult: submitRows[0], runtimeEvent: eventRows[0], orderResponse };
}

async function projectUntilSubmitResult(commandId, partition) {
  let projected = 0;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    projected += Number(await queryRuntimeValue(`
      SELECT runtime.runtime_project_canonical_command_outcomes('${sqlLiteral(projectionName)}', 1000, ARRAY[${partition}])
    `));
    const rows = await queryRuntimeRows(`
      SELECT command_id
      FROM runtime.submit_results
      WHERE command_id = '${sqlLiteral(commandId)}'
    `, ["command_id"]);
    if (rows.length === 1) return projected;
    if (rows.length > 1) {
      throw new Error(`projection duplicated submit_result for ${commandId}: ${rows.length}`);
    }
    await sleep(materializerPollMs);
  }
  throw new Error(`timeout waiting for compact projection of ${commandId}; projected=${projected}`);
}

async function seedProjectionWatermark(partition, lastPartitionSequence) {
  await runRuntimePsql(`
    INSERT INTO runtime.projection_watermarks(
      projection_name,
      partition_id,
      last_partition_seq,
      last_projected_at,
      updated_at,
      last_error
    )
    VALUES (
      '${sqlLiteral(projectionName)}',
      ${partition},
      ${lastPartitionSequence},
      now(),
      now(),
      ''
    )
    ON CONFLICT (projection_name, partition_id) DO UPDATE SET
      last_partition_seq = GREATEST(
        runtime.projection_watermarks.last_partition_seq,
        EXCLUDED.last_partition_seq
      ),
      last_projected_at = EXCLUDED.last_projected_at,
      updated_at = EXCLUDED.updated_at,
      last_error = ''
  `);
}

async function queryRuntimeValue(sql) {
  return (await runRuntimePsql(sql)).trim();
}

async function queryRuntimeRows(sql, columns) {
  const output = await runRuntimePsql(sql);
  return output
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => Object.fromEntries(line.split("\t").map((value, index) => [columns[index], value ?? ""])));
}

async function runRuntimePsql(sql) {
  const output = await runCapture("docker", [
    "compose",
    "exec",
    "-T",
    "postgres",
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

async function getJson(url) {
  const body = await getText(url);
  return JSON.parse(body || "{}");
}

async function getText(url) {
  const response = await request("GET", url, null, {}, 5000);
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
    const req = transport.request(parsed, {
      method,
      timeout: timeoutMs,
      headers: {
        ...(payload == null ? {} : {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        }),
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

function runCapture(cmd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", reject);
    child.on("close", (code) => {
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

function setDefault(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

function setValue(name, value) {
  process.env[name] = value;
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
