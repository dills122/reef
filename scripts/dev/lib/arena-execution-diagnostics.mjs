export function enrichBotResultsWithExecutionDiagnostics(botResults, venueReadback, healthSamples, options = {}) {
  if (venueReadback?.skipped === true || !Array.isArray(venueReadback?.ownOrders)) {
    return botResults;
  }
  const fillsByBotId = new Map();
  for (const entry of venueReadback.ownOrders) {
    fillsByBotId.set(entry.botId, Array.isArray(entry.fills?.body?.fills) ? entry.fills.body.fills : []);
  }
  const markPrices = markPricesByInstrument(venueReadback, healthSamples, options);
  return botResults.map((result) => {
    const diagnostics = summarizeExecutionDiagnostics(fillsByBotId.get(result.botId) ?? [], markPrices);
    return {
      ...result,
      tradingMetrics: {
        ...result.tradingMetrics,
        basis: "report-only command mix plus zero-fee execution diagnostics; public score remains participation/policy-compliance",
        executions: diagnostics.executions,
        pnl: diagnostics.pnl,
        fees: diagnostics.fees,
        inventory: diagnostics.inventory,
      },
    };
  });
}

export function summarizeExecutionDiagnostics(fills, markPrices = {}) {
  const byInstrument = {};
  let fillCount = 0;
  let buyFillCount = 0;
  let sellFillCount = 0;
  let buyQuantity = 0;
  let sellQuantity = 0;
  let filledQuantity = 0;
  let grossNotional = 0;
  let cash = 0;

  for (const fill of fills) {
    const instrumentId = typeof fill.instrumentId === "string" && fill.instrumentId.length > 0 ? fill.instrumentId : "unknown";
    const side = fill.side === "SELL" ? "SELL" : "BUY";
    const quantity = numberValue(fill.quantityUnits);
    const price = priceFromExecutionPrice(fill.executionPrice);
    const notional = price === undefined ? 0 : quantity * price;
    const bucket = byInstrument[instrumentId] ?? createExecutionInstrumentBucket(instrumentId);

    fillCount += 1;
    filledQuantity += quantity;
    grossNotional += notional;
    bucket.fillCount += 1;
    bucket.filledQuantity += quantity;
    bucket.grossNotional += notional;

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
      byInstrument: instrumentRows,
    },
    pnl: {
      available: true,
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

function syntheticSnapshot(instrumentId) {
  const midPrice = 100 + (String(instrumentId).charCodeAt(0) % 50);
  return { midPrice };
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
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
