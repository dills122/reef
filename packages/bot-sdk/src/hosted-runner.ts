import {
  REEF_BOT_API_VERSION_V1,
  REEF_BOT_SDK_VERSION,
  ReefBotV1,
} from "./index";
import type { ReefBotV1Constructor } from "./harness";
import { runBotScenarioV1, type BotScenarioFixtureV1, type BotScenarioRunReportV1 } from "./runner";
import { reefBotHostedSandboxPolicyV1, scanBotSourceForSandboxViolationsV1 } from "./sandbox-policy";
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

export interface BotHostedLoadOptionsV1 {
  readonly source: string;
  readonly fileName: string;
  readonly compartmentFactory?: BotHostedCompartmentFactoryV1;
  readonly sdkModule?: Record<string, unknown>;
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

export interface BotHostedScenarioOptionsV1 extends BotHostedLoadOptionsV1 {
  readonly fixture: BotScenarioFixtureV1;
  readonly venueTransport?: VenueCommandTransportV1;
}

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
    return { ok: true, BotClass: BotClass as ReefBotV1Constructor };
  } catch (error) {
    return {
      ok: false,
      issues: [{ code: "hosted_compartment_evaluation_failed", message: error instanceof Error ? error.message : String(error) }],
    };
  }
}

export async function runHostedBotScenarioV1(options: BotHostedScenarioOptionsV1): Promise<BotScenarioRunReportV1> {
  const loadResult = await loadHostedBotClassV1(options);
  if (!loadResult.ok) {
    return {
      status: "do_not_merge",
      scenarioId: options.fixture.scenarioId,
      runId: options.fixture.runId,
      ticksRun: 0,
      actionsProposed: 0,
      orderActionsProposed: 0,
      dataCalls: 0,
      issues: loadResult.issues,
      denials: [],
      logs: [],
      ticks: [],
      finalOrders: [],
    };
  }

  return runBotScenarioV1({
    BotClass: loadResult.BotClass,
    fixture: options.fixture,
    ...(options.venueTransport === undefined ? {} : { venueTransport: options.venueTransport }),
  });
}

export function wrapHostedBotModuleSourceV1(source: string): string {
  return `(function () {
const module = { exports: {} };
const exports = module.exports;
${source}
return module.exports;
})()`;
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

