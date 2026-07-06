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
  type BotSignalV1,
  type BotStrategyEventV1,
  type BotStrategyV1,
  type HistoricalBarV1,
  type MarketSnapshotV1,
  type OwnOrderV1,
  type ReefBotMetadataV1,
} from "./index";
import { reefBotHostedSourceSandboxPolicyV1, scanBotSourceForSandboxViolationsV1 } from "./sandbox-policy";

export interface ReefBotV1Instance {
  readonly strategies?: readonly BotStrategyV1[];
  onStart(ctx: BotContextV1): Promise<void>;
  onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]>;
  onSignal(signal: BotSignalV1, ctx: BotContextV1, event: BotStrategyEventV1): Promise<readonly BotActionV1[]>;
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
  readonly sourceHash: string;
  readonly metadata?: ReefBotMetadataV1;
  readonly registryEntry?: BotRegistryEntryV1;
  readonly gitAuthorEmail?: string;
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
  readonly registryEntries?: readonly BotRegistryEntryV1[];
  readonly gitAuthorEmail?: string;
  readonly sourceHash?: string;
  readonly tickCount?: number;
  readonly policy?: Partial<BotRuntimePolicyV1>;
  readonly fixtureData?: BotFixtureDataV1;
}

export interface BotLogEntryV1 {
  readonly level: "info" | "warn" | "error";
  readonly message: string;
  readonly fields?: Record<string, unknown>;
}

export type BotRegistryStatusV1 = "draft" | "approved" | "quarantined" | "banned";

export interface BotRegistryEntryV1 {
  readonly fileName: string;
  readonly botId: string;
  readonly owner: string;
  readonly publisher: string;
  readonly approvedVersion: string;
  readonly status: BotRegistryStatusV1;
  readonly artifactHash?: string;
}

const requiredMetadataFields: readonly (keyof ReefBotMetadataV1)[] = [
  "name",
  "publisher",
  "email",
  "version",
  "sdkVersion",
  "botApiVersion",
];

export function validateBotRegistrationV1(options: {
  readonly fileName: string;
  readonly source: string;
  readonly BotClass: ReefBotV1Constructor;
  readonly existingFileNames?: readonly string[];
  readonly registryEntries?: readonly BotRegistryEntryV1[];
  readonly gitAuthorEmail?: string;
  readonly sourceHash?: string;
}): BotRegistrationReportV1 {
  const issues: BotRegistrationIssueV1[] = [];
  const metadata = readMetadata(options.BotClass, issues);
  const sourceHash = options.sourceHash ?? "";

  if (!options.fileName.endsWith(".ts")) {
    issues.push(errorIssue("invalid_file_extension", "Bot entry file must be a TypeScript .ts file."));
  }

  if ((options.existingFileNames ?? []).filter((name) => name === options.fileName).length > 1) {
    issues.push(errorIssue("duplicate_file_name", `Bot filename ${options.fileName} is not unique.`));
  }

  const registryEntries = options.registryEntries ?? [];
  const registryFileNames = registryEntries.map((entry) => entry.fileName);
  const registryDuplicates = duplicateValues(registryFileNames);
  for (const duplicateFileName of registryDuplicates) {
    issues.push(errorIssue("duplicate_registry_file_name", `Bot registry contains duplicate filename ${duplicateFileName}.`));
  }

  if (registryFileNames.length > 0 && !registryFileNames.includes(options.fileName)) {
    issues.push(errorIssue("unregistered_file_name", `Bot filename ${options.fileName} is not present in the registry.`));
  }

  const registryEntry = registryEntries.find((entry) => entry.fileName === options.fileName);
  validateRegistryEntry(registryEntry, metadata, sourceHash, issues);

  if (options.gitAuthorEmail !== undefined && !isBasicEmail(options.gitAuthorEmail)) {
    issues.push(errorIssue("invalid_git_author_email", "Git author email must pass basic email syntax validation."));
  }

  for (const violation of scanBotSourceForSandboxViolationsV1(options.source, reefBotHostedSourceSandboxPolicyV1)) {
    issues.push(errorIssue(violation.code, violation.message));
  }

  if (typeof options.BotClass !== "function") {
    issues.push(errorIssue("missing_default_class", "Bot file must default-export a class."));
  }

  return {
    status: hasError(issues) ? "do_not_merge" : "accepted",
    fileName: options.fileName,
    sourceHash,
    ...(metadata === undefined ? {} : { metadata }),
    ...(registryEntry === undefined ? {} : { registryEntry }),
    ...(options.gitAuthorEmail === undefined ? {} : { gitAuthorEmail: options.gitAuthorEmail }),
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
    async snapshots(instrumentIds) {
      return dataGate(() => {
        const values: Record<string, MarketSnapshotV1> = {};
        const missing: string[] = [];
        for (const instrumentId of instrumentIds) {
          const snapshot = fixtureData.marketSnapshots?.[instrumentId];
          if (!snapshot) {
            missing.push(instrumentId);
          } else {
            values[instrumentId] = snapshot;
          }
        }
        if (missing.length > 0) {
          const denial = notFoundDenial(`No market snapshot for ${missing.join(", ")}.`);
          denials.push(denial);
          return { ok: false, denial };
        }
        return { ok: true, value: values };
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
    async intradayBarsBatch(requests) {
      const result: Record<string, readonly HistoricalBarV1[]> = {};
      const misses = requests.filter((request) => {
        const cacheKey = `${request.instrumentId}:${request.interval}:${request.start}:${request.end}`;
        const cached = historicalCache.get(cacheKey);
        if (cached !== undefined) {
          result[request.instrumentId] = cached;
          return false;
        }
        return true;
      });
      if (misses.length === 0) {
        return { ok: true, value: result, cached: true };
      }

      return dataGate(() => {
        const missing: string[] = [];
        for (const request of misses) {
          const bars = fixtureData.historicalBars?.[request.instrumentId];
          if (!bars) {
            missing.push(request.instrumentId);
          } else {
            const cacheKey = `${request.instrumentId}:${request.interval}:${request.start}:${request.end}`;
            historicalCache.set(cacheKey, bars);
            result[request.instrumentId] = bars;
          }
        }
        if (missing.length > 0) {
          const denial = notFoundDenial(`No historical bars for ${missing.join(", ")}.`);
          denials.push(denial);
          return { ok: false, denial };
        }
        return { ok: true, value: result };
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
      safe: {
        async modify(order) {
          return safeModifyOrder(order, fixtureData.currentOrders ?? [], actions);
        },
        async cancel(order) {
          return safeCancelOrder(order, fixtureData.currentOrders ?? [], actions);
        },
      },
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

  if (metadata.name && !/^[a-z0-9][a-z0-9._-]{2,63}$/.test(metadata.name)) {
    issues.push(
      errorIssue(
        "invalid_bot_name",
        "Metadata name must be 3-64 characters and use lowercase letters, numbers, dots, underscores, or hyphens.",
      ),
    );
  }

  if (metadata.version && !isSemverLike(metadata.version)) {
    issues.push(errorIssue("invalid_bot_version", "Metadata version must be semver-like, for example 1.0.0."));
  }

  if (metadata.sdkVersion && !isSemverLike(metadata.sdkVersion)) {
    issues.push(errorIssue("invalid_sdk_version", "Metadata sdkVersion must be semver-like, for example 1.0.0."));
  }

  if (metadata.email && !isBasicEmail(metadata.email)) {
    issues.push(errorIssue("invalid_email", "Metadata email must pass basic email syntax validation."));
  }

  if (metadata.botApiVersion !== REEF_BOT_API_VERSION_V1) {
    issues.push(errorIssue("unsupported_bot_api_version", `Unsupported bot API version ${metadata.botApiVersion}.`));
  }

  return metadata;
}

function validateRegistryEntry(
  entry: BotRegistryEntryV1 | undefined,
  metadata: ReefBotMetadataV1 | undefined,
  sourceHash: string,
  issues: BotRegistrationIssueV1[],
): void {
  if (entry === undefined) {
    return;
  }

  if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(entry.botId)) {
    issues.push(errorIssue("invalid_registry_bot_id", "Registry botId must be 3-64 lowercase identifier characters."));
  }
  if (!isSemverLike(entry.approvedVersion)) {
    issues.push(errorIssue("invalid_registry_version", "Registry approvedVersion must be semver-like."));
  }
  if (!["draft", "approved", "quarantined", "banned"].includes(entry.status)) {
    issues.push(errorIssue("invalid_registry_status", `Unsupported registry status ${entry.status}.`));
  }
  if (entry.artifactHash !== undefined && !/^sha256:[a-f0-9]{64}$/.test(entry.artifactHash)) {
    issues.push(errorIssue("invalid_registry_artifact_hash", "Registry artifactHash must be a sha256:<hex> value."));
  }
  if (entry.artifactHash !== undefined && sourceHash.length > 0 && entry.artifactHash !== sourceHash) {
    issues.push(errorIssue("registry_artifact_hash_mismatch", "Registry artifactHash must match the bot source hash."));
  }
  if (metadata !== undefined && entry.publisher !== metadata.publisher) {
    issues.push(errorIssue("registry_publisher_mismatch", "Registry publisher must match bot metadata publisher."));
  }
  if (metadata !== undefined && entry.approvedVersion !== metadata.version) {
    issues.push(errorIssue("registry_version_mismatch", "Registry approvedVersion must match bot metadata version."));
  }
}

function isOrderAction(action: BotActionV1): boolean {
  return action.type !== "noop";
}

export function safeModifyOrder(
  order: Parameters<BotActionFactoryV1["modify"]>[0],
  currentOrders: readonly OwnOrderV1[],
  actions: BotActionFactoryV1,
): BotResultV1<BotActionV1> {
  const existingOrder = currentOrders.find((currentOrder) => currentOrder.orderId === order.orderId);
  const denial = validateOwnOrderAction(existingOrder, order.instrumentId, "modify");
  if (denial !== undefined) {
    return { ok: false, denial };
  }
  return { ok: true, value: actions.modify(order) };
}

export function safeCancelOrder(
  order: Parameters<BotActionFactoryV1["cancel"]>[0],
  currentOrders: readonly OwnOrderV1[],
  actions: BotActionFactoryV1,
): BotResultV1<BotActionV1> {
  const existingOrder = currentOrders.find((currentOrder) => currentOrder.orderId === order.orderId);
  const denial = validateOwnOrderAction(existingOrder, order.instrumentId, "cancel");
  if (denial !== undefined) {
    return { ok: false, denial };
  }
  return { ok: true, value: actions.cancel(order) };
}

function validateOwnOrderAction(
  order: OwnOrderV1 | undefined,
  instrumentId: string,
  action: "modify" | "cancel",
): BotDenialV1 | undefined {
  if (order === undefined) {
    return { code: "NOT_FOUND", message: `Cannot ${action} unknown order.` };
  }
  if (order.instrumentId !== instrumentId) {
    return { code: "NOT_ALLOWED", message: `Cannot ${action} order on a different instrument.` };
  }
  if (order.status !== "OPEN" && order.status !== "PARTIALLY_FILLED") {
    return { code: "NOT_ALLOWED", message: `Cannot ${action} order in ${order.status} state.` };
  }
  if (order.remainingQuantity <= 0) {
    return { code: "NOT_ALLOWED", message: `Cannot ${action} order with no remaining quantity.` };
  }
  return undefined;
}

export function createConfig(values: Record<string, string | number | boolean>): BotConfigV1 {
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

export function createSeededRandom(seed: number): BotRandomV1 {
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

export function createLogger(logs: BotLogEntryV1[]): BotLoggerV1 {
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

function duplicateValues(values: readonly string[]): readonly string[] {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const value of values) {
    if (seen.has(value)) {
      duplicates.add(value);
    }
    seen.add(value);
  }
  return Array.from(duplicates).sort();
}

function isBasicEmail(value: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value);
}

function isSemverLike(value: string): boolean {
  return /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(value);
}
