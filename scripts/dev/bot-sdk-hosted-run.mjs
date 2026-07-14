import vm from "node:vm";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { basename, isAbsolute, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { hostedSesLockdownOptions } from "./lib/bot-isolation.mjs";

const repoRoot = new URL("../../", import.meta.url).pathname;
const hostedRunnerUrl = new URL("../../packages/bot-sdk/src/hosted-runner.ts", import.meta.url);
const venueClientUrl = new URL("../../packages/bot-sdk/src/venue-client.ts", import.meta.url);
const liveClientUrl = new URL("../../packages/bot-sdk/src/live-client.ts", import.meta.url);

const args = process.argv.slice(2);
const artifactPathArg = args.find((arg) => !arg.startsWith("--"));
const fixturePathArg = args.filter((arg) => !arg.startsWith("--"))[1] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const venueUrl = optionValue("--venue-url");
const readMode = optionValue("--read-mode") ?? "fixture";
const isolation = optionValue("--isolation") ?? "ses";
const participantId = optionValue("--participant-id") ?? undefined;
const unsafeVmForLocalDev = args.includes("--unsafe-vm-for-local-dev");
const tickTimeoutMs = numberOption("--tick-timeout-ms");
const lifecycleTimeoutMs = numberOption("--lifecycle-timeout-ms");

if (!artifactPathArg) {
  console.error("usage: bun scripts/dev/bot-sdk-hosted-run.mjs <compiled-bot.js> [fixture.json] [--isolation=ses|worker|container] [--unsafe-vm-for-local-dev] [--venue-url=http://127.0.0.1:8080] [--read-mode=fixture|live] [--participant-id=participant-1] [--tick-timeout-ms=1000] [--lifecycle-timeout-ms=1000]");
  process.exit(2);
}

if (!["ses", "worker", "container"].includes(isolation)) {
  throw new Error(`--isolation must be ses, worker, or container; got ${isolation}`);
}
if (!["fixture", "live"].includes(readMode)) {
  throw new Error(`--read-mode must be fixture or live; got ${readMode}`);
}
if (readMode === "live" && venueUrl === undefined) {
  throw new Error("--read-mode=live requires --venue-url");
}

const artifactPath = isAbsolute(artifactPathArg) ? artifactPathArg : resolve(repoRoot, artifactPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);

if (isolation !== "ses") {
  if (unsafeVmForLocalDev) {
    throw new Error("--unsafe-vm-for-local-dev is only valid with --isolation=ses");
  }
  if (venueUrl !== undefined || readMode === "live") {
    throw new Error("--isolation=worker|container currently supports fixture-only hosted runs; use --isolation=ses for live venue transport");
  }
  const forwardedArgs = [
    "scripts/dev/bot-sdk-hosted-worker-run.mjs",
    artifactPath,
    fixturePath,
    `--isolation=${isolation === "container" ? "container" : "worker"}`,
    ...(tickTimeoutMs === undefined ? [] : [`--tick-timeout-ms=${tickTimeoutMs}`]),
    ...(lifecycleTimeoutMs === undefined ? [] : [`--lifecycle-timeout-ms=${lifecycleTimeoutMs}`]),
  ];
  const run = spawnSync("bun", forwardedArgs, { cwd: repoRoot, stdio: "inherit" });
  process.exit(run.status ?? 1);
}

if (!unsafeVmForLocalDev) {
  await import("ses");
  lockdown(hostedSesLockdownOptions);
}

const { runHostedBotScenarioV1 } = await import(hostedRunnerUrl.href);
const { createVenueHttpTransportV1 } = await import(venueClientUrl.href);
const {
  createLiveMarketDataClientV1,
  createLiveHistoricalDataClientV1,
  createLiveOwnOrdersReadClientV1,
  createLiveDataAvailabilityClientV1,
} = await import(liveClientUrl.href);

const source = readFileSync(artifactPath, "utf8");
const rawFixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const fixture = {
  ...rawFixture,
  botId: rawFixture.botId ?? basename(artifactPath, ".js"),
};

const executionLimits = {
  ...(tickTimeoutMs === undefined ? {} : { tickTimeoutMs }),
  ...(lifecycleTimeoutMs === undefined ? {} : { lifecycleTimeoutMs }),
};
const liveClientOptions = readMode === "live"
  ? { baseUrl: venueUrl, participantId: participantId ?? fixture.participantId }
  : undefined;

const report = await runHostedBotScenarioV1({
  source,
  fileName: basename(artifactPath),
  fixture,
  readMode,
  ...(Object.keys(executionLimits).length === 0 ? {} : { executionLimits }),
  ...(unsafeVmForLocalDev ? { compartmentFactory: createVmCompartmentFactory() } : {}),
  ...(venueUrl === undefined ? {} : { venueTransport: createVenueHttpTransportV1({ baseUrl: venueUrl }) }),
  ...(liveClientOptions === undefined
    ? {}
    : {
        dataAvailabilityClient: createLiveDataAvailabilityClientV1(liveClientOptions),
        readClients: {
          marketData: createLiveMarketDataClientV1(liveClientOptions),
          historical: createLiveHistoricalDataClientV1(liveClientOptions),
          orders: createLiveOwnOrdersReadClientV1(liveClientOptions),
        },
      }),
});

console.log(JSON.stringify(report, null, 2));

if (report.status !== "completed") {
  process.exit(1);
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}

function numberOption(name) {
  const raw = optionValue(name);
  if (raw === undefined) return undefined;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be numeric; got ${raw}`);
  }
  return parsed;
}

function createVmCompartmentFactory() {
  return {
    create(options) {
      return {
        evaluate(source) {
          const context = vm.createContext(Object.freeze({ ...options.endowments }));
          return new vm.Script(source, { filename: options.name }).runInContext(context, { timeout: 1000 });
        },
      };
    },
  };
}
