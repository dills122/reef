export const REEF_BOT_API_VERSION_V1 = "v1" as const;
export const REEF_BOT_SDK_VERSION = "1.0.0" as const;

export type BotApiVersionV1 = typeof REEF_BOT_API_VERSION_V1;
export type BotSdkVersion = typeof REEF_BOT_SDK_VERSION;

export interface ReefBotMetadataV1 {
  readonly name: string;
  readonly publisher: string;
  readonly email: string;
  readonly version: string;
  readonly sdkVersion: BotSdkVersion | string;
  readonly botApiVersion: BotApiVersionV1;
  readonly description?: string;
  readonly tags?: readonly string[];
  readonly repository?: string;
  readonly license?: string;
  readonly homepage?: string;
}

export interface BotRuntimePolicyV1 {
  readonly tickIntervalMs: number;
  readonly minTickIntervalMs: number;
  readonly maxOrderActionsPerTick: number;
  readonly maxDataCallsPerTick: number;
  readonly maxTradeCommandsPerSecond: number;
}

export type BotResultV1<T> =
  | {
      readonly ok: true;
      readonly value: T;
      readonly cached?: boolean;
    }
  | {
      readonly ok: false;
      readonly denial: BotDenialV1;
    };

export interface BotDenialV1 {
  readonly code:
    | "RATE_LIMITED"
    | "NOT_ALLOWED"
    | "NOT_FOUND"
    | "STALE_DATA"
    | "RISK_REJECTED"
    | "TEMPORARILY_UNAVAILABLE";
  readonly message: string;
  readonly retryAfterMs?: number;
}

export type OrderSideV1 = "BUY" | "SELL";
export type TimeInForceV1 = "DAY" | "IOC" | "FOK";

export interface MarketSnapshotV1 {
  readonly instrumentId: string;
  readonly asOf: string;
  readonly bidPrice?: number;
  readonly askPrice?: number;
  readonly midPrice: number;
  readonly lastPrice?: number;
}

export interface HistoricalBarV1 {
  readonly instrumentId: string;
  readonly start: string;
  readonly end: string;
  readonly open: number;
  readonly high: number;
  readonly low: number;
  readonly close: number;
  readonly volume: number;
}

export interface OwnOrderV1 {
  readonly orderId: string;
  readonly instrumentId: string;
  readonly side: OrderSideV1;
  readonly quantity: number;
  readonly remainingQuantity: number;
  readonly limitPrice?: number;
  readonly status: "OPEN" | "PARTIALLY_FILLED" | "FILLED" | "CANCELED" | "REJECTED";
}

export interface SubmitLimitOrderV1 {
  readonly instrumentId: string;
  readonly side: OrderSideV1;
  readonly quantity: number;
  readonly limitPrice: number;
  readonly timeInForce?: TimeInForceV1;
  readonly clientOrderId?: string;
}

export interface SubmitMarketOrderV1 {
  readonly instrumentId: string;
  readonly side: OrderSideV1;
  readonly quantity: number;
  readonly timeInForce?: Extract<TimeInForceV1, "IOC" | "FOK">;
  readonly clientOrderId?: string;
}

export interface ModifyOrderV1 {
  readonly orderId: string;
  readonly instrumentId: string;
  readonly quantity?: number;
  readonly limitPrice?: number;
}

export interface CancelOrderV1 {
  readonly orderId: string;
  readonly instrumentId: string;
}

export type BotActionV1 =
  | {
      readonly type: "submit_limit";
      readonly order: SubmitLimitOrderV1;
    }
  | {
      readonly type: "submit_market";
      readonly order: SubmitMarketOrderV1;
    }
  | {
      readonly type: "modify_order";
      readonly order: ModifyOrderV1;
    }
  | {
      readonly type: "cancel_order";
      readonly order: CancelOrderV1;
    }
  | {
      readonly type: "cancel_all";
      readonly instrumentId?: string;
    }
  | {
      readonly type: "noop";
      readonly reason?: string;
    };

export interface BotActionFactoryV1 {
  submitLimit(order: SubmitLimitOrderV1): BotActionV1;
  submitMarket(order: SubmitMarketOrderV1): BotActionV1;
  modify(order: ModifyOrderV1): BotActionV1;
  cancel(order: CancelOrderV1): BotActionV1;
  cancelAll(instrumentId?: string): BotActionV1;
  noop(reason?: string): BotActionV1;
}

export interface BotMarketDataClientV1 {
  snapshot(instrumentId: string): Promise<BotResultV1<MarketSnapshotV1>>;
}

export interface BotHistoricalDataClientV1 {
  intradayBars(request: {
    readonly instrumentId: string;
    readonly interval: "1m" | "5m" | "15m" | "1h";
    readonly start: string;
    readonly end: string;
  }): Promise<BotResultV1<readonly HistoricalBarV1[]>>;
}

export interface BotOrdersClientV1 {
  readonly safe: BotSafeOrdersClientV1;
  current(): Promise<BotResultV1<readonly OwnOrderV1[]>>;
  history(request?: { readonly instrumentId?: string; readonly limit?: number }): Promise<BotResultV1<readonly OwnOrderV1[]>>;
  placeLimit(order: SubmitLimitOrderV1): BotActionV1;
  placeMarket(order: SubmitMarketOrderV1): BotActionV1;
  modify(order: ModifyOrderV1): BotActionV1;
  cancel(order: CancelOrderV1): BotActionV1;
  cancelAll(instrumentId?: string): BotActionV1;
}

export interface BotSafeOrdersClientV1 {
  modify(order: ModifyOrderV1): Promise<BotResultV1<BotActionV1>>;
  cancel(order: CancelOrderV1): Promise<BotResultV1<BotActionV1>>;
}

export interface BotConfigV1 {
  string(key: string): string;
  number(key: string): number;
  boolean(key: string): boolean;
  optionalString(key: string): string | undefined;
  optionalNumber(key: string): number | undefined;
  optionalBoolean(key: string): boolean | undefined;
}

export interface BotClockV1 {
  now(): Date;
  nowIso(): string;
}

export interface BotRandomV1 {
  next(): number;
  integer(minInclusive: number, maxInclusive: number): number;
}

export interface BotLoggerV1 {
  info(message: string, fields?: Record<string, unknown>): void;
  warn(message: string, fields?: Record<string, unknown>): void;
  error(message: string, fields?: Record<string, unknown>): void;
}

export interface BotContextV1 {
  readonly policy: BotRuntimePolicyV1;
  readonly config: BotConfigV1;
  readonly clock: BotClockV1;
  readonly random: BotRandomV1;
  readonly log: BotLoggerV1;
  readonly actions: BotActionFactoryV1;
  readonly marketData: BotMarketDataClientV1;
  readonly historical: BotHistoricalDataClientV1;
  readonly orders: BotOrdersClientV1;
}

export abstract class ReefBotV1 {
  static metadata: ReefBotMetadataV1;

  async onStart(_ctx: BotContextV1): Promise<void> {
    return;
  }

  abstract onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]>;

  async onStop(_ctx: BotContextV1): Promise<void> {
    return;
  }
}

export * from "./harness";
export * from "./runner";
export * from "./sandbox-policy";
export * from "./venue-adapter";
export * from "./venue-client";
