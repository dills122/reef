import "ses";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });

const repoRoot = new URL("../../", import.meta.url).pathname;
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-artifact-"));
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);
const { scanBotSourceForSandboxViolationsV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/sandbox-policy.ts")).href
);

await assertSimpleMarketMakerArtifact();
await assertMultiSymbolStrategyArtifact();

console.log("bot SDK hosted artifact build checks passed");

async function assertSimpleMarketMakerArtifact() {
  const artifact = buildArtifact("packages/bot-sdk/examples/simple-market-maker.ts", "simple-market-maker");
  const report = await hostedRunner.runHostedBotScenarioV1({
    source: artifact.source,
    fileName: artifact.fileName,
    fixture,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.dataCalls, 3);
  assert.equal(report.ticks[0].venueCommands.length, 2);
}

async function assertMultiSymbolStrategyArtifact() {
  const artifact = buildArtifact("packages/bot-sdk/examples/multi-symbol-strategy-bot.ts", "multi-symbol-strategy-bot");
  const report = await hostedRunner.runHostedBotScenarioV1({
    source: artifact.source,
    fileName: artifact.fileName,
    fixture: multiSymbolFixture(),
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.signalsGenerated, 6);
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.dataCalls, 6);
  assert.equal(report.ticks[0].signals.length, 2);
  assert.equal(report.ticks[0].venueCommands.length, 2);
}

function buildArtifact(entryPath, name) {
  const artifactPath = join(outDir, `${name}.bundle.js`);
  const manifestPath = join(outDir, `${name}.bundle.manifest.json`);
  const build = spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
      entryPath,
      `--out=${artifactPath}`,
      `--manifest-out=${manifestPath}`,
    ],
    { cwd: repoRoot, encoding: "utf8" },
  );

  assert.equal(build.status, 0, `artifact build failed\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);

  const source = readFileSync(artifactPath, "utf8");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  assert.equal(manifest.schemaVersion, "reef.bot.hostedArtifact.v1");
  assert.equal(manifest.entryPath, entryPath);
  assert.match(manifest.sourceHash, /^sha256:[a-f0-9]{64}$/);
  assert.match(manifest.artifactHash, /^sha256:[a-f0-9]{64}$/);
  assert.equal(source.includes("__reefBotSdk"), true);
  assert.equal(source.includes("import "), false);
  assert.equal(source.includes("export default"), false);
  assert.deepEqual(scanBotSourceForSandboxViolationsV1(source), []);

  return { source, fileName: `${name}.bundle.js` };
}

function multiSymbolFixture() {
  return {
    ...fixture,
    botId: "multi-symbol-strategy",
    actorId: "actor-multi-symbol-strategy",
    historicalBars: {
      AAPL: bars("AAPL", [
        100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
        100, 100, 100, 100, 100, 80, 85, 90, 92, 95,
      ]),
      MSFT: bars("MSFT", [
        200, 200, 200, 200, 200, 200, 200, 200, 200, 200,
        200, 200, 200, 200, 200, 220, 215, 212, 210, 205,
      ]),
    },
    ticks: fixture.ticks.map((tick) => ({
      ...tick,
      marketSnapshots: {
        ...tick.marketSnapshots,
        MSFT: {
          instrumentId: "MSFT",
          asOf: tick.occurredAt,
          bidPrice: 211,
          askPrice: 213,
          midPrice: 212,
          lastPrice: 212,
        },
        NVDA: {
          instrumentId: "NVDA",
          asOf: tick.occurredAt,
          bidPrice: 499,
          askPrice: 501,
          midPrice: 500,
          lastPrice: 500,
        },
        TSLA: {
          instrumentId: "TSLA",
          asOf: tick.occurredAt,
          bidPrice: 249,
          askPrice: 251,
          midPrice: 250,
          lastPrice: 250,
        },
      },
    })),
  };
}

function bars(instrumentId, closes) {
  return closes.map((close, index) => ({
    instrumentId,
    start: `2026-07-04T14:${String(index).padStart(2, "0")}:00.000Z`,
    end: `2026-07-04T14:${String(index + 1).padStart(2, "0")}:00.000Z`,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
    volume: 1000 + index,
  }));
}
