import {
  REEF_BOT_API_VERSION_V1,
  REEF_BOT_SDK_VERSION,
  ReefBotV1,
  type BotActionV1,
  type BotContextV1,
  type BotDataAvailabilityClientV1,
  type BotDenialV1,
  type BotReadClientsV1,
  type BotSignalV1,
  type BotStrategyEventV1,
  type BotStrategyV1,
  type DataAvailabilityV1,
} from "./index";

declare const setTimeout: (callback: () => void, delayMs: number) => unknown;
declare const clearTimeout: (timeoutId: unknown) => void;
import type { ReefBotV1Constructor } from "./harness";
import type { ReefBotV1Instance } from "./harness";
import { type BotScenarioFixtureV1 } from "./runner";
import { reefBotHostedSandboxPolicyV1, scanBotSourceForSandboxViolationsV1 } from "./sandbox-policy";
import { runBotStrategyScenarioV1, type BotStrategyScenarioRunReportV1 } from "./strategy-runner";
import type { VenueCommandTransportV1 } from "./venue-client";

export interface BotHostedCompartmentV1 {
  evaluate(source: string): unknown;
}

export interface BotHostedCompartmentFactoryV1 {
  create(options: BotHostedCompartmentOptionsV1): BotHostedCompartmentV1;
}

export interface BotHostedCompartmentOptionsV1 {
  readonly name: string;
  readonly endowments: Record<string, unknown>;
}

export interface BotHostedExecutionLimitsV1 {
  readonly lifecycleTimeoutMs: number;
  readonly tickTimeoutMs: number;
}

export interface BotHostedLoadOptionsV1 {
  readonly source: string;
  readonly fileName: string;
  readonly compartmentFactory?: BotHostedCompartmentFactoryV1;
  readonly sdkModule?: Record<string, unknown>;
  readonly executionLimits?: Partial<BotHostedExecutionLimitsV1>;
}

export type BotHostedLoadResultV1 =
  | {
      readonly ok: true;
      readonly BotClass: ReefBotV1Constructor;
    }
  | {
      readonly ok: false;
      readonly issues: readonly BotHostedRunnerIssueV1[];
    };

export interface BotHostedRunnerIssueV1 {
  readonly code: string;
  readonly message: string;
}

export type BotHostedReadModeV1 = "fixture" | "live";

export interface BotHostedScenarioOptionsV1 extends BotHostedLoadOptionsV1 {
  readonly fixture: BotScenarioFixtureV1;
  readonly venueTransport?: VenueCommandTransportV1;
  readonly readMode?: BotHostedReadModeV1;
  readonly readClients?: BotReadClientsV1;
  readonly dataAvailabilityClient?: BotDataAvailabilityClientV1;
}

export interface BotHostedScenarioRunReportV1 extends BotStrategyScenarioRunReportV1 {
  readonly readMode: BotHostedReadModeV1;
  readonly dataAvailability?: DataAvailabilityV1;
}

export const defaultBotHostedExecutionLimitsV1: BotHostedExecutionLimitsV1 = {
  lifecycleTimeoutMs: 1000,
  tickTimeoutMs: 1000,
};

export interface SesCompartmentConstructorV1 {
  new (endowments?: Record<string, unknown>, modules?: Record<string, unknown>, options?: Record<string, unknown>): BotHostedCompartmentV1;
}

export function createSesCompartmentFactoryV1(
  CompartmentCtor: SesCompartmentConstructorV1 = requiredGlobalSesCompartmentV1(),
): BotHostedCompartmentFactoryV1 {
  return {
    create(options) {
      return new CompartmentCtor(options.endowments, {}, { name: options.name });
    },
  };
}

export async function loadHostedBotClassV1(options: BotHostedLoadOptionsV1): Promise<BotHostedLoadResultV1> {
  const policyViolations = scanBotSourceForSandboxViolationsV1(options.source);
  if (policyViolations.length > 0) {
    return {
      ok: false,
      issues: policyViolations.map((violation) => ({
        code: violation.code,
        message: violation.message,
      })),
    };
  }

  const factory = options.compartmentFactory ?? createSesCompartmentFactoryV1();
  const compartment = factory.create({
    name: `reef-bot:${options.fileName}`,
    endowments: hostedEndowmentsV1(options.sdkModule ?? defaultHostedSdkModuleV1()),
  });

  try {
    const namespace = compartment.evaluate(wrapHostedBotModuleSourceV1(options.source));
    const BotClass = readDefaultExport(namespace);
    if (typeof BotClass !== "function") {
      return { ok: false, issues: [{ code: "missing_default_class", message: "Hosted bot bundle must export a default class." }] };
    }
    return {
      ok: true,
      BotClass: wrapHostedBotClassWithExecutionGuardsV1(
        BotClass as ReefBotV1Constructor,
        { ...defaultBotHostedExecutionLimitsV1, ...options.executionLimits },
      ),
    };
  } catch (error) {
    return {
      ok: false,
      issues: [{ code: "hosted_compartment_evaluation_failed", message: error instanceof Error ? error.message : String(error) }],
    };
  }
}

export async function runHostedBotScenarioV1(options: BotHostedScenarioOptionsV1): Promise<BotHostedScenarioRunReportV1> {
  const readMode = options.readMode ?? (options.readClients === undefined ? "fixture" : "live");
  const loadResult = await loadHostedBotClassV1(options);
  if (!loadResult.ok) {
    return hostedFailureReportV1(options.fixture, readMode, loadResult.issues);
  }

  let dataAvailability: DataAvailabilityV1 | undefined;
  if (options.dataAvailabilityClient !== undefined) {
    const availability = await options.dataAvailabilityClient.availability();
    if (!availability.ok) {
      return hostedFailureReportV1(
        options.fixture,
        readMode,
        [{ code: "data_availability_denial", message: availability.denial.message }],
        [availability.denial],
      );
    }
    dataAvailability = availability.value;
  }

  try {
    const report = await runBotStrategyScenarioV1({
      BotClass: loadResult.BotClass,
      fixture: options.fixture,
      ...(options.venueTransport === undefined ? {} : { venueTransport: options.venueTransport }),
      ...(options.readClients === undefined ? {} : { readClients: options.readClients }),
    });
    return {
      ...report,
      readMode,
      ...(dataAvailability === undefined ? {} : { dataAvailability }),
    };
  } catch (error) {
    return hostedFailureReportV1(
      options.fixture,
      readMode,
      [{ code: "hosted_execution_failed", message: error instanceof Error ? error.message : String(error) }],
      [],
      dataAvailability,
    );
  }
}

function hostedFailureReportV1(
  fixture: BotScenarioFixtureV1,
  readMode: BotHostedReadModeV1,
  issues: readonly BotHostedRunnerIssueV1[],
  denials: readonly BotDenialV1[] = [],
  dataAvailability?: DataAvailabilityV1,
): BotHostedScenarioRunReportV1 {
  return {
    status: "do_not_merge",
    scenarioId: fixture.scenarioId,
    runId: fixture.runId,
    ticksRun: 0,
    actionsProposed: 0,
    orderActionsProposed: 0,
    dataCalls: 0,
    signalsGenerated: 0,
    eventsProcessed: 0,
    issues,
    denials,
    logs: [],
    ticks: [],
    finalOrders: [],
    readMode,
    ...(dataAvailability === undefined ? {} : { dataAvailability }),
  };
}

export function wrapHostedBotClassWithExecutionGuardsV1(
  BotClass: ReefBotV1Constructor,
  limits: BotHostedExecutionLimitsV1 = defaultBotHostedExecutionLimitsV1,
): ReefBotV1Constructor {
  class GuardedHostedBot extends ReefBotV1 {
    private readonly inner: ReefBotV1Instance;
    override readonly strategies: readonly BotStrategyV1[];

    constructor() {
      super();
      this.inner = new BotClass();
      this.strategies = this.inner.strategies?.map((strategy) => wrapHostedStrategyV1(strategy, limits)) ?? [];
    }

    override async onStart(ctx: BotContextV1): Promise<void> {
      await withHostedTimeoutV1(this.inner.onStart(ctx), limits.lifecycleTimeoutMs, "onStart");
    }

    override async onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]> {
      return withHostedTimeoutV1(this.inner.onTick(ctx), limits.tickTimeoutMs, "onTick");
    }

    override async onSignal(
      signal: BotSignalV1,
      ctx: BotContextV1,
      event: BotStrategyEventV1,
    ): Promise<readonly BotActionV1[]> {
      return withHostedTimeoutV1(this.inner.onSignal(signal, ctx, event), limits.tickTimeoutMs, "onSignal");
    }

    override async onStop(ctx: BotContextV1): Promise<void> {
      await withHostedTimeoutV1(this.inner.onStop(ctx), limits.lifecycleTimeoutMs, "onStop");
    }
  }

  Object.defineProperty(GuardedHostedBot, "metadata", {
    value: BotClass.metadata,
    enumerable: true,
    configurable: true,
  });

  return GuardedHostedBot;
}

function wrapHostedStrategyV1(strategy: BotStrategyV1, limits: BotHostedExecutionLimitsV1): BotStrategyV1 {
  const onStart = strategy.onStart?.bind(strategy);
  const onEvent = strategy.onEvent.bind(strategy);
  const onStop = strategy.onStop?.bind(strategy);

  return {
    id: strategy.id,
    subscription: strategy.subscription,
    ...(onStart === undefined
      ? {}
      : {
          onStart(ctx: BotContextV1): Promise<void> {
            return withHostedTimeoutV1(onStart(ctx), limits.lifecycleTimeoutMs, `strategy ${strategy.id} onStart`);
          },
        }),
    onEvent(event: BotStrategyEventV1, ctx: BotContextV1): Promise<readonly BotSignalV1[]> {
      return withHostedTimeoutV1(onEvent(event, ctx), limits.tickTimeoutMs, `strategy ${strategy.id} onEvent`);
    },
    ...(onStop === undefined
      ? {}
      : {
          onStop(ctx: BotContextV1): Promise<void> {
            return withHostedTimeoutV1(onStop(ctx), limits.lifecycleTimeoutMs, `strategy ${strategy.id} onStop`);
          },
        }),
  };
}

export function wrapHostedBotModuleSourceV1(source: string): string {
  return `(function () {
const module = { exports: {} };
const exports = module.exports;
${source}
return module.exports;
})()`;
}

function withHostedTimeoutV1<T>(operation: Promise<T>, timeoutMs: number, lifecycle: string): Promise<T> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    return operation;
  }

  let timeoutId: unknown;
  const timeout = new Promise<never>((_, reject) => {
    timeoutId = setTimeout(() => {
      reject(new Error(`Hosted bot ${lifecycle} timed out after ${timeoutMs}ms.`));
    }, timeoutMs);
  });

  return Promise.race([operation, timeout]).finally(() => {
    if (timeoutId !== undefined) {
      clearTimeout(timeoutId);
    }
  });
}

function hostedEndowmentsV1(sdkModule: Record<string, unknown>): Record<string, unknown> {
  return {
    __reefBotSdk: Object.freeze({ ...sdkModule }),
  };
}

function defaultHostedSdkModuleV1(): Record<string, unknown> {
  return {
    REEF_BOT_API_VERSION_V1,
    REEF_BOT_SDK_VERSION,
    ReefBotV1,
  } satisfies Record<string, unknown>;
}

function readDefaultExport(namespace: unknown): unknown {
  if (namespace !== null && typeof namespace === "object" && "default" in namespace) {
    return (namespace as { default?: unknown }).default;
  }
  return undefined;
}

function requiredGlobalSesCompartmentV1(): SesCompartmentConstructorV1 {
  const globalWithSes = globalThis as unknown as { Compartment?: SesCompartmentConstructorV1 };
  if (globalWithSes.Compartment === undefined) {
    throw new Error("SES Compartment is not installed. Provide a compartmentFactory or install/init SES lockdown first.");
  }
  return globalWithSes.Compartment;
}
