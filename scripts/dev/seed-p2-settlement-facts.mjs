#!/usr/bin/env node
import { deriveDevUrls, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const args = parseArgs(process.argv.slice(2));
const { runtimeUrl: defaultRuntimeUrl } = deriveDevUrls();
const runtimeUrl = (args.runtimeUrl ?? defaultRuntimeUrl).replace(/\/$/, "");
const adminApiToken = args.token ?? process.env.ADMIN_API_TOKEN ?? "";
const actorId = args.actorId ?? process.env.ADMIN_ACTOR_ID ?? "settlement-seeder";
const scenarioRunId = args.scenarioRunId ?? process.env.SCENARIO_RUN_ID ?? "p2-settlement-live";
const facts = p2SettlementFacts(scenarioRunId);

if (args.dryRun) {
  console.log(JSON.stringify(facts, null, 2));
  process.exit(0);
}

const posted = await postJson(`${runtimeUrl}/admin/v1/settlement/facts`, facts);
const fetched = await fetchJson(`${runtimeUrl}/api/v1/settlement/facts/${encodeURIComponent(scenarioRunId)}`);
validateReadback(fetched, scenarioRunId);

console.log(
  JSON.stringify(
    {
      status: "ok",
      runtimeUrl,
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

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "X-Reef-Actor-Id": actorId,
      ...(adminApiToken ? { authorization: `Bearer ${adminApiToken}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`POST ${url} failed with ${response.status}: ${text}`);
  }
  return text ? JSON.parse(text) : {};
}

async function fetchJson(url) {
  const response = await fetch(url);
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`GET ${url} failed with ${response.status}: ${text}`);
  }
  return text ? JSON.parse(text) : {};
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
