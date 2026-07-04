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

setValue("STREAM_ACK_LOG_PROVIDER", "redpanda");
setDefault("STREAM_ACK_COMMAND_STREAM", "REEF_MATERIALIZER_SMOKE_COMMANDS");
setDefault("STREAM_ACK_SUBJECT_PREFIX", "reef.materializer.smoke.cmd.v1");
setDefault("STREAM_ACK_PARTITION_COUNT", "4");
setDefault("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..3");
setDefault("MATCHING_ENGINE_EVENT_STREAM", "REEF_MATERIALIZER_SMOKE_VENUE_EVENTS");
setDefault("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", "reef.materializer.smoke.venue.events.v1");
setDefault("VENUE_EVENT_MATERIALIZER_TOPIC", env("MATCHING_ENGINE_EVENT_STREAM"));
setDefault("VENUE_EVENT_MATERIALIZER_GROUP_ID", `reef-venue-event-materializer-${smokeId}`);
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
      SELECT batch_id, command_id, result_status
      FROM runtime.canonical_command_outcomes
      WHERE command_id = '${sqlLiteral(id)}'
      LIMIT 1
    `);
    if (rows.length > 0) return rows[0];
    last = `no canonical outcome for ${id}`;
    await sleep(materializerPollMs);
  }
  throw new Error(`timeout waiting for canonical outcome (${last})`);
}

async function queryRuntimeRows(sql) {
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
  return output
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => {
      const [batch_id, command_id, result_status] = line.split("\t");
      return { batch_id, command_id, result_status };
    });
}

async function getJson(url) {
  const response = await request("GET", url, null, {}, 5000);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`GET ${url} failed (${response.statusCode}): ${response.body}`);
  }
  return JSON.parse(response.body || "{}");
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
