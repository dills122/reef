import type {
  BotContextV1,
  BotDataAvailabilityClientV1,
  BotHistoricalBarsRequestV1,
  BotHistoricalDataClientV1,
  BotMarketDataClientV1,
  BotOrdersClientV1,
  BotResultV1,
  DataAvailabilityProjectionV1,
  DataAvailabilityProjectionWatermarkV1,
  DataAvailabilitySurfaceV1,
  DataAvailabilityV1,
  HistoricalBarV1,
  MarketSnapshotV1,
  OwnOrderV1,
} from "./index";
import {
  createBotActionFactoryV1,
  createConfig,
  createLogger,
  createSeededRandom,
  safeCancelOrder,
  safeModifyOrder,
} from "./harness";

export type LiveFetchV1 = (url: string) => Promise<{ readonly status: number; text(): Promise<string> }>;

export interface LiveVenueDataClientOptionsV1 {
  readonly baseUrl: string;
  readonly participantId: string;
  readonly fetch?: LiveFetchV1;
}

function resolveFetch(fetchImpl: LiveFetchV1 | undefined): LiveFetchV1 {
  const impl = fetchImpl ?? (globalThis as { fetch?: LiveFetchV1 }).fetch;
  if (impl === undefined) {
    throw new Error("No fetch implementation available for live venue data client.");
  }
  return impl;
}

async function getJson(fetchImpl: LiveFetchV1, url: string): Promise<{ readonly status: number; readonly json: Record<string, unknown> }> {
  const response = await fetchImpl(url);
  const text = await response.text();
  return { status: response.status, json: text.length === 0 ? {} : (JSON.parse(text) as Record<string, unknown>) };
}

function unavailableDenial(message: string): BotResultV1<never> {
  return { ok: false, denial: { code: "TEMPORARILY_UNAVAILABLE", message } };
}

function notFoundDenial(message: string): BotResultV1<never> {
  return { ok: false, denial: { code: "NOT_FOUND", message } };
}

// Prices are stored venue-wide as fixed-point nanos (contracts/proto/order_execution.proto's
// Price.nanos: price_nanos = price_dollars * 10^9), not plain decimals. Quantity fields
// (OrderQuantity.units) are NOT scaled this way - only price fields need this conversion.
const PRICE_SCALE_NANOS = 1_000_000_000;

function priceFromNanos(value: string | undefined): number | undefined {
  const parsed = numberOrUndefined(value);
  return parsed === undefined ? undefined : parsed / PRICE_SCALE_NANOS;
}

function recordOrEmpty(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function arrayOrEmpty(value: unknown): readonly unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function stringArrayValue(value: unknown): readonly string[] {
  return arrayOrEmpty(value).map(stringValue).filter((item) => item.length > 0);
}

function parseAvailabilityWatermark(value: unknown): DataAvailabilityProjectionWatermarkV1 {
  const row = recordOrEmpty(value);
  return {
    projectionName: stringValue(row.projectionName),
    partition: numberValue(row.partition),
    lastPartitionSequence: numberValue(row.lastPartitionSequence),
    canonicalMaxPartitionSequence: numberValue(row.canonicalMaxPartitionSequence),
    lag: numberValue(row.lag),
    updatedAt: stringValue(row.updatedAt),
    lastError: stringValue(row.lastError),
  };
}

function parseAvailabilityProjection(value: unknown): DataAvailabilityProjectionV1 {
  const row = recordOrEmpty(value);
  return {
    projectionName: stringValue(row.projectionName),
    role: stringValue(row.role),
    projectedCount: numberValue(row.projectedCount),
    lag: numberValue(row.lag),
    watermarks: arrayOrEmpty(row.watermarks).map(parseAvailabilityWatermark),
  };
}

function parseAvailabilitySurface(value: unknown): DataAvailabilitySurfaceV1 {
  const row = recordOrEmpty(value);
  return {
    name: stringValue(row.name),
    endpoint: stringValue(row.endpoint),
    source: stringValue(row.source),
    freshness: stringValue(row.freshness),
    scope: stringValue(row.scope),
    requiredQuery: stringArrayValue(row.requiredQuery),
    optionalQuery: stringArrayValue(row.optionalQuery),
    projectionName: stringValue(row.projectionName),
    lag: numberValue(row.lag),
    lastPartitionSequence: numberValue(row.lastPartitionSequence),
    lastUpdatedAt: stringValue(row.lastUpdatedAt),
    notes: stringValue(row.notes),
  };
}

const OrderStatusFromPlatformV1: Record<string, OwnOrderV1["status"]> = {
  OPEN: "OPEN",
  PARTIALLY_FILLED: "PARTIALLY_FILLED",
  FILLED: "FILLED",
  CANCELLED: "CANCELED",
  REJECTED: "REJECTED",
};

export function createLiveMarketDataClientV1(options: LiveVenueDataClientOptionsV1): BotMarketDataClientV1 {
  const fetchImpl = resolveFetch(options.fetch);
  const baseUrl = options.baseUrl.replace(/\/$/, "");

  async function fetchSnapshot(instrumentId: string): Promise<BotResultV1<MarketSnapshotV1>> {
    const snapshotResult = await getJson(fetchImpl, `${baseUrl}/api/v1/market-data/snapshots/${encodeURIComponent(instrumentId)}`);
    if (snapshotResult.status < 200 || snapshotResult.status >= 300 || snapshotResult.json.error !== undefined) {
      return notFoundDenial(`No market snapshot for ${instrumentId}.`) as BotResultV1<MarketSnapshotV1>;
    }
    const snapshot = snapshotResult.json.snapshot as Record<string, string> | undefined;
    if (snapshot === undefined) {
      return notFoundDenial(`No market snapshot for ${instrumentId}.`) as BotResultV1<MarketSnapshotV1>;
    }

    const bidPrice = priceFromNanos(snapshot.bestBidPrice);
    const askPrice = priceFromNanos(snapshot.bestAskPrice);

    let lastPrice: number | undefined;
    const tapeResult = await getJson(fetchImpl, `${baseUrl}/api/v1/market-data/trades/${encodeURIComponent(instrumentId)}?limit=1`);
    if (tapeResult.status >= 200 && tapeResult.status < 300) {
      const trades = tapeResult.json.trades as Array<Record<string, string>> | undefined;
      lastPrice = priceFromNanos(trades?.[0]?.price);
    }

    const midPrice = bidPrice !== undefined && askPrice !== undefined
      ? (bidPrice + askPrice) / 2
      : bidPrice ?? askPrice ?? lastPrice;
    if (midPrice === undefined) {
      return notFoundDenial(`No market snapshot for ${instrumentId}.`) as BotResultV1<MarketSnapshotV1>;
    }

    return {
      ok: true,
      value: {
        instrumentId,
        asOf: (snapshot.updatedAt as string | undefined) ?? new Date().toISOString(),
        ...(bidPrice === undefined ? {} : { bidPrice }),
        ...(askPrice === undefined ? {} : { askPrice }),
        midPrice,
        ...(lastPrice === undefined ? {} : { lastPrice }),
      },
    };
  }

  return {
    async snapshot(instrumentId) {
      return fetchSnapshot(instrumentId);
    },
    async snapshots(instrumentIds) {
      const values: Record<string, MarketSnapshotV1> = {};
      const missing: string[] = [];
      const results = await Promise.all(instrumentIds.map((instrumentId) => fetchSnapshot(instrumentId)));
      instrumentIds.forEach((instrumentId, index) => {
        const result = results[index]!;
        if (result.ok) {
          values[instrumentId] = result.value;
        } else {
          missing.push(instrumentId);
        }
      });
      if (missing.length > 0) {
        return notFoundDenial(`No market snapshot for ${missing.join(", ")}.`) as BotResultV1<Record<string, MarketSnapshotV1>>;
      }
      return { ok: true, value: values };
    },
  };
}

export function createLiveHistoricalDataClientV1(options: LiveVenueDataClientOptionsV1): BotHistoricalDataClientV1 {
  const fetchImpl = resolveFetch(options.fetch);
  const baseUrl = options.baseUrl.replace(/\/$/, "");

  async function fetchBars(request: BotHistoricalBarsRequestV1): Promise<BotResultV1<readonly HistoricalBarV1[]>> {
    const url = `${baseUrl}/api/v1/market-data/bars/${encodeURIComponent(request.instrumentId)}?interval=${request.interval}&start=${encodeURIComponent(request.start)}&end=${encodeURIComponent(request.end)}`;
    const result = await getJson(fetchImpl, url);
    if (result.status < 200 || result.status >= 300 || result.json.error !== undefined) {
      return unavailableDenial(`Failed to load bars for ${request.instrumentId}.`) as BotResultV1<readonly HistoricalBarV1[]>;
    }
    const bars = (result.json.bars as Array<Record<string, string>> | undefined) ?? [];
    return {
      ok: true,
      value: bars.map((bar) => ({
        instrumentId: request.instrumentId,
        start: bar.start ?? "",
        end: bar.end ?? "",
        open: priceFromNanos(bar.open) ?? 0,
        high: priceFromNanos(bar.high) ?? 0,
        low: priceFromNanos(bar.low) ?? 0,
        close: priceFromNanos(bar.close) ?? 0,
        volume: Number(bar.volume),
      })),
    };
  }

  return {
    async intradayBars(request) {
      return fetchBars(request);
    },
    async intradayBarsBatch(requests) {
      const result: Record<string, readonly HistoricalBarV1[]> = {};
      const missing: string[] = [];
      const barsResults = await Promise.all(requests.map((request) => fetchBars(request)));
      requests.forEach((request, index) => {
        const barsResult = barsResults[index]!;
        if (barsResult.ok) {
          result[request.instrumentId] = barsResult.value;
        } else {
          missing.push(request.instrumentId);
        }
      });
      if (missing.length > 0) {
        return unavailableDenial(`Failed to load bars for ${missing.join(", ")}.`) as BotResultV1<Record<string, readonly HistoricalBarV1[]>>;
      }
      return { ok: true, value: result };
    },
  };
}

export function createLiveOwnOrdersReadClientV1(
  options: LiveVenueDataClientOptionsV1,
): Pick<BotOrdersClientV1, "current" | "history"> {
  const fetchImpl = resolveFetch(options.fetch);
  const baseUrl = options.baseUrl.replace(/\/$/, "");
  const participantId = encodeURIComponent(options.participantId);

  function ownOrdersUrl(path: "current" | "history", request?: { readonly instrumentId?: string; readonly limit?: number }): string {
    const params = [`participantId=${participantId}`];
    if (request?.instrumentId !== undefined) {
      params.push(`instrumentId=${encodeURIComponent(request.instrumentId)}`);
    }
    if (request?.limit !== undefined) {
      params.push(`limit=${encodeURIComponent(String(request.limit))}`);
    }
    return `${baseUrl}/api/v1/orders/${path}?${params.join("&")}`;
  }

  function toOwnOrder(order: Record<string, string>): OwnOrderV1 {
    const status = OrderStatusFromPlatformV1[order.status ?? ""] ?? "REJECTED";
    const limitPrice = priceFromNanos(order.limitPrice);
    return {
      orderId: order.orderId ?? "",
      instrumentId: order.instrumentId ?? "",
      side: (order.side as OwnOrderV1["side"]) ?? "BUY",
      quantity: numberValue(order.quantityUnits),
      remainingQuantity: numberValue(order.remainingQuantityUnits),
      ...(limitPrice === undefined ? {} : { limitPrice }),
      status,
    };
  }

  return {
    async current() {
      const result = await getJson(fetchImpl, ownOrdersUrl("current"));
      if (result.status < 200 || result.status >= 300) {
        return unavailableDenial("Failed to load current orders.") as BotResultV1<readonly OwnOrderV1[]>;
      }
      const orders = (result.json.orders as Array<Record<string, string>> | undefined) ?? [];
      return { ok: true, value: orders.map(toOwnOrder) };
    },
    async history(request) {
      const result = await getJson(fetchImpl, ownOrdersUrl("history", request));
      if (result.status < 200 || result.status >= 300) {
        return unavailableDenial("Failed to load order history.") as BotResultV1<readonly OwnOrderV1[]>;
      }
      const orders = (result.json.orders as Array<Record<string, string>> | undefined) ?? [];
      const mapped = orders.map(toOwnOrder);
      const filtered = request?.instrumentId ? mapped.filter((order) => order.instrumentId === request.instrumentId) : mapped;
      return { ok: true, value: filtered.slice(0, request?.limit ?? filtered.length) };
    },
  };
}

export function createLiveDataAvailabilityClientV1(options: LiveVenueDataClientOptionsV1): BotDataAvailabilityClientV1 {
  const fetchImpl = resolveFetch(options.fetch);
  const baseUrl = options.baseUrl.replace(/\/$/, "");

  return {
    async availability() {
      const result = await getJson(fetchImpl, `${baseUrl}/api/v1/data/availability`);
      if (result.status < 200 || result.status >= 300 || result.json.error !== undefined) {
        return unavailableDenial("Failed to load data availability.") as BotResultV1<DataAvailabilityV1>;
      }
      return {
        ok: true,
        value: {
          generatedAt: stringValue(result.json.generatedAt),
          source: stringValue(result.json.source),
          projections: arrayOrEmpty(result.json.projections).map(parseAvailabilityProjection),
          surfaces: arrayOrEmpty(result.json.surfaces).map(parseAvailabilitySurface),
        },
      };
    },
  };
}

export interface LiveBotContextOptionsV1 extends LiveVenueDataClientOptionsV1 {
  readonly config?: Record<string, string | number | boolean>;
  readonly randomSeed?: number;
  readonly policy: BotContextV1["policy"];
}

/**
 * Builds a BotContextV1 that reads market data, bars, and own-order state from a
 * live platform-runtime instance instead of a fixture. Order actions still flow
 * through the existing venue-adapter/venue-client HTTP command path; this only
 * covers reads. runner.ts/strategy-runner.ts can receive these clients through
 * their readClients option; fixture mode remains the default.
 *
 * Read-side prices are converted from the venue's fixed-point nanos to plain
 * dollars (see priceFromNanos). The venue adapter converts bot action limit
 * prices back to fixed-point nanos before submitting commands.
 */
export function createLiveBotContextV1(options: LiveBotContextOptionsV1): BotContextV1 {
  const marketData = createLiveMarketDataClientV1(options);
  const historical = createLiveHistoricalDataClientV1(options);
  const ownOrdersRead = createLiveOwnOrdersReadClientV1(options);
  const actions = createBotActionFactoryV1();
  const logs: Parameters<typeof createLogger>[0] = [];

  const orders: BotOrdersClientV1 = {
    safe: {
      async modify(order) {
        const current = await ownOrdersRead.current();
        if (!current.ok) {
          return { ok: false, denial: current.denial };
        }
        return safeModifyOrder(order, current.value, actions);
      },
      async cancel(order) {
        const current = await ownOrdersRead.current();
        if (!current.ok) {
          return { ok: false, denial: current.denial };
        }
        return safeCancelOrder(order, current.value, actions);
      },
    },
    current: ownOrdersRead.current,
    history: ownOrdersRead.history,
    placeLimit: actions.submitLimit,
    placeMarket: actions.submitMarket,
    modify: actions.modify,
    cancel: actions.cancel,
    cancelAll: actions.cancelAll,
  };

  return {
    policy: options.policy,
    config: createConfig(options.config ?? {}),
    clock: {
      now: () => new Date(),
      nowIso: () => new Date().toISOString(),
    },
    random: createSeededRandom(options.randomSeed ?? 1),
    log: createLogger(logs),
    actions,
    marketData,
    historical,
    orders,
  };
}

function numberOrUndefined(value: string | undefined): number | undefined {
  if (value === undefined || value === "") return undefined;
  const parsed = Number(value);
  return Number.isNaN(parsed) ? undefined : parsed;
}
