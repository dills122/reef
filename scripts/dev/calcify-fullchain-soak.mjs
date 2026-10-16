// Full-chain paced run of the Calcify capacity path: HTTP intake -> Go matching engine -> Phase 1 extractor/verifier/receipt -> Phase 2 resolver -> capacity finance lanes -> projection.
//
// It brings the stack up through the full-path smoke (reference data for every instrument, the preflight trade), writes a capacity plan on the platform's own routing,
// starts the capacity service in a container on the stack's network, funds the load accounts, drives `calcify-load` at a paced rate, samples every stage's progress and
// the containers' CPU while it runs, drains, and checks that every stage saw exactly the trades that were accepted. Results go to FC_OUT_DIR (CSV time series + result.json).
// No capacity claim follows from a run of this script by itself: read docs/work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md section 0 for what the numbers mean.
//
//   FC_RATE=1000 FC_SECONDS=60 node scripts/dev/calcify-fullchain-soak.mjs
//
// Environment: FC_ID (run id), FC_RATE (booked trades per second offered, default 1000), FC_SECONDS (default 60), FC_INSTRUMENTS (default 64, at most 64),
// FC_MODE (two-step | capture-settle-v1, default capture-settle-v1), FC_EVICT (true|false, default true), FC_QUANTITY (order quantity, default 1: int64 cash lasts),
// FC_TMPFS (true: the stack's data volumes on tmpfs, see compose.fullchain-tmpfs.yml; not durable), FC_WORKERS (load workers, default 512), FC_SAMPLE_SECONDS (default 10), FC_DRAIN_SECONDS (default 600), FC_OUT_DIR, FC_TEARDOWN (true: remove the capacity container and
// `compose down -v` at the end; default true), FC_SKIP_STACK (true: the stack and reference data are already up from a run with the same FC_ID).
// The stack uses 4 engine partitions and so 4 capacity lanes (fixed by the full-path smoke).
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync, appendFileSync, existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { composeArgs } from "./lib/compose-utils.mjs";
import { composePsqlArgs } from "./lib/compose-psql.mjs";
import { deriveDevUrls, env, loadDotEnv, sleep } from "./lib/dev-utils.mjs";

loadDotEnv();
const id = env("FC_ID", `fc${Date.now()}`).replace(/[^A-Za-z0-9_-]/g, "_");
const token = id.toUpperCase();
const rate = Number(env("FC_RATE", "1000"));
const seconds = Number(env("FC_SECONDS", "60"));
const instrumentCount = Number(env("FC_INSTRUMENTS", "64"));
const mode = env("FC_MODE", "capture-settle-v1");
const evict = env("FC_EVICT", "true");
const quantity = env("FC_QUANTITY", "1");
const workers = Number(env("FC_WORKERS", "512"));
const sampleSeconds = Number(env("FC_SAMPLE_SECONDS", "10"));
const drainSeconds = Number(env("FC_DRAIN_SECONDS", "600"));
const teardown = env("FC_TEARDOWN", "true") === "true";
const skipStack = env("FC_SKIP_STACK", "false") === "true";
const tmpfs = env("FC_TMPFS", "false") === "true";
const composeFiles = ["compose.base.yml", "compose.local.yml", "compose.calcify.yml", ...(tmpfs ? ["compose.fullchain-tmpfs.yml"] : [])].join(",");
process.env.REEF_COMPOSE_FILES = composeFiles;
const outDir = resolve(env("FC_OUT_DIR", `reports/calcify-fullchain/${id}`));
const lanes = 4;
if (!Number.isSafeInteger(rate) || rate < 1 || !Number.isSafeInteger(seconds) || seconds < 1 || !(instrumentCount >= 1 && instrumentCount <= 64) ||
    !["two-step", "capture-settle-v1"].includes(mode) || !["true", "false"].includes(evict) || !/^[1-9][0-9]{0,8}$/.test(quantity)) {
  throw new Error("invalid FC_* settings");
}
mkdirSync(outDir, { recursive: true });

const perTrade = mode === "capture-settle-v1" ? 1 : 2;
const expectedTrades = rate * seconds;
const resolvedTopic = `REEF_${token}_RESOLVED`;
const sourceTopic = `REEF_${token}_EVENTS`;
const controlTopic = `FC_${token}_CONTROL`;
const decisionsTopic = `FC_${token}_DECISIONS`;
const applicationId = `capacity-fc-${id}`.toLowerCase();
const baseInstrument = `AAPL-${id}`;
// The platform routes an instrument to partition (first 8 bytes of SHA-256("run|session|instrument") as a big-endian non-negative long) mod lanes
// (StreamCommandEnvelopeBuilder.partition). Every lane needs an instrument, so the extra instruments are picked to cover all lanes; the plan tool re-derives the lanes in Kotlin and
// the two are compared below, so a change to the platform's hash fails the run instead of mis-sizing it.
const laneOf = (instrument) => Number((createHash("sha256").update(`${id}|session-${id}|${instrument}`).digest().readBigUInt64BE(0) & 0x7fffffffffffffffn) % BigInt(lanes));
const instruments = pickInstruments();
const extras = instruments.slice(1);
function pickInstruments() {
  const chosen = [baseInstrument];
  const perLane = new Array(lanes).fill(0); perLane[laneOf(baseInstrument)]++;
  for (let i = 0; chosen.length < instrumentCount; i++) { const candidate = `FC${String(i).padStart(3, "0")}-${id}`; chosen.push(candidate); perLane[laneOf(candidate)]++; }
  // replace extras from over-full lanes with later candidates that land on an empty lane
  for (let i = instrumentCount; perLane.some((n) => n === 0) && i < instrumentCount + 5000; i++) {
    const candidate = `FC${String(i).padStart(3, "0")}-${id}`; const lane = laneOf(candidate);
    if (perLane[lane] !== 0) continue;
    const victim = chosen.findLastIndex((name, at) => at > 0 && perLane[laneOf(name)] > 1);
    if (victim < 1) break;
    perLane[laneOf(chosen[victim])]--; chosen[victim] = candidate; perLane[lane]++;
  }
  if (perLane.some((n) => n === 0)) throw new Error(`cannot cover ${lanes} lanes with ${instrumentCount} instruments`);
  return chosen;
}
const { runtimeUrl, engineUrl } = deriveDevUrls();
const image = env("REEF_PLATFORM_RUNTIME_IMAGE", "reef-platform-runtime");
const capacityName = `reef-fc-capacity-${id}`.toLowerCase();
const log = (message) => console.log(`[fullchain ${new Date().toISOString().slice(11, 19)}] ${message}`);

try {
  await main();
} catch (error) {
  log(`FAILED: ${error?.stack ?? error}`);
  await finish(false);
  process.exitCode = 1;
}

async function main() {
  log(`run ${id}: ${rate} trades/s for ${seconds} s (${expectedTrades} trades), ${instrumentCount} instruments, ${lanes} lanes, mode ${mode}, evict ${evict}, quantity ${quantity}, tmpfs ${tmpfs}`);
  if (!skipStack) {
    log("bringing up the stack through the full-path smoke (reference data, Phase 1 and 2 sidecars, one preflight trade)");
    const code = await runLogged("node", ["scripts/dev/calcify-full-path-smoke.mjs"], join(outDir, "stack-smoke.log"), {
      DEV_CALCIFY_PHASE2: "1", DEV_CALCIFY_FULL_PATH_ID: id, DEV_CALCIFY_FULL_PATH_INSTRUMENT_IDS: extras.join(","), DEV_CALCIFY_FULL_PATH_LOAD_PAIRS: "0",
      DEV_CALCIFY_FULL_PATH_TIMEOUT_MS: "180000", DEV_CALCIFY_FULL_PATH_EXTRA_COMPOSE_FILES: tmpfs ? "compose.fullchain-tmpfs.yml" : "",
    });
    if (code !== 0) throw new Error(`the full-path smoke failed (${code}); see ${join(outDir, "stack-smoke.log")}`);
  }
  const generation = Number((await psql("SELECT COALESCE(MAX(source_generation), 0) FROM runtime.calcify_source_generations", "postgres")).trim());

  await discoverNetwork();
  log("writing the capacity plan on the engine's routing");
  const plan = (await runCapture("docker", ["run", "--rm", "--entrypoint", "/opt/java/openjdk/bin/java", image, "-cp", "/app/platform-runtime/lib/*",
    "com.reef.platform.calcify.finance.CapacityPlanTool", "--run", id, "--session", `session-${id}`, "--lanes", String(lanes), "--instruments", instruments.join(","),
    "--expected-trades", String(expectedTrades + 10), "--mode", mode, "--capture-topic", resolvedTopic, "--control-topic", controlTopic,
    "--decisions-topic", decisionsTopic, "--application-id", applicationId])).trim();
  writeFileSync(join(outDir, "plan.json"), plan + "\n");
  JSON.parse(plan).laneInstruments.forEach((set, lane) => set.forEach((instrument) => {
    if (laneOf(instrument) !== lane) throw new Error(`routing drift: ${instrument} is lane ${lane} in the Kotlin plan and ${laneOf(instrument)} in this script`);
  }));

  log("provisioning the control and decisions topics");
  for (const topic of [controlTopic, decisionsTopic]) {
    const out = await runCapture("docker", composeArgs(["exec", "-T", "redpanda", "rpk", "topic", "create", topic, "-p", String(lanes), "-r", "1",
      "-c", "retention.ms=-1", "-c", "retention.bytes=-1"]), true);
    if (!/OK|TOPIC_ALREADY_EXISTS/.test(out)) throw new Error(`creating ${topic}: ${out}`);
  }
  const resolvedPartitions = (await topicOffsets(resolvedTopic)).partitions;
  if (resolvedPartitions !== lanes) throw new Error(`the resolved topic has ${resolvedPartitions} partitions, the plan has ${lanes} lanes`);

  // Funding goes on the control topic before the service exists: a lane that meets a captured trade for an account it has not seen stops (by design), and funding
  // written afterwards could not unstick it. Controls are stamped to sort first, so the funded state is in place when the first capture is read.
  log("funding the load accounts (control records, before the service starts)");
  await fund(plan);

  log("starting the capacity service");
  await startCapacity(plan);
  await waitForCapacity();

  log("building the load tool");
  const loadBinary = await buildLoadTool();

  log("starting the load and the sampler");
  const startedAt = Date.now();
  const csv = join(outDir, "samples.csv");
  writeFileSync(csv, "t_s,engine_acked,source_trades_hw,resolved_hw,decisions_hw,projected,capacity_rows_per_s_hint,cpu_engine,cpu_capacity,cpu_redpanda,cpu_resolver,cpu_postgres,cpu_runtime,load1\n");
  const loadReport = join(outDir, "load-report.json");
  const loadArgs = ["-smoke-id", id, "-duration", `${seconds}s`, "-pairs-per-second", String(rate), "-workers", String(workers), "-instrument-ids", instruments.join(","),
    "-quantity", quantity, "-base-url", runtimeUrl, "-report-out", loadReport, "-receipt-generation", String(generation)];
  const loadDone = runLogged(loadBinary, loadArgs, join(outDir, "load.log"), {});
  let loadFinished = false; loadDone.then(() => { loadFinished = true; });
  let previous = null;
  while (!loadFinished) {
    await Promise.race([loadDone, sleep(sampleSeconds * 1000)]);
    previous = await sample(csv, startedAt, previous);
  }
  const loadCode = await loadDone;
  log(`load finished with code ${loadCode}; draining`);

  const result = await drain(startedAt, loadReport, generation);
  writeFileSync(join(outDir, "result.json"), JSON.stringify(result, null, 2) + "\n");
  console.log(`FULLCHAIN-RESULT ${JSON.stringify(result)}`);
  await finish(result.integrity === "ok");
  if (result.integrity !== "ok") process.exitCode = 1;
}

async function discoverNetwork() {
  const container = (await runCapture("docker", composeArgs(["ps", "-q", "redpanda"]))).trim().split("\n")[0];
  const networkName = (await runCapture("docker", ["inspect", "-f", "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}", container])).trim().split(" ")[0];
  writeFileSync(join(outDir, "network.txt"), networkName + "\n");
}

async function startCapacity(plan) {
  const networkName = readFileSync(join(outDir, "network.txt"), "utf8").trim();
  const script = [
    "umask 077",
    "mkdir -p /app/calcify-state/work /app/calcify-state/data",
    "printf '%s' \"$FC_PLAN\" > /app/calcify-state/work/plan.json",
    "printf '%s' reef > /app/calcify-state/work/pg.password",
    "chmod 700 /app/calcify-state/data",
    "exec /app/platform-runtime/bin/platform-runtime",
  ].join(" && ");
  const args = ["run", "-d", "--name", capacityName, "--network", networkName, "--entrypoint", "sh",
    "-e", `FC_PLAN=${plan}`,
    "-e", "CALCIFY_CAPACITY_PLAN_FILE=/app/calcify-state/work/plan.json",
    "-e", "CALCIFY_CAPACITY_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092", "-e", "CALCIFY_CAPACITY_KAFKA_SECURITY_PROTOCOL=PLAINTEXT", "-e", "CALCIFY_CAPACITY_DEV_PLAINTEXT=true",
    "-e", "CALCIFY_CAPACITY_STATE_DIRECTORY=/app/calcify-state/data",
    "-e", "CALCIFY_CAPACITY_POSTGRES_JDBC_URL=jdbc:postgresql://boundary-postgres:5432/reef?currentSchema=boundary",
    "-e", "CALCIFY_CAPACITY_POSTGRES_USER=reef", "-e", "CALCIFY_CAPACITY_POSTGRES_PASSWORD_FILE=/app/calcify-state/work/pg.password",
    "-e", `CALCIFY_CAPACITY_EVICT_SETTLED=${evict}`,
    "-e", `JAVA_OPTS=${env("FC_CAPACITY_JAVA_OPTS", "-Xmx8g -XX:+UseParallelGC")}`,
    image, "-c", script];
  await runCapture("docker", args);
}

async function waitForCapacity() {
  // the service prints no readiness line; Streams starts the lanes within seconds and the control records wait on the topic, so it is enough that the process stays up
  for (let i = 0; i < 8; i++) {
    await sleep(2500);
    const state = (await runCapture("docker", ["inspect", "-f", "{{.State.Status}} {{.State.ExitCode}}", capacityName])).trim();
    if (!state.startsWith("running")) throw new Error(`the capacity service is not running: ${state}\n${(await runCapture("docker", ["logs", "--tail", "60", capacityName], true)).slice(-3500)}`);
  }
}

async function fund(planJson) {
  const planPath = "/tmp/fc-plan.json";
  const buyer = `buyer-account-${id}`, seller = `seller-account-${id}`;
  // cash: every trade moves quantity x 150,250,000,000 from the buyer; 1.25x the expected trades, the same in every lane (an int64 limit applies, see FC_QUANTITY)
  const perTradeCash = BigInt(quantity) * 150_250_000_000n;
  const cash = perTradeCash * BigInt(Math.ceil(expectedTrades * 1.25) + 1000) + 200_000_000_000_000n;
  if (cash >= 9_000_000_000_000_000_000n) throw new Error("the buyer cash needed does not fit int64; lower FC_QUANTITY, FC_RATE or FC_SECONDS");
  const units = BigInt(quantity) * BigInt(Math.ceil(expectedTrades * 1.25) + 1000) + 100_000n;
  const cli = (...args) => `/opt/java/openjdk/bin/java -cp '/app/platform-runtime/lib/*' com.reef.platform.calcify.finance.CapacityControlCli ${args.join(" ")}`;
  const commands = [cli("fund-cash", planPath, `fc-${id}-cash-b`, buyer, cash.toString()), cli("fund-cash", planPath, `fc-${id}-cash-s`, seller, "1000000000000"),
    ...instruments.map((instrument, i) => cli("fund-security", planPath, `fc-${id}-sec-${i}`, seller, instrument, units.toString()))];
  const network = readFileSync(join(outDir, "network.txt"), "utf8").trim();
  const script = `printf '%s' "$FC_PLAN" > ${planPath} && ${commands.join(" && ")}`;
  const out = await runCapture("docker", ["run", "--rm", "--network", network, "--entrypoint", "sh", "-e", `FC_PLAN=${planJson}`, "-e", "CALCIFY_CAPACITY_CONTROL_BOOTSTRAP=redpanda:9092",
    "-e", "CALCIFY_CAPACITY_CONTROL_PROTOCOL=PLAINTEXT", "-e", "CALCIFY_CAPACITY_DEV_PLAINTEXT=true", image, "-c", script]);
  writeFileSync(join(outDir, "funding.log"), out);
}

async function buildLoadTool() {
  const out = join(outDir, "calcify-load");
  if (existsSync(out)) return out;
  const hasGo = await runCapture("sh", ["-c", "command -v go"], true);
  if (hasGo.trim()) {
    await runCapture("sh", ["-c", `cd services/simulator && go build -o ${JSON.stringify(out)} ./cmd/calcify-load`]);
  } else {
    await runCapture("docker", ["run", "--rm", "-v", `${resolve("services/simulator")}:/src`, "-v", `${outDir}:/out`, "-w", "/src", "-e", "CGO_ENABLED=0", "golang:1.24", "go", "build", "-o", "/out/calcify-load", "./cmd/calcify-load"]);
  }
  return out;
}

async function topicOffsets(topic) {
  const out = await runCapture("docker", composeArgs(["exec", "-T", "redpanda", "rpk", "topic", "describe", topic, "-p"]), true);
  const lines = out.split("\n");
  const header = lines.findIndex((line) => /PARTITION/.test(line) && /HIGH-WATERMARK/.test(line));
  let partitions = 0, hw = 0;
  if (header >= 0) {
    const names = lines[header].trim().split(/\s+/);
    const hwAt = names.indexOf("HIGH-WATERMARK");
    for (const line of lines.slice(header + 1)) {
      const cols = line.trim().split(/\s+/);
      if (/^\d+$/.test(cols[0]) && /^\d+$/.test(cols[hwAt] ?? "")) { partitions++; hw += Number(cols[hwAt]); }
    }
  }
  return { partitions, hw };
}

async function psql(query, service = "boundary-postgres") {
  return await runCapture("docker", composePsqlArgs(service, query));
}

async function projected() {
  const out = (await psql("SELECT COALESCE(SUM(last_sequence), 0) FROM boundary.calcify_finance_checkpoint")).trim();
  return Number(out) || 0;
}

async function engineAcked() {
  try {
    const response = await fetch(`${engineUrl}/internal/stream-direct/stats`);
    if (!response.ok) return null;
    const stats = await response.json();
    return (stats.partitions ?? []).reduce((sum, p) => sum + (p.acked ?? 0), 0);
  } catch { return null; }
}

async function cpu() {
  const out = await runCapture("docker", ["stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}"], true);
  const map = {};
  for (const line of out.trim().split("\n")) { const [name, pct] = line.split(" "); if (name) map[name] = parseFloat(pct) || 0; }
  const pick = (needle) => Object.entries(map).filter(([name]) => name.includes(needle)).reduce((s, [, v]) => s + v, 0).toFixed(0);
  return { engine: pick("matching-engine"), capacity: pick("fc-capacity"), redpanda: pick("redpanda"), resolver: pick("resolver"), postgres: pick("postgres"), runtime: pick("platform-runtime") };
}

async function sample(csv, startedAt, previous) {
  const t = Math.round((Date.now() - startedAt) / 1000);
  const [acked, source, resolved, decisions, proj, c] = await Promise.all([engineAcked(), topicOffsets(sourceTopic), topicOffsets(resolvedTopic), topicOffsets(decisionsTopic), projected(), cpu()]);
  const load1 = (await runCapture("sh", ["-c", "uptime | sed 's/.*load average[s]*: //' | cut -d, -f1"], true)).trim();
  const row = { t, acked, source: source.hw, resolved: resolved.hw, decisions: decisions.hw, proj };
  appendFileSync(csv, `${t},${acked ?? ""},${source.hw},${resolved.hw},${decisions.hw},${proj},,${c.engine},${c.capacity},${c.redpanda},${c.resolver},${c.postgres},${c.runtime},${load1}\n`);
  log(`t=${t}s acked=${acked} source=${source.hw} resolved=${resolved.hw} decisions=${decisions.hw} projected=${proj} cpu%(engine/capacity/redpanda/resolver/pg)=${c.engine}/${c.capacity}/${c.redpanda}/${c.resolver}/${c.postgres}`);
  return row;
}

async function drain(startedAt, loadReport, generation) {
  const report = existsSync(loadReport) ? JSON.parse(await runCapture("cat", [loadReport])) : {};
  const accepted = report.acceptedOrders ?? 0;
  const trades = report.completedTrades ?? Math.floor(accepted / 2);
  // Offsets of transactional topics count commit markers, so they are progress hints, not record counts. The authority is the projection: it applies every decision exactly once
  // and verifies the chain as it goes, so its checkpoint sequences sum to the records the lanes decided: one decision (or two) per trade plus the preflight trade and the funding records.
  const fundingRecords = lanes * 2 + instruments.length;
  const want = (trades + 1) * perTrade + fundingRecords;
  const deadline = Date.now() + drainSeconds * 1000;
  const drainStart = Date.now();
  let proj = 0, decisions = 0, stableSince = null, lastProj = -1;
  while (Date.now() < deadline) {
    [decisions, proj] = [(await topicOffsets(decisionsTopic)).hw, await projected()];
    if (proj >= want) break;
    if (proj === lastProj) { stableSince ??= Date.now(); if (Date.now() - stableSince > 90_000) break; } else { stableSince = null; lastProj = proj; }
    await sleep(2000);
  }
  const drainMs = Date.now() - drainStart;
  const problems = [];
  if (proj !== want) problems.push(`projected ${proj} decisions, expected exactly ${want} ((${trades} trades + 1 preflight) x ${perTrade} + ${fundingRecords} funding records)`);
  if (report.droppedPairs) problems.push(`the load tool dropped ${report.droppedPairs} pairs`);
  if ((report.acceptedOrders ?? 0) !== trades * 2) problems.push(`accepted ${report.acceptedOrders} orders for ${trades} trades`);
  return { id, mode, evict: evict === "true", lanes, instruments: instruments.length, quantity: Number(quantity), targetTradesPerSecond: rate, seconds, expectedTrades,
    loadAcceptedOrders: accepted, loadCompletedTrades: trades,
    loadReportSummary: { acceptedOrdersPerSecond: report.acceptedOrdersPerSecond ?? null, acceptedOrdersAtDeadline: report.acceptedOrdersAtDeadline ?? null, droppedPairs: report.droppedPairs ?? null, retries: report.retries ?? null,
      phase1ReceiptGatePassed: report.gatePassed ?? null, phase1ReceiptGateReasons: report.gateReasons ?? null, p95AcceptedToReceiptGap: report.p95AcceptedToReceiptGap ?? null },
    projectedDecisions: proj, expectedDecisions: want, decisionLogOffsetsHint: decisions, drainMs, integrity: problems.length === 0 ? "ok" : "FAILED", problems };
}

async function finish(ok) {
  if (!teardown) return;
  await runCapture("docker", ["rm", "-f", "-v", capacityName], true).catch(() => {});
  if (!ok) await runCapture("sh", ["-c", `docker logs --tail 200 ${capacityName} > ${JSON.stringify(join(outDir, "capacity-tail.log"))} 2>&1 || true`], true).catch(() => {});
  await runCapture("docker", composeArgs(["--profile", "*", "down", "-v", "--remove-orphans"]), true).catch(() => {});
}

function runLogged(command, args, logPath, extraEnv) {
  return new Promise((resolveRun) => {
    const child = spawn(command, args, { env: { ...process.env, ...extraEnv }, stdio: ["ignore", "pipe", "pipe"] });
    const sink = (chunk) => appendFileSync(logPath, chunk);
    child.stdout.on("data", sink); child.stderr.on("data", sink);
    child.on("close", (code) => resolveRun(code ?? 1));
    child.on("error", (error) => { appendFileSync(logPath, String(error)); resolveRun(1); });
  });
}

function runCapture(command, args, allowFailure = false) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (c) => { out += c; }); child.stderr.on("data", (c) => { out += c; });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0 || allowFailure) resolveRun(out);
      else reject(new Error(`${command} ${args.slice(0, 6).join(" ")} failed (${code}): ${out.slice(-2000)}`));
    });
  });
}
