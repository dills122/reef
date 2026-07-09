import { spawn } from "node:child_process";
import http from "node:http";
import https from "node:https";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const smokeId = env("DEV_KUBE_STREAM_ACK_SMOKE_ID", `kube-stream-ack-smoke-${Date.now()}`);
const runtimeUrl = env("RUNTIME_BASE_URL", `http://127.0.0.1:${env("REEF_PLATFORM_API_HOST_PORT", "8080")}`);
const engineUrl = env("ENGINE_BASE_URL", `http://127.0.0.1:${env("REEF_MATCHING_ENGINE_HOST_PORT", "8081")}`);
const waitTimeoutSeconds = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "120"));
const smokeTimeoutMs = Number(env("DEV_KUBE_STREAM_ACK_SMOKE_TIMEOUT_MS", "60000"));
const smokePollMs = Number(env("DEV_KUBE_STREAM_ACK_SMOKE_POLL_MS", "1000"));
const namespace = env("KUBE_NAMESPACE", "reef-local");
const kubeContext = env("KUBE_CONTEXT", "");

const commandId = `cmd-${smokeId}`;
const traceId = `trace-${smokeId}`;
const instrumentId = `AAPL-${smokeId}`;
const participantId = `participant-${smokeId}`;
const accountId = `account-${smokeId}`;
const actorId = `actor-${smokeId}`;
const orderId = `ord-${smokeId}`;
const clientId = `client-${smokeId}`;
const apiHeaders = {
  "X-Client-Id": clientId,
  "X-Participant-Id": participantId,
};

console.log("waiting for matching-engine health...");
await waitForHttp(`${engineUrl}/health`, waitTimeoutSeconds);
console.log("waiting for platform-api health...");
await waitForHttp(`${runtimeUrl}/health`, waitTimeoutSeconds);

console.log("seeding stream-ack smoke reference/auth state...");
await seedReferenceData();

console.log(`submitting ${commandId} through kube stream-ack API...`);
const submit = await postJson(
  `${runtimeUrl}/api/v1/orders/submit`,
  {
    commandId,
    traceId,
    causationId: `cause-${smokeId}`,
    correlationId: `corr-${smokeId}`,
    actorId,
    runId: smokeId,
    venueSessionId: `session-${smokeId}`,
    occurredAt: "2026-07-08T18:00:00Z",
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
  { ...apiHeaders, "Idempotency-Key": `idem-${smokeId}` },
);
assertAccepted(submit);

console.log("waiting for durable stream reference...");
const status = await waitForStreamReference(commandId);
if (status.processingMode !== "stream-ack") {
  throw new Error(`expected stream-ack processing mode, got ${JSON.stringify(status)}`);
}
if (status.commandStream !== "REEF_KUBE_STREAM_ACK_COMMANDS") {
  throw new Error(`expected kube stream name, got ${JSON.stringify(status)}`);
}
const partition = Number(status.partition);
if (!Number.isInteger(partition) || partition < 0 || partition > 63) {
  throw new Error(`stream reference partition outside 64-partition stream: ${JSON.stringify(status)}`);
}
const streamSequence = Number(status.streamSequence);
if (!Number.isInteger(streamSequence) || streamSequence < 1) {
  throw new Error(`stream reference sequence missing: ${JSON.stringify(status)}`);
}

console.log("waiting for partition worker to drain the command...");
const workerProof = await waitForWorkerCompletion(partition, streamSequence);

console.log("checking canonical submit result row through kubectl psql...");
const canonical = await canonicalCommandResult(commandId);
if (canonical.result_status !== "accepted") {
  throw new Error(`expected accepted canonical command result, got ${JSON.stringify(canonical)}`);
}
if (canonical.command_type !== "SubmitOrder" || canonical.instrument_id !== instrumentId) {
  throw new Error(`canonical command result metadata mismatch: ${JSON.stringify(canonical)}`);
}
if (canonical.stream_name !== "REEF_KUBE_STREAM_ACK_COMMANDS") {
  throw new Error(`canonical command result stream mismatch: ${JSON.stringify(canonical)}`);
}
if (Number(canonical.partition_id) !== partition || Number(canonical.stream_seq) !== streamSequence) {
  throw new Error(`canonical command result partition/sequence mismatch: ${JSON.stringify(canonical)}`);
}

console.log("waiting for partition projector to materialize the submit result...");
const projectionProof = await waitForProjection(partition, streamSequence);
if (projectionProof.submitResult.order_id !== orderId) {
  throw new Error(`projected submit result order mismatch: ${JSON.stringify(projectionProof)}`);
}

console.log("kube stream-ack smoke passed");
console.log(JSON.stringify({
  smokeId,
  commandId,
  status: status.status,
  source: status.source,
  partition,
  streamSequence,
  workerDeployment: workerProof.deployment,
  workerCompleted: workerProof.partitionMetric.completed,
  projectionName: projectionProof.watermark.projection_name,
  projectedResultType: projectionProof.submitResult.result_type,
  commandStream: canonical.stream_name,
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
    name: "Kube Stream Ack Smoke Participant",
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
    actorId,
    roleId: "order_trader",
  }, internal);
}

async function waitForStreamReference(id) {
  const started = Date.now();
  let last = "";
  while (Date.now() - started < smokeTimeoutMs) {
    try {
      const status = await getJson(`${runtimeUrl}/api/v1/commands/${encodeURIComponent(id)}`, apiHeaders);
      if (status.source === "stream_reference" && Number(status.streamSequence) > 0) {
        return status;
      }
      last = JSON.stringify(status);
    } catch (error) {
      last = error?.message ?? String(error);
    }
    await sleep(smokePollMs);
  }
  throw new Error(`timeout waiting for ${id} stream reference; last=${last}`);
}

async function waitForWorkerCompletion(partition, streamSequence) {
  const deployment = `platform-worker-${workerIndexForPartition(partition)}`;
  const started = Date.now();
  let last = "";
  while (Date.now() - started < smokeTimeoutMs) {
    try {
      const stats = await getInternalJson(deployment, "/internal/stream-ack/worker/stats");
      const partitionMetric = (stats.partitionMetrics ?? []).find((metric) => Number(metric.partition) === partition);
      if (
        partitionMetric &&
        Number(partitionMetric.lastCompletedStreamSequence) >= streamSequence &&
        Number(partitionMetric.failed) === 0 &&
        Number(partitionMetric.ackFailed) === 0
      ) {
        return { deployment, partitionMetric };
      }
      last = JSON.stringify({ deployment, partitionMetric, metrics: stats.metrics });
    } catch (error) {
      last = error?.message ?? String(error);
    }
    await sleep(smokePollMs);
  }
  throw new Error(`timeout waiting for ${deployment} to complete partition=${partition} sequence=${streamSequence}; last=${last}`);
}

async function canonicalCommandResult(id) {
  const rows = parsePsqlRows(await runPsql("postgres", `
    SELECT command_id, partition_id, stream_seq, stream_name, command_type, result_status, instrument_id
    FROM runtime.canonical_command_results
    WHERE command_id = '${sqlLiteral(id)}'
    LIMIT 1
  `), ["command_id", "partition_id", "stream_seq", "stream_name", "command_type", "result_status", "instrument_id"]);
  if (rows.length !== 1) {
    throw new Error(`expected one canonical command result row for ${id}, got ${rows.length}`);
  }
  return rows[0];
}

async function waitForProjection(partition, streamSequence) {
  const started = Date.now();
  let last = "";
  while (Date.now() - started < smokeTimeoutMs) {
    try {
      const submitRows = parsePsqlRows(await runPsql("projection-postgres", `
        SELECT command_id, result_type, order_id, engine_order_id
        FROM runtime.submit_results
        WHERE command_id = '${sqlLiteral(commandId)}'
      `), ["command_id", "result_type", "order_id", "engine_order_id"]);
      const watermarkRows = parsePsqlRows(await runPsql("projection-postgres", `
        SELECT projection_name, partition_id, last_partition_seq, last_error
        FROM runtime.projection_watermarks
        WHERE projection_name = 'runtime-normalized-submit'
          AND partition_id = ${partition}
      `), ["projection_name", "partition_id", "last_partition_seq", "last_error"]);
      if (
        submitRows.length === 1 &&
        submitRows[0].result_type === "accepted" &&
        watermarkRows.length === 1 &&
        Number(watermarkRows[0].last_partition_seq) >= streamSequence &&
        !watermarkRows[0].last_error
      ) {
        return { submitResult: submitRows[0], watermark: watermarkRows[0] };
      }
      last = JSON.stringify({ submitRows, watermarkRows });
    } catch (error) {
      last = error?.message ?? String(error);
    }
    await sleep(smokePollMs);
  }
  throw new Error(`timeout waiting for projected submit result partition=${partition} sequence=${streamSequence}; last=${last}`);
}

function parsePsqlRows(output, columns) {
  return output
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => Object.fromEntries(line.split("\t").map((value, index) => [columns[index], value ?? ""])));
}

async function runPsql(service, sql) {
  return await runCapture("kubectl", [
    ...kubectlContextArgs(),
    "-n",
    namespace,
    "exec",
    "-i",
    `statefulset/${service}`,
    "--",
    "psql",
    "-U",
    env("DEV_KUBE_STREAM_ACK_DB_USER", "reef"),
    "-d",
    env("DEV_KUBE_STREAM_ACK_DB_NAME", "reef"),
    "-At",
    "-F",
    "\t",
    "-c",
    sql,
  ]);
}

async function getInternalJson(deployment, path) {
  const output = await runCapture("kubectl", [
    ...kubectlContextArgs(),
    "-n",
    namespace,
    "exec",
    `deployment/${deployment}`,
    "--",
    "wget",
    "-qO-",
    `http://127.0.0.1:8080${path}`,
  ]);
  return JSON.parse(output || "{}");
}

function workerIndexForPartition(partition) {
  return Math.floor(partition / 16);
}

function kubectlContextArgs() {
  return kubeContext ? ["--context", kubeContext] : [];
}

async function getJson(url, headers = {}) {
  const response = await request("GET", url, null, headers, 5000);
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
