import { existsSync, readFileSync, readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const sdkHarnessUrl = new URL("../../packages/bot-sdk/src/harness.ts", import.meta.url);
const { qualifyBotV1, defaultBotRuntimePolicyV1 } = await import(sdkHarnessUrl.href);

const botPathArg = process.argv[2];
if (!botPathArg) {
  console.error("usage: bun scripts/dev/bot-sdk-register.mjs <bot-file.ts>");
  process.exit(2);
}

const botPath = isAbsolute(botPathArg) ? botPathArg : resolve(repoRoot, botPathArg);
const source = readFileSync(botPath, "utf8");
const sourceHash = `sha256:${createHash("sha256").update(source, "utf8").digest("hex")}`;
const botModule = await import(pathToFileURL(botPath).href);
const BotClass = botModule.default;
const botDir = dirname(botPath);
const existingFileNames = readdirSync(botDir).filter((name) => name.endsWith(".ts"));
const registryPath = resolve(repoRoot, "packages/bot-sdk/bot-registry.example.json");
const registryEntries = existsSync(registryPath)
  ? JSON.parse(readFileSync(registryPath, "utf8")).bots ?? []
  : [];
const gitAuthorEmail = readGitAuthorEmail(botPath);

const report = await qualifyBotV1({
  fileName: basename(botPath),
  source,
  BotClass,
  existingFileNames,
  registryEntries,
  gitAuthorEmail,
  sourceHash,
  tickCount: 5,
  policy: defaultBotRuntimePolicyV1,
  fixtureData: {
    config: {
      instrumentId: "AAPL",
      orderSize: 10,
      spread: 1,
    },
    marketSnapshots: {
      AAPL: {
        instrumentId: "AAPL",
        asOf: "2026-07-04T14:30:00.000Z",
        bidPrice: 99.5,
        askPrice: 100.5,
        midPrice: 100,
        lastPrice: 100,
      },
    },
    historicalBars: {
      AAPL: [
        {
          instrumentId: "AAPL",
          start: "2026-07-04T14:00:00.000Z",
          end: "2026-07-04T14:01:00.000Z",
          open: 99,
          high: 101,
          low: 98.75,
          close: 100,
          volume: 1000,
        },
      ],
    },
    currentOrders: [],
    orderHistory: [],
  },
});

console.log(JSON.stringify(report, null, 2));

if (report.status !== "accepted") {
  process.exit(1);
}

function readGitAuthorEmail(path) {
  const logResult = spawnSync("git", ["log", "-1", "--format=%ae", "--", path], {
    cwd: repoRoot,
    encoding: "utf8",
  });
  const logEmail = logResult.stdout.trim();
  if (logEmail.length > 0) {
    return logEmail;
  }

  const configResult = spawnSync("git", ["config", "user.email"], {
    cwd: repoRoot,
    encoding: "utf8",
  });
  const configEmail = configResult.stdout.trim();
  return configEmail.length > 0 ? configEmail : undefined;
}
