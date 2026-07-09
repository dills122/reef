import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const args = process.argv.slice(2);

const config = {
  durationSeconds: numberOption("--duration-seconds", 180),
  mode: stringOption("--mode", "packages/scenario-definitions/arena/equity-multi-local.v1.json"),
  venueUrl: stringOption("--venue-url", "http://127.0.0.1:8080"),
  arenaAdminUrl: stringOption("--arena-admin-url", stringOption("--venue-url", "http://127.0.0.1:8080")),
  compartment: stringOption("--compartment", "ses"),
  out: stringOption("--out", "/tmp/reef-arena-local-hardening.json"),
  summaryOut: stringOption("--summary-out", ""),
  projectionDrainTimeoutMs: numberOption("--projection-drain-timeout-ms", 60000),
  projectionDrainPollMs: numberOption("--projection-drain-poll-ms", 500),
};

const summaryOut = config.summaryOut || config.out.replace(/\.json$/i, ".summary.json");
const passthrough = args.filter((arg) =>
  !arg.startsWith("--duration-seconds=")
  && !arg.startsWith("--mode=")
  && !arg.startsWith("--venue-url=")
  && !arg.startsWith("--arena-admin-url=")
  && !arg.startsWith("--compartment=")
  && !arg.startsWith("--out=")
  && !arg.startsWith("--summary-out=")
  && !arg.startsWith("--projection-drain-timeout-ms=")
  && !arg.startsWith("--projection-drain-poll-ms="),
);

const runArgs = [
  "scripts/dev/arena-local-tick-run.mjs",
  `--mode=${config.mode}`,
  `--duration-seconds=${config.durationSeconds}`,
  "--submit-mode=live",
  `--venue-url=${config.venueUrl}`,
  `--arena-admin-url=${config.arenaAdminUrl}`,
  "--seed-reference",
  `--compartment=${config.compartment}`,
  "--command-wait-mode=terminal",
  `--projection-drain-timeout-ms=${config.projectionDrainTimeoutMs}`,
  `--projection-drain-poll-ms=${config.projectionDrainPollMs}`,
  "--require-projection-drain",
  `--out=${config.out}`,
  ...passthrough,
];

const result = spawnSync(process.execPath, runArgs, {
  cwd: new URL("../../", import.meta.url).pathname,
  stdio: "inherit",
});
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const report = JSON.parse(readFileSync(config.out, "utf8"));
const summary = hardeningSummary(report);
mkdirSync(dirname(summaryOut), { recursive: true });
writeFileSync(summaryOut, `${JSON.stringify(summary, null, 2)}\n`);

console.log(`arena local hardening summary: ${resolve(summaryOut)}`);
console.log(
  `hardening=${summary.status} duration=${summary.durationSeconds}s commands=${summary.commands.total} timedOut=${summary.commands.timedOut} health=${summary.health.status} houseCommands=${summary.house.submittedCommands}`,
);

if (summary.status !== "pass") {
  process.exitCode = 1;
}

function hardeningSummary(report) {
  const house = report.activityBySchedulingClass?.house_responsive ?? {};
  const commandStatusSummary = report.commandStatusSummary ?? {};
  const healthSummary = report.healthSummary ?? {};
  const rejectSummary = summarizeRejects(report);
  const ownOrderCounts = (report.venueReadback?.ownOrders ?? [])
    .filter((entry) => String(entry.botId ?? "").startsWith("builtin-mm"))
    .map((entry) => ({
      botId: entry.botId,
      current: entry.current?.body?.orders?.length ?? 0,
      history: entry.history?.body?.orders?.length ?? 0,
    }));
  const failures = [];
  if (report.status !== "completed") failures.push(`report status ${report.status}`);
  if (commandStatusSummary.timedOut !== 0) failures.push(`timed out commands ${commandStatusSummary.timedOut}`);
  if ((rejectSummary.count ?? 0) > 0) failures.push(`rejected commands ${rejectSummary.count}`);
  if ((commandStatusSummary.byFinalStatus?.COMPLETED ?? 0) + (rejectSummary.count ?? 0) !== commandStatusSummary.commandCount) failures.push("not all commands reached terminal accounting");
  if (healthSummary.status !== "pass") failures.push(`health status ${healthSummary.status}`);
  if (report.venueReadback?.projectionDrained !== true) failures.push("projection did not drain");
  if ((report.enforcementEvents ?? []).some((event) => event.decision === "freeze")) failures.push("freeze events present");
  if (ownOrderCounts.some((entry) => entry.current === 0)) failures.push("house LP own-order readback empty");

  return {
    schemaVersion: "reef.arena.localHardeningSummary.v0",
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    reportPath: resolve(config.out),
    durationSeconds: report.runPlan?.durationSeconds ?? config.durationSeconds,
    startedAt: report.startedAt,
    completedAt: report.completedAt,
    elapsedMs: report.elapsedMs,
    runPlan: {
      modeId: report.modeId,
      selectedBotCount: report.runPlan?.selectedBotCount,
      instruments: report.runPlan?.instruments,
      schedulingMode: report.runPlan?.schedulingMode,
      totalTickCount: report.runPlan?.totalTickCount,
    },
    commands: {
      total: commandStatusSummary.commandCount ?? 0,
      timedOut: commandStatusSummary.timedOut ?? 0,
      byRoute: commandStatusSummary.byRoute ?? {},
      byFinalStatus: commandStatusSummary.byFinalStatus ?? {},
      rejects: rejectSummary,
      avgIntakeElapsedMs: commandStatusSummary.avgIntakeElapsedMs ?? 0,
      avgStatusElapsedMs: commandStatusSummary.avgStatusElapsedMs ?? 0,
    },
    health: {
      status: healthSummary.status ?? "unknown",
      topOfBookPct: healthSummary.topOfBookPct ?? 0,
      depthPct: healthSummary.depthPct ?? 0,
      medianQuotedSpreadBps: healthSummary.medianQuotedSpreadBps ?? null,
      p95QuotedSpreadBps: healthSummary.p95QuotedSpreadBps ?? null,
      crossedBookCount: healthSummary.crossedBookCount ?? 0,
      emptyBookCount: healthSummary.emptyBookCount ?? 0,
    },
    house: {
      botCount: house.botCount ?? 0,
      ticks: house.ticks ?? 0,
      submittedCommands: house.submittedCommands ?? 0,
      timedOutCommands: house.timedOutCommands ?? 0,
      dataCalls: house.dataCalls ?? 0,
      ownOrderCounts,
    },
    activityBySchedulingClass: report.activityBySchedulingClass ?? {},
  };
}

function summarizeRejects(report) {
  const commands = (report.sessionReports ?? [])
    .flatMap((session) => session.ticks ?? [])
    .flatMap((tick) => tick.submission?.commands ?? [])
    .filter((command) => command.finalStatus === "REJECTED" || command.rejected === true);
  const byCode = {};
  const byBotId = {};
  for (const command of commands) {
    const payload = safeJson(command.statusBody?.responsePayloadJson);
    const code = payload?.rejected?.code ?? command.statusBody?.resultStatus ?? "unknown";
    byCode[code] = Number(byCode[code] ?? 0) + 1;
    const botId = botIdFromCommandId(command.commandId);
    byBotId[botId] = Number(byBotId[botId] ?? 0) + 1;
  }
  return { count: commands.length, byCode, byBotId };
}

function safeJson(value) {
  if (typeof value !== "string" || value.length === 0) return {};
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

function botIdFromCommandId(commandId) {
  const value = String(commandId ?? "");
  const match = value.match(/^equity-multi-local-(.+)-\d+-cmd-/);
  return match?.[1] ?? "unknown";
}

function stringOption(name, fallback) {
  const prefix = `${name}=`;
  const found = args.find((arg) => arg.startsWith(prefix));
  return found === undefined ? fallback : found.slice(prefix.length);
}

function numberOption(name, fallback) {
  const value = Number(stringOption(name, ""));
  return Number.isFinite(value) && value > 0 ? value : fallback;
}
