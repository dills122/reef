export function enrichBotResultsWithExecutionDiagnostics(botResults, venueReadback, healthSamples, options = {}) {
  if (venueReadback?.skipped === true || !Array.isArray(venueReadback?.ownOrders)) {
    return botResults;
  }
  const fillsByBotId = new Map();
  for (const entry of venueReadback.ownOrders) {
    const available = entry.fills?.statusCode >= 200
      && entry.fills.statusCode < 300
      && Array.isArray(entry.fills?.body?.fills);
    fillsByBotId.set(entry.botId, {
      available,
      fills: available ? entry.fills.body.fills : [],
    });
  }
  const markPrices = markPricesByInstrument(venueReadback, healthSamples, options);
  return botResults.map((result) => {
    const readback = fillsByBotId.get(result.botId) ?? { available: false, fills: [] };
    const diagnostics = summarizeExecutionDiagnostics(readback.fills, markPrices, {
      healthSamples,
      adverseSelectionWindowMs: options.adverseSelectionWindowMs,
      available: readback.available,
    });
    return {
      ...result,
      tradingMetrics: {
        ...result.tradingMetrics,
        basis: "report-only command mix plus zero-fee execution diagnostics; public score remains participation/policy-compliance",
        executions: diagnostics.executions,
        pnl: diagnostics.pnl,
        fees: diagnostics.fees,
        inventory: diagnostics.inventory,
        adverseSelection: diagnostics.adverseSelection,
      },
    };
  });
}

export function summarizeExecutionDiagnostics(fills, markPrices = {}, options = {}) {
  const byInstrument = {};
  let fillCount = 0;
  let buyFillCount = 0;
  let sellFillCount = 0;
  let buyQuantity = 0;
  let sellQuantity = 0;
  let filledQuantity = 0;
  let grossNotional = 0;
  let cash = 0;
  let makerFillCount = 0;
  let takerFillCount = 0;
  let unspecifiedLiquidityRoleFillCount = 0;
  let makerNotional = 0;
  let takerNotional = 0;

  for (const fill of fills) {
    const instrumentId = typeof fill.instrumentId === "string" && fill.instrumentId.length > 0 ? fill.instrumentId : "unknown";
    const side = fill.side === "SELL" ? "SELL" : "BUY";
    const quantity = numberValue(fill.quantityUnits);
    const price = priceFromExecutionPrice(fill.executionPrice);
    const notional = price === undefined ? 0 : quantity * price;
    const liquidityRole = fill.liquidityRole === "MAKER" || fill.liquidityRole === "TAKER"
      ? fill.liquidityRole
      : "UNSPECIFIED";
    const bucket = byInstrument[instrumentId] ?? createExecutionInstrumentBucket(instrumentId);

    fillCount += 1;
    filledQuantity += quantity;
    grossNotional += notional;
    bucket.fillCount += 1;
    bucket.filledQuantity += quantity;
    bucket.grossNotional += notional;
    if (liquidityRole === "MAKER") {
      makerFillCount += 1;
      makerNotional += notional;
    } else if (liquidityRole === "TAKER") {
      takerFillCount += 1;
      takerNotional += notional;
    } else {
      unspecifiedLiquidityRoleFillCount += 1;
    }

    if (side === "BUY") {
      buyFillCount += 1;
      buyQuantity += quantity;
      cash -= notional;
      bucket.buyQuantity += quantity;
      bucket.netQuantity += quantity;
      bucket.cash -= notional;
    } else {
      sellFillCount += 1;
      sellQuantity += quantity;
      cash += notional;
      bucket.sellQuantity += quantity;
      bucket.netQuantity -= quantity;
      bucket.cash += notional;
    }
    byInstrument[instrumentId] = bucket;
  }

  let inventoryValue = 0;
  let grossMarkedNotional = 0;
  const netQuantityByInstrument = {};
  const markPriceByInstrument = {};
  const instrumentRows = {};

  for (const [instrumentId, bucket] of Object.entries(byInstrument).sort(([left], [right]) => left.localeCompare(right))) {
    const markPrice = markPrices[instrumentId] ?? syntheticSnapshot(instrumentId).midPrice;
    const markedValue = bucket.netQuantity * markPrice;
    inventoryValue += markedValue;
    grossMarkedNotional += Math.abs(markedValue);
    netQuantityByInstrument[instrumentId] = bucket.netQuantity;
    markPriceByInstrument[instrumentId] = markPrice;
    instrumentRows[instrumentId] = {
      fillCount: bucket.fillCount,
      filledQuantity: bucket.filledQuantity,
      buyQuantity: bucket.buyQuantity,
      sellQuantity: bucket.sellQuantity,
      netQuantity: bucket.netQuantity,
      grossNotional: fixed(bucket.grossNotional),
      avgFillPrice: bucket.filledQuantity === 0 ? null : fixed(bucket.grossNotional / bucket.filledQuantity),
      cash: fixed(bucket.cash),
      markPrice,
      markedValue: fixed(markedValue),
    };
  }

  const total = cash + inventoryValue;
  return {
    executions: {
      fillCount,
      buyFillCount,
      sellFillCount,
      filledQuantity,
      buyQuantity,
      sellQuantity,
      grossNotional: fixed(grossNotional),
      avgFillPrice: filledQuantity === 0 ? null : fixed(grossNotional / filledQuantity),
      makerFillCount,
      takerFillCount,
      unspecifiedLiquidityRoleFillCount,
      makerNotional: fixed(makerNotional),
      takerNotional: fixed(takerNotional),
      liquidityRoleComplete: unspecifiedLiquidityRoleFillCount === 0,
      byInstrument: instrumentRows,
    },
    pnl: {
      available: options.available !== false,
      realized: null,
      unrealized: null,
      cash: fixed(cash),
      inventoryValue: fixed(inventoryValue),
      total: fixed(total),
      finalEquityDiagnostic: fixed(1_000_000 + total),
      currency: "USD",
      basis: "zero-fee cash plus marked inventory from participant-scoped fills",
      markPriceSource: "final venue top-of-book mid, latest health sample mid, then deterministic fixture mid",
    },
    fees: {
      total: 0,
      maker: 0,
      taker: 0,
      currency: "USD",
      policy: "fees-v0-zero-placeholder",
    },
    inventory: {
      netQuantityByInstrument,
      markPriceByInstrument,
      grossNotional: fixed(grossMarkedNotional),
      markPriceSource: "final venue top-of-book mid, latest health sample mid, then deterministic fixture mid",
    },
    adverseSelection: summarizeAdverseSelection(fills, options.healthSamples ?? [], {
      windowMs: options.adverseSelectionWindowMs ?? 5000,
    }),
  };
}

export function summarizeAdverseSelection(fills, healthSamples = [], options = {}) {
  const windowMs = positiveNumber(options.windowMs, 5000);
  const marks = postFillMarks(healthSamples);
  const byInstrument = {};
  let comparableFillCount = 0;
  let adverseFillCount = 0;
  let favorableFillCount = 0;
  let neutralFillCount = 0;
  let missingPostFillMarkCount = 0;
  let markoutBpsTotal = 0;
  let markoutNotionalTotal = 0;

  for (const fill of fills ?? []) {
    const instrumentId = typeof fill.instrumentId === "string" && fill.instrumentId.length > 0 ? fill.instrumentId : "unknown";
    const fillTime = Date.parse(fill.occurredAt ?? "");
    const fillPrice = priceFromExecutionPrice(fill.executionPrice);
    const quantity = numberValue(fill.quantityUnits);
    const side = fill.side === "SELL" ? "SELL" : "BUY";
    const bucket = byInstrument[instrumentId] ?? createAdverseSelectionInstrumentBucket(instrumentId);
    bucket.fillCount += 1;

    const mark = Number.isFinite(fillTime) && fillPrice !== undefined && fillPrice > 0 && quantity > 0
      ? firstPostFillMark(marks.get(instrumentId) ?? [], fillTime, windowMs)
      : null;
    if (mark === null) {
      missingPostFillMarkCount += 1;
      bucket.missingPostFillMarkCount += 1;
      byInstrument[instrumentId] = bucket;
      continue;
    }

    const signedMarkout = side === "SELL" ? fillPrice - mark.midPrice : mark.midPrice - fillPrice;
    const markoutBps = (signedMarkout / fillPrice) * 10_000;
    const markoutNotional = signedMarkout * quantity;

    comparableFillCount += 1;
    markoutBpsTotal += markoutBps;
    markoutNotionalTotal += markoutNotional;
    bucket.comparableFillCount += 1;
    bucket.markoutBpsTotal += markoutBps;
    bucket.markoutNotional += markoutNotional;
    if (markoutBps < 0) {
      adverseFillCount += 1;
      bucket.adverseFillCount += 1;
    } else if (markoutBps > 0) {
      favorableFillCount += 1;
      bucket.favorableFillCount += 1;
    } else {
      neutralFillCount += 1;
      bucket.neutralFillCount += 1;
    }
    byInstrument[instrumentId] = bucket;
  }

  const available = comparableFillCount > 0;
  return {
    schemaVersion: "reef.arena.adverseSelectionDiagnostics.v1",
    available,
    source: "participant-scoped fills plus post-fill health sample mids",
    markWindowMs: windowMs,
    fillCount: Array.isArray(fills) ? fills.length : 0,
    comparableFillCount,
    missingPostFillMarkCount,
    adverseFillCount,
    favorableFillCount,
    neutralFillCount,
    adverseFillPct: pct(adverseFillCount, comparableFillCount),
    avgMarkoutBps: available ? fixed(markoutBpsTotal / comparableFillCount) : null,
    markoutNotional: fixed(markoutNotionalTotal),
    byInstrument: sortedAdverseSelectionBuckets(byInstrument),
    reason: available ? "" : "requires fill timestamps plus post-fill health sample mids within markWindowMs",
    interpretation: "negative markout means the provider was adversely selected after the fill; positive markout means favorable post-fill movement",
  };
}

export function markPricesByInstrument(venueReadback, healthSamples, options = {}) {
  const prices = {};
  for (const snapshot of latestHealthSnapshotsByInstrument(healthSamples)) {
    const mid = marketPriceValue(snapshot.midPrice);
    if (mid !== null) prices[snapshot.instrumentId] = mid;
  }
  for (const entry of venueReadback?.snapshots ?? []) {
    const snapshot = entry.body?.snapshot ?? entry.snapshot ?? entry;
    const instrumentId = String(entry.instrumentId ?? snapshot.instrumentId ?? "");
    const mid = marketPriceValue(snapshot.midPrice) ??
      midpoint(
        marketPriceValue(snapshot.bestBidPrice ?? snapshot.bidPrice),
        marketPriceValue(snapshot.bestAskPrice ?? snapshot.askPrice),
      );
    if (instrumentId.length > 0 && mid !== null) prices[instrumentId] = mid;
  }
  for (const instrumentId of options.fallbackInstruments ?? []) {
    prices[instrumentId] ??= syntheticSnapshot(instrumentId).midPrice;
  }
  return prices;
}

export function priceFromExecutionPrice(value) {
  if (value === undefined || value === null || value === "") {
    return undefined;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return undefined;
  return Math.abs(parsed) >= 1_000_000 ? parsed / 1_000_000_000 : parsed;
}

export function marketPriceValue(value) {
  const parsed = nullableNumber(value);
  if (parsed === null) return null;
  return Math.abs(parsed) >= 1_000_000 ? parsed / 1_000_000_000 : parsed;
}

function createExecutionInstrumentBucket(instrumentId) {
  return {
    instrumentId,
    fillCount: 0,
    filledQuantity: 0,
    buyQuantity: 0,
    sellQuantity: 0,
    netQuantity: 0,
    grossNotional: 0,
    cash: 0,
  };
}

function latestHealthSnapshotsByInstrument(healthSamples) {
  const snapshots = new Map();
  for (const sample of healthSamples ?? []) {
    for (const snapshot of sample.snapshots ?? []) {
      if (snapshot.instrumentId !== undefined) snapshots.set(snapshot.instrumentId, snapshot);
    }
  }
  return Array.from(snapshots.values());
}

function postFillMarks(healthSamples) {
  const byInstrument = new Map();
  for (const sample of healthSamples ?? []) {
    const sampleTime = Date.parse(sample.occurredAt ?? "");
    if (!Number.isFinite(sampleTime)) continue;
    for (const snapshot of sample.snapshots ?? []) {
      const instrumentId = String(snapshot.instrumentId ?? "");
      if (instrumentId.length === 0) continue;
      const midPrice = marketPriceValue(snapshot.midPrice) ??
        midpoint(
          marketPriceValue(snapshot.bestBidPrice ?? snapshot.bidPrice),
          marketPriceValue(snapshot.bestAskPrice ?? snapshot.askPrice),
        );
      if (midPrice === null) continue;
      const entries = byInstrument.get(instrumentId) ?? [];
      entries.push({ occurredAtMs: sampleTime, midPrice });
      byInstrument.set(instrumentId, entries);
    }
  }
  for (const entries of byInstrument.values()) {
    entries.sort((left, right) => left.occurredAtMs - right.occurredAtMs);
  }
  return byInstrument;
}

function firstPostFillMark(marks, fillTimeMs, windowMs) {
  const maxTimeMs = fillTimeMs + windowMs;
  return marks.find((mark) => mark.occurredAtMs >= fillTimeMs && mark.occurredAtMs <= maxTimeMs) ?? null;
}

function createAdverseSelectionInstrumentBucket(instrumentId) {
  return {
    instrumentId,
    fillCount: 0,
    comparableFillCount: 0,
    missingPostFillMarkCount: 0,
    adverseFillCount: 0,
    favorableFillCount: 0,
    neutralFillCount: 0,
    markoutBpsTotal: 0,
    markoutNotional: 0,
  };
}

function sortedAdverseSelectionBuckets(buckets) {
  return Object.fromEntries(
    Object.entries(buckets)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([instrumentId, bucket]) => [
        instrumentId,
        {
          fillCount: bucket.fillCount,
          comparableFillCount: bucket.comparableFillCount,
          missingPostFillMarkCount: bucket.missingPostFillMarkCount,
          adverseFillCount: bucket.adverseFillCount,
          favorableFillCount: bucket.favorableFillCount,
          neutralFillCount: bucket.neutralFillCount,
          adverseFillPct: pct(bucket.adverseFillCount, bucket.comparableFillCount),
          avgMarkoutBps: bucket.comparableFillCount === 0 ? null : fixed(bucket.markoutBpsTotal / bucket.comparableFillCount),
          markoutNotional: fixed(bucket.markoutNotional),
        },
      ]),
  );
}

function syntheticSnapshot(instrumentId) {
  const midPrice = 100 + (String(instrumentId).charCodeAt(0) % 50);
  return { midPrice };
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function positiveNumber(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function nullableNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function midpoint(bid, ask) {
  return bid !== null && ask !== null ? (bid + ask) / 2 : null;
}

function fixed(value) {
  return Number(value.toFixed(6));
}

function pct(count, total) {
  return total > 0 ? Number(((count / total) * 100).toFixed(6)) : 0;
}
