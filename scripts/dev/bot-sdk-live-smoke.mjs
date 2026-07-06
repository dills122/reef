import http from "node:http";
import https from "node:https";
import { readFileSync } from "node:fs";
import { basename, isAbsolute, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const repoRoot = new URL("../../", import.meta.url).pathname;
const runnerUrl = new URL("../../packages/bot-sdk/src/runner.ts", import.meta.url);
const { runBotScenarioV1 } = await import(runnerUrl.href);
const venueClientUrl = new URL("../../packages/bot-sdk/src/venue-client.ts", import.meta.url);
const { createVenueHttpTransportV1 } = await import(venueClientUrl.href);
const liveClientUrl = new URL("../../packages/bot-sdk/src/live-client.ts", import.meta.url);
const {
  createLiveMarketDataClientV1,
  createLiveHistoricalDataClientV1,
  createLiveOwnOrdersReadClientV1,
  createLiveDataAvailabilityClientV1,
} = await import(liveClientUrl.href);
const preflightUrl = new URL("../../packages/bot-sdk/src/venue-preflight.ts", import.meta.url);
const { validateVenuePreflightV1 } = await import(preflightUrl.href);

const args = process.argv.slice(2);
const botPathArg = args.find((arg) => !arg.startsWith("--"));
const fixturePathArg = args.filter((arg) => !arg.startsWith("--"))[1] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const venueUrl = optionValue("--venue-url") ?? env("BOT_SDK_VENUE_URL", "");
const seedReference = args.includes("--seed-reference");
const waitTimeoutSeconds = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "120"));

if (!botPathArg || !venueUrl) {
  console.error("usage: bun scripts/dev/bot-sdk-live-smoke.mjs <bot-file.ts> [fixture.json] --venue-url=http://127.0.0.1:8080 [--seed-reference]");
  process.exit(2);
}

const botPath = isAbsolute(botPathArg) ? botPathArg : resolve(repoRoot, botPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);
const botModule = await import(pathToFileURL(botPath).href);
const rawFixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const fixture = {
  ...rawFixture,
  botId: rawFixture.botId ?? basename(botPath, ".ts"),
  botVersion: rawFixture.botVersion ?? botModule.default.metadata?.version ?? "0.0.0",
};

const preflight = validateVenuePreflightV1(fixture);
console.log("Bot SDK venue preflight:");
console.log(JSON.stringify(preflight, null, 2));
if (preflight.status !== "ready") {
  process.exit(1);
}

await waitForHttp(`${venueUrl}/health`, waitTimeoutSeconds);
const liveClientOptions = { baseUrl: venueUrl, participantId: fixture.participantId };
const availabilityClient = createLiveDataAvailabilityClientV1(liveClientOptions);
const availability = await availabilityClient.availability();
console.log("Bot SDK venue data availability:");
console.log(JSON.stringify(availability, null, 2));
if (!availability.ok) {
  process.exit(1);
}

if (seedReference) {
  console.log("seeding reference/auth data inferred from fixture...");
  await seedReferenceData(venueUrl, fixture, preflight.requirements);
}

const report = await runBotScenarioV1({
  BotClass: botModule.default,
  fixture,
  venueTransport: createVenueHttpTransportV1({ baseUrl: venueUrl }),
  readClients: {
    marketData: createLiveMarketDataClientV1(liveClientOptions),
    historical: createLiveHistoricalDataClientV1(liveClientOptions),
    orders: createLiveOwnOrdersReadClientV1(liveClientOptions),
  },
});

console.log(JSON.stringify(report, null, 2));

if (report.status !== "completed") {
  process.exit(1);
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}

async function seedReferenceData(baseUrl, fixture, requirements) {
  const internal = { "X-Reef-Internal-Route": "true" };
  for (const requirement of requirements) {
    if (requirement.kind === "instrument") {
      await postJson(`${baseUrl}/reference/instruments`, {
        instrumentId: requirement.id,
        symbol: requirement.id,
        assetClass: "US_EQ",
        currency: "USD",
      }, internal);
    }
  }

  await postJson(`${baseUrl}/reference/participants`, {
    participantId: fixture.participantId,
    name: fixture.participantId,
  }, internal);
  await postJson(`${baseUrl}/reference/accounts`, {
    accountId: fixture.accountId,
    participantId: fixture.participantId,
    accountType: "HOUSE",
  }, internal);
  await postJson(`${baseUrl}/auth/roles`, {
    roleId: "order_trader",
    permissions: "order.submit,order.cancel,order.modify",
  }, internal);
  await postJson(`${baseUrl}/auth/actor-roles`, {
    actorId: fixture.actorId,
    roleId: "order_trader",
  }, internal);
}

async function postJson(url, payload, headers = {}) {
  const response = await request("POST", url, payload, headers, 5000);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`POST ${url} failed (${response.statusCode}): ${response.body}`);
  }
  return response.body;
}

function request(method, url, payload, headers = {}, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const body = payload == null ? "" : JSON.stringify(payload);
    const transport = parsed.protocol === "https:" ? https : http;
    const req = transport.request(parsed, {
      method,
      timeout: timeoutMs,
      headers: {
        ...(payload == null ? {} : {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        }),
        ...headers,
      },
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        resolve({ statusCode: res.statusCode ?? 0, body: data });
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}
