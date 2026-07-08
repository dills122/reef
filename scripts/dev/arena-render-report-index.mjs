import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const args = parseArgs(process.argv.slice(2));
const reportPaths = [
  ...csv(args.reports),
  ...csv(args.report),
].filter(Boolean);

if (reportPaths.length === 0 || !args.out) {
  console.error("usage: node scripts/dev/arena-render-report-index.mjs --reports=<a.json,b.json> --out=<arena-index.html>");
  process.exit(1);
}

const reports = reportPaths.map((path) => ({
  path,
  report: JSON.parse(readFileSync(path, "utf8")),
}));
const html = renderIndex(reports, { generatedAt: new Date().toISOString() });

mkdirSync(dirname(args.out), { recursive: true });
writeFileSync(args.out, html);
console.log(`arena report index written: ${resolve(args.out)}`);

function renderIndex(entries, context) {
  const rows = entries.map(({ path, report }) => {
    const mode = report.mode ?? {};
    const totals = report.totals ?? {};
    const persistence = report.persistence ?? {};
    const freezes = (report.enforcementEvents ?? []).filter((event) => event.decision === "freeze");
    const publicLeaderboard = report.leaderboard ?? [];
    const winner = publicLeaderboard[0]?.botId ?? "none";
    return {
      path,
      runId: report.runId ?? "unknown-run",
      modeId: mode.modeId ?? report.modeId ?? "unknown-mode",
      status: report.status ?? "unknown",
      scoringPolicyVersion: mode.scoringPolicyVersion ?? report.scoringPolicyVersion ?? "n/a",
      bots: report.botResults?.length ?? 0,
      ticks: totals.ticks ?? 0,
      commands: totals.venueCommands ?? 0,
      submitted: totals.submittedCommands ?? 0,
      freezes: freezes.length,
      frozenBots: freezes.map((event) => event.botId).join(", ") || "none",
      persisted: persistence.enabled && !persistence.skipped,
      persistenceMode: persistence.mode ?? "none",
      operations: Array.isArray(persistence.operations) ? persistence.operations.length : 0,
      winner,
      generatedAt: report.generatedAt ?? "n/a",
    };
  });
  const totals = rows.reduce(
    (acc, row) => {
      acc.runs += 1;
      acc.bots += row.bots;
      acc.commands += row.commands;
      acc.submitted += row.submitted;
      acc.freezes += row.freezes;
      acc.persisted += row.persisted ? 1 : 0;
      return acc;
    },
    { runs: 0, bots: 0, commands: 0, submitted: 0, freezes: 0, persisted: 0 },
  );

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Reef Arena Report Index</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f6f7f9;
      --panel: #ffffff;
      --ink: #17202a;
      --muted: #5f6b7a;
      --line: #d8dee8;
      --accent: #006b5f;
      --accent-soft: #dff3ef;
      --warn: #9a4f00;
      --warn-soft: #fff0da;
      --bad: #9d2727;
      --bad-soft: #ffe3e3;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: var(--bg);
      color: var(--ink);
    }
    header {
      background: #fff;
      border-bottom: 1px solid var(--line);
      padding: 20px 28px 16px;
    }
    main {
      max-width: 1440px;
      margin: 0 auto;
      padding: 22px 28px 40px;
    }
    h1, p { margin: 0; }
    h1 { font-size: 24px; font-weight: 740; letter-spacing: 0; }
    .subtle { color: var(--muted); font-size: 13px; margin-top: 4px; }
    .grid {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 14px;
      margin-bottom: 18px;
    }
    .section {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 18px;
      min-width: 0;
    }
    .metric .label {
      color: var(--muted);
      font-size: 12px;
      font-weight: 650;
      text-transform: uppercase;
    }
    .metric .value {
      margin-top: 8px;
      font-size: 26px;
      font-weight: 760;
      overflow-wrap: anywhere;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      table-layout: fixed;
      font-size: 13px;
    }
    th, td {
      border-bottom: 1px solid var(--line);
      padding: 10px 8px;
      text-align: left;
      vertical-align: top;
      overflow-wrap: anywhere;
    }
    th {
      color: var(--muted);
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
    }
    tr:last-child td { border-bottom: 0; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      font-size: 12px;
    }
    .pill {
      display: inline-block;
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 5px 9px;
      font-size: 12px;
      font-weight: 650;
      white-space: nowrap;
    }
    .pill.ok { background: var(--accent-soft); color: var(--accent); border-color: #a8d9d1; }
    .pill.warn { background: var(--warn-soft); color: var(--warn); border-color: #f0c684; }
    .pill.bad { background: var(--bad-soft); color: var(--bad); border-color: #efb5b5; }
    @media (max-width: 980px) {
      header { padding: 18px; }
      main { padding: 18px; }
      .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
    @media (max-width: 640px) {
      .grid { grid-template-columns: 1fr; }
      table { font-size: 12px; }
      th, td { padding: 8px 6px; }
    }
  </style>
</head>
<body>
  <header>
    <h1>Reef Arena Report Index</h1>
    <p class="subtle">${rows.length} run artifacts · rendered ${escapeHtml(context.generatedAt)}</p>
  </header>
  <main>
    <section class="grid">
      ${metric("Runs", totals.runs)}
      ${metric("Bots", totals.bots)}
      ${metric("Commands", totals.commands)}
      ${metric("Submitted", totals.submitted)}
      ${metric("Freezes", totals.freezes)}
    </section>
    <section class="section">
      <table>
        <thead>
          <tr>
            <th>Run</th>
            <th>Mode</th>
            <th>Status</th>
            <th class="num">Bots</th>
            <th class="num">Commands</th>
            <th class="num">Freezes</th>
            <th>Winner</th>
            <th>Persistence</th>
            <th>Source</th>
          </tr>
        </thead>
        <tbody>
          ${rows.map(row).join("")}
        </tbody>
      </table>
    </section>
  </main>
</body>
</html>`;
}

function row(item) {
  const statusKind = item.status.includes("freeze") ? "warn" : item.status === "completed" ? "ok" : "bad";
  return `<tr>
    <td><strong>${escapeHtml(item.runId)}</strong><div class="subtle mono">${escapeHtml(item.generatedAt)}</div></td>
    <td>${escapeHtml(item.modeId)}<div class="subtle mono">${escapeHtml(item.scoringPolicyVersion)}</div></td>
    <td><span class="pill ${statusKind}">${escapeHtml(item.status)}</span></td>
    <td class="num">${formatNumber(item.bots)}</td>
    <td class="num">${formatNumber(item.commands)}<div class="subtle">${formatNumber(item.submitted)} submitted</div></td>
    <td class="num">${formatNumber(item.freezes)}<div class="subtle">${escapeHtml(item.frozenBots)}</div></td>
    <td>${escapeHtml(item.winner)}</td>
    <td><span class="pill ${item.persisted ? "ok" : "warn"}">${item.persisted ? "persisted" : "not persisted"}</span><div class="subtle">${escapeHtml(item.persistenceMode)} · ${formatNumber(item.operations)} ops</div></td>
    <td class="mono">${escapeHtml(item.path)}</td>
  </tr>`;
}

function metric(label, value) {
  return `<div class="section metric"><div class="label">${escapeHtml(label)}</div><div class="value">${formatNumber(value)}</div></div>`;
}

function formatNumber(value) {
  const parsed = Number(value ?? 0);
  if (!Number.isFinite(parsed)) return escapeHtml(String(value));
  return parsed.toLocaleString("en-US");
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function csv(value) {
  if (value === undefined || value === true) return [];
  return String(value).split(",").map((item) => item.trim()).filter(Boolean);
}

function parseArgs(values) {
  const parsed = {};
  for (const value of values) {
    if (!value.startsWith("--")) continue;
    const index = value.indexOf("=");
    if (index === -1) {
      parsed[value.slice(2)] = true;
    } else {
      parsed[value.slice(2, index)] = value.slice(index + 1);
    }
  }
  return parsed;
}
