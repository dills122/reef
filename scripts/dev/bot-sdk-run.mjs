import { readFileSync } from "node:fs";
import { basename, isAbsolute, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const runnerUrl = new URL("../../packages/bot-sdk/src/runner.ts", import.meta.url);
const { runBotScenarioV1 } = await import(runnerUrl.href);
const venueClientUrl = new URL("../../packages/bot-sdk/src/venue-client.ts", import.meta.url);
const { createVenueHttpTransportV1 } = await import(venueClientUrl.href);

const botPathArg = process.argv[2];
const fixturePathArg = process.argv[3] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const venueUrl = process.argv.find((arg) => arg.startsWith("--venue-url="))?.slice("--venue-url=".length);

if (!botPathArg) {
  console.error("usage: bun scripts/dev/bot-sdk-run.mjs <bot-file.ts> [fixture.json]");
  process.exit(2);
}

const botPath = isAbsolute(botPathArg) ? botPathArg : resolve(repoRoot, botPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);
const botModule = await import(pathToFileURL(botPath).href);
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const report = await runBotScenarioV1({
  BotClass: botModule.default,
  fixture: {
    ...fixture,
    botId: fixture.botId ?? basename(botPath, ".ts"),
    botVersion: fixture.botVersion ?? botModule.default.metadata?.version ?? "0.0.0",
  },
  ...(venueUrl === undefined ? {} : { venueTransport: createVenueHttpTransportV1({ baseUrl: venueUrl }) }),
});

console.log(JSON.stringify(report, null, 2));

if (report.status !== "completed") {
  process.exit(1);
}
