import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import http from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname.replace(/\/$/, "");
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-live-container-"));
const artifactPath = join(outDir, "simple-market-maker.bundle.js");
const workerTimeoutMs = Number(process.env.BOT_SDK_LIVE_CONTAINER_TIMEOUT_MS ?? 15000);

const docker = dockerAvailability();
if (!docker.ok) {
  console.warn(`skipping hosted live container smoke: ${docker.reason}`);
  process.exit(0);
}

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

const requests = { snapshots: 0, trades: 0, ordersCurrent: 0, submit: 0, availability: 0 };
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", "http://127.0.0.1");
  if (url.pathname === "/api/v1/data/availability") {
    requests.availability += 1;
    return json(response, {
      generatedAt: "2026-07-04T14:30:00.000Z",
      source: "container-live-smoke",
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

await listenOnHost(server);

try {
  const address = server.address();
  const port = typeof address === "object" && address !== null ? address.port : undefined;
  assert.equal(typeof port, "number", "host smoke server did not expose a TCP port");
  const run = await spawnRun([
    "scripts/dev/bot-sdk-hosted-worker-run.mjs",
    artifactPath,
    "packages/bot-sdk/fixtures/aapl-multi-tick.json",
    "--isolation=container",
    "--read-mode=live",
    `--venue-url=http://host.docker.internal:${port}`,
    "--container-network=bridge",
    `--wall-timeout-ms=${workerTimeoutMs}`,
  ]);
  assert.equal(run.status, 0, `hosted live container run failed\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}`);
  const report = JSON.parse(run.stdout);
  assert.equal(report.status, "completed");
  assert.equal(report.readMode, "live");
  assert.equal(report.dataAvailability.source, "container-live-smoke");
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.ticks[0].venueResponses.length, 2);
  assert.equal(requests.ordersCurrent, 4);
  assert.equal(requests.submit, 6);
  assert.ok(requests.snapshots >= 3);
  console.log("hosted live container smoke passed");
} finally {
  await new Promise((resolve) => server.close(resolve));
}

function dockerAvailability() {
  const result = spawnSync("docker", ["info", "--format", "{{.ServerVersion}}"], {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: 5000,
  });
  if (result.status === 0) {
    return { ok: true };
  }
  const reason = (result.stderr || result.stdout || result.error?.message || "docker unavailable").trim();
  return { ok: false, reason };
}

function listenOnHost(serverValue) {
  return new Promise((resolve, reject) => {
    serverValue.once("error", reject);
    serverValue.listen(0, "0.0.0.0", () => {
      serverValue.off("error", reject);
      resolve();
    });
  });
}

function spawnRun(args) {
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
