import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveEconomicPolicy } from "./lib/arena-policy-resolver.mjs";
import { reconcileArenaEconomics } from "./lib/arena-economic-reconciliation.mjs";

const policy = resolveEconomicPolicy(JSON.parse(readFileSync(
  "packages/scenario-definitions/arena/economics/preview-zero-fee-v1.json",
  "utf8",
)));
const results = [
  bot("competitor-1", "competitor", -100, 100, { AAPL: 1 }, 0, "TAKER"),
  bot("house-1", "house_market_maker", 100, -100, { AAPL: -1 }, 0, "MAKER"),
];
const first = reconcileArenaEconomics(results, policy);
const second = reconcileArenaEconomics([...results].reverse(), policy);
assert.equal(first.status, "pass");
assert.equal(first.cashTransferGap, 0);
assert.deepEqual(first.inventoryGapByInstrument, {});
assert.deepEqual(first.policyViolations, []);
assert.equal(first.ledgers.competition.startingCash, 1_000_000);
assert.equal(first.ledgers.house.startingCash, 10_000_000);
assert.equal(first.reconciliationHash, second.reconciliationHash);

const missingCounterparty = reconcileArenaEconomics(results.slice(0, 1), policy);
assert.equal(missingCounterparty.status, "fail");
assert.equal(missingCounterparty.cashTransferGap, -100);
assert.deepEqual(missingCounterparty.inventoryGapByInstrument, { AAPL: 1 });

const unavailableReadback = structuredClone(results);
unavailableReadback[0].tradingMetrics.pnl.available = false;
const incomplete = reconcileArenaEconomics(unavailableReadback, policy);
assert.equal(incomplete.complete, false);
assert.equal(incomplete.status, "fail");

const inconsistentPnl = reconcileArenaEconomics([
  bot("competitor-1", "competitor", -100, 100, { AAPL: 1 }, 1),
  bot("house-1", "house_market_maker", 100, -100, { AAPL: -1 }),
], policy);
assert.equal(inconsistentPnl.status, "fail");
assert.deepEqual(inconsistentPnl.policyViolations, ["competitor-1:pnl_total_mismatch"]);

const duplicateActor = reconcileArenaEconomics([...results, results[0]], policy);
assert.equal(duplicateActor.status, "fail");
assert.deepEqual(duplicateActor.policyViolations, ["competitor-1/v1:duplicate_actor"]);

const feePolicy = resolveEconomicPolicy(JSON.parse(readFileSync(
  "packages/scenario-definitions/arena/economics/preview-balanced-fee-v1.json",
  "utf8",
)));
const balancedFees = reconcileArenaEconomics(results, feePolicy);
assert.equal(balancedFees.status, "pass");
assert.deepEqual(balancedFees.unsupportedPolicyTerms, []);
assert.equal(balancedFees.actors.find((actor) => actor.botId === "competitor-1").takerFeesPaid, 0.02);
assert.equal(balancedFees.actors.find((actor) => actor.botId === "house-1").rebatesReceived, 0.005);
assert.equal(balancedFees.economicFacility.cashDelta, 0.015);
assert.equal(balancedFees.reconciledCashGap, 0);
assert.equal(balancedFees.declaredSinks[0].amount, 0.02);

const missingRole = structuredClone(results);
missingRole[0].tradingMetrics.executions.liquidityRoleComplete = false;
const incompleteFees = reconcileArenaEconomics(missingRole, feePolicy);
assert.equal(incompleteFees.status, "fail");
assert.deepEqual(incompleteFees.policyViolations, ["competitor-1:liquidity_role_incomplete"]);

const subsidyPolicy = resolveEconomicPolicy(JSON.parse(readFileSync(
  "packages/scenario-definitions/arena/economics/preview-liquidity-subsidy-v1.json",
  "utf8",
)));
const subsidy = reconcileArenaEconomics(results, subsidyPolicy);
assert.equal(subsidy.status, "pass");
assert.equal(subsidy.economicFacility.rebatePayments, 0.01);
assert.equal(subsidy.economicFacility.subsidyBudgetRemaining, 249_999.99);

const unsupportedPolicyFixture = JSON.parse(readFileSync(
  "packages/scenario-definitions/arena/economics/preview-balanced-fee-v1.json",
  "utf8",
));
unsupportedPolicyFixture.fees.cancelFee = "1.00";
const unsupported = reconcileArenaEconomics(results, resolveEconomicPolicy(unsupportedPolicyFixture));
assert.equal(unsupported.status, "fail");
assert.deepEqual(unsupported.unsupportedPolicyTerms, ["fees.cancelFee"]);

console.log("arena economic reconciliation checks passed");

function bot(botId, actorClass, cash, inventoryValue, netQuantityByInstrument, total = cash + inventoryValue, liquidityRole = "UNSPECIFIED") {
  const notional = Math.abs(cash);
  return {
    botId,
    versionId: "v1",
    actorClass,
    tradingMetrics: {
      pnl: { available: true, cash, inventoryValue, total },
      executions: {
        fillCount: notional === 0 ? 0 : 1,
        makerNotional: liquidityRole === "MAKER" ? notional : 0,
        takerNotional: liquidityRole === "TAKER" ? notional : 0,
        liquidityRoleComplete: liquidityRole !== "UNSPECIFIED",
      },
      inventory: { netQuantityByInstrument },
    },
  };
}
