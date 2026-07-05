import "ses";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const source = readFileSync(join(repoRoot, "packages/bot-sdk/examples/hosted-simple-market-maker.bundle.js"), "utf8");
const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);

await assertSesHostedScenarioRuns();
await assertSesCompartmentDoesNotExposeAmbientNetwork();

console.log("bot SDK hosted SES E2E checks passed");

async function assertSesHostedScenarioRuns() {
  const report = await hostedRunner.runHostedBotScenarioV1({
    source,
    fileName: "hosted-simple-market-maker.bundle.js",
    fixture,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.orderActionsProposed, 3);
  assert.equal(report.dataCalls, 3);
  assert.equal(report.issues.length, 0);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(report.ticks[0].venueCommands[0].body.limitPrice, "99");
}

async function assertSesCompartmentDoesNotExposeAmbientNetwork() {
  const noNetworkSource = `
const { ReefBotV1 } = __reefBotSdk;
const fetchKey = "fet" + "ch";
if (typeof globalThis[fetchKey] !== "undefined") throw new Error("ambient network leaked into SES compartment");
module.exports.default = class HostedNoNetworkBot extends ReefBotV1 { async onTick(ctx) { return [ctx.actions.noop("ok")]; } };`;

  const result = await hostedRunner.loadHostedBotClassV1({
    source: noNetworkSource,
    fileName: "hosted-no-network-bot.js",
  });

  assert.equal(result.ok, true);
}
