import { canonicalHash } from "./arena-policy-resolver.mjs";

export function reconcileArenaEconomics(botResults, economicPolicy) {
  const tolerance = decimal(economicPolicy.reconciliation.tolerance);
  const unsupportedPolicyTerms = nonzeroPolicyTerms(economicPolicy);
  const actors = [...botResults]
    .sort((left, right) => left.botId.localeCompare(right.botId))
    .map((result) => actorRow(result, economicPolicy));
  const inventoryGapByInstrument = {};
  for (const actor of actors) {
    for (const [instrumentId, quantity] of Object.entries(actor.netQuantityByInstrument)) {
      inventoryGapByInstrument[instrumentId] = fixed((inventoryGapByInstrument[instrumentId] ?? 0) + quantity);
    }
  }
  const cashTransferGap = fixed(actors.reduce((sum, actor) => sum + actor.tradingCashDelta, 0));
  const unexplainedInventory = Object.fromEntries(
    Object.entries(inventoryGapByInstrument)
      .filter(([, quantity]) => Math.abs(quantity) > 1e-9)
      .sort(([left], [right]) => left.localeCompare(right)),
  );
  const duplicateActors = duplicateActorIdentities(actors);
  const policyViolations = actors.flatMap((actor) => {
    const violations = [];
    if (Math.abs(actor.pnlConsistencyGap) > tolerance) violations.push(`${actor.botId}:pnl_total_mismatch`);
    if (actor.ledger === "competition" && economicPolicy.competitionLedger.allowNegativeCash === false && actor.finalCash < -tolerance) {
      violations.push(`${actor.botId}:negative_competition_cash`);
    }
    return violations;
  });
  if (duplicateActors.length > 0) policyViolations.push(...duplicateActors.map((identity) => `${identity}:duplicate_actor`));
  const complete = actors.every((actor) => actor.pnlAvailable);
  const balanced = !economicPolicy.reconciliation.requireBalancedTransfers
    || (Math.abs(cashTransferGap) <= tolerance && Object.keys(unexplainedInventory).length === 0);
  const compliant = policyViolations.length === 0;
  const evidence = {
    schemaVersion: "reef.arena.economicReconciliation.v1",
    economicPolicyVersion: economicPolicy.version,
    economicPolicyHash: economicPolicy.contentHash,
    currency: economicPolicy.currency,
    tolerance,
    complete,
    supported: unsupportedPolicyTerms.length === 0,
    status: complete && balanced && compliant && unsupportedPolicyTerms.length === 0 ? "pass" : "fail",
    unsupportedPolicyTerms,
    policyViolations,
    actors,
    ledgers: {
      competition: summarizeLedger(actors, "competition"),
      house: summarizeLedger(actors, "house"),
    },
    declaredSources: declaredSources(actors, economicPolicy),
    declaredSinks: economicPolicy.sinks.filter((sink) => sink.enabled),
    cashTransferGap,
    inventoryGapByInstrument: unexplainedInventory,
  };
  return { ...evidence, reconciliationHash: canonicalHash(evidence) };
}

function actorRow(result, policy) {
  const actorClass = result.actorClass ?? "competitor";
  const ledger = actorClass === "competitor" ? "competition" : "house";
  const startingCash = startingCashFor(actorClass, policy);
  const pnl = result.tradingMetrics?.pnl ?? {};
  const tradingCashDelta = decimal(pnl.cash ?? 0);
  const inventoryValue = decimal(pnl.inventoryValue ?? 0);
  const totalPnl = decimal(pnl.total ?? 0);
  return {
    botId: result.botId,
    versionId: result.versionId,
    actorClass,
    ledger,
    startingCash,
    tradingCashDelta,
    finalCash: fixed(startingCash + tradingCashDelta),
    inventoryValue,
    finalEquity: fixed(startingCash + tradingCashDelta + inventoryValue),
    totalPnl,
    pnlConsistencyGap: fixed(totalPnl - tradingCashDelta - inventoryValue),
    pnlAvailable: pnl.available === true,
    netQuantityByInstrument: sortedNumericRecord(result.tradingMetrics?.inventory?.netQuantityByInstrument ?? {}),
  };
}

function duplicateActorIdentities(actors) {
  const seen = new Set();
  const duplicates = new Set();
  for (const actor of actors) {
    const identity = `${actor.botId}/${actor.versionId}`;
    if (seen.has(identity)) duplicates.add(identity);
    seen.add(identity);
  }
  return [...duplicates].sort();
}

function startingCashFor(actorClass, policy) {
  if (actorClass === "competitor") return decimal(policy.competitionLedger.startingCashPerCompetitor);
  if (actorClass === "npc_flow") return decimal(policy.houseLedger.npcStartingCash);
  return decimal(policy.houseLedger.marketMakerStartingCash);
}

function summarizeLedger(actors, ledger) {
  const selected = actors.filter((actor) => actor.ledger === ledger);
  return {
    actorCount: selected.length,
    startingCash: fixed(selected.reduce((sum, actor) => sum + actor.startingCash, 0)),
    tradingCashDelta: fixed(selected.reduce((sum, actor) => sum + actor.tradingCashDelta, 0)),
    finalCash: fixed(selected.reduce((sum, actor) => sum + actor.finalCash, 0)),
    inventoryValue: fixed(selected.reduce((sum, actor) => sum + actor.inventoryValue, 0)),
    finalEquity: fixed(selected.reduce((sum, actor) => sum + actor.finalEquity, 0)),
  };
}

function declaredSources(actors, policy) {
  const counts = actors.reduce((out, actor) => ({ ...out, [actor.actorClass]: (out[actor.actorClass] ?? 0) + 1 }), {});
  return policy.sources.filter((source) => source.enabled).map((source) => ({
    ...source,
    amount: source.code === "competitor_starting_cash"
      ? fixed((counts.competitor ?? 0) * decimal(policy.competitionLedger.startingCashPerCompetitor))
      : source.code === "market_maker_starting_cash"
        ? fixed(((counts.house_market_maker ?? 0) + (counts.benchmark ?? 0)) * decimal(policy.houseLedger.marketMakerStartingCash))
        : source.code === "npc_starting_cash"
          ? fixed((counts.npc_flow ?? 0) * decimal(policy.houseLedger.npcStartingCash))
          : source.code === "liquidity_subsidy_budget"
            ? decimal(policy.houseLedger.subsidyBudget)
            : 0,
  }));
}

function nonzeroPolicyTerms(policy) {
  return [
    ["fees.makerBps", policy.fees.makerBps],
    ["fees.takerBps", policy.fees.takerBps],
    ["fees.cancelFee", policy.fees.cancelFee],
    ["fees.borrowBps", policy.fees.borrowBps],
    ["fees.liquidationPenaltyBps", policy.fees.liquidationPenaltyBps],
    ["rebates.makerBps", policy.rebates.makerBps],
  ].filter(([, value]) => decimal(value) !== 0).map(([name]) => name);
}

function sortedNumericRecord(value) {
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, number]) => [key, decimal(number)]));
}

function decimal(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`economic reconciliation value is not finite: ${value}`);
  return fixed(number);
}

function fixed(value) {
  return Number(Number(value).toFixed(8));
}
