import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;
const commands = new Map();
const receivedCommands = [];
const referenceWrites = [];

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
    return json(res, commands.has(commandId) ? 200 : 404, commands.get(commandId) ?? { error: "not found" });
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
  return json(res, 404, { error: "not found", path: url.pathname });
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
const baseUrl = `http://127.0.0.1:${address.port}`;

try {
  await run("bun", [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=live",
    `--venue-url=${baseUrl}`,
    "--seed-reference",
    "--out=/tmp/reef-arena-local-tick-run-live-test.json",
  ]);

  assert.equal(referenceWrites.filter((write) => write.path === "/reference/instruments").length, 4);
  assert.equal(receivedCommands.length, 14);
  assert.ok(Array.from(commands.values()).every((command) => command.status === "COMPLETED"));
  console.log("arena local tick live path checks passed");
} finally {
  await new Promise((resolve) => server.close(resolve));
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
