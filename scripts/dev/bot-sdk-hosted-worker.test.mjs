import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import http from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn, spawnSync } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname;
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-worker-"));
const artifactPath = join(outDir, "simple-market-maker.bundle.js");
const hangingArtifactPath = join(outDir, "sync-hanging-bot.bundle.js");

const build = spawnSync(
  "bun",
  [
    "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
    "packages/bot-sdk/examples/simple-market-maker.ts",
    `--out=${artifactPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(build.status, 0, `artifact build failed\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);

const run = spawnSync(
  "bun",
  ["scripts/dev/bot-sdk-hosted-worker-run.mjs", artifactPath, "packages/bot-sdk/fixtures/aapl-multi-tick.json"],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(run.status, 0, `hosted worker run failed\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}`);
const report = JSON.parse(run.stdout);
assert.equal(report.status, "completed");
assert.equal(report.orderActionsProposed, 6);

const live = await runLiveWorker(artifactPath);
if (live.skipped) {
  console.warn(`skipped hosted live worker loopback check: ${live.reason}`);
} else {
  assert.equal(live.report.status, "completed");
  assert.equal(live.report.readMode, "live");
  assert.equal(live.report.dataAvailability.source, "worker-live-test");
  assert.equal(live.report.orderActionsProposed, 6);
  assert.equal(live.report.ticks[0].venueResponses.length, 2);
  assert.equal(live.requests.ordersCurrent, 4);
  assert.equal(live.requests.submit, 6);
  assert.ok(live.requests.snapshots >= 3);
}

writeFileSync(
  hangingArtifactPath,
  `
while (true) {}
const { ReefBotV1 } = __reefBotSdk;
module.exports.default = class SyncHangingBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("never")]; } };
`,
);
const hangingRun = spawnSync(
  "bun",
  ["scripts/dev/bot-sdk-hosted-worker-run.mjs", hangingArtifactPath, "packages/bot-sdk/fixtures/aapl-multi-tick.json", "--wall-timeout-ms=250"],
  { cwd: repoRoot, encoding: "utf8" },
);
assert.equal(hangingRun.status, 1);
const hangingReport = JSON.parse(hangingRun.stdout);
assert.equal(hangingReport.status, "do_not_merge");
assert.ok(hangingReport.issues.some((issue) => issue.code === "hosted_worker_timeout"));

console.log("bot SDK hosted worker checks passed");

async function runLiveWorker(artifact) {
  const requests = { snapshots: 0, trades: 0, ordersCurrent: 0, submit: 0, availability: 0 };
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (url.pathname === "/api/v1/data/availability") {
      requests.availability += 1;
      return json(response, {
        generatedAt: "2026-07-04T14:30:00.000Z",
        source: "worker-live-test",
        projections: [],
        surfaces: [],
      });
    }
    if (url.pathname === "/api/v1/market-data/snapshots/AAPL") {
      requests.snapshots += 1;
      return json(response, {
        snapshot: {
          instrumentId: "AAPL",
          bestBidPrice: "99500000000",
          bestAskPrice: "100500000000",
          updatedAt: "2026-07-04T14:30:00.000Z",
        },
      });
    }
    if (url.pathname === "/api/v1/market-data/trades/AAPL") {
      requests.trades += 1;
      return json(response, { trades: [{ price: "100000000000" }] });
    }
    if (url.pathname === "/api/v1/orders/current") {
      requests.ordersCurrent += 1;
      assert.equal(url.searchParams.get("participantId"), "participant-simple-market-maker");
      return json(response, { orders: [] });
    }
    if (url.pathname === "/api/v1/orders/submit") {
      requests.submit += 1;
      const body = await readRequestBody(request);
      assert.equal(body.instrumentId, "AAPL");
      response.writeHead(202, { "content-type": "application/json" });
      response.end(JSON.stringify({ accepted: true, commandId: body.commandId }));
      return;
    }
    response.writeHead(404, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "not found", path: url.pathname }));
  });
  const port = await listenOnAvailablePort(server);
  if (port.skipped) {
    return port;
  }
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address?.port ?? port.value}`;
  try {
    const liveRun = await spawnLiveWorkerRun([
      "scripts/dev/bot-sdk-hosted-worker-run.mjs",
      artifact,
      "packages/bot-sdk/fixtures/aapl-multi-tick.json",
      "--read-mode=live",
      `--venue-url=${baseUrl}`,
    ]);
    assert.equal(liveRun.status, 0, `hosted live worker run failed\nstdout:\n${liveRun.stdout}\nstderr:\n${liveRun.stderr}`);
    return { report: JSON.parse(liveRun.stdout), requests };
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function spawnLiveWorkerRun(args) {
  return new Promise((resolve, reject) => {
    const child = spawn("bun", args, { cwd: repoRoot, stdio: ["ignore", "pipe", "pipe"] });
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
    child.on("close", (status) => {
      resolve({ status, stdout, stderr });
    });
  });
}

async function listenOnAvailablePort(server) {
  const firstPort = 19000 + (process.pid % 1000);
  for (let offset = 0; offset < 50; offset += 1) {
    const port = firstPort + offset;
    try {
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, "127.0.0.1", () => {
          server.off("error", reject);
          resolve();
        });
      });
      return { value: port };
    } catch (error) {
      if (error?.code === "EPERM") {
        return { skipped: true, reason: error.message };
      }
      if (error?.code !== "EADDRINUSE" && !String(error?.message ?? "").includes("port")) {
        throw error;
      }
    }
  }
  return { skipped: true, reason: "no loopback HTTP test port available" };
}

function json(response, body) {
  response.writeHead(200, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
}

async function readRequestBody(request) {
  let body = "";
  request.setEncoding("utf8");
  for await (const chunk of request) {
    body += chunk;
  }
  return JSON.parse(body);
}
