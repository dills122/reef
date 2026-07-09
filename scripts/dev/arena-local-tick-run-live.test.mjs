import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;
const commands = new Map();
const receivedCommands = [];
const commandStatusReads = [];
const referenceWrites = [];
const arena = {
  bots: new Map(),
  versions: new Map(),
  runs: new Map(),
  results: [],
  enforcementEvents: [],
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://127.0.0.1");
  if (req.method === "GET" && url.pathname === "/health") {
    return json(res, 200, { ok: true });
  }
  if (req.method === "POST" && ["/reference/instruments", "/reference/participants", "/reference/accounts", "/auth/roles", "/auth/actor-roles"].includes(url.pathname)) {
    referenceWrites.push({ path: url.pathname, body: await readJson(req) });
    return json(res, 200, { ok: true });
  }
  if (req.method === "POST" && ["/api/v1/orders/submit", "/api/v1/orders/modify", "/api/v1/orders/cancel"].includes(url.pathname)) {
    const body = await readJson(req);
    receivedCommands.push({ path: url.pathname, body });
    commands.set(body.commandId, {
      commandId: body.commandId,
      participantId: req.headers["x-participant-id"] ?? body.participantId,
      status: "COMPLETED",
      responseStatus: 200,
      responsePayloadJson: "{}",
      resultStatus: "accepted",
      canonicalMaterialized: true,
    });
    return json(res, 202, { commandId: body.commandId, status: "RECEIVED", statusUrl: `/api/v1/commands/${body.commandId}` });
  }
  if (req.method === "GET" && url.pathname.startsWith("/api/v1/commands/")) {
    const commandId = decodeURIComponent(url.pathname.slice("/api/v1/commands/".length));
    const command = commands.get(commandId);
    const participantId = req.headers["x-participant-id"] ?? "";
    commandStatusReads.push({ commandId, participantId });
    if (command !== undefined && participantId !== command.participantId) {
      return json(res, 403, { error: "participant mismatch" });
    }
    return json(res, commands.has(commandId) ? 200 : 404, command ?? { error: "not found" });
  }
  if (req.method === "GET" && url.pathname === "/api/v1/data/availability") {
    return json(res, 200, {
      generatedAt: "2026-07-07T00:00:00.000Z",
      source: "mock",
      projections: [{ projectionName: "runtime-normalized-venue-outcomes", role: "venue", projectedCount: receivedCommands.length, lag: 0, watermarks: [] }],
      surfaces: [{ name: "currentOrders", endpoint: "/api/v1/orders/current", source: "mock", freshness: "mock", scope: "participant-own-orders" }],
    });
  }
  if (req.method === "GET" && url.pathname.startsWith("/api/v1/market-data/snapshots/")) {
    const instrumentId = decodeURIComponent(url.pathname.slice("/api/v1/market-data/snapshots/".length));
    return json(res, 200, { snapshot: { instrumentId, bestBidPrice: "100000000000", bestAskPrice: "101000000000", updatedAt: "2026-07-07T00:00:00.000Z" } });
  }
  if (req.method === "GET" && (url.pathname === "/api/v1/orders/current" || url.pathname === "/api/v1/orders/history")) {
    return json(res, 200, { orders: [] });
  }
  if (url.pathname.startsWith("/internal/admin/arena/")) {
    return await handleArenaAdmin(req, res, url);
  }
  return json(res, 404, { error: "not found", path: url.pathname });
});

const configuredTestPort = process.env.REEF_ARENA_LOCAL_TICK_TEST_PORT === undefined
  ? undefined
  : Number(process.env.REEF_ARENA_LOCAL_TICK_TEST_PORT);
const address = await listenOnAvailablePort(server, configuredTestPort);
const baseUrl = `http://127.0.0.1:${address.port}`;

try {
  await run("bun", [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=live",
    `--venue-url=${baseUrl}`,
    `--arena-admin-url=${baseUrl}`,
    "--seed-reference",
    "--persist-results",
    "--command-wait-mode=accepted",
    "--out=/tmp/reef-arena-local-tick-run-live-test.json",
  ]);

  assert.equal(referenceWrites.filter((write) => write.path === "/reference/instruments").length, 4);
  assert.equal(referenceWrites.some((write) => JSON.stringify(write.body).includes("undefined")), false);
  assert.ok(referenceWrites.some((write) => write.body.participantId === "participant-builtin-mm-simple"));
  assert.ok(referenceWrites.some((write) => write.body.accountId === "account-custom-technical-indicator"));
  assert.ok(referenceWrites.some((write) => write.body.actorId === "actor-builtin-mm-refreshing"));
  assert.equal(receivedCommands.length, 16);
  assert.ok(Array.from(commands.values()).every((command) => command.status === "COMPLETED"));
  assert.ok(commandStatusReads.length >= receivedCommands.length);
  assert.ok(commandStatusReads.every((read) => commands.get(read.commandId)?.participantId === read.participantId));
  assert.equal(arena.bots.size, 5);
  assert.equal(arena.versions.size, 5);
  assert.equal(arena.runs.size, 1);
  assert.equal(arena.results.length, 5);
  const report = JSON.parse(await readFile("/tmp/reef-arena-local-tick-run-live-test.json", "utf8"));
  assert.equal(report.runPlan.tickCount, 3);
  assert.equal(report.runPlan.durationSeconds, 1.5);
  assert.equal(report.runPlan.schedulingMode, "shared-arena-time");
  assert.equal(report.runPlan.totalTickCount, 24);
  assert.equal(report.commandWaitMode, "accepted");
  assert.equal(report.healthSamples.length, 3);
  assert.equal(report.activityBySchedulingClass.house_responsive.ticks, 18);
  assert.equal(report.activityBySchedulingClass.house_responsive.submittedCommands, 16);
  assert.equal(report.activityBySchedulingClass.contestant_tick.ticks, 3);
  assert.equal(report.healthSummary.topOfBookPct, 100);
  assert.equal(report.healthSummary.crossedBookCount, 0);
  const submittedCommands = report.sessionReports.flatMap((session) => session.ticks.flatMap((tick) => tick.submission.commands));
  assert.ok(submittedCommands.length > 0);
  assert.ok(submittedCommands.every((command) => command.statusPollCount >= 1));
  assert.ok(submittedCommands.every((command) => command.firstStatus === "COMPLETED"));
  assert.ok(submittedCommands.every((command) => Number.isFinite(command.intakeElapsedMs)));

  await run("bun", [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=2",
    "--tick-interval-ms=500",
    "--out=/tmp/reef-arena-local-tick-run-duration-test.json",
  ]);
  const durationReport = JSON.parse(await readFile("/tmp/reef-arena-local-tick-run-duration-test.json", "utf8"));
  assert.equal(durationReport.runPlan.selectedBotCount, 5);
  assert.equal(durationReport.runPlan.schedulingMode, "shared-arena-time");
  assert.equal(durationReport.runPlan.perBotTickCount, 4);
  assert.equal(durationReport.runPlan.totalTickCount, 32);
  assert.equal(durationReport.runPlan.durationSeconds, 2);
  assert.equal(durationReport.totals.ticks, 32);
  console.log("arena local tick live path checks passed");
} finally {
  await new Promise((resolve) => server.close(resolve));
}

async function handleArenaAdmin(req, res, url) {
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/bots") {
    const body = await readJson(req);
    if (arena.bots.has(body.botId)) return json(res, 400, { error: `botId already exists: ${body.botId}` });
    arena.bots.set(body.botId, body);
    return json(res, 200, { bot: body });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/bot-versions") {
    const body = await readJson(req);
    const key = `${body.botId}/${body.versionId}`;
    if (arena.versions.has(key)) return json(res, 400, { error: `versionId already exists for botId: ${key}` });
    arena.versions.set(key, { ...body, status: "draft" });
    return json(res, 200, { version: arena.versions.get(key) });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/bot-versions/transition") {
    const body = await readJson(req);
    const key = `${body.botId}/${body.versionId}`;
    const version = arena.versions.get(key);
    if (version !== undefined) version.status = body.status;
    return json(res, 200, { version });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/runs") {
    const body = await readJson(req);
    if (arena.runs.has(body.runId)) return json(res, 400, { error: `runId already exists: ${body.runId}` });
    arena.runs.set(body.runId, { ...body, status: "planned" });
    return json(res, 200, { run: arena.runs.get(body.runId) });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/runs/status") {
    const body = await readJson(req);
    const run = arena.runs.get(body.runId);
    if (run !== undefined) run.status = body.status;
    return json(res, 200, { run });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/run-bot-results") {
    const body = await readJson(req);
    arena.results.push(body);
    return json(res, 200, { result: body });
  }
  if (req.method === "POST" && url.pathname === "/internal/admin/arena/run-enforcement-events") {
    const body = await readJson(req);
    arena.enforcementEvents.push(body);
    return json(res, 200, { event: body });
  }
  if (req.method === "GET" && url.pathname === "/internal/admin/arena/run-bot-results") {
    const runId = url.searchParams.get("runId");
    return json(res, 200, { results: arena.results.filter((result) => result.runId === runId) });
  }
  if (req.method === "GET" && url.pathname === "/internal/admin/arena/run-enforcement-events") {
    const runId = url.searchParams.get("runId");
    return json(res, 200, { events: arena.enforcementEvents.filter((event) => event.runId === runId) });
  }
  if (req.method === "GET" && url.pathname === "/internal/admin/arena/leaderboard") {
    const modeId = url.searchParams.get("modeId");
    const scoringPolicyVersion = url.searchParams.get("scoringPolicyVersion");
    const entries = arena.results
      .filter((result) => arena.runs.get(result.runId)?.modeId === modeId && result.scoringPolicyVersion === scoringPolicyVersion)
      .sort((left, right) => Number(left.disqualified) - Number(right.disqualified) || right.finalEquity - left.finalEquity)
      .map((result, index) => ({ rank: index + 1, ...result }));
    return json(res, 200, { entries });
  }
  return json(res, 404, { error: "not found", path: url.pathname });
}

function run(cmd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd: repoRoot, stdio: ["ignore", "pipe", "pipe"] });
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
    child.on("close", (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}\nstdout:\n${stdout}\nstderr:\n${stderr}`));
    });
  });
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      resolve(raw.length === 0 ? {} : JSON.parse(raw));
    });
    req.on("error", reject);
  });
}

function json(res, status, body) {
  const raw = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(raw) });
  res.end(raw);
}

async function listenOnAvailablePort(server, configuredPort) {
  const ports = configuredPort === undefined
    ? Array.from({ length: 50 }, (_, index) => 45000 + ((process.pid + Date.now() + index) % 10000))
    : [configuredPort];
  let lastError;
  for (const port of ports) {
    try {
      await new Promise((resolve, reject) => {
        const onError = (error) => {
          server.off("listening", onListening);
          reject(error);
        };
        const onListening = () => {
          server.off("error", onError);
          resolve();
        };
        server.once("error", onError);
        server.once("listening", onListening);
        server.listen(port, "127.0.0.1");
      });
      return server.address();
    } catch (error) {
      lastError = error;
      if (configuredPort !== undefined || error?.code !== "EADDRINUSE") {
        throw error;
      }
    }
  }
  throw lastError ?? new Error("failed to bind test server");
}
