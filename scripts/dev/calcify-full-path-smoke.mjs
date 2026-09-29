import { spawn } from "node:child_process";
import { composeArgs } from "./lib/compose-utils.mjs";
import { composePsqlArgs } from "./lib/compose-psql.mjs";
import { applyStackProfile } from "./lib/dev-stack-profiles.mjs";
import { devUp } from "./lib/dev-stack.mjs";
import { deriveDevUrls, env, loadDotEnv, run, setValue, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();
const smokeId = env("DEV_CALCIFY_FULL_PATH_ID", `calcify-${Date.now()}`).replace(/[^A-Za-z0-9_-]/g, "_");
const token = smokeId.toUpperCase();
const commandTopic = `REEF_${token}_COMMANDS`;
const sourceTopic = `REEF_${token}_EVENTS`;
const commitmentTopic = `REEF_${token}_COMMITMENTS`;
const verifiedTopic = `REEF_${token}_VERIFIED`;
const instrumentId = `AAPL-${smokeId}`;
const sessionId = `session-${smokeId}`;
const { runtimeUrl, engineUrl } = deriveDevUrls();
const timeoutMs = Number(env("DEV_CALCIFY_FULL_PATH_TIMEOUT_MS", "60000"));
const loadPairs = Number(env("DEV_CALCIFY_FULL_PATH_LOAD_PAIRS", "0"));
const loadBatchSize = Number(env("DEV_CALCIFY_FULL_PATH_LOAD_BATCH_SIZE", "10"));
if (!Number.isSafeInteger(loadPairs) || loadPairs < 0 ||
    !Number.isSafeInteger(loadBatchSize) || loadBatchSize < 1) {
  throw new Error("load pairs must be nonnegative and batch size must be positive integers");
}

setValue("REEF_COMPOSE_FILES", "compose.base.yml,compose.local.yml,compose.calcify.yml");
setValue("DEV_COMPOSE_PROFILES", "redpanda");
setValue("STREAM_ACK_LOG_PROVIDER", "redpanda");
setValue("STREAM_ACK_PARTITION_COUNT", "4");
setValue("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "0..3");
setValue("STREAM_ACK_COMMAND_STREAM", commandTopic);
setValue("MATCHING_ENGINE_EVENT_STREAM", sourceTopic);
setValue("STREAM_ACK_SUBJECT_PREFIX", `reef.calcify.${smokeId}.cmd.v1`);
setValue("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", `reef.calcify.${smokeId}.event.v1`);
setValue("PLATFORM_INTERNAL_HTTP_MODE", "enabled");

console.log(`starting full-path Calcify smoke ${smokeId}`);
const afterUp = applyStackProfile("stream-ack");
setValue("RUNTIME_PERSISTENCE", "postgres");
setValue("EXTERNAL_API_IDEMPOTENCY_STORE", "postgres");
setValue("STREAM_ACK_INTAKE_STORE", "postgres");
setValue("STREAM_ACK_WORKER_ENABLED", "false");
setValue("STREAM_ACK_PROJECTOR_ENABLED", "false");
setValue("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true");
await devUp();
for (const action of afterUp) await action();
await waitForHttp(`${runtimeUrl}/health`, 120);
await waitForHttp(`${engineUrl}/health`, 120);

// Sidecars start only after source identity is registered. Each stage provisions
// both link topics from source partition count before it subscribes.
const create = await capture("docker", composeArgs([
  "exec", "-T", "redpanda", "rpk", "topic", "create", sourceTopic, "-p", "4", "-r", "1",
]), 15000, true);
if (create.code !== 0 && !create.output.includes("TOPIC_ALREADY_EXISTS")) {
  throw new Error(`source topic creation failed: ${create.output}`);
}
const generation = Number((await psql("SELECT COALESCE(MAX(source_generation), 0) + 1 FROM runtime.calcify_source_generations")).trim());
if (!Number.isSafeInteger(generation) || generation <= 0) throw new Error("invalid source generation");
await psql(`INSERT INTO runtime.calcify_source_generations (source_generation, source_topic) VALUES (${generation}, '${sourceTopic}')`);
setValue("CALCIFY_SOURCE_TOPIC", sourceTopic);
setValue("CALCIFY_COMMITMENT_TOPIC", commitmentTopic);
setValue("CALCIFY_VERIFIED_TOPIC", verifiedTopic);
setValue("CALCIFY_SOURCE_GENERATION", String(generation));
setValue("COMPOSE_PROFILES", "redpanda,calcify-phase1");
await run("docker", composeArgs([
  "up", "-d", "--no-deps", "--force-recreate",
  "calcify-extractor", "calcify-verifier", "calcify-receipt",
]));

await seedReferenceData();
await submit("buyer", "BUY");
const [resting] = await readBatches(1);
assertBatch(resting, "buyer", 0);
await submit("seller", "SELL");
const batches = await readBatches(2);
const matching = batches.find((record) => record.batch.outcomes.some((outcome) => outcome.commandId === `seller-cmd-${smokeId}`));
if (!matching) throw new Error("matching engine did not emit seller outcome");
assertBatch(matching, "seller", 1);
const trade = matching.batch.outcomes.flatMap((outcome) => outcome.result.trades ?? [])[0];
if (!trade.tradeId || !trade.executionId) throw new Error("matching outcome missing trade identity");
if (matching.partition !== resting.partition || matching.offset <= resting.offset) {
  throw new Error("matching output lost same-partition source order");
}

const receipt = await waitForReceipt(generation, matching.partition, matching.offset);
const expected = [generation, matching.partition, matching.offset, 0, 1].join("\t");
if (receipt !== expected) throw new Error(`receipt identity ${receipt}; want ${expected}`);
const count = (await psql(`SELECT COUNT(*) FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`)).trim();
if (count !== "1") throw new Error(`expected one real-match receipt and no resting-order receipt; got ${count}`);
const intakeCount = (await psql(`SELECT COUNT(*) FROM boundary.stream_command_intake WHERE command_id IN ('buyer-cmd-${smokeId}', 'seller-cmd-${smokeId}') AND stream_name = '${commandTopic}'`, "boundary-postgres")).trim();
if (intakeCount !== "2") throw new Error(`expected both commands in PostgreSQL intake, got ${intakeCount}`);

console.log("Calcify full-path smoke passed");
console.log(JSON.stringify({ smokeId, commandTopic, sourceTopic, commitmentTopic, verifiedTopic,
  generation, resting: { partition: resting.partition, offset: resting.offset, trades: 0 },
  matching: { partition: matching.partition, offset: matching.offset, trades: 1, tradeId: trade.tradeId },
  receipt, intakeRows: Number(intakeCount), profile: "PostgreSQL-backed HTTP intake + Redpanda + Go matching + Calcify Phase 1", capacityClaim: false }, null, 2));
if (loadPairs > 0) await runBasicLoad(generation, matching.partition);

async function runBasicLoad(generation, partition) {
  const started = Date.now();
  let acceptedPairs = 0;
  let halfwayReceipts = null;
  for (let first = 0; first < loadPairs; first += loadBatchSize) {
    const last = Math.min(first + loadBatchSize, loadPairs);
    const ids = Array.from({ length: last - first }, (_, index) => first + index);
    await Promise.all(ids.map((id) => submitLoad(id, "buyer", "BUY")));
    await Promise.all(ids.map((id) => submitLoad(id, "seller", "SELL")));
    acceptedPairs = last;
    if (halfwayReceipts === null && acceptedPairs >= Math.ceil(loadPairs / 2)) {
      halfwayReceipts = Number((await psql(`SELECT COUNT(*) FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`)).trim());
    }
  }
  const acceptedAt = Date.now();
  console.log(`Calcify load accepted ${acceptedPairs * 2} orders in ${acceptedAt - started} ms`);
  const expectedReceipts = loadPairs + 1;
  let finalReceipts = 0;
  while (Date.now() - acceptedAt < timeoutMs) {
    finalReceipts = Number((await psql(`SELECT COUNT(*) FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`)).trim());
    if (finalReceipts >= expectedReceipts) break;
    await sleep(500);
  }
  const drainedAt = Date.now();
  const batches = await readAllBatches(partition);
  const sourceCommands = batches.reduce((sum, record) => sum + record.batch.commandCount, 0);
  const tradeCount = batches.reduce((sum, record) => sum + record.batch.outcomes.reduce(
    (outcomeSum, outcome) => outcomeSum + (outcome.result.trades?.length ?? 0), 0), 0);
  const commitmentMeta = await readTopicMeta(commitmentTopic, partition);
  const verifiedMeta = await readTopicMeta(verifiedTopic, partition);
  const sourceLastMs = batches.reduce((latest, record) => Math.max(latest, record.timestamp), 0);
  const receiptLastMs = Number((await psql(`SELECT FLOOR(EXTRACT(EPOCH FROM MAX(recorded_at)) * 1000)::bigint FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation}`)).trim());
  const intakeRows = Number((await psql(`SELECT COUNT(*) FROM boundary.stream_command_intake WHERE stream_name = '${commandTopic}'`, "boundary-postgres")).trim());
  const result = { smokeId, generation, loadPairs, loadBatchSize, acceptedOrders: acceptedPairs * 2,
    acceptedMs: acceptedAt - started, acceptedOrdersPerSecond: Number((acceptedPairs * 2000 / (acceptedAt - started)).toFixed(2)),
    halfwayReceipts, sourceBatches: batches.length, sourceCommands, sourceTrades: tradeCount,
    commitmentLinks: commitmentMeta.count, verifiedLinks: verifiedMeta.count,
    sourceLastMs, commitmentLastMs: commitmentMeta.lastTimestampMs,
    verifiedLastMs: verifiedMeta.lastTimestampMs, receiptLastMs,
    sourceToCommitmentLastMs: commitmentMeta.lastTimestampMs - sourceLastMs,
    commitmentToVerifiedLastMs: verifiedMeta.lastTimestampMs - commitmentMeta.lastTimestampMs,
    verifiedToReceiptLastMs: receiptLastMs - verifiedMeta.lastTimestampMs,
    finalReceipts, drainMs: drainedAt - acceptedAt, totalMs: drainedAt - started,
    intakeRows, profile: "PostgreSQL-backed HTTP intake + Redpanda + Go matching + Calcify Phase 1",
    capacityClaim: false };
  console.log(JSON.stringify(result, null, 2));
  if (intakeRows !== loadPairs * 2 + 2 || sourceCommands !== loadPairs * 2 + 2 ||
      tradeCount !== expectedReceipts || commitmentMeta.count !== expectedReceipts ||
      verifiedMeta.count !== expectedReceipts || finalReceipts !== expectedReceipts) {
    throw new Error(`Calcify basic load accounting failed: ${JSON.stringify(result)}`);
  }
  console.log("Calcify basic load passed");
}

async function seedReferenceData() {
  const internal = { "X-Reef-Internal-Route": "true" };
  await post("/reference/instruments", { instrumentId, symbol: instrumentId, assetClass: "US_EQ", currency: "USD" }, internal);
  for (const party of ["buyer", "seller"]) {
    await post("/reference/participants", { participantId: `${party}-${smokeId}`, name: party }, internal);
    await post("/reference/accounts", { accountId: `${party}-account-${smokeId}`, participantId: `${party}-${smokeId}`, accountType: "HOUSE" }, internal);
  }
  await post("/auth/roles", { roleId: "order_trader", permissions: "order.submit,order.cancel,order.modify" }, internal);
  for (const party of ["buyer", "seller"]) {
    await post("/auth/actor-roles", { actorId: `${party}-actor-${smokeId}`, roleId: "order_trader" }, internal);
  }
}

async function submit(party, side) {
  const commandId = `${party}-cmd-${smokeId}`;
  const response = await post("/api/v1/orders/submit", {
    commandId, traceId: `trace-${commandId}`, causationId: `cause-${commandId}`, correlationId: `corr-${commandId}`,
    actorId: `${party}-actor-${smokeId}`, runId: smokeId, venueSessionId: sessionId,
    occurredAt: "2026-09-29T15:00:00Z", orderId: `${party}-order-${smokeId}`, instrumentId,
    participantId: `${party}-${smokeId}`, accountId: `${party}-account-${smokeId}`,
    side, orderType: "LIMIT", quantityUnits: "100", limitPrice: "150250000000", currency: "USD", timeInForce: "DAY",
  }, { "X-Client-Id": `${party}-client-${smokeId}`, "Idempotency-Key": `${party}-idem-${smokeId}` });
  if (String(response.status).toLowerCase() !== "accepted" && response.accepted !== true) {
    throw new Error(`command ${commandId} not accepted: ${JSON.stringify(response)}`);
  }
}

async function submitLoad(index, party, side) {
  const commandId = `${party}-load-${index}-${smokeId}`;
  const response = await post("/api/v1/orders/submit", {
    commandId, traceId: `trace-${commandId}`, causationId: `cause-${commandId}`, correlationId: `corr-${commandId}`,
    actorId: `${party}-actor-${smokeId}`, runId: smokeId, venueSessionId: sessionId,
    occurredAt: "2026-09-29T15:00:00Z", orderId: `${party}-load-order-${index}-${smokeId}`, instrumentId,
    participantId: `${party}-${smokeId}`, accountId: `${party}-account-${smokeId}`,
    side, orderType: "LIMIT", quantityUnits: "100", limitPrice: "150250000000", currency: "USD", timeInForce: "DAY",
  }, { "X-Client-Id": `${party}-client-${smokeId}`, "Idempotency-Key": `${party}-load-idem-${index}-${smokeId}` });
  if (String(response.status).toLowerCase() !== "accepted" && response.accepted !== true) {
    throw new Error(`command ${commandId} not accepted: ${JSON.stringify(response)}`);
  }
}

async function readBatches(count) {
  const output = (await capture("docker", composeArgs([
    "exec", "-T", "redpanda", "rpk", "topic", "consume", sourceTopic,
    "--read-committed", "-n", String(count), "-o", "start", "--format", "json", "--pretty-print=false",
  ]), timeoutMs)).output;
  const records = output.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const record = JSON.parse(line);
    return { partition: Number(record.partition), offset: Number(record.offset), batch: JSON.parse(record.value) };
  });
  if (records.length !== count) throw new Error(`expected ${count} committed batches, got ${records.length}`);
  return records;
}

async function readAllBatches(partition) {
  const output = (await capture("docker", composeArgs([
    "exec", "-T", "redpanda", "rpk", "topic", "consume", sourceTopic,
    "--read-committed", "-p", String(partition), "-o", ":end", "--format", "json", "--pretty-print=false",
  ]), timeoutMs)).output;
  return output.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const record = JSON.parse(line);
    return { partition: Number(record.partition), offset: Number(record.offset), timestamp: Number(record.timestamp), batch: JSON.parse(record.value) };
  });
}

async function readTopicMeta(topic, partition) {
  const output = (await capture("docker", composeArgs([
    "exec", "-T", "redpanda", "rpk", "topic", "consume", topic,
    "--read-committed", "-p", String(partition), "-o", ":end", "--meta-only",
    "--format", "json", "--pretty-print=false",
  ]), timeoutMs)).output;
  const records = output.trim().split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
  return { count: records.length, lastTimestampMs: records.reduce((latest, record) => Math.max(latest, Number(record.timestamp)), 0) };
}

function assertBatch(record, party, tradeCount) {
  const { batch, partition } = record;
  if (partition !== batch.partition || batch.eventStream !== sourceTopic || batch.commandCount !== 1 ||
      batch.outcomes[0]?.commandId !== `${party}-cmd-${smokeId}` ||
      (batch.outcomes[0].result.trades?.length ?? 0) !== tradeCount) {
    throw new Error(`unexpected ${party} venue batch: ${JSON.stringify(record)}`);
  }
}

async function waitForReceipt(generation, partition, offset) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const result = (await psql(`SELECT source_generation, source_partition, source_offset, trade_ordinal, policy_version FROM runtime.calcify_commitment_receipts WHERE source_generation = ${generation} AND source_partition = ${partition} AND source_offset = ${offset}`)).trim();
    if (result) return result;
    await sleep(500);
  }
  throw new Error(`timed out waiting for receipt generation=${generation} partition=${partition} offset=${offset}`);
}

async function psql(query, service = "postgres") {
  return (await capture("docker", composePsqlArgs(service, query))).output;
}

async function post(path, body, headers = {}) {
  const response = await fetch(`${runtimeUrl}${path}`, {
    method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`POST ${path} failed (${response.status}): ${text}`);
  return JSON.parse(text);
}

function capture(command, args, timeout = 15000, allowFailure = false) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    const timer = setTimeout(() => child.kill("SIGTERM"), timeout);
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    child.on("error", reject);
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0 || allowFailure) resolve({ code, output });
      else reject(new Error(`${command} ${args.join(" ")} failed (${code}): ${output.slice(-4096)}`));
    });
  });
}
