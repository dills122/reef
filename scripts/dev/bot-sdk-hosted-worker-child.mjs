import "ses";
import { stdin } from "node:process";
import { pathToFileURL } from "node:url";
import { hostedSesLockdownOptions } from "./lib/bot-isolation.mjs";

lockdown(hostedSesLockdownOptions);

const repoRoot = new URL("../../", import.meta.url).pathname;
const hostedRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/hosted-runner.ts`).href);
const payload = JSON.parse(await readStdin());

const report = await hostedRunner.runHostedBotScenarioV1({
  source: payload.source,
  fileName: payload.fileName,
  fixture: payload.fixture,
  ...(payload.executionLimits === undefined ? {} : { executionLimits: payload.executionLimits }),
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
