export const SHADOW_SCORE_FORMULA_VERSION = "shadow-score-v1";

export function attachScoreBreakdowns(botResults, context) {
  return botResults.map((result) => ({
    ...result,
    scoreBreakdown: buildScoreBreakdown(result, context),
  }));
}

export function buildScoreContext({ scoringPolicyVersion, npcDifficultyBuckets = [] } = {}) {
  const multiplier = npcDifficultyBuckets.reduce((current, bucket) => Math.max(current, difficultyMultiplier(bucket)), 1);
  return {
    baseline: 1_000_000,
    scoringPolicyVersion,
    npcDifficultyBuckets,
    difficultyMultiplier: Number(multiplier.toFixed(4)),
    formulaVersion: SHADOW_SCORE_FORMULA_VERSION,
  };
}

export function buildScoreBreakdown(result, context = buildScoreContext()) {
  const scoreEffect = result.actorProfile?.scoreEffect ?? "eligible-for-score";
  const scoreEligible = result.scoreEligible === true && scoreEffect === "eligible-for-score";
  if (!scoreEligible) {
    return nonScoringBreakdown(result, context, scoreEffect);
  }

  const inputs = scoreInputs(result);
  const equity = equityComponent(result, inputs);
  const risk = riskComponent(result, inputs);
  const conduct = conductComponent(result);
  const marketInteraction = marketInteractionComponent(result, inputs);
  const components = {
    baseline: context.baseline,
    equity: equity.score,
    risk: risk.score,
    conduct: conduct.score,
    marketInteraction: marketInteraction.score,
    difficulty: 0,
  };
  const variableBeforeDifficulty = components.equity + components.risk + components.conduct + components.marketInteraction;
  components.difficulty = Math.round(variableBeforeDifficulty * (context.difficultyMultiplier - 1));
  const shadowScore = Math.max(0, Math.round(context.baseline + variableBeforeDifficulty + components.difficulty));
  return {
    schemaVersion: "reef.arena.scoreBreakdown.v1",
    formulaVersion: context.formulaVersion ?? SHADOW_SCORE_FORMULA_VERSION,
    scoringPolicyVersion: context.scoringPolicyVersion,
    scoreEligible: true,
    actorClass: result.actorClass,
    scoreEffect,
    publicScore: result.score,
    shadowScore,
    scoringMode: "shadow-report-only",
    components,
    componentDetails: {
      equity: equity.details,
      risk: risk.details,
      conduct: conduct.details,
      marketInteraction: marketInteraction.details,
      difficulty: {
        multiplier: context.difficultyMultiplier,
        variableScoreBeforeDifficulty: variableBeforeDifficulty,
        appliedScore: components.difficulty,
      },
    },
    diagnostics: scoreDiagnostics(result, context, inputs, variableBeforeDifficulty),
    notes: "shadowScore is report-only; public leaderboard still uses top-level score",
  };
}

export function summarizeScoreCalibration(botResults) {
  const results = Array.isArray(botResults) ? botResults : [];
  const eligible = results.filter((result) => result.scoreBreakdown?.scoreEligible === true);
  const scoreBreakdowns = eligible.map((result) => result.scoreBreakdown);
  const firstBreakdown = results.find((result) => result.scoreBreakdown !== undefined)?.scoreBreakdown;
  const actorCounts = {};
  const scoreEffectCounts = {};
  for (const result of results) {
    increment(actorCounts, result.actorClass ?? "unknown");
    increment(scoreEffectCounts, result.scoreBreakdown?.scoreEffect ?? result.actorProfile?.scoreEffect ?? "unknown");
  }

  const diagnostics = scoreBreakdowns.map((breakdown) => breakdown.diagnostics ?? {});
  const flags = [];
  const fillCount = sum(diagnostics.map((entry) => numberValue(entry.fillCount)));
  const pnlAvailableCount = diagnostics.filter((entry) => entry.pnlAvailable === true).length;
  const publicScoreMismatchCount = eligible.filter((result) => result.scoreBreakdown?.publicScore !== result.score).length;

  if (eligible.length === 0) flags.push("no-eligible-competitors");
  if (eligible.length > 0 && eligible.length < 3) flags.push("low-eligible-competitor-count");
  if (eligible.length > 0 && fillCount === 0) flags.push("no-eligible-fills");
  if (eligible.length > 0 && pnlAvailableCount === 0) flags.push("no-pnl-attribution");
  if (pnlAvailableCount > 0 && pnlAvailableCount < eligible.length) flags.push("partial-pnl-attribution");
  if (publicScoreMismatchCount > 0) flags.push("public-score-mismatch");

  return {
    schemaVersion: "reef.arena.scoringCalibration.v1",
    formulaVersion: firstBreakdown?.formulaVersion ?? SHADOW_SCORE_FORMULA_VERSION,
    scoringPolicyVersion: firstBreakdown?.scoringPolicyVersion ?? "",
    mode: "report-only-shadow-score-calibration",
    eligibility: {
      totalBots: results.length,
      eligibleCompetitors: eligible.length,
      nonScoringActors: results.length - eligible.length,
      byActorClass: sortedRecord(actorCounts),
      byScoreEffect: sortedRecord(scoreEffectCounts),
    },
    difficultyContext: {
      npcDifficultyBuckets: firstBreakdown?.diagnostics?.npcDifficultyBuckets ?? [],
      difficultyMultiplier: nullableNumber(firstBreakdown?.diagnostics?.difficultyMultiplier),
    },
    scoreDistribution: {
      publicScore: numericStats(eligible.map((result) => result.scoreBreakdown?.publicScore)),
      shadowScore: numericStats(scoreBreakdowns.map((breakdown) => breakdown.shadowScore)),
      components: {
        equity: numericStats(scoreBreakdowns.map((breakdown) => breakdown.components?.equity)),
        risk: numericStats(scoreBreakdowns.map((breakdown) => breakdown.components?.risk)),
        conduct: numericStats(scoreBreakdowns.map((breakdown) => breakdown.components?.conduct)),
        marketInteraction: numericStats(scoreBreakdowns.map((breakdown) => breakdown.components?.marketInteraction)),
        difficulty: numericStats(scoreBreakdowns.map((breakdown) => breakdown.components?.difficulty)),
      },
      diagnostics: {
        fillRatio: numericStats(diagnostics.map((entry) => entry.fillRatio)),
        completionRate: numericStats(diagnostics.map((entry) => entry.completionRate)),
        pnlPerExecutedNotionalBps: numericStats(diagnostics.map((entry) => entry.pnlPerExecutedNotionalBps)),
        inventoryExposureRatio: numericStats(diagnostics.map((entry) => entry.inventoryExposureRatio)),
        inventoryConcentration: numericStats(diagnostics.map((entry) => entry.inventoryConcentration)),
      },
    },
    dataQuality: {
      flags,
      fillCount,
      pnlAvailableCount,
      publicScoreMismatchCount,
      publicScoreUnchanged: publicScoreMismatchCount === 0,
    },
  };
}

function nonScoringBreakdown(result, context, scoreEffect) {
  return {
    schemaVersion: "reef.arena.scoreBreakdown.v1",
    formulaVersion: context.formulaVersion ?? SHADOW_SCORE_FORMULA_VERSION,
    scoringPolicyVersion: context.scoringPolicyVersion,
    scoreEligible: false,
    actorClass: result.actorClass,
    scoreEffect,
    publicScore: null,
    shadowScore: null,
    scoringMode: scoreEffect === "difficulty-bucket" ? "difficulty-context-only" : "diagnostic-only",
    components: {
      baseline: 0,
      equity: 0,
      risk: 0,
      conduct: 0,
      marketInteraction: 0,
      difficulty: 0,
    },
    componentDetails: {
      equity: { score: 0 },
      risk: { score: 0 },
      conduct: { score: 0 },
      marketInteraction: { score: 0 },
      difficulty: { multiplier: context.difficultyMultiplier, variableScoreBeforeDifficulty: 0, appliedScore: 0 },
    },
    diagnostics: scoreDiagnostics(result, context, scoreInputs(result), 0),
    notes: scoreEffect === "difficulty-bucket"
      ? "NPC profile contributes difficulty context but is not ranked"
      : "liquidity/house actor diagnostics do not create point gains or losses",
  };
}

function equityComponent(result, inputs) {
  const pnl = nullableNumber(result.tradingMetrics?.pnl?.total);
  const score = pnl === null ? 0 : Math.round(clamp(pnl, -100_000, 100_000));
  return {
    score,
    details: {
      pnlAvailable: result.tradingMetrics?.pnl?.available === true,
      rawPnl: pnl,
      cappedPnl: score,
      capMin: -100_000,
      capMax: 100_000,
      pnlPerExecutedNotionalBps: inputs.pnlPerExecutedNotionalBps,
    },
  };
}

function riskComponent(result, inputs) {
  const failedTicks = numberValue(result.failedTicks);
  const freezeCount = numberValue(result.freezeCount);
  const operationalPauseCount = numberValue(result.operationalPauseCount);
  const inventorySizePenalty = Math.min(25_000, Math.round(inputs.inventoryGrossNotional * 0.01));
  const inventoryExposurePenalty = Math.min(30_000, Math.round(clamp(inputs.inventoryExposureRatio, 0, 2) * 15_000));
  const concentrationPenalty = inputs.inventoryGrossNotional > 0
    ? Math.min(10_000, Math.round(Math.max(0, inputs.inventoryConcentration - 0.6) * 25_000))
    : 0;
  const turnoverPenalty = Math.min(10_000, Math.round(Math.max(0, inputs.submittedTurnoverRatio - 5) * 1_000));
  const operationalPenalty = failedTicks * 5_000 + freezeCount * 50_000 + operationalPauseCount * 1_000;
  const totalPenalty = Math.min(
    100_000,
    inventorySizePenalty + inventoryExposurePenalty + concentrationPenalty + turnoverPenalty + operationalPenalty,
  );
  return {
    score: totalPenalty === 0 ? 0 : -totalPenalty,
    details: {
      inventorySizePenalty,
      inventoryExposurePenalty,
      concentrationPenalty,
      turnoverPenalty,
      operationalPenalty,
      inventoryExposureRatio: inputs.inventoryExposureRatio,
      inventoryConcentration: inputs.inventoryConcentration,
      submittedTurnoverRatio: inputs.submittedTurnoverRatio,
      capMaxPenalty: 100_000,
    },
  };
}

function conductComponent(result) {
  const conduct = result.conductMetrics ?? {};
  const timeoutPenalty = Math.round(numberValue(conduct.timeoutRate) * 50_000);
  const invalidPenalty = Math.round(numberValue(conduct.invalidIntentRate) * 50_000);
  const cancelPenalty = Math.max(0, Math.round((numberValue(conduct.cancelReplaceRatio) - 1) * 2_500));
  const freezePenalty = numberValue(conduct.freezeCount) * 50_000;
  const totalPenalty = Math.min(100_000, timeoutPenalty + invalidPenalty + cancelPenalty + freezePenalty);
  return {
    score: totalPenalty === 0 ? 0 : -totalPenalty,
    details: {
      timeoutPenalty,
      invalidPenalty,
      cancelPenalty,
      freezePenalty,
      capMaxPenalty: 100_000,
    },
  };
}

function marketInteractionComponent(result, inputs) {
  const commands = result.tradingMetrics?.commands ?? {};
  const executions = result.tradingMetrics?.executions ?? {};
  const submitted = numberValue(commands.submitted);
  const completed = numberValue(commands.completed);
  const fills = numberValue(executions.fillCount);
  const fillQuantity = numberValue(executions.filledQuantity);
  const commandScore = Math.min(5_000, completed * 20 + submitted * 2);
  const fillScore = Math.min(15_000, fills * 75 + fillQuantity * 12);
  const fillEfficiencyScore = Math.min(5_000, Math.round(inputs.fillRatio * 5_000));
  const completionScore = Math.min(5_000, Math.round(inputs.completionRate * 5_000));
  return {
    score: Math.round(commandScore + fillScore + fillEfficiencyScore + completionScore),
    details: {
      commandScore,
      fillScore,
      fillEfficiencyScore,
      completionScore,
      fillRatio: inputs.fillRatio,
      completionRate: inputs.completionRate,
      submittedCommands: submitted,
      completedCommands: completed,
      fillCount: fills,
      filledQuantity: fillQuantity,
    },
  };
}

function scoreDiagnostics(result, context, inputs, variableScoreBeforeDifficulty) {
  return {
    finalEquity: nullableNumber(result.tradingMetrics?.pnl?.finalEquityDiagnostic),
    realizedPnl: nullableNumber(result.tradingMetrics?.pnl?.realized),
    unrealizedPnl: nullableNumber(result.tradingMetrics?.pnl?.unrealized),
    totalPnl: nullableNumber(result.tradingMetrics?.pnl?.total),
    pnlAvailable: result.tradingMetrics?.pnl?.available === true,
    markPriceSource: result.tradingMetrics?.pnl?.markPriceSource ?? result.tradingMetrics?.inventory?.markPriceSource ?? "",
    grossSubmittedQuantity: inputs.grossSubmittedQuantity,
    grossSubmittedNotional: inputs.grossSubmittedNotional,
    grossExecutedNotional: inputs.grossExecutedNotional,
    filledQuantity: inputs.filledQuantity,
    fillRatio: inputs.fillRatio,
    completionRate: inputs.completionRate,
    pnlPerExecutedNotionalBps: inputs.pnlPerExecutedNotionalBps,
    inventoryGrossNotional: inputs.inventoryGrossNotional,
    inventoryExposureRatio: inputs.inventoryExposureRatio,
    inventoryConcentration: inputs.inventoryConcentration,
    submittedTurnoverRatio: inputs.submittedTurnoverRatio,
    fillCount: numberValue(result.tradingMetrics?.executions?.fillCount),
    submittedCommands: numberValue(result.tradingMetrics?.commands?.submitted),
    completedCommands: numberValue(result.tradingMetrics?.commands?.completed),
    failedCommands: numberValue(result.tradingMetrics?.commands?.failed),
    rejectedCommands: numberValue(result.tradingMetrics?.commands?.rejected),
    timedOutCommands: numberValue(result.tradingMetrics?.commands?.timedOut),
    cancelReplaceRatio: numberValue(result.conductMetrics?.cancelReplaceRatio),
    invalidIntentRate: numberValue(result.conductMetrics?.invalidIntentRate),
    timeoutRate: numberValue(result.conductMetrics?.timeoutRate),
    maxActionsPerTick: numberValue(result.conductMetrics?.maxActionsPerTick),
    maxVenueCommandsPerTick: numberValue(result.conductMetrics?.maxVenueCommandsPerTick),
    freezeCount: numberValue(result.freezeCount),
    operationalPauseCount: numberValue(result.operationalPauseCount),
    variableScoreBeforeDifficulty,
    npcDifficultyBuckets: context.npcDifficultyBuckets,
    difficultyMultiplier: context.difficultyMultiplier,
  };
}

function scoreInputs(result) {
  const grossSubmittedQuantity = numberValue(result.tradingMetrics?.orderFlow?.grossSubmittedQuantity);
  const grossSubmittedNotional = numberValue(result.tradingMetrics?.orderFlow?.grossSubmittedNotional);
  const grossExecutedNotional = numberValue(result.tradingMetrics?.executions?.grossNotional);
  const filledQuantity = numberValue(result.tradingMetrics?.executions?.filledQuantity);
  const submittedCommands = numberValue(result.tradingMetrics?.commands?.submitted);
  const completedCommands = numberValue(result.tradingMetrics?.commands?.completed);
  const totalPnl = nullableNumber(result.tradingMetrics?.pnl?.total);
  const inventoryGrossNotional = numberValue(result.tradingMetrics?.inventory?.grossNotional);
  const exposureBasis = Math.max(1, grossExecutedNotional, grossSubmittedNotional);
  return {
    grossSubmittedQuantity,
    grossSubmittedNotional,
    grossExecutedNotional,
    filledQuantity,
    fillRatio: boundedRatio(filledQuantity, Math.max(1, grossSubmittedQuantity)),
    completionRate: boundedRatio(completedCommands, Math.max(1, submittedCommands)),
    pnlPerExecutedNotionalBps: totalPnl === null || grossExecutedNotional <= 0 ? null : Number(((totalPnl / grossExecutedNotional) * 10_000).toFixed(6)),
    inventoryGrossNotional,
    inventoryExposureRatio: ratio(inventoryGrossNotional, exposureBasis),
    inventoryConcentration: terminalInventoryConcentration(result.tradingMetrics?.inventory),
    submittedTurnoverRatio: ratio(grossSubmittedNotional, 1_000_000),
  };
}

function terminalInventoryConcentration(inventory = {}) {
  const netQuantityByInstrument = inventory.netQuantityByInstrument ?? {};
  const markPriceByInstrument = inventory.markPriceByInstrument ?? {};
  let gross = 0;
  let largest = 0;
  for (const [instrumentId, quantityValue] of Object.entries(netQuantityByInstrument)) {
    const quantity = Math.abs(numberValue(quantityValue));
    const markPrice = numberValue(markPriceByInstrument[instrumentId]);
    const markedNotional = quantity * markPrice;
    gross += markedNotional;
    largest = Math.max(largest, markedNotional);
  }
  return gross > 0 ? Number((largest / gross).toFixed(6)) : 0;
}

function difficultyMultiplier(bucket) {
  const multipliers = {
    "benign-noise": 1,
    "ranked-standard": 1,
    "balanced-flow": 1.05,
    "toxic-momentum": 1.1,
    "stress-liquidity": 1.15,
    "event-shock": 1.2,
  };
  return multipliers[bucket] ?? 1;
}

function ratio(count, total) {
  return total > 0 ? Number((count / total).toFixed(6)) : 0;
}

function boundedRatio(count, total) {
  return clamp(ratio(count, total), 0, 1);
}

function numericStats(values) {
  const numbers = values
    .map((value) => nullableNumber(value))
    .filter((value) => value !== null)
    .sort((left, right) => left - right);
  if (numbers.length === 0) {
    return { count: 0, min: null, max: null, avg: null };
  }
  return {
    count: numbers.length,
    min: numbers[0],
    max: numbers[numbers.length - 1],
    avg: Number((sum(numbers) / numbers.length).toFixed(6)),
  };
}

function sum(values) {
  return values.reduce((total, value) => total + numberValue(value), 0);
}

function increment(record, key, amount = 1) {
  record[key] = (record[key] ?? 0) + amount;
}

function sortedRecord(record) {
  return Object.fromEntries(Object.entries(record).sort(([left], [right]) => left.localeCompare(right)));
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function nullableNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}
