import {
  REEF_BOT_API_VERSION_V1,
  type BotActionFactoryV1,
  type BotActionV1,
  type BotConfigV1,
  type BotContextV1,
  type BotDenialV1,
  type BotHistoricalDataClientV1,
  type BotLoggerV1,
  type BotMarketDataClientV1,
  type BotRandomV1,
  type BotResultV1,
  type BotRuntimePolicyV1,
  type HistoricalBarV1,
  type MarketSnapshotV1,
  type OwnOrderV1,
  type ReefBotMetadataV1,
} from "./index";

export interface ReefBotV1Instance {
  onStart(ctx: BotContextV1): Promise<void>;
  onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]>;
  onStop(ctx: BotContextV1): Promise<void>;
}

export interface ReefBotV1Constructor {
  readonly metadata?: ReefBotMetadataV1;
  new (): ReefBotV1Instance;
}

export const defaultBotRuntimePolicyV1: BotRuntimePolicyV1 = {
  tickIntervalMs: 500,
  minTickIntervalMs: 250,
  maxOrderActionsPerTick: 10,
  maxDataCallsPerTick: 20,
  maxTradeCommandsPerSecond: 10,
};

export type BotRegistrationStatusV1 = "accepted" | "do_not_merge";

export interface BotRegistrationIssueV1 {
  readonly code: string;
  readonly message: string;
  readonly severity: "error" | "warning";
}

export interface BotRegistrationReportV1 {
  readonly status: BotRegistrationStatusV1;
  readonly fileName: string;
  readonly metadata?: ReefBotMetadataV1;
  readonly issues: readonly BotRegistrationIssueV1[];
}

export interface BotQualificationReportV1 extends BotRegistrationReportV1 {
  readonly ticksRun: number;
  readonly actionsProposed: number;
  readonly orderActionsProposed: number;
  readonly dataCalls: number;
  readonly denials: readonly BotDenialV1[];
  readonly logs: readonly BotLogEntryV1[];
}

export interface BotFixtureDataV1 {
  readonly marketSnapshots?: Record<string, MarketSnapshotV1>;
  readonly historicalBars?: Record<string, readonly HistoricalBarV1[]>;
  readonly currentOrders?: readonly OwnOrderV1[];
  readonly orderHistory?: readonly OwnOrderV1[];
  readonly config?: Record<string, string | number | boolean>;
}

export interface BotQualificationOptionsV1 {
  readonly fileName: string;
  readonly source: string;
  readonly BotClass: ReefBotV1Constructor;
  readonly existingFileNames?: readonly string[];
  readonly tickCount?: number;
  readonly policy?: Partial<BotRuntimePolicyV1>;
  readonly fixtureData?: BotFixtureDataV1;
}

export interface BotLogEntryV1 {
  readonly level: "info" | "warn" | "error";
  readonly message: string;
  readonly fields?: Record<string, unknown>;
}

const requiredMetadataFields: readonly (keyof ReefBotMetadataV1)[] = [
  "name",
  "publisher",
  "email",
  "version",
  "sdkVersion",
  "botApiVersion",
];

const forbiddenHostedPatterns: readonly RegExp[] = [
  /\bsetTimeout\s*\(/,
  /\bsetInterval\s*\(/,
  /\bfetch\s*\(/,
  /from\s+["']node:/,
  /from\s+["']fs["']/,
  /from\s+["']net["']/,
  /from\s+["']http["']/,
  /from\s+["']https["']/,
  /child_process/,
  /worker_threads/,
  /new\s+WebSocket\s*\(/,
];

export function validateBotRegistrationV1(options: {
  readonly fileName: string;
  readonly source: string;
  readonly BotClass: ReefBotV1Constructor;
  readonly existingFileNames?: readonly string[];
}): BotRegistrationReportV1 {
  const issues: BotRegistrationIssueV1[] = [];
  const metadata = readMetadata(options.BotClass, issues);

  if (!options.fileName.endsWith(".ts")) {
    issues.push(errorIssue("invalid_file_extension", "Bot entry file must be a TypeScript .ts file."));
  }

  if ((options.existingFileNames ?? []).filter((name) => name === options.fileName).length > 1) {
    issues.push(errorIssue("duplicate_file_name", `Bot filename ${options.fileName} is not unique.`));
  }

  for (const pattern of forbiddenHostedPatterns) {
    if (pattern.test(options.source)) {
      issues.push(errorIssue("hosted_api_forbidden", `Bot source uses forbidden hosted-mode API pattern ${pattern}.`));
    }
  }

  if (typeof options.BotClass !== "function") {
    issues.push(errorIssue("missing_default_class", "Bot file must default-export a class."));
  }

  return {
    status: hasError(issues) ? "do_not_merge" : "accepted",
    fileName: options.fileName,
    ...(metadata === undefined ? {} : { metadata }),
    issues,
  };
}

export async function qualifyBotV1(options: BotQualificationOptionsV1): Promise<BotQualificationReportV1> {
  const policy = { ...defaultBotRuntimePolicyV1, ...options.policy };
  const registration = validateBotRegistrationV1(options);
  const issues = [...registration.issues];
  const logs: BotLogEntryV1[] = [];
  const denials: BotDenialV1[] = [];
  const counters = {
    actionsProposed: 0,
    orderActionsProposed: 0,
    dataCalls: 0,
    dataCallsThisTick: 0,
  };
  const tradeCommandWindows = new Map<number, number>();
  let ticksRun = 0;

  if (!hasError(issues)) {
    const bot = new options.BotClass();

    if (typeof bot.onTick !== "function") {
      issues.push(errorIssue("missing_on_tick", "Bot must implement onTick(ctx)."));
    } else {
      const ctx = createFixtureBotContextV1({
        policy,
        ...(options.fixtureData === undefined ? {} : { fixtureData: options.fixtureData }),
        logs,
        denials,
        counters,
      });

      await bot.onStart(ctx);

      const tickCount = options.tickCount ?? 5;
      for (let tick = 0; tick < tickCount; tick += 1) {
        counters.dataCallsThisTick = 0;
        const tickActions = await bot.onTick(ctx);
        ticksRun += 1;
        const actions: BotActionV1[] = Array.from(tickActions ?? []);
        counters.actionsProposed += actions.length;

        const orderActionCount = actions.filter(isOrderAction).length;
        counters.orderActionsProposed += orderActionCount;

        if (orderActionCount > policy.maxOrderActionsPerTick) {
          issues.push(
            errorIssue(
              "max_order_actions_per_tick_exceeded",
              `Tick ${tick} proposed ${orderActionCount} order actions; limit is ${policy.maxOrderActionsPerTick}.`,
            ),
          );
        }

        const secondWindow = Math.floor((tick * policy.tickIntervalMs) / 1000);
        const updatedWindowCount = (tradeCommandWindows.get(secondWindow) ?? 0) + orderActionCount;
        tradeCommandWindows.set(secondWindow, updatedWindowCount);

        if (updatedWindowCount > policy.maxTradeCommandsPerSecond) {
          issues.push(
            errorIssue(
              "max_trade_commands_per_second_exceeded",
              `Second ${secondWindow} proposed ${updatedWindowCount} trade commands; limit is ${policy.maxTradeCommandsPerSecond}.`,
            ),
          );
        }
      }

      await bot.onStop(ctx);
    }
  }

  return {
    ...registration,
    status: hasError(issues) ? "do_not_merge" : "accepted",
    issues,
    ticksRun,
    actionsProposed: counters.actionsProposed,
    orderActionsProposed: counters.orderActionsProposed,
    dataCalls: counters.dataCalls,
    denials,
    logs,
  };
}

export function createFixtureBotContextV1(options?: {
  readonly policy?: BotRuntimePolicyV1;
  readonly fixtureData?: BotFixtureDataV1;
  readonly logs?: BotLogEntryV1[];
  readonly denials?: BotDenialV1[];
  readonly counters?: { dataCalls: number; dataCallsThisTick: number };
}): BotContextV1 {
  const policy = options?.policy ?? defaultBotRuntimePolicyV1;
  const fixtureData = options?.fixtureData ?? {};
  const logs = options?.logs ?? [];
  const denials = options?.denials ?? [];
  const counters = options?.counters ?? { dataCalls: 0, dataCallsThisTick: 0 };
  const historicalCache = new Map<string, readonly HistoricalBarV1[]>();

  const dataGate = <T>(read: () => BotResultV1<T>): BotResultV1<T> => {
    counters.dataCalls += 1;
    counters.dataCallsThisTick += 1;
    if (counters.dataCallsThisTick > policy.maxDataCallsPerTick) {
      const denial = rateLimitDenial("data API call limit exceeded");
      denials.push(denial);
      return { ok: false, denial };
    }
    return read();
  };

  const actions = createBotActionFactoryV1();
  const marketData: BotMarketDataClientV1 = {
    async snapshot(instrumentId) {
      return dataGate(() => {
        const snapshot = fixtureData.marketSnapshots?.[instrumentId];
        if (!snapshot) {
          const denial = notFoundDenial(`No market snapshot for ${instrumentId}.`);
          denials.push(denial);
          return { ok: false, denial };
        }
        return { ok: true, value: snapshot };
      });
    },
  };

  const historical: BotHistoricalDataClientV1 = {
    async intradayBars(request) {
      const cacheKey = `${request.instrumentId}:${request.interval}:${request.start}:${request.end}`;
      if (historicalCache.has(cacheKey)) {
        return { ok: true, value: historicalCache.get(cacheKey) ?? [], cached: true };
      }

      return dataGate(() => {
        const bars = fixtureData.historicalBars?.[request.instrumentId];
        if (!bars) {
          const denial = notFoundDenial(`No historical bars for ${request.instrumentId}.`);
          denials.push(denial);
          return { ok: false, denial };
        }
        historicalCache.set(cacheKey, bars);
        return { ok: true, value: bars };
      });
    },
  };

  return {
    policy,
    config: createConfig(fixtureData.config ?? {}),
    clock: {
      now: () => new Date("2026-07-04T14:30:00.000Z"),
      nowIso: () => "2026-07-04T14:30:00.000Z",
    },
    random: createSeededRandom(1),
    log: createLogger(logs),
    actions,
    marketData,
    historical,
    orders: {
      async current() {
        return dataGate(() => ({ ok: true, value: fixtureData.currentOrders ?? [] }));
      },
      async history(request) {
        return dataGate(() => {
          const allOrders = fixtureData.orderHistory ?? [];
          const filtered = request?.instrumentId
            ? allOrders.filter((order) => order.instrumentId === request.instrumentId)
            : allOrders;
          return { ok: true, value: filtered.slice(0, request?.limit ?? filtered.length) };
        });
      },
      placeLimit: actions.submitLimit,
      placeMarket: actions.submitMarket,
      modify: actions.modify,
      cancel: actions.cancel,
      cancelAll: actions.cancelAll,
    },
  };
}

export function createBotActionFactoryV1(): BotActionFactoryV1 {
  return {
    submitLimit: (order) => ({ type: "submit_limit", order }),
    submitMarket: (order) => ({ type: "submit_market", order }),
    modify: (order) => ({ type: "modify_order", order }),
    cancel: (order) => ({ type: "cancel_order", order }),
    cancelAll: (instrumentId) => (instrumentId === undefined ? { type: "cancel_all" } : { type: "cancel_all", instrumentId }),
    noop: (reason) => (reason === undefined ? { type: "noop" } : { type: "noop", reason }),
  };
}

function readMetadata(BotClass: ReefBotV1Constructor, issues: BotRegistrationIssueV1[]): ReefBotMetadataV1 | undefined {
  const metadata = BotClass.metadata;
  if (!metadata || typeof metadata !== "object") {
    issues.push(errorIssue("missing_metadata", "Bot class must define static metadata."));
    return undefined;
  }

  for (const field of requiredMetadataFields) {
    if (typeof metadata[field] !== "string" || metadata[field].trim().length === 0) {
      issues.push(errorIssue("invalid_metadata", `Metadata field ${field} is required.`));
    }
  }

  if (metadata.email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(metadata.email)) {
    issues.push(errorIssue("invalid_email", "Metadata email must pass basic email syntax validation."));
  }

  if (metadata.botApiVersion !== REEF_BOT_API_VERSION_V1) {
    issues.push(errorIssue("unsupported_bot_api_version", `Unsupported bot API version ${metadata.botApiVersion}.`));
  }

  return metadata;
}

function isOrderAction(action: BotActionV1): boolean {
  return action.type !== "noop";
}

function createConfig(values: Record<string, string | number | boolean>): BotConfigV1 {
  return {
    string: (key) => requiredValue(values, key, "string"),
    number: (key) => requiredValue(values, key, "number"),
    boolean: (key) => requiredValue(values, key, "boolean"),
    optionalString: (key) => optionalValue(values, key, "string"),
    optionalNumber: (key) => optionalValue(values, key, "number"),
    optionalBoolean: (key) => optionalValue(values, key, "boolean"),
  };
}

function requiredValue<T extends "string" | "number" | "boolean">(
  values: Record<string, string | number | boolean>,
  key: string,
  type: T,
): T extends "string" ? string : T extends "number" ? number : boolean {
  const value = optionalValue(values, key, type);
  if (value === undefined) {
    throw new Error(`Missing required bot config ${key}.`);
  }
  return value as T extends "string" ? string : T extends "number" ? number : boolean;
}

function optionalValue<T extends "string" | "number" | "boolean">(
  values: Record<string, string | number | boolean>,
  key: string,
  type: T,
): (T extends "string" ? string : T extends "number" ? number : boolean) | undefined {
  const value = values[key];
  if (value === undefined) {
    return undefined;
  }
  if (typeof value !== type) {
    throw new Error(`Bot config ${key} must be ${type}.`);
  }
  return value as T extends "string" ? string : T extends "number" ? number : boolean;
}

function createSeededRandom(seed: number): BotRandomV1 {
  let state = seed >>> 0;
  return {
    next() {
      state = (1664525 * state + 1013904223) >>> 0;
      return state / 0x100000000;
    },
    integer(minInclusive, maxInclusive) {
      const value = this.next();
      return Math.floor(value * (maxInclusive - minInclusive + 1)) + minInclusive;
    },
  };
}

function createLogger(logs: BotLogEntryV1[]): BotLoggerV1 {
  return {
    info: (message, fields) => logs.push(logEntry("info", message, fields)),
    warn: (message, fields) => logs.push(logEntry("warn", message, fields)),
    error: (message, fields) => logs.push(logEntry("error", message, fields)),
  };
}

function logEntry(
  level: BotLogEntryV1["level"],
  message: string,
  fields: Record<string, unknown> | undefined,
): BotLogEntryV1 {
  return fields === undefined ? { level, message } : { level, message, fields };
}

function hasError(issues: readonly BotRegistrationIssueV1[]): boolean {
  return issues.some((issue) => issue.severity === "error");
}

function errorIssue(code: string, message: string): BotRegistrationIssueV1 {
  return { code, message, severity: "error" };
}

function rateLimitDenial(message: string): BotDenialV1 {
  return { code: "RATE_LIMITED", message, retryAfterMs: 1000 };
}

function notFoundDenial(message: string): BotDenialV1 {
  return { code: "NOT_FOUND", message };
}
