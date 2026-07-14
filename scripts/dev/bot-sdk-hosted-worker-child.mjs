import "ses";
import { stdin } from "node:process";
import { pathToFileURL } from "node:url";
import { hostedSesLockdownOptions } from "./lib/bot-isolation.mjs";

lockdown(hostedSesLockdownOptions);

const repoRoot = new URL("../../", import.meta.url).pathname;
const hostedRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/hosted-runner.ts`).href);
const venueClient = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/venue-client.ts`).href);
const liveClient = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/live-client.ts`).href);
const payload = JSON.parse(await readStdin());
const liveClientOptions = payload.liveClientOptions;

const report = await hostedRunner.runHostedBotScenarioV1({
  source: payload.source,
  fileName: payload.fileName,
  fixture: payload.fixture,
  ...(payload.readMode === undefined ? {} : { readMode: payload.readMode }),
  ...(payload.executionLimits === undefined ? {} : { executionLimits: payload.executionLimits }),
  ...(payload.venueUrl === undefined ? {} : { venueTransport: venueClient.createVenueHttpTransportV1({ baseUrl: payload.venueUrl }) }),
  ...(liveClientOptions === undefined
    ? {}
    : {
        dataAvailabilityClient: liveClient.createLiveDataAvailabilityClientV1(liveClientOptions),
        readClients: {
          marketData: liveClient.createLiveMarketDataClientV1(liveClientOptions),
          historical: liveClient.createLiveHistoricalDataClientV1(liveClientOptions),
          orders: liveClient.createLiveOwnOrdersReadClientV1(liveClientOptions),
        },
      }),
});

console.log(JSON.stringify(report));
if (report.status !== "completed") {
  process.exit(1);
}

async function readStdin() {
  let body = "";
  stdin.setEncoding("utf8");
  for await (const chunk of stdin) {
    body += chunk;
  }
  return body;
}
