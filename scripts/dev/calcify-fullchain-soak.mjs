// Full-chain paced run of the Calcify capacity path on the validated 10k ingress profile (docs/research/CALCIFY_DIRECT_THROUGHPUT_2026-10-02.md, D7):
// Netty HTTP -> in-memory bounded intake -> durable Redpanda ACK -> 16 direct Go matching lanes -> canonical event batches -> Phase 1 extractor/verifier -> Phase 2 managed resolver
// -> capacity finance lanes (RocksDB + changelog, eviction on) -> projection into Postgres.
//
// The stack and its reference data come from scripts/dev/calcify-direct-bootstrap.mjs unchanged (standing BUY makers, so one timed SELL is one trade). This script adds what that
// profile never had: the capacity service in a container, a plan on the platform's own routing, funding written before the service starts, a paced `calcify-load` run, a sampler
// for every stage and container, and an exact check that the projection holds one decision (two in two-step mode) per trade plus the funding records.
// No capacity claim follows from a run by itself: see docs/work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md section 0.
//
//   FC_RATE=2000 FC_SECONDS=60 node scripts/dev/calcify-fullchain-soak.mjs
//
// Environment: FC_ID (run id, default fc<time>), FC_RATE (timed SELLs = booked trades per second offered, default 1000), FC_SECONDS (default 60), FC_MODE (two-step |
// capture-settle-v1, default capture-settle-v1), FC_EVICT (true|false, default true), FC_QUANTITY (units per order, default 1: at the load price int64 cash lasts 9e18/1.5e11 trades),
// FC_WORKERS (load workers, default 1024), FC_TMPFS (true: the broker and the databases on tmpfs volumes; not durable), FC_TMPFS_BROKER_GB / FC_TMPFS_BOUNDARY_GB / FC_TMPFS_OTHER_GB
// (limits), FC_SAMPLE_SECONDS (10), FC_DRAIN_SECONDS (600), FC_OUT_DIR, FC_TEARDOWN (true: remove the capacity container and the compose project at the end), FC_SKIP_BUILD (true: use
// the images already built), FC_CAPACITY_JAVA_OPTS (default "-Xmx8g -XX:+UseParallelGC"), FC_ENGINE_BATCH (matching batch, default the bootstrap's 500).
// The stack has 16 partitions and so 16 capacity lanes (fixed by the bootstrap).
// Two-box runs: FC_LOAD_SSH (user@host of a second box on the private network), FC_LOAD_KEY (private key path on this box) and FC_VENUE_URL (this box's API as the load box sees it, e.g.
// http://10.x.x.x:8080) run `calcify-load` on the other box, so the load generator's CPU is not taken from the system under test. The binary is built here and copied over.
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync, appendFileSync, existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { sleep } from "./lib/dev-utils.mjs";

const root = resolve(fileURLToPath(import.meta.url), "../../..");
const env = (name, fallback) => process.env[name] ?? fallback;
const id = env("FC_ID", `fc${Date.now()}`).replace(/[^A-Za-z0-9_-]/g, "_");
const rate = Number(env("FC_RATE", "1000"));
const seconds = Number(env("FC_SECONDS", "60"));
const mode = env("FC_MODE", "capture-settle-v1");
const evict = env("FC_EVICT", "true");
const quantity = env("FC_QUANTITY", "1");
const workers = Number(env("FC_WORKERS", "1024"));
const sampleSeconds = Number(env("FC_SAMPLE_SECONDS", "10"));
const drainSeconds = Number(env("FC_DRAIN_SECONDS", "600"));
const teardown = env("FC_TEARDOWN", "true") === "true";
const tmpfs = env("FC_TMPFS", "false") === "true";
const skipBuild = env("FC_SKIP_BUILD", "false") === "true";
const outDir = resolve(env("FC_OUT_DIR", `reports/calcify-fullchain/${id}`));
const lanes = 16;
const loadSsh = env("FC_LOAD_SSH", ""), loadKey = env("FC_LOAD_KEY", ""), venueUrl = env("FC_VENUE_URL", "http://127.0.0.1:8080");
const twoBox = loadSsh !== "";
if (twoBox && (!loadKey || venueUrl.startsWith("http://127."))) throw new Error("FC_LOAD_SSH needs FC_LOAD_KEY and an FC_VENUE_URL the other box can reach");
if (!Number.isSafeInteger(rate) || rate < 1 || !Number.isSafeInteger(seconds) || seconds < 1 || !["two-step", "capture-settle-v1"].includes(mode) ||
    !["true", "false"].includes(evict) || !/^[1-9][0-9]{0,8}$/.test(quantity)) throw new Error("invalid FC_* settings");
mkdirSync(outDir, { recursive: true });

const perTrade = mode === "capture-settle-v1" ? 1 : 2;
const expectedTrades = rate * seconds;
const prefix = `REEF_${id.toUpperCase()}`;
const eventsTopic = `${prefix}_EVENTS`, resolvedTopic = `${prefix}_RESOLVED`;
const controlTopic = `FC_${id.toUpperCase()}_CONTROL`, decisionsTopic = `FC_${id.toUpperCase()}_DECISIONS`;
const applicationId = `capacity-fc-${id}`.toLowerCase();
const project = `reef-calcify-fc-${id.toLowerCase().replace(/[^a-z0-9]/g, "-")}`;
const setupDir = join(outDir, "setup");
const image = "reef-platform-runtime", matcherImage = "reef-matching-engine";
const capacityName = `${project}-capacity`;
const redpanda = `${project}-redpanda`;
const boundaryDb = "reef-boundary-postgres";
const log = (message) => console.log(`[fullchain ${new Date().toISOString().slice(11, 19)}] ${message}`);
let instruments = [];
const sshOptions = ["-i", loadKey, "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR", "-o", "ServerAliveInterval=30"];
const remoteBinary = "/tmp/calcify-load", remoteReport = "/tmp/calcify-load-report.json";
const shellQuote = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`;

try { await main(); } catch (error) { log(`FAILED: ${error?.stack ?? error}`); await finish(false); process.exitCode = 1; }

async function main() {
  log(`run ${id}: ${rate} trades/s (one timed SELL each) for ${seconds} s = ${expectedTrades} trades, ${lanes} lanes, mode ${mode}, evict ${evict}, quantity ${quantity}, tmpfs ${tmpfs}, project ${project}`);
  if (!skipBuild) {
    log("building the runtime and matching-engine images from this checkout");
    await runCapture("docker", ["build", "-q", "-f", "services/platform-runtime/Dockerfile", "-t", image, "."], false, root);
    await runCapture("docker", ["build", "-q", "-t", matcherImage, "services/matching-engine"], false, root);
  }
  if (tmpfs) {
    log("creating tmpfs volumes for the broker and the databases");
    const volume = (name, gb) => runCapture("docker", ["volume", "create", "--driver", "local", "--opt", "type=tmpfs", "--opt", "device=tmpfs", "--opt", `o=size=${gb}g,mode=1777`, `${project}_${name}`]);
    await volume("reef-redpanda-data", env("FC_TMPFS_BROKER_GB", "24"));
    await volume("reef-postgres-data", env("FC_TMPFS_OTHER_GB", "6"));
    await volume("reef-boundary-postgres-data", env("FC_TMPFS_BOUNDARY_GB", "40"));
  }

  log("bringing up the validated direct profile (bootstrap: topics, reference data, standing makers, Phase 1 and 2 sidecars)");
  const code = await runLogged("node", ["scripts/dev/calcify-direct-bootstrap.mjs"], join(outDir, "bootstrap.log"), {
    CALCIFY_DIRECT_SMOKE_ID: id, CALCIFY_DIRECT_WORKLOAD_MODE: "aggressor", CALCIFY_DIRECT_GENERATION: "2", CALCIFY_DIRECT_SECONDS: String(seconds),
    CALCIFY_DIRECT_PAIRS_PER_SECOND: String(rate), CALCIFY_DIRECT_IMAGE: image, CALCIFY_DIRECT_MATCHING_IMAGE: matcherImage, CALCIFY_DIRECT_SETUP_OUT: setupDir,
    CALCIFY_DIRECT_PROJECT: project, CALCIFY_DIRECT_TERMINAL_PER_BOOK: env("FC_TERMINAL_PER_BOOK", "3906"), ...(process.env.FC_ENGINE_BATCH ? { CALCIFY_DIRECT_ENGINE_BATCH: process.env.FC_ENGINE_BATCH } : {}),
  }, root);
  if (code !== 0) throw new Error(`the direct bootstrap failed (${code}); see ${join(outDir, "bootstrap.log")}`);
  const setup = JSON.parse(readFileSync(join(setupDir, "setup.json"), "utf8"));
  instruments = setup.instruments;
  log(`stack ready: ${instruments.length} instruments, ${setup.preflightAcceptedOrders} standing makers acknowledged`);

  await compose(["up", "-d", "--no-deps", "--wait", "boundary-postgres"]);
  await migrateBoundary();

  const network = (await runCapture("docker", ["inspect", "-f", "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}", redpanda])).trim().split(" ")[0];
  log("writing the capacity plan on the engine's routing");
  const plan = (await runCapture("docker", ["run", "--rm", "--entrypoint", "/opt/java/openjdk/bin/java", image, "-cp", "/app/platform-runtime/lib/*",
    "com.reef.platform.calcify.finance.CapacityPlanTool", "--run", id, "--session", `session-${id}`, "--lanes", String(lanes), "--instruments", instruments.join(","),
    "--expected-trades", String(expectedTrades + 10), "--mode", mode, "--capture-topic", resolvedTopic, "--control-topic", controlTopic,
    "--decisions-topic", decisionsTopic, "--application-id", applicationId])).trim();
  writeFileSync(join(outDir, "plan.json"), plan + "\n");

  log("provisioning the control and decisions topics");
  for (const topic of [controlTopic, decisionsTopic]) {
    const out = await runCapture("docker", ["exec", redpanda, "rpk", "topic", "create", topic, "-p", String(lanes), "-r", "1", "-c", "retention.ms=-1", "-c", "retention.bytes=-1"], true);
    if (!/OK|TOPIC_ALREADY_EXISTS/.test(out)) throw new Error(`creating ${topic}: ${out}`);
  }
  const resolvedPartitions = (await topicOffsets(resolvedTopic)).partitions;
  if (resolvedPartitions !== lanes) throw new Error(`the resolved topic has ${resolvedPartitions} partitions, the plan has ${lanes} lanes`);

  // Funding goes on the control topic before the service exists: a lane that meets a captured trade for an account it has not seen stops (by design), and funding written afterwards
  // could not unstick it. Controls are stamped to sort first, so the funded state is in place when the first capture is read.
  log("funding the load accounts (control records, before the service starts)");
  await fund(plan, network);

  log("starting the capacity service");
  await startCapacity(plan, network);
  await waitForCapacity();

  log("building the load tool");
  const loadBinary = await buildLoadTool();

  if (twoBox) {
    log(`copying the load tool to ${loadSsh}`);
    await runCapture("scp", [...sshOptions, loadBinary, `${loadSsh}:${remoteBinary}`]);
    await runCapture("ssh", [...sshOptions, loadSsh, `chmod +x ${remoteBinary}`]);
  }
  log("starting the load and the sampler");
  const startedAt = Date.now();
  const csv = join(outDir, "samples.csv");
  writeFileSync(csv, "t_s,engine_acked,events_hw,resolved_hw,decisions_hw,projected,cpu_engine,cpu_capacity,cpu_redpanda,cpu_resolver,cpu_api,cpu_extractor,cpu_verifier,cpu_pg_boundary,cpu_pg_runtime,load1\n");
  const loadReport = join(outDir, "load-report.json");
  const loadArgs = ["-smoke-id", id, "-workload-mode", "aggressor", "-duration", `${seconds}s`, "-pairs-per-second", String(rate), "-workers", String(workers), "-instrument-ids", instruments.join(","),
    "-quantity", quantity, "-base-url", venueUrl, "-report-out", twoBox ? remoteReport : loadReport];
  const loadDone = twoBox
    ? runLogged("ssh", [...sshOptions, loadSsh, `rm -f ${remoteReport}; ${remoteBinary} ${loadArgs.map(shellQuote).join(" ")}`], join(outDir, "load.log"), {}, root)
    : runLogged(loadBinary, loadArgs, join(outDir, "load.log"), {}, root);
  let loadFinished = false; loadDone.then(() => { loadFinished = true; });
  while (!loadFinished) {
    await Promise.race([loadDone, sleep(sampleSeconds * 1000)]);
    await sample(csv, startedAt);
  }
  const loadCode = await loadDone;
  if (twoBox) await runCapture("scp", [...sshOptions, `${loadSsh}:${remoteReport}`, loadReport], true);
  log(`load finished with code ${loadCode}; draining`);

  const result = await drain(loadReport, loadCode);
  writeFileSync(join(outDir, "result.json"), JSON.stringify(result, null, 2) + "\n");
  console.log(`FULLCHAIN-RESULT ${JSON.stringify(result)}`);
  await finish(result.integrity === "ok");
  if (result.integrity !== "ok") process.exitCode = 1;
}

function composeEnv() {
  return { ...process.env, COMPOSE_PROJECT_NAME: project, COMPOSE_PROFILES: "redpanda,calcify-phase1,calcify-phase2" };
}
async function compose(args, allowFailure = false) {
  return await runCapture("docker", ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml", "-f", "compose.calcify.yml", "-f", join(setupDir, "compose-profile.json"), ...args], allowFailure, root, composeEnv());
}

async function migrateBoundary() {
  const exists = async () => (await runCapture("docker", ["exec", boundaryDb, "psql", "-U", "reef", "-d", "reef", "-At", "-c", "SELECT to_regclass('boundary.calcify_finance_checkpoint') IS NOT NULL"], true)).trim() === "t";
  if (await exists()) return;
  log("applying the boundary migrations (the capacity projection tables)");
  const dir = join(root, "scripts/dev/db/migrations/boundary");
  const files = (await runCapture("ls", [dir])).trim().split("\n").filter((f) => f.endsWith(".sql")).sort();
  for (const file of files) {
    const sql = readFileSync(join(dir, file), "utf8");
    const out = await runCapture("docker", ["exec", "-i", boundaryDb, "psql", "-U", "reef", "-d", "reef", "-v", "ON_ERROR_STOP=1", "-q"], true, root, process.env, sql);
    if (/ERROR/.test(out)) throw new Error(`migration ${file}: ${out.slice(-800)}`);
  }
  if (!(await exists())) throw new Error("the capacity projection tables are missing after the boundary migrations");
}

async function startCapacity(plan, network) {
  const script = [
    "umask 077",
    "mkdir -p /app/calcify-state/work /app/calcify-state/data",
    "printf '%s' \"$FC_PLAN\" > /app/calcify-state/work/plan.json",
    "printf '%s' reef > /app/calcify-state/work/pg.password",
    "chmod 700 /app/calcify-state/data",
    "exec /app/platform-runtime/bin/platform-runtime",
  ].join(" && ");
  await runCapture("docker", ["run", "-d", "--name", capacityName, "--network", network, "--entrypoint", "sh",
    "-e", `FC_PLAN=${plan}`,
    "-e", "CALCIFY_CAPACITY_PLAN_FILE=/app/calcify-state/work/plan.json",
    "-e", "CALCIFY_CAPACITY_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092", "-e", "CALCIFY_CAPACITY_KAFKA_SECURITY_PROTOCOL=PLAINTEXT", "-e", "CALCIFY_CAPACITY_DEV_PLAINTEXT=true",
    "-e", "CALCIFY_CAPACITY_STATE_DIRECTORY=/app/calcify-state/data",
    "-e", "CALCIFY_CAPACITY_POSTGRES_JDBC_URL=jdbc:postgresql://boundary-postgres:5432/reef?currentSchema=boundary",
    "-e", "CALCIFY_CAPACITY_POSTGRES_USER=reef", "-e", "CALCIFY_CAPACITY_POSTGRES_PASSWORD_FILE=/app/calcify-state/work/pg.password",
    "-e", `CALCIFY_CAPACITY_EVICT_SETTLED=${evict}`,
    "-e", `JAVA_OPTS=${env("FC_CAPACITY_JAVA_OPTS", "-Xmx8g -XX:+UseParallelGC")}`,
    image, "-c", script]);
}

async function waitForCapacity() {
  // the service prints no readiness line; Streams starts the lanes within seconds and the control records wait on the topic, so it is enough that the process stays up
  for (let i = 0; i < 8; i++) {
    await sleep(2500);
    const state = (await runCapture("docker", ["inspect", "-f", "{{.State.Status}} {{.State.ExitCode}}", capacityName])).trim();
    if (!state.startsWith("running")) throw new Error(`the capacity service is not running: ${state}\n${(await runCapture("docker", ["logs", "--tail", "60", capacityName], true)).slice(-3500)}`);
  }
}

async function fund(planJson, network) {
  const planPath = "/tmp/fc-plan.json";
  const buyer = `buyer-account-${id}`, seller = `seller-account-${id}`;
  // every trade moves quantity x 150,250,000,000 from the buyer; 1.25x the expected trades, the same in every lane (int64 bounds apply, see FC_QUANTITY)
  const perTradeCash = BigInt(quantity) * 150_250_000_000n;
  const cash = perTradeCash * BigInt(Math.ceil(expectedTrades * 1.25) + 1000) + 200_000_000_000_000n;
  if (cash >= 9_000_000_000_000_000_000n) throw new Error("the buyer cash needed does not fit int64; lower FC_QUANTITY, FC_RATE or FC_SECONDS");
  const units = BigInt(quantity) * BigInt(Math.ceil(expectedTrades * 1.25) + 1000) + 100_000n;
  const cli = (...args) => `/opt/java/openjdk/bin/java -cp '/app/platform-runtime/lib/*' com.reef.platform.calcify.finance.CapacityControlCli ${args.join(" ")}`;
  const commands = [cli("fund-cash", planPath, `fc-${id}-cash-b`, buyer, cash.toString()), cli("fund-cash", planPath, `fc-${id}-cash-s`, seller, "1000000000000"),
    ...instruments.map((instrument, i) => cli("fund-security", planPath, `fc-${id}-sec-${i}`, seller, instrument, units.toString()))];
  const script = `printf '%s' "$FC_PLAN" > ${planPath} && ${commands.join(" && ")}`;
  const out = await runCapture("docker", ["run", "--rm", "--network", network, "--entrypoint", "sh", "-e", `FC_PLAN=${planJson}`, "-e", "CALCIFY_CAPACITY_CONTROL_BOOTSTRAP=redpanda:9092",
    "-e", "CALCIFY_CAPACITY_CONTROL_PROTOCOL=PLAINTEXT", "-e", "CALCIFY_CAPACITY_DEV_PLAINTEXT=true", image, "-c", script]);
  writeFileSync(join(outDir, "funding.log"), out);
}

async function buildLoadTool() {
  const out = join(outDir, "calcify-load");
  if (existsSync(out)) return out;
  const hasGo = await runCapture("sh", ["-c", "command -v go"], true);
  if (hasGo.trim()) await runCapture("sh", ["-c", `cd services/simulator && go build -o ${JSON.stringify(out)} ./cmd/calcify-load`], false, root);
  else await runCapture("docker", ["run", "--rm", "-v", `${join(root, "services/simulator")}:/src`, "-v", `${outDir}:/out`, "-w", "/src", "-e", "CGO_ENABLED=0", "golang:1.24", "go", "build", "-o", "/out/calcify-load", "./cmd/calcify-load"]);
  return out;
}

async function topicOffsets(topic) {
  const out = await runCapture("docker", ["exec", redpanda, "rpk", "topic", "describe", topic, "-p"], true);
  const lines = out.split("\n");
  const header = lines.findIndex((line) => /PARTITION/.test(line) && /HIGH-WATERMARK/.test(line));
  let partitions = 0, hw = 0;
  if (header >= 0) {
    const hwAt = lines[header].trim().split(/\s+/).indexOf("HIGH-WATERMARK");
    for (const line of lines.slice(header + 1)) {
      const cols = line.trim().split(/\s+/);
      if (/^\d+$/.test(cols[0]) && /^\d+$/.test(cols[hwAt] ?? "")) { partitions++; hw += Number(cols[hwAt]); }
    }
  }
  return { partitions, hw };
}

async function projected() {
  const out = (await runCapture("docker", ["exec", boundaryDb, "psql", "-U", "reef", "-d", "reef", "-At", "-c", "SELECT COALESCE(SUM(last_sequence), 0) FROM boundary.calcify_finance_checkpoint"], true)).trim();
  return Number(out) || 0;
}

async function engineAcked() {
  try {
    const response = await fetch("http://127.0.0.1:8081/internal/stream-direct/stats");
    if (!response.ok) return null;
    const stats = await response.json();
    return (stats.partitions ?? []).reduce((sum, p) => sum + (p.acked ?? 0), 0);
  } catch { return null; }
}

async function cpu() {
  const out = await runCapture("docker", ["stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}"], true);
  const map = {};
  for (const line of out.trim().split("\n")) { const [name, pct] = line.split(" "); if (name) map[name] = parseFloat(pct) || 0; }
  const sum = (match) => Object.entries(map).filter(([name]) => match(name)).reduce((s, [, v]) => s + v, 0).toFixed(0);
  return { engine: sum((n) => n.includes("matching-engine")), capacity: sum((n) => n === capacityName), redpanda: sum((n) => n.includes("redpanda")), resolver: sum((n) => n.includes("resolver")),
    api: sum((n) => n.includes("platform-api")), extractor: sum((n) => n.includes("extractor")), verifier: sum((n) => n.includes("verifier")),
    pgBoundary: sum((n) => n === boundaryDb), pgRuntime: sum((n) => n.startsWith(project) && n.includes("postgres")) };
}

async function sample(csv, startedAt) {
  const t = Math.round((Date.now() - startedAt) / 1000);
  const [acked, events, resolved, decisions, proj, c] = await Promise.all([engineAcked(), topicOffsets(eventsTopic), topicOffsets(resolvedTopic), topicOffsets(decisionsTopic), projected(), cpu()]);
  const load1 = (await runCapture("sh", ["-c", "uptime | sed 's/.*load average[s]*: //' | cut -d, -f1"], true)).trim();
  appendFileSync(csv, `${t},${acked ?? ""},${events.hw},${resolved.hw},${decisions.hw},${proj},${c.engine},${c.capacity},${c.redpanda},${c.resolver},${c.api},${c.extractor},${c.verifier},${c.pgBoundary},${c.pgRuntime},${load1}\n`);
  log(`t=${t}s acked=${acked} events=${events.hw} resolved=${resolved.hw} decisions=${decisions.hw} projected=${proj} cpu%(api/engine/redpanda/extractor/verifier/resolver/capacity/pg)=${c.api}/${c.engine}/${c.redpanda}/${c.extractor}/${c.verifier}/${c.resolver}/${c.capacity}/${c.pgBoundary}`);
}

async function drain(loadReport, loadCode) {
  const report = existsSync(loadReport) ? JSON.parse(readFileSync(loadReport, "utf8")) : {};
  const trades = report.completedTrades ?? 0;
  // Offsets of transactional topics count commit markers, so they are progress hints, not record counts. The authority is the projection: it applies every decision exactly once
  // and verifies the chain as it goes, so its checkpoint sequences sum to the records the lanes decided: one decision (or two) per trade plus the funding records.
  const fundingRecords = lanes * 2 + instruments.length;
  const want = trades * perTrade + fundingRecords;
  const deadline = Date.now() + drainSeconds * 1000;
  const drainStart = Date.now();
  let proj = 0, decisions = 0, stableSince = null, lastProj = -1;
  while (Date.now() < deadline) {
    [decisions, proj] = [(await topicOffsets(decisionsTopic)).hw, await projected()];
    if (proj >= want) break;
    if (proj === lastProj) { stableSince ??= Date.now(); if (Date.now() - stableSince > 90_000) break; } else { stableSince = null; lastProj = proj; }
    await sleep(2000);
  }
  const problems = [];
  if (proj !== want) problems.push(`projected ${proj} decisions, expected exactly ${want} (${trades} trades x ${perTrade} + ${fundingRecords} funding records)`);
  if (report.droppedPairs) problems.push(`the load tool dropped ${report.droppedPairs} trade jobs`);
  if ((report.acceptedOrders ?? 0) !== trades) problems.push(`accepted ${report.acceptedOrders} orders for ${trades} trades (aggressor mode: one order per trade)`);
  return { id, project, profile: "direct in-memory intake + durable Redpanda ack, 16 lanes, standing makers (D7 profile)", mode, evict: evict === "true", lanes, instruments: instruments.length, quantity: Number(quantity), tmpfs, topology: twoBox ? "two-box (load generator on its own box)" : "one-box (load generator on the venue box)",
    targetTradesPerSecond: rate, seconds, expectedTrades, loadAcceptedOrders: report.acceptedOrders ?? null, loadCompletedTrades: trades, loadExitCode: loadCode,
    loadReportSummary: { acceptedOrdersPerSecond: report.acceptedOrdersPerSecond ?? null, acceptedOrdersAtDeadline: report.acceptedOrdersAtDeadline ?? null, droppedPairs: report.droppedPairs ?? null, retries: report.retries ?? null,
      failures: report.failures ?? null, gatePassed: report.gatePassed ?? null, gateReasons: report.gateReasons ?? null },
    projectedDecisions: proj, expectedDecisions: want, decisionLogOffsetsHint: decisions, drainMs: Date.now() - drainStart, integrity: problems.length === 0 ? "ok" : "FAILED", problems };
}

async function finish(ok) {
  if (!ok) await runCapture("sh", ["-c", `docker logs --tail 300 ${capacityName} > ${JSON.stringify(join(outDir, "capacity-tail.log"))} 2>&1 || true`], true).catch(() => {});
  if (!teardown) return;
  await runCapture("docker", ["rm", "-f", "-v", capacityName], true).catch(() => {});
  if (existsSync(join(setupDir, "compose-profile.json"))) await compose(["--profile", "*", "down", "-v", "--remove-orphans"], true).catch(() => {});
  for (const name of ["reef-redpanda-data", "reef-postgres-data", "reef-boundary-postgres-data"]) await runCapture("docker", ["volume", "rm", "-f", `${project}_${name}`], true).catch(() => {});
}

function runLogged(command, args, logPath, extraEnv, cwd) {
  return new Promise((resolveRun) => {
    const child = spawn(command, args, { cwd: cwd ?? process.cwd(), env: { ...process.env, ...extraEnv }, stdio: ["ignore", "pipe", "pipe"] });
    const sink = (chunk) => appendFileSync(logPath, chunk);
    child.stdout.on("data", sink); child.stderr.on("data", sink);
    child.on("close", (code) => resolveRun(code ?? 1));
    child.on("error", (error) => { appendFileSync(logPath, String(error)); resolveRun(1); });
  });
}

function runCapture(command, args, allowFailure = false, cwd, environment, input) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { cwd: cwd ?? process.cwd(), env: environment ?? process.env, stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (c) => { out += c; }); child.stderr.on("data", (c) => { out += c; });
    child.on("error", reject);
    if (input !== undefined) { child.stdin.on("error", () => {}); child.stdin.end(input); }
    child.on("close", (code) => {
      if (code === 0 || allowFailure) resolveRun(out);
      else reject(new Error(`${command} ${args.slice(0, 6).join(" ")} failed (${code}): ${out.slice(-2000)}`));
    });
  });
}
