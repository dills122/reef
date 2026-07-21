import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveEconomicPolicy } from "./lib/arena-policy-resolver.mjs";
import { reconcileArenaEconomics } from "./lib/arena-economic-reconciliation.mjs";

const policy = resolveEconomicPolicy(JSON.parse(readFileSync(
  "packages/scenario-definitions/arena/economics/preview-zero-fee-v1.json",
  "utf8",
)));
const results = [
  bot("competitor-1", "competitor", -100, 100, { AAPL: 1 }),
  bot("house-1", "house_market_maker", 100, -100, { AAPL: -1 }),
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
const unsupported = reconcileArenaEconomics(results, feePolicy);
assert.equal(unsupported.status, "fail");
assert.deepEqual(unsupported.unsupportedPolicyTerms, ["fees.takerBps", "rebates.makerBps"]);

console.log("arena economic reconciliation checks passed");

function bot(botId, actorClass, cash, inventoryValue, netQuantityByInstrument, total = cash + inventoryValue) {
  return {
    botId,
    versionId: "v1",
    actorClass,
    tradingMetrics: {
      pnl: { available: true, cash, inventoryValue, total },
      inventory: { netQuantityByInstrument },
    },
  };
}
