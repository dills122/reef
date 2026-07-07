import { spawn } from "node:child_process";
import crypto from "node:crypto";
import http from "node:http";
import https from "node:https";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const gateId = env("DEV_VENUE_EVENT_CRASH_GATE_ID", `venue-crash-gate-${Date.now()}`);
const streamToken = gateId.replace(/[^A-Za-z0-9_-]/g, "_");
const runtimeUrl = env("RUNTIME_BASE_URL", `http://127.0.0.1:${env("REEF_PLATFORM_API_HOST_PORT", "8080")}`);
const engineUrl = env("ENGINE_BASE_URL", `http://127.0.0.1:${env("REEF_MATCHING_ENGINE_HOST_PORT", "8081")}`);
const materializerUrl = env(
  "VENUE_EVENT_MATERIALIZER_BASE_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091")}`,
);
const readApiUrl = env(
  "DEV_VENUE_EVENT_CRASH_GATE_READ_API_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084")}`,
);
const waitTimeoutSeconds = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "120"));
const gateTimeoutMs = Number(env("DEV_VENUE_EVENT_CRASH_GATE_TIMEOUT_MS", "90000"));
const pollMs = Number(env("DEV_VENUE_EVENT_CRASH_GATE_POLL_MS", "1000"));
const projectionName = env("DEV_VENUE_EVENT_CRASH_GATE_PROJECTION_NAME", `runtime-normalized-crash-gate-${gateId}`);
const marketDataProjectionName = env("DEV_VENUE_EVENT_CRASH_GATE_MARKET_DATA_PROJECTION_NAME", `market-data-crash-gate-${gateId}`);
const commandStream = env("STREAM_ACK_COMMAND_STREAM", `REEF_CRASH_GATE_COMMANDS_${streamToken.toUpperCase()}`);
const eventStream = env("MATCHING_ENGINE_EVENT_STREAM", `REEF_CRASH_GATE_VENUE_EVENTS_${streamToken.toUpperCase()}`);
const partitionCount = 4;
const venueSessionId = `session-${gateId}`;
const internalHeaders = { "X-Reef-Internal-Route": "true" };

setValue("STREAM_ACK_LOG_PROVIDER", "redpanda");
setValue("PLATFORM_INTERNAL_HTTP_MODE", "enabled");
setDefault("STREAM_ACK_COMMAND_STREAM", commandStream);
setDefault("STREAM_ACK_SUBJECT_PREFIX", `reef.crash.gate.${streamToken}.cmd.v1`);
setValue("STREAM_ACK_PARTITION_COUNT", String(partitionCount));
setValue("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..3");
setDefault("MATCHING_ENGINE_EVENT_STREAM", eventStream);
setDefault("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", `reef.crash.gate.${streamToken}.venue.events.v1`);
setDefault("VENUE_EVENT_MATERIALIZER_TOPIC", eventStream);
setDefault("VENUE_EVENT_MATERIALIZER_GROUP_ID", `reef-venue-event-crash-gate-${gateId}`);
setValue("VENUE_EVENT_MATERIALIZER_ENABLED", "true");
setDefault("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", "20");
setDefault("VENUE_EVENT_MATERIALIZER_POLL_MS", "10");
setDefault("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", "200");
setValue("VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE", "false");
setValue("VENUE_EVENT_MATERIALIZER_TEST_STOP_AFTER_ACK_FAILURE", "false");
setValue("MATCHING_ENGINE_DIRECT_TEST_FAIL_ACK_ONCE", "false");
setValue("MATCHING_ENGINE_DIRECT_TEST_STOP_AFTER_ACK_FAILURE", "false");
setValue("STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE", "false");
setValue("STREAM_ACK_PROJECTOR_TEST_STOP_AFTER_FAILURE", "false");
setValue("DEV_COMPOSE_PROFILES", appendProfiles(env("DEV_COMPOSE_PROFILES"), ["redpanda", "venue-event-materializer"]));
setValue("DEV_STREAM_DIRECT_NODB_PROJECTOR_ENABLED", "1");
setValue("STREAM_ACK_PROJECTION_SOURCE", "venue-event-batch");
setValue("STREAM_ACK_PROJECTION_NAME", projectionName);
setValue("STREAM_ACK_PROJECTION_EVENT_STREAM", eventStream);
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
setValue("DEV_VENUE_EVENT_REPLAY_CHECK_EVENT_STREAM", eventStream);
process.env.COMPOSE_PROFILES = env("DEV_COMPOSE_PROFILES");

console.log("starting Redpanda direct-stream crash gate stack...");
await import("./stream-direct-nodb-up.mjs");

console.log("waiting for runtime, engine, materializer, projector health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${materializerUrl}/health`, waitTimeoutSeconds);
await waitForHttp(`${readApiUrl}/health`, waitTimeoutSeconds);

console.log("seeding reference/auth state...");
const partitionInstruments = selectPartitionInstruments();
const materializerStoppedCommands = partitionInstruments.map((instrument, index) =>
  commandSpec(`materializer-stopped-p${instrument.partition}`, index % 2 === 0 ? "BUY" : "SELL", "150250000000", instrument.instrumentId),
);
const engineStoppedCommands = partitionInstruments.map((instrument, index) =>
  commandSpec(`engine-stopped-p${instrument.partition}`, index % 2 === 0 ? "BUY" : "SELL", "150260000000", instrument.instrumentId),
);
const commands = [...materializerStoppedCommands, ...engineStoppedCommands];
await seedReferenceData(commands);

const engineBefore = await enginePublished();
const materializerBefore = await materializedOutcomes();

console.log("stopping materializer and projector to build durable backlog...");
await dockerCompose(["stop", "platform-materializer", "platform-projector-0"]);

console.log(`submitting ${materializerStoppedCommands.length} commands while materializer is stopped...`);
for (const command of materializerStoppedCommands) {
  await submitOrder(command);
}
await waitForEnginePublishedAtLeast(engineBefore + materializerStoppedCommands.length);

console.log("restarting matching-engine after event-batch publication...");
await dockerCompose(["restart", "matching-engine"]);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);

console.log(`stopping engine, then submitting ${engineStoppedCommands.length} commands into durable broker backlog...`);
await dockerCompose(["stop", "matching-engine"]);
for (const command of engineStoppedCommands) {
  await submitOrder(command);
}

console.log("starting engine with one-shot command ack failure hook...");
setValue("MATCHING_ENGINE_DIRECT_TEST_FAIL_ACK_ONCE", "true");
setValue("MATCHING_ENGINE_DIRECT_TEST_STOP_AFTER_ACK_FAILURE", "true");
await dockerCompose(["up", "-d", "--force-recreate", "--wait", "matching-engine"]);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
const engineAckFailureStats = await waitForEngineAckFailure({
  minFailed: 1,
  minPublished: 1,
});

console.log("restarting engine without command ack failure hook...");
setValue("MATCHING_ENGINE_DIRECT_TEST_FAIL_ACK_ONCE", "false");
setValue("MATCHING_ENGINE_DIRECT_TEST_STOP_AFTER_ACK_FAILURE", "false");
await dockerCompose(["up", "-d", "--force-recreate", "--wait", "matching-engine"]);
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
await waitForEnginePublishedAtLeast(engineStoppedCommands.length);

console.log("starting materializer with one-shot event ack failure hook...");
setValue("VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE", "true");
setValue("VENUE_EVENT_MATERIALIZER_TEST_STOP_AFTER_ACK_FAILURE", "true");
await dockerCompose(["up", "-d", "--wait", "platform-materializer"]);
await waitForHttp(`${materializerUrl}/health`, waitTimeoutSeconds);
const ackFailureStats = await waitForMaterializerAckFailedAtLeast(1);

console.log("restarting materializer without ack failure hook...");
setValue("VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE", "false");
setValue("VENUE_EVENT_MATERIALIZER_TEST_STOP_AFTER_ACK_FAILURE", "false");
await dockerCompose(["up", "-d", "--force-recreate", "--wait", "platform-materializer"]);
await waitForHttp(`${materializerUrl}/health`, waitTimeoutSeconds);
const redeliveryStats = await waitForMaterializerFetchedAtLeast(1);

const outcomes = await waitForCanonicalOutcomes(commands.map((command) => command.commandId));
assertPartitionCoverage(outcomes);

console.log("starting projector with one-shot row-before-watermark failure hook...");
setValue("STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE", "true");
setValue("STREAM_ACK_PROJECTOR_TEST_STOP_AFTER_FAILURE", "true");
await dockerCompose(["up", "-d", "--force-recreate", "--wait", "platform-projector-0"]);
await waitForHttp(`${readApiUrl}/health`, waitTimeoutSeconds);
const projectorFailureStats = await waitForProjectorRowsBeforeWatermarkFailure(commands);

console.log("restarting projector without failure hook...");
setValue("STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE", "false");
setValue("STREAM_ACK_PROJECTOR_TEST_STOP_AFTER_FAILURE", "false");
await dockerCompose(["up", "-d", "--force-recreate", "--wait", "platform-projector-0"]);
await waitForHttp(`${readApiUrl}/health`, waitTimeoutSeconds);
await waitForProjection(commands, outcomes);

console.log("running venue event replay/checksum gate...");
const replay = JSON.parse(await runCapture(env("JS_RUNTIME", "bun"), ["scripts/dev/venue-event-replay-check.mjs"], 60000));
if (!replay.pass) {
  throw new Error(`replay/checksum gate failed: ${JSON.stringify(replay.failures)}`);
}

const engineAfter = await internalGetJson(`${engineUrl}/internal/stream-direct/stats`);
const materializerAfter = await internalGetJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
const projectorAfter = await internalGetJson(`${readApiUrl}/internal/projector/status`);

console.log("venue event crash gate passed");
console.log(JSON.stringify({
  gateId,
  commandStream,
  eventStream,
  projectionName,
  commands: commands.map((command) => command.commandId),
  partitionCoverage: partitionCoverage(outcomes),
  injectedAckFailure: {
    engine: {
      failed: engineAckFailureStats.failed,
      published: engineAckFailureStats.published,
      acked: engineAckFailureStats.acked,
      ackLag: engineAckFailureStats.ackLag,
      lastErrors: engineAckFailureStats.lastErrors,
    },
    materializer: {
    ackFailed: ackFailureStats.metrics?.ackFailed ?? 0,
    lastError: ackFailureStats.metrics?.lastError ?? "",
    redeliveredAfterRestart: redeliveryStats.metrics?.fetched ?? 0,
    },
    projector: {
      failed: projectorFailureStats.metrics?.failed ?? 0,
      lastError: projectorFailureStats.metrics?.lastError ?? "",
      rowsCommittedBeforeWatermark: projectorFailureStats.rowsCommittedBeforeWatermark,
    },
  },
  canonicalOutcomes: outcomes,
  engine: summarizeEngineStats(engineAfter),
  materializer: materializerAfter.metrics,
  projector: projectorAfter.metrics,
  replay: {
    checkedAt: replay.checkedAt,
    report: replay.report,
  },
}, null, 2));

function commandSpec(label, side, limitPrice, instrumentId) {
  const suffix = `${gateId}-${label}`;
  return {
    commandId: `cmd-${suffix}`,
    traceId: `trace-${suffix}`,
    orderId: `ord-${suffix}`,
    instrumentId,
    participantId: `participant-${gateId}`,
    accountId: `account-${gateId}`,
    actorId: "venue-crash-gate-user",
    side,
    limitPrice,
    idempotencyKey: `idem-${suffix}`,
  };
}

async function seedReferenceData(commands) {
  const internal = { "X-Reef-Internal-Route": "true" };
  const instrumentIds = [...new Set(commands.map((command) => command.instrumentId))];
  for (const instrumentId of instrumentIds) {
    await postJson(`${runtimeUrl}/reference/instruments`, {
      instrumentId,
      symbol: instrumentId,
      assetClass: "US_EQ",
      currency: "USD",
    }, internal);
  }
  await postJson(`${runtimeUrl}/reference/participants`, {
    participantId: `participant-${gateId}`,
    name: "Venue Crash Gate Participant",
  }, internal);
  await postJson(`${runtimeUrl}/reference/accounts`, {
    accountId: `account-${gateId}`,
    participantId: `participant-${gateId}`,
    accountType: "HOUSE",
  }, internal);
  await postJson(`${runtimeUrl}/auth/roles`, {
    roleId: "order_trader",
    permissions: "order.submit,order.cancel,order.modify",
  }, internal);
  await postJson(`${runtimeUrl}/auth/actor-roles`, {
    actorId: "venue-crash-gate-user",
    roleId: "order_trader",
  }, internal);
}

async function submitOrder(command) {
  const response = await postJson(
    `${runtimeUrl}/api/v1/orders/submit`,
    {
      commandId: command.commandId,
      traceId: command.traceId,
      causationId: `cause-${gateId}`,
      correlationId: `corr-${gateId}`,
      actorId: command.actorId,
      runId: gateId,
      venueSessionId,
      occurredAt: "2026-07-04T18:00:00Z",
      orderId: command.orderId,
      instrumentId: command.instrumentId,
      participantId: command.participantId,
      accountId: command.accountId,
      side: command.side,
      orderType: "LIMIT",
      quantityUnits: "100",
      limitPrice: command.limitPrice,
      currency: "USD",
      timeInForce: "DAY",
    },
    { "X-Client-Id": "venue-crash-gate-client", "Idempotency-Key": command.idempotencyKey },
  );
  if (!response.includes(`"commandId":"${command.commandId}"`) || !response.includes('"statusUrl"')) {
    throw new Error(`unexpected submit response for ${command.commandId}: ${response}`);
  }
}

function selectPartitionInstruments() {
  const byPartition = new Map();
  for (let index = 0; byPartition.size < partitionCount && index < 1000; index += 1) {
    const instrumentId = `AAPL${String(index).padStart(3, "0")}-${gateId}`;
    const partition = streamPartition(gateId, venueSessionId, instrumentId, partitionCount);
    if (!byPartition.has(partition)) {
      byPartition.set(partition, { instrumentId, partition });
    }
  }
  if (byPartition.size < partitionCount) {
    throw new Error(`could not find instruments for ${partitionCount} partitions`);
  }
  return [...byPartition.values()].sort((left, right) => left.partition - right.partition);
}

function streamPartition(runId, sessionId, instrumentId, count) {
  const digest = crypto.createHash("sha256").update(`${runId}|${sessionId}|${instrumentId}`, "utf8").digest();
  let value = 0n;
  for (let index = 0; index < 8; index += 1) {
    value = (value << 8n) | BigInt(digest[index]);
  }
  value &= 0x7fffffffffffffffn;
  return Number(value % BigInt(count));
}

async function waitForEnginePublishedAtLeast(target) {
  await waitForCondition(`engine published >= ${target}`, async () => (await enginePublished()) >= target);
}

async function enginePublished() {
  const stats = await internalGetJson(`${engineUrl}/internal/stream-direct/stats`);
  return summarizeEngineStats(stats).published;
}

async function waitForEngineAckFailure({ minFailed, minPublished }) {
  let summary = {};
  await waitForCondition(`engine ack failure failed >= ${minFailed}`, async () => {
    const stats = await internalGetJson(`${engineUrl}/internal/stream-direct/stats`);
    summary = summarizeEngineStats(stats);
    return summary.failed >= minFailed &&
      summary.published >= minPublished &&
      summary.lastErrors.includes("injected matching-engine ack failure before command offset commit");
  });
  return summary;
}

function summarizeEngineStats(stats) {
  const partitions = Array.isArray(stats.partitions) ? stats.partitions : [];
  return {
    partitions: partitions.length,
    fetched: sum(partitions, "fetched"),
    processed: sum(partitions, "processed"),
    published: sum(partitions, "published"),
    acked: sum(partitions, "acked"),
    failed: sum(partitions, "failed"),
    nacked: sum(partitions, "nacked"),
    ackLag: sum(partitions, "ackLag"),
    lastBatchIds: partitions.map((partition) => partition.lastBatchId).filter(Boolean),
    lastErrors: partitions.map((partition) => partition.lastError).filter(Boolean),
  };
}

async function materializedOutcomes() {
  const stats = await internalGetJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
  return Number(stats.metrics?.materializedOutcomes ?? 0);
}

async function waitForMaterializerAckFailedAtLeast(target) {
  let stats = {};
  await waitForCondition(`materializer ackFailed >= ${target}`, async () => {
    stats = await internalGetJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
    return Number(stats.metrics?.ackFailed ?? 0) >= target &&
      Number(stats.metrics?.materializedOutcomes ?? 0) > materializerBefore;
  });
  return stats;
}

async function waitForMaterializerFetchedAtLeast(target) {
  let stats = {};
  await waitForCondition(`materializer fetched >= ${target} after clean restart`, async () => {
    stats = await internalGetJson(`${materializerUrl}/internal/venue-event-materializer/stats`);
    return Number(stats.metrics?.fetched ?? 0) >= target;
  });
  return stats;
}

async function waitForProjectorRowsBeforeWatermarkFailure(commands) {
  let stats = {};
  let rowCount = 0;
  await waitForCondition("projector rows committed before watermark failure", async () => {
    stats = await internalGetJson(`${readApiUrl}/internal/projector/status`);
    const rows = await queryProjectionRows(`
      SELECT command_id
      FROM runtime.submit_results
      WHERE command_id IN (${commands.map((command) => `'${sqlLiteral(command.commandId)}'`).join(",")})
    `, ["command_id"]);
    rowCount = rows.length;
    const watermarkRows = await queryProjectionRows(`
      SELECT partition_id
      FROM runtime.projection_watermarks
      WHERE projection_name = '${sqlLiteral(projectionName)}'
        AND partition_id >= 0
    `, ["partition_id"]);
    return Number(stats.metrics?.failed ?? 0) >= 1 &&
      String(stats.metrics?.lastError ?? "").includes("injected projector failure after read-model rows before watermark") &&
      rowCount > 0 &&
      watermarkRows.length === 0;
  });
  return { ...stats, rowsCommittedBeforeWatermark: rowCount };
}

async function waitForCanonicalOutcomes(commandIds) {
  let rows = [];
  await waitForCondition(`canonical outcomes for ${commandIds.join(",")}`, async () => {
    rows = await queryRuntimeRows(`
      SELECT command_id, batch_id, result_status, partition_id, stream_sequence
      FROM runtime.canonical_command_outcomes
      WHERE command_id IN (${commandIds.map((id) => `'${sqlLiteral(id)}'`).join(",")})
      ORDER BY command_id
    `, ["command_id", "batch_id", "result_status", "partition_id", "stream_sequence"]);
    return rows.length === commandIds.length;
  });
  return rows;
}

function assertPartitionCoverage(outcomes) {
  const coverage = partitionCoverage(outcomes);
  if (coverage.count < partitionCount) {
    throw new Error(`expected outcomes across ${partitionCount} partitions, got ${coverage.count}: ${coverage.partitions.join(",")}`);
  }
}

function partitionCoverage(outcomes) {
  const partitions = [...new Set(outcomes.map((outcome) => Number(outcome.partition_id)))].sort((left, right) => left - right);
  return { count: partitions.length, partitions };
}

async function waitForProjection(commands, outcomes) {
  await waitForCondition("projection watermarks and order rows", async () => {
    for (const command of commands) {
      const submitRows = await queryProjectionRows(`
        SELECT command_id, result_type, order_id
        FROM runtime.submit_results
        WHERE command_id = '${sqlLiteral(command.commandId)}'
      `, ["command_id", "result_type", "order_id"]);
      if (submitRows.length !== 1 || submitRows[0].order_id !== command.orderId) return false;
      const orderRows = await queryProjectionRows(`
        SELECT order_id, instrument_id, participant_id, account_id
        FROM runtime.orders
        WHERE order_id = '${sqlLiteral(command.orderId)}'
      `, ["order_id", "instrument_id", "participant_id", "account_id"]);
      if (orderRows.length !== 1 || orderRows[0].participant_id !== command.participantId) return false;
    }
    for (const outcome of outcomes) {
      const watermarkRows = await queryProjectionRows(`
        SELECT last_partition_seq, last_error
        FROM runtime.projection_watermarks
        WHERE projection_name = '${sqlLiteral(projectionName)}'
          AND partition_id = ${Number(outcome.partition_id)}
      `, ["last_partition_seq", "last_error"]);
      if (watermarkRows.length !== 1 || watermarkRows[0].last_error) return false;
      if (Number(watermarkRows[0].last_partition_seq) < Number(outcome.stream_sequence)) return false;
    }
    return true;
  });
}

async function waitForCondition(label, check) {
  const started = Date.now();
  while (Date.now() - started < gateTimeoutMs) {
    if (await check()) return;
    await sleep(pollMs);
  }
  throw new Error(`timeout waiting for ${label}`);
}

async function queryRuntimeRows(sql, columns) {
  return parsePsqlRows(await runPsql("postgres", sql), columns);
}

async function queryProjectionRows(sql, columns) {
  return parsePsqlRows(await runPsql("projection-postgres", sql), columns);
}

function parsePsqlRows(output, columns) {
  return output
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => Object.fromEntries(line.split("\t").map((value, index) => [columns[index], value ?? ""])));
}

async function runPsql(service, sql) {
  return runCapture("docker", [
    "compose",
    "exec",
    "-T",
    service,
    "psql",
    "-U",
    env("DEV_VENUE_EVENT_CRASH_GATE_DB_USER", "reef"),
    "-d",
    env("DEV_VENUE_EVENT_CRASH_GATE_DB_NAME", "reef"),
    "-At",
    "-F",
    "\t",
    "-c",
    sql,
  ]);
}

async function dockerCompose(args) {
  await runCapture("docker", ["compose", ...args], 60000);
}

async function getJson(url) {
  const body = await getText(url);
  return JSON.parse(body || "{}");
}

async function internalGetJson(url) {
  const body = await getText(url, internalHeaders);
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

function sum(rows, key) {
  return rows.reduce((total, row) => total + Number(row?.[key] ?? 0), 0);
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
