#!/usr/bin/env node
import { mkdir, writeFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import { dirname } from "node:path";
import { deriveDevUrls, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const args = parseArgs(process.argv.slice(2));
const { runtimeUrl: defaultRuntimeUrl } = deriveDevUrls();
const runtimeUrl = (args.runtimeUrl ?? defaultRuntimeUrl).replace(/\/$/, "");
const adminApiToken = args.token ?? process.env.ADMIN_API_TOKEN ?? "";
const actorId = args.actorId ?? process.env.ADMIN_ACTOR_ID ?? "settlement-seeder";
const scenarioRunId = args.scenarioRunId ?? process.env.SCENARIO_RUN_ID ?? "p2-settlement-live";
const mode = args.mode ?? process.env.P2_SETTLEMENT_FACTS_MODE ?? "exception";
const factIdScope = args.factIdScope ?? process.env.P2_SETTLEMENT_FACT_ID_SCOPE ?? "stable";
const facts = p2SettlementFacts(scenarioRunId, mode, factIdScope);
const settlementFactsPath = "/admin/v1/settlement/facts";

if (args.dryRun) {
  console.log(JSON.stringify(facts, null, 2));
  process.exit(0);
}

const posted = await postJson(`${runtimeUrl}${settlementFactsPath}`, facts, requestHeaders(settlementFactsPath));
const fetched = await fetchSettlementFacts(posted);
validateReadback(fetched, scenarioRunId);
if (args.reportOut) {
  await mkdir(dirname(args.reportOut), { recursive: true });
  await writeFile(args.reportOut, `${JSON.stringify(fetched, null, 2)}\n`);
}

console.log(
  JSON.stringify(
    {
      status: "ok",
      runtimeUrl,
      settlementFactsPath,
      actorId,
      mode,
      factIdScope,
      scenarioRunId,
      postedStatus: posted.status ?? "ok",
      reportOut: args.reportOut ?? "",
      obligationCount: factCount(fetched, "obligations"),
      allocationCount: factCount(fetched, "allocations"),
      confirmationCount: factCount(fetched, "confirmations"),
      affirmationCount: factCount(fetched, "affirmations"),
      clearingSubmissionCount: factCount(fetched, "clearingSubmissions"),
      clearingAcceptanceCount: factCount(fetched, "clearingAcceptances"),
      clearingRejectionCount: factCount(fetched, "clearingRejections"),
      novationCount: factCount(fetched, "novations"),
      instructionCount: factCount(fetched, "instructions"),
      attemptCount: factCount(fetched, "attempts"),
      settlementCount: factCount(fetched, "settlements"),
      breakCount: factCount(fetched, "breaks"),
      repairCount: factCount(fetched, "repairs"),
      resolutionCount: factCount(fetched, "resolutions"),
    },
    null,
    2,
  ),
);

function factCount(facts, key) {
  return Array.isArray(facts[key]) ? facts[key].length : 0;
}

async function fetchSettlementFacts(posted) {
  try {
    return await fetchJson(`${runtimeUrl}/api/v1/settlement/facts/${encodeURIComponent(scenarioRunId)}`);
  } catch (error) {
    if (args.reportOut && error.statusCode === 404 && posted?.facts) {
      console.warn(`public settlement facts read unavailable; wrote posted facts artifact: ${args.reportOut}`);
      return posted.facts;
    }
    throw error;
  }
}

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
  };
}

async function postJson(url, body, headers) {
  return requestJson(url, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
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

function validateReadback(facts, scenarioRunId) {
  const settledMode = hasSettledChain(facts);
  const counts = settledMode
    ? [
        ["obligations", 1],
        ["allocations", 1],
        ["confirmations", 1],
        ["affirmations", 1],
        ["clearingSubmissions", 1],
        ["clearingAcceptances", 1],
        ["clearingRejections", 0],
        ["novations", 1],
        ["instructions", 1],
        ["attempts", 1],
        ["settlements", 1],
      ]
    : [
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

function hasSettledChain(facts) {
  return Array.isArray(facts.settlements) && facts.settlements.length > 0;
}

function p2SettlementFacts(scenarioRunId, mode, factIdScope) {
  if (mode === "settled") {
    return p2SettledPostTradeFacts(scenarioRunId, factIdScope);
  }
  if (mode !== "exception") {
    throw new Error(`unknown P2 settlement facts mode: ${mode}`);
  }
  return p2ExceptionFacts(scenarioRunId);
}

function p2ExceptionFacts(scenarioRunId) {
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

function p2SettledPostTradeFacts(scenarioRunId, factIdScope) {
  if (!["stable", "run"].includes(factIdScope)) {
    throw new Error(`unknown P2 settlement fact id scope: ${factIdScope}`);
  }
  const profileId = "instant-post-trade-v1";
  const policyVersion = 4;
  const correlationId = `${scenarioRunId}-corr-1`;
  const tradeId = "trade-p2_settlement_break_repair-ord-001-p2_settlement_break_repair-ord-002-1";
  const idPrefix = factIdScope === "run" ? `${scenarioRunId}-` : "";
  const obligationId = `${idPrefix}settlement-obligation-${tradeId}`;
  const allocationId = `settlement-allocation-${obligationId}`;
  const confirmationId = `settlement-confirmation-${obligationId}`;
  const affirmationId = `settlement-affirmation-${obligationId}`;
  const clearingSubmissionId = `settlement-clearing-submission-${obligationId}`;
  const clearingAcceptanceId = `settlement-clearing-acceptance-${obligationId}`;
  const novationId = `settlement-novation-${obligationId}`;
  const instructionId = `settlement-instruction-${obligationId}-1`;
  const attemptId = `settlement-attempt-${obligationId}-1`;
  return {
    scenarioRunId,
    obligations: [
      {
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: tradeId,
        tradeId,
        buyerParticipantId: "BUY_SIDE_1",
        sellerParticipantId: "SELL_SIDE_1",
        instrumentId: "XYZ",
        quantity: "1000",
        cashAmount: "150000000000000",
        currency: "USD",
        state: "OBLIGATION_CREATED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    allocations: [
      {
        settlementAllocationId: allocationId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: obligationId,
        tradeId,
        buyOrderId: "p2_settlement_break_repair-ord-001",
        sellOrderId: "p2_settlement_break_repair-ord-002",
        buyerAccountId: "BUY_SIDE_1_MAIN",
        sellerAccountId: "SELL_SIDE_1_MAIN",
        quantity: "1000",
        state: "ALLOCATION_PROPOSED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    confirmations: [
      {
        settlementConfirmationId: confirmationId,
        settlementAllocationId: allocationId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: allocationId,
        tradeId,
        state: "CONFIRMATION_GENERATED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    affirmations: [
      {
        settlementAffirmationId: affirmationId,
        settlementConfirmationId: confirmationId,
        settlementAllocationId: allocationId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: confirmationId,
        tradeId,
        actorType: "SYSTEM",
        actorId: "instant-post-trade",
        state: "AFFIRMATION_ACCEPTED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    clearingSubmissions: [
      {
        settlementClearingSubmissionId: clearingSubmissionId,
        settlementObligationId: obligationId,
        settlementAffirmationId: affirmationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: affirmationId,
        state: "CLEARING_SUBMITTED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    clearingAcceptances: [
      {
        settlementClearingAcceptanceId: clearingAcceptanceId,
        settlementClearingSubmissionId: clearingSubmissionId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: clearingSubmissionId,
        state: "CLEARING_ACCEPTED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    clearingRejections: [],
    novations: [
      {
        settlementNovationId: novationId,
        settlementClearingAcceptanceId: clearingAcceptanceId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: clearingAcceptanceId,
        state: "NOVATION_RECORDED",
        occurredAt: "2026-03-14T18:00:04Z",
      },
    ],
    instructions: [
      {
        settlementInstructionId: instructionId,
        settlementObligationId: obligationId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: novationId,
        instructionType: "DVP",
        state: "INSTRUCTION_CREATED",
        occurredAt: "2026-03-14T18:00:05Z",
      },
    ],
    attempts: [
      {
        settlementAttemptId: attemptId,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: instructionId,
        attemptNumber: 1,
        state: "ATTEMPT_STARTED",
        occurredAt: "2026-03-14T18:00:05Z",
      },
    ],
    legOutcomes: [
      {
        settlementLegOutcomeId: `${attemptId}-cash`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        legType: "CASH",
        state: "LEG_SUCCEEDED",
        occurredAt: "2026-03-14T18:00:06Z",
      },
      {
        settlementLegOutcomeId: `${attemptId}-security`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        legType: "SECURITY",
        state: "LEG_SUCCEEDED",
        occurredAt: "2026-03-14T18:00:06Z",
      },
    ],
    ledgerEntries: [
      {
        ledgerEntryId: `settlement-ledger-${attemptId}-buyer-cash-debit`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        participantId: "BUY_SIDE_1",
        accountId: "BUY_SIDE_1_MAIN",
        assetType: "CASH",
        assetId: "USD",
        direction: "DEBIT",
        quantity: "150000000000000",
        occurredAt: "2026-03-14T18:00:06Z",
      },
      {
        ledgerEntryId: `settlement-ledger-${attemptId}-seller-cash-credit`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        participantId: "SELL_SIDE_1",
        accountId: "SELL_SIDE_1_MAIN",
        assetType: "CASH",
        assetId: "USD",
        direction: "CREDIT",
        quantity: "150000000000000",
        occurredAt: "2026-03-14T18:00:06Z",
      },
      {
        ledgerEntryId: `settlement-ledger-${attemptId}-seller-security-debit`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        participantId: "SELL_SIDE_1",
        accountId: "SELL_SIDE_1_MAIN",
        assetType: "SECURITY",
        assetId: "XYZ",
        direction: "DEBIT",
        quantity: "1000",
        occurredAt: "2026-03-14T18:00:06Z",
      },
      {
        ledgerEntryId: `settlement-ledger-${attemptId}-buyer-security-credit`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        participantId: "BUY_SIDE_1",
        accountId: "BUY_SIDE_1_MAIN",
        assetType: "SECURITY",
        assetId: "XYZ",
        direction: "CREDIT",
        quantity: "1000",
        occurredAt: "2026-03-14T18:00:06Z",
      },
    ],
    settlements: [
      {
        settlementId: `settlement-final-${obligationId}`,
        settlementObligationId: obligationId,
        settlementInstructionId: instructionId,
        settlementAttemptId: attemptId,
        scenarioRunId,
        postTradeProfileId: profileId,
        postTradePolicyVersion: policyVersion,
        correlationId,
        causationId: attemptId,
        settlementState: "SETTLED",
        occurredAt: "2026-03-14T18:00:06Z",
      },
    ],
  };
}
