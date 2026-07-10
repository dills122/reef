#!/usr/bin/env node
import { spawn } from "node:child_process";
import http from "node:http";
import https from "node:https";
import { composeArgs } from "./lib/compose-utils.mjs";
import { deriveDevUrls, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const args = parseArgs(process.argv.slice(2));
const { runtimeUrl: defaultRuntimeUrl } = deriveDevUrls();
const runtimeUrl = (args.runtimeUrl ?? defaultRuntimeUrl).replace(/\/$/, "");
const adminApiToken = args.token ?? process.env.ADMIN_API_TOKEN ?? "";
const actorId = args.actorId ?? process.env.ADMIN_ACTOR_ID ?? "settlement-seeder";
const scenarioRunId = args.scenarioRunId ?? process.env.SCENARIO_RUN_ID ?? "p2-settlement-live";
const facts = p2SettlementFacts(scenarioRunId);
const useAdminGateway = args.adminGateway || adminApiToken.trim() !== "";
const settlementFactsPath = useAdminGateway
  ? "/admin/v1/settlement/facts"
  : "/internal/admin/settlement/facts";

if (args.dryRun) {
  console.log(JSON.stringify(facts, null, 2));
  process.exit(0);
}

const posted = await postJson(`${runtimeUrl}${settlementFactsPath}`, facts, requestHeaders(settlementFactsPath));
const fetched = await fetchJson(`${runtimeUrl}/api/v1/settlement/facts/${encodeURIComponent(scenarioRunId)}`);
validateReadback(fetched, scenarioRunId);

console.log(
  JSON.stringify(
    {
      status: "ok",
      runtimeUrl,
      settlementFactsPath,
      actorId,
      scenarioRunId,
      postedStatus: posted.status ?? "ok",
      obligationCount: fetched.obligations.length,
      breakCount: fetched.breaks.length,
      repairCount: fetched.repairs.length,
      resolutionCount: fetched.resolutions.length,
    },
    null,
    2,
  ),
);

function parseArgs(argv) {
  const out = {};
  for (const arg of argv) {
    if (arg === "--dry-run") {
      out.dryRun = true;
      continue;
    }
    if (arg === "--admin-gateway") {
      out.adminGateway = true;
      continue;
    }
    const match = arg.match(/^--([^=]+)=(.*)$/);
    if (!match) {
      throw new Error(`unknown argument: ${arg}`);
    }
    out[toCamel(match[1])] = match[2];
  }
  return out;
}

function toCamel(value) {
  return value.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
}

function requestHeaders(path) {
  return {
    "content-type": "application/json",
    "X-Reef-Actor-Id": actorId,
    ...(path.startsWith("/admin/v1/") && adminApiToken.trim() !== "" ? { Authorization: `Bearer ${adminApiToken}` } : {}),
    ...(path.startsWith("/internal/") ? { "X-Reef-Internal-Route": "true" } : {}),
  };
}

async function postJson(url, body, headers) {
  try {
    return await requestJson(url, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
  } catch (error) {
    if (shouldRetryInternalPostViaContainer(url, error)) {
      return postInternalSettlementFactsViaContainer(body);
    }
    throw error;
  }
}

async function fetchJson(url) {
  return requestJson(url, { headers: { "X-Client-Id": "seed-p2-settlement-facts" } });
}

async function requestJson(url, options = {}) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  const body = options.body ?? "";
  const headers = {
    ...(options.headers ?? {}),
    ...(body ? { "content-length": Buffer.byteLength(body) } : {}),
  };
  return new Promise((resolve, reject) => {
    const request = transport.request(
      parsed,
      {
        method: options.method ?? "GET",
        headers,
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          if (response.statusCode < 200 || response.statusCode >= 300) {
            const error = new Error(`${options.method ?? "GET"} ${url} failed with ${response.statusCode}: ${text}`);
            error.statusCode = response.statusCode;
            error.body = text;
            reject(error);
            return;
          }
          try {
            resolve(text ? JSON.parse(text) : {});
          } catch (error) {
            reject(new Error(`${options.method ?? "GET"} ${url} returned invalid JSON: ${error.message}`));
          }
        });
      },
    );
    request.on("error", reject);
    if (body) {
      request.write(body);
    }
    request.end();
  });
}

function shouldRetryInternalPostViaContainer(url, error) {
  const parsed = new URL(url);
  return parsed.pathname === "/internal/admin/settlement/facts" &&
    error.statusCode === 403 &&
    String(error.body ?? "").includes("internal HTTP route requires loopback access");
}

async function postInternalSettlementFactsViaContainer(body) {
  const stdout = await runWithInput(
    "docker",
    composeArgs([
      "exec",
      "-T",
      "platform-api",
      "curl",
      "-sS",
      "-X",
      "POST",
      "-H",
      "Content-Type: application/json",
      "-H",
      "X-Reef-Internal-Route: true",
      "--data-binary",
      "@-",
      "http://127.0.0.1:8080/internal/admin/settlement/facts",
    ]),
    JSON.stringify(body),
  );
  return stdout ? JSON.parse(stdout) : {};
}

function runWithInput(command, commandArgs, input) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, { stdio: ["pipe", "pipe", "pipe"] });
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      const stdoutText = Buffer.concat(stdout).toString("utf8").trim();
      const stderrText = Buffer.concat(stderr).toString("utf8").trim();
      if (code !== 0) {
        reject(new Error(`${command} ${commandArgs.join(" ")} failed with ${code}: ${stderrText || stdoutText}`));
        return;
      }
      resolve(stdoutText);
    });
    child.stdin.end(input);
  });
}

function validateReadback(facts, scenarioRunId) {
  const counts = [
    ["obligations", 1],
    ["breaks", 1],
    ["repairs", 1],
    ["resolutions", 1],
  ];
  if (facts.scenarioRunId !== scenarioRunId) {
    throw new Error(`readback scenarioRunId mismatch: ${facts.scenarioRunId}`);
  }
  for (const [key, expected] of counts) {
    if (!Array.isArray(facts[key]) || facts[key].length !== expected) {
      throw new Error(`readback ${key} count mismatch: got ${facts[key]?.length ?? 0}, want ${expected}`);
    }
  }
}

function p2SettlementFacts(scenarioRunId) {
  return {
    scenarioRunId,
    obligations: [
      {
        settlementObligationId: `${scenarioRunId}-obl-1`,
        scenarioRunId,
        correlationId: `${scenarioRunId}-corr-1`,
        causationId: `${scenarioRunId}-trade-1`,
        tradeId: `${scenarioRunId}-trade-1`,
        buyerParticipantId: "BUY_SIDE_1",
        sellerParticipantId: "SELL_SIDE_1",
        instrumentId: "XYZ",
        quantity: "1000",
        cashAmount: "100000.00",
        currency: "USD",
        state: "OBLIGATION_CREATED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    breaks: [
      {
        settlementBreakId: `${scenarioRunId}-break-1`,
        settlementObligationId: `${scenarioRunId}-obl-1`,
        scenarioRunId,
        correlationId: `${scenarioRunId}-corr-1`,
        causationId: `${scenarioRunId}-cash-leg-failed-1`,
        reason: "CASH_LEG_FAILED",
        state: "BROKEN",
        occurredAt: "2026-03-14T18:00:05Z",
      },
    ],
    repairs: [
      {
        settlementRepairId: `${scenarioRunId}-repair-1`,
        settlementBreakId: `${scenarioRunId}-break-1`,
        settlementObligationId: `${scenarioRunId}-obl-1`,
        scenarioRunId,
        correlationId: `${scenarioRunId}-corr-1`,
        causationId: `${scenarioRunId}-repair-command-1`,
        repairAction: "POST_CASH_LEG_REPAIR",
        actorType: "USER",
        actorId: "settlement-ops-1",
        occurredAt: "2026-03-14T18:00:06Z",
      },
    ],
    resolutions: [
      {
        settlementResolutionId: `${scenarioRunId}-resolution-1`,
        settlementObligationId: `${scenarioRunId}-obl-1`,
        settlementBreakId: `${scenarioRunId}-break-1`,
        settlementRepairId: `${scenarioRunId}-repair-1`,
        scenarioRunId,
        correlationId: `${scenarioRunId}-corr-1`,
        causationId: `${scenarioRunId}-repair-command-1`,
        settlementState: "RESOLVED",
        exceptionState: "RESOLVED",
        occurredAt: "2026-03-14T18:00:07Z",
      },
    ],
  };
}
