import { canonicalHash } from "./arena-policy-resolver.mjs";

export function reconcileArenaEconomics(botResults, economicPolicy) {
  const tolerance = decimal(economicPolicy.reconciliation.tolerance);
  const unsupportedPolicyTerms = nonzeroPolicyTerms(economicPolicy);
  const requiresLiquidityRoles = roleDependentPolicyEnabled(economicPolicy);
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
  const makerNotional = fixed(actors.reduce((sum, actor) => sum + actor.makerNotional, 0));
  const takerNotional = fixed(actors.reduce((sum, actor) => sum + actor.takerNotional, 0));
  const liquidityRoleNotionalGap = fixed(makerNotional - takerNotional);
  const feeReceipts = fixed(actors.reduce((sum, actor) => sum + actor.feesPaid, 0));
  const rebatePayments = fixed(actors.reduce((sum, actor) => sum + actor.rebatesReceived, 0));
  const facilityCashDelta = fixed(feeReceipts - rebatePayments);
  const reconciledCashGap = fixed(actors.reduce((sum, actor) => sum + actor.economicCashDelta, 0) + facilityCashDelta);
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
  if (requiresLiquidityRoles) {
    policyViolations.push(...actors.filter((actor) => !actor.liquidityRoleComplete).map((actor) => `${actor.botId}:liquidity_role_incomplete`));
  }
  if (economicPolicy.rebates.fundingSource === "none" && rebatePayments > tolerance) {
    policyViolations.push("rebates:funding_source_none");
  }
  if (economicPolicy.rebates.fundingSource === "taker_fees" && rebatePayments - actors.reduce((sum, actor) => sum + actor.takerFeesPaid, 0) > tolerance) {
    policyViolations.push("rebates:exceed_taker_fees");
  }
  if (economicPolicy.rebates.fundingSource === "house_subsidy" && rebatePayments - decimal(economicPolicy.houseLedger.subsidyBudget) > tolerance) {
    policyViolations.push("rebates:exceed_house_subsidy_budget");
  }
  const complete = actors.every((actor) => actor.pnlAvailable && (!requiresLiquidityRoles || actor.liquidityRoleComplete));
  const balanced = !economicPolicy.reconciliation.requireBalancedTransfers
    || (Math.abs(cashTransferGap) <= tolerance
      && Math.abs(reconciledCashGap) <= tolerance
      && (!requiresLiquidityRoles || Math.abs(liquidityRoleNotionalGap) <= tolerance)
      && Object.keys(unexplainedInventory).length === 0);
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
    requiresLiquidityRoles,
    actors,
    ledgers: {
      competition: summarizeLedger(actors, "competition"),
      house: summarizeLedger(actors, "house"),
    },
    declaredSources: declaredSources(actors, economicPolicy),
    declaredSinks: declaredSinks(economicPolicy, actors),
    economicFacility: {
      feeReceipts,
      rebatePayments,
      cashDelta: facilityCashDelta,
      subsidyBudget: decimal(economicPolicy.houseLedger.subsidyBudget),
      subsidyBudgetRemaining: economicPolicy.rebates.fundingSource === "house_subsidy"
        ? fixed(decimal(economicPolicy.houseLedger.subsidyBudget) - rebatePayments)
        : decimal(economicPolicy.houseLedger.subsidyBudget),
    },
    cashTransferGap,
    reconciledCashGap,
    makerNotional,
    takerNotional,
    liquidityRoleNotionalGap,
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
  const executions = result.tradingMetrics?.executions ?? {};
  const makerNotional = decimal(executions.makerNotional ?? 0);
  const takerNotional = decimal(executions.takerNotional ?? 0);
  const makerFeesPaid = bps(makerNotional, policy.fees.makerBps);
  const takerFeesPaid = bps(takerNotional, policy.fees.takerBps);
  const feesPaid = fixed(makerFeesPaid + takerFeesPaid);
  const rebatesReceived = bps(makerNotional, policy.rebates.makerBps);
  const economicCashDelta = fixed(tradingCashDelta - feesPaid + rebatesReceived);
  return {
    botId: result.botId,
    versionId: result.versionId,
    actorClass,
    ledger,
    startingCash,
    tradingCashDelta,
    makerNotional,
    takerNotional,
    makerFeesPaid,
    takerFeesPaid,
    feesPaid,
    rebatesReceived,
    economicCashDelta,
    finalCash: fixed(startingCash + economicCashDelta),
    inventoryValue,
    finalEquity: fixed(startingCash + economicCashDelta + inventoryValue),
    tradingPnl: totalPnl,
    economicPnl: fixed(totalPnl - feesPaid + rebatesReceived),
    pnlConsistencyGap: fixed(totalPnl - tradingCashDelta - inventoryValue),
    pnlAvailable: pnl.available === true,
    liquidityRoleComplete: Number(executions.fillCount ?? 0) === 0 || executions.liquidityRoleComplete === true,
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
    economicCashDelta: fixed(selected.reduce((sum, actor) => sum + actor.economicCashDelta, 0)),
    feesPaid: fixed(selected.reduce((sum, actor) => sum + actor.feesPaid, 0)),
    rebatesReceived: fixed(selected.reduce((sum, actor) => sum + actor.rebatesReceived, 0)),
    finalCash: fixed(selected.reduce((sum, actor) => sum + actor.finalCash, 0)),
    inventoryValue: fixed(selected.reduce((sum, actor) => sum + actor.inventoryValue, 0)),
    finalEquity: fixed(selected.reduce((sum, actor) => sum + actor.finalEquity, 0)),
  };
}

function declaredSinks(policy, actors) {
  const takerFees = fixed(actors.reduce((sum, actor) => sum + actor.takerFeesPaid, 0));
  return policy.sinks.filter((sink) => sink.enabled).map((sink) => ({
    ...sink,
    amount: sink.code === "taker_fees" ? takerFees : 0,
  }));
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
    ["fees.cancelFee", policy.fees.cancelFee],
    ["fees.borrowBps", policy.fees.borrowBps],
    ["fees.liquidationPenaltyBps", policy.fees.liquidationPenaltyBps],
  ].filter(([, value]) => decimal(value) !== 0).map(([name]) => name);
}

function roleDependentPolicyEnabled(policy) {
  return decimal(policy.fees.makerBps) !== 0
    || decimal(policy.fees.takerBps) !== 0
    || decimal(policy.rebates.makerBps) !== 0;
}

function bps(notional, rate) {
  return fixed(notional * decimal(rate) / 10_000);
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
  const rounded = Number(Number(value).toFixed(8));
  return Object.is(rounded, -0) ? 0 : rounded;
}
