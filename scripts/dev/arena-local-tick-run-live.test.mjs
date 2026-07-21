import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;
const commands = new Map();
const openOrdersByParticipant = new Map();
const receivedCommands = [];
const commandStatusReads = [];
const referenceWrites = [];
let syncResultMode = false;
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
  if (req.method === "POST" && [
    "/admin/v1/reference/instruments",
    "/admin/v1/reference/participants",
    "/admin/v1/reference/accounts",
    "/admin/v1/auth/roles",
    "/admin/v1/auth/actor-roles",
  ].includes(url.pathname)) {
    referenceWrites.push({ path: url.pathname, body: await readJson(req) });
    return json(res, 200, { ok: true });
  }
  if (req.method === "POST" && ["/api/v1/orders/submit", "/api/v1/orders/modify", "/api/v1/orders/cancel"].includes(url.pathname)) {
    const body = await readJson(req);
    const participantId = req.headers["x-participant-id"] ?? body.participantId;
    receivedCommands.push({ path: url.pathname, body });
    if (url.pathname === "/api/v1/orders/submit") {
      const orders = openOrdersByParticipant.get(participantId) ?? [];
      orders.push({
        orderId: body.clientOrderId ?? body.commandId,
        instrumentId: body.instrumentId,
        side: body.side,
        quantityUnits: body.quantityUnits,
        remainingQuantityUnits: body.quantityUnits,
        limitPrice: body.limitPrice,
        status: "OPEN",
      });
      openOrdersByParticipant.set(participantId, orders);
    }
    if (url.pathname === "/api/v1/orders/cancel") {
      const orders = openOrdersByParticipant.get(participantId) ?? [];
      openOrdersByParticipant.set(participantId, orders.filter((order) => order.orderId !== body.orderId));
    }
    commands.set(body.commandId, {
      commandId: body.commandId,
      participantId,
      status: "COMPLETED",
      responseStatus: 200,
      responsePayloadJson: "{}",
      resultStatus: "accepted",
      canonicalMaterialized: true,
    });
    if (syncResultMode) {
      return json(res, 200, {
        accepted: {
          eventId: `${body.commandId}-accepted`,
          orderId: body.clientOrderId ?? body.commandId,
          engineOrderId: `${body.commandId}-engine`,
          occurredAt: body.occurredAt ?? "2026-07-07T00:00:00.000Z",
        },
        executions: [],
        trades: [],
      });
    }
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
    const participantId = url.searchParams.get("participantId") ?? "";
    return json(res, 200, { orders: openOrdersByParticipant.get(participantId) ?? [] });
  }
  if (req.method === "GET" && url.pathname === "/api/v1/orders/fills") {
    const participantId = url.searchParams.get("participantId") ?? "";
    const fills = participantId.endsWith("builtin-mm-simple")
      ? [{
        executionId: "exec-mm-simple-1",
        orderId: "order-mm-simple-1",
        instrumentId: "AAPL",
        side: "BUY",
        quantityUnits: "1",
        executionPrice: "100000000000",
        occurredAt: "2026-07-04T14:30:00.000Z",
      }]
      : [];
    return json(res, 200, {
      participantId,
      meta: { source: "mock", freshness: "mock", scope: "participant" },
      fills,
    });
  }
  if (url.pathname.startsWith("/admin/v1/arena/")) {
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
  const commandCountBeforePreflight = receivedCommands.length;
  await assert.rejects(
    run("bun", [
      "scripts/dev/arena-local-tick-run.mjs",
      "--compartment=vm",
      "--submit-mode=live",
      `--venue-url=${baseUrl}`,
      "--require-projection-drain",
      "--projector-preflight=http",
      "--out=/tmp/reef-arena-local-tick-run-projector-preflight-test.json",
    ]),
    /live arena market-quality preflight failed/,
  );
  assert.equal(receivedCommands.length, commandCountBeforePreflight);

  await run("bun", [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=live",
    `--venue-url=${baseUrl}`,
    `--arena-admin-url=${baseUrl}`,
    "--seed-reference",
    "--persist-results",
    "--command-wait-mode=accepted",
    "--require-projection-drain",
    "--skip-projector-preflight",
    "--out=/tmp/reef-arena-local-tick-run-live-test.json",
  ]);

  assert.equal(referenceWrites.filter((write) => write.path === "/admin/v1/reference/instruments").length, 4);
  assert.equal(referenceWrites.some((write) => JSON.stringify(write.body).includes("undefined")), false);
  assert.ok(referenceWrites.some((write) => String(write.body.participantId ?? "").endsWith("-builtin-mm-simple")));
  assert.ok(referenceWrites.some((write) => String(write.body.accountId ?? "").endsWith("-custom-technical-indicator")));
  assert.ok(referenceWrites.some((write) => String(write.body.actorId ?? "").endsWith("-builtin-mm-refreshing")));
  assert.ok(receivedCommands.length >= 17);
  assert.ok(Array.from(commands.values()).every((command) => command.status === "COMPLETED"));
  assert.ok(commandStatusReads.length >= receivedCommands.length);
  assert.ok(commandStatusReads.every((read) => commands.get(read.commandId)?.participantId === read.participantId));
  assert.equal(arena.bots.size, 5);
  assert.equal(arena.bots.get("builtin-mm-simple").name, "Blue Saber Trading");
  assert.equal(arena.versions.size, 5);
  assert.equal(arena.runs.size, 1);
  assert.equal(arena.results.length, 5);
  const report = JSON.parse(await readFile("/tmp/reef-arena-local-tick-run-live-test.json", "utf8"));
  assert.equal(report.runPlan.tickCount, 3);
  assert.equal(report.runPlan.durationSeconds, 1.5);
  assert.equal(report.runPlan.schedulingMode, "shared-arena-time");
  assert.equal(report.runPlan.totalTickCount, 24);
  assert.equal(report.commandWaitMode, "accepted");
  assert.equal(report.scoringAssumptions.scoreBasis, "public score remains participation-and-policy-compliance; scoreBreakdown.shadowScore reports score-v0 tuning inputs");
  assert.equal(report.botResults.find((result) => result.botId === "builtin-mm-simple")?.displayName, "Blue Saber Trading");
  assert.equal(report.botResults.find((result) => result.botId === "builtin-mm-simple")?.tradingMetrics.schemaVersion, "reef.arena.tradingMetrics.v0");
  assert.equal(report.healthSamples.length, 3);
  assert.equal(report.activityBySchedulingClass.house_responsive.ticks, 18);
  assert.equal(report.activityBySchedulingClass.house_responsive.submittedCommands, report.totals.submittedCommands);
  assert.equal(report.activityBySchedulingClass.contestant_tick.ticks, 3);
  assert.equal(report.healthSummary.topOfBookPct, 100);
  assert.equal(report.healthSummary.crossedBookCount, 0);
  assert.equal(report.executionSummary.fillCount, 1);
  const simpleMarketMaker = report.botResults.find((result) => result.botId === "builtin-mm-simple");
  assert.equal(simpleMarketMaker?.tradingMetrics.executions.fillCount, 1);
  assert.equal(simpleMarketMaker?.tradingMetrics.inventory.netQuantityByInstrument.AAPL, 1);
  assert.equal(simpleMarketMaker?.tradingMetrics.pnl.cash, -100);
  assert.equal(simpleMarketMaker?.tradingMetrics.pnl.inventoryValue, 100.5);
  assert.equal(simpleMarketMaker?.tradingMetrics.pnl.total, 0.5);
  const persistedSimpleMarketMaker = arena.results.find((result) => result.botId === "builtin-mm-simple");
  assert.equal(persistedSimpleMarketMaker?.finalEquity, Math.round(simpleMarketMaker.finalEquityDiagnostic));
  assert.equal(persistedSimpleMarketMaker?.realizedPnl, Math.round(simpleMarketMaker.scoreBreakdown.diagnostics.realizedPnl));
  assert.equal(persistedSimpleMarketMaker?.scoringPolicyHash, report.mode.scoringPolicyHash);
  assert.equal(persistedSimpleMarketMaker?.policyEnvelopeHash, report.policyEnvelopeHash);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.attribution.source, "participant-scoped-readback-and-trading-metrics");
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.attribution.fillContribution.fillCount, 1);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.attribution.fillContribution.fillSharePct, 100);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.providerQuoteQuality.source, "participant-current-orders");
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.providerQuoteQuality.attribution, "provider-owned-current-orders");
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.providerQuoteQuality.currentOrderCount > 0, true);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.providerQuoteQuality.instruments.some((entry) => entry.instrumentId === "AAPL"), true);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.adverseSelection.available, true);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.adverseSelection.avgMarkoutBps, 50);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.adverseSelection.favorableFillCount, 1);
  assert.equal(simpleMarketMaker?.liquidityDiagnostics.adverseSelection.adverseFillCount, 0);
  assert.equal(report.liquiditySummary.providerDiagnostics.find((entry) => entry.botId === "builtin-mm-simple")?.attribution.fillContribution.fillCount, 1);
  assert.equal(report.liquiditySummary.providerDiagnostics.find((entry) => entry.botId === "builtin-mm-simple")?.adverseSelection.avgMarkoutBps, 50);
  const submittedCommands = report.sessionReports.flatMap((session) => session.ticks.flatMap((tick) => tick.submission.commands));
  assert.ok(submittedCommands.length > 0);
  assert.equal(submittedCommands.filter((command) => command.route === "/api/v1/orders/submit").length, 16);
  assert.ok(submittedCommands.filter((command) => command.route === "/api/v1/orders/cancel").length >= 1);
  assert.ok(submittedCommands.every((command) => command.statusPollCount >= 1));
  assert.ok(submittedCommands.every((command) => command.firstStatus === "COMPLETED"));
  assert.ok(submittedCommands.every((command) => Number.isFinite(command.intakeElapsedMs)));
  const submissionsWithCommands = report.sessionReports
    .flatMap((session) => session.ticks.map((tick) => tick.submission))
    .filter((submission) => Number(submission?.submitted ?? 0) > 0);
  assert.ok(submissionsWithCommands.length > 0);
  assert.ok(submissionsWithCommands.every((submission) => submission.projectionDrain?.required === true));
  assert.ok(submissionsWithCommands.every((submission) => submission.projectionDrain?.drained === true));

  const statusReadsBeforeSyncResultRun = commandStatusReads.length;
  syncResultMode = true;
  await run("bun", [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=live",
    `--venue-url=${baseUrl}`,
    `--arena-admin-url=${baseUrl}`,
    "--command-wait-mode=terminal",
    "--out=/tmp/reef-arena-local-tick-run-sync-result-test.json",
  ]);
  syncResultMode = false;
  const syncResultReport = JSON.parse(await readFile("/tmp/reef-arena-local-tick-run-sync-result-test.json", "utf8"));
  assert.equal(commandStatusReads.length, statusReadsBeforeSyncResultRun);
  assert.equal(syncResultReport.commandWaitMode, "terminal");
  assert.equal(syncResultReport.commandStatusSummary.timedOut, 0);
  assert.equal(syncResultReport.commandStatusSummary.byFinalStatus.COMPLETED, syncResultReport.commandStatusSummary.commandCount);
  const syncResultCommands = syncResultReport.sessionReports.flatMap((session) => session.ticks.flatMap((tick) => tick.submission.commands));
  assert.ok(syncResultCommands.length > 0);
  assert.ok(syncResultCommands.every((command) => command.statusPollCount === 0));
  assert.ok(syncResultCommands.every((command) => command.statusBody.source === "sync_result_intake_response"));

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
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/bots") {
    const body = await readJson(req);
    if (arena.bots.has(body.botId)) return json(res, 400, { error: `botId already exists: ${body.botId}` });
    arena.bots.set(body.botId, body);
    return json(res, 200, { bot: body });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/bot-versions") {
    const body = await readJson(req);
    const key = `${body.botId}/${body.versionId}`;
    if (arena.versions.has(key)) return json(res, 400, { error: `versionId already exists for botId: ${key}` });
    arena.versions.set(key, { ...body, status: "draft" });
    return json(res, 200, { version: arena.versions.get(key) });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/bot-versions/transition") {
    const body = await readJson(req);
    const key = `${body.botId}/${body.versionId}`;
    const version = arena.versions.get(key);
    if (version !== undefined) version.status = body.status;
    return json(res, 200, { version });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/runs") {
    const body = await readJson(req);
    if (arena.runs.has(body.runId)) return json(res, 400, { error: `runId already exists: ${body.runId}` });
    arena.runs.set(body.runId, { ...body, status: "planned" });
    return json(res, 200, { run: arena.runs.get(body.runId) });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/runs/status") {
    const body = await readJson(req);
    const run = arena.runs.get(body.runId);
    if (run !== undefined) run.status = body.status;
    return json(res, 200, { run });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/run-bot-results") {
    const body = await readJson(req);
    arena.results.push(body);
    return json(res, 200, { result: body });
  }
  if (req.method === "POST" && url.pathname === "/admin/v1/arena/run-enforcement-events") {
    const body = await readJson(req);
    arena.enforcementEvents.push(body);
    return json(res, 200, { event: body });
  }
  if (req.method === "GET" && url.pathname === "/admin/v1/arena/run-bot-results") {
    const runId = url.searchParams.get("runId");
    return json(res, 200, { results: arena.results.filter((result) => result.runId === runId) });
  }
  if (req.method === "GET" && url.pathname === "/admin/v1/arena/run-enforcement-events") {
    const runId = url.searchParams.get("runId");
    return json(res, 200, { events: arena.enforcementEvents.filter((event) => event.runId === runId) });
  }
  if (req.method === "GET" && url.pathname === "/admin/v1/arena/leaderboard") {
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
    ? [0]
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
