import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const args = parseArgs(process.argv.slice(2));

if (!args.report || !args.out) {
  console.error("usage: node scripts/dev/arena-render-report.mjs --report=<arena-report.json> --out=<arena-report.html>");
  process.exit(1);
}

const report = JSON.parse(readFileSync(args.report, "utf8"));
const html = renderReport(report, {
  sourcePath: args.report,
  generatedAt: new Date().toISOString(),
});

mkdirSync(dirname(args.out), { recursive: true });
writeFileSync(args.out, html);
console.log(`arena operator report written: ${resolve(args.out)}`);

function renderReport(report, context) {
  const mode = report.mode ?? {};
  const totals = report.totals ?? {};
  const accounting = report.commandAccounting ?? {};
  const botResults = report.botResults ?? [];
  const leaderboard = report.leaderboard ?? [];
  const enforcementEvents = report.enforcementEvents ?? [];
  const persistence = report.persistence ?? {};
  const venueReadback = report.venueReadback ?? {};
  const scoringAssumptions = report.scoringAssumptions ?? {};
  const marketQualitySummary = report.marketQualitySummary ?? {};
  const statusClass = String(report.status ?? "unknown").includes("freeze") ? "warn" : "ok";
  const persisted = persistence.enabled && !persistence.skipped;
  const projectionDrained = venueReadback.projectionDrained === true;

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Reef Arena Operator Report - ${escapeHtml(report.runId ?? "unknown-run")}</title>
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
      --code: #233142;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: var(--bg);
      color: var(--ink);
    }
    header {
      background: #ffffff;
      border-bottom: 1px solid var(--line);
      padding: 20px 28px 16px;
    }
    main {
      max-width: 1440px;
      margin: 0 auto;
      padding: 22px 28px 40px;
    }
    h1, h2, h3, p { margin: 0; }
    h1 {
      font-size: 24px;
      font-weight: 720;
      letter-spacing: 0;
    }
    h2 {
      font-size: 16px;
      margin-bottom: 12px;
      letter-spacing: 0;
    }
    h3 {
      font-size: 14px;
      margin-bottom: 8px;
      letter-spacing: 0;
    }
    .subtle {
      color: var(--muted);
      font-size: 13px;
      margin-top: 4px;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 14px;
      align-items: center;
    }
    .pill {
      border: 1px solid var(--line);
      background: #f9fafb;
      color: var(--code);
      border-radius: 999px;
      padding: 6px 10px;
      font-size: 12px;
      font-weight: 650;
      white-space: nowrap;
    }
    .pill.ok { background: var(--accent-soft); color: var(--accent); border-color: #a8d9d1; }
    .pill.warn { background: var(--warn-soft); color: var(--warn); border-color: #f0c684; }
    .pill.bad { background: var(--bad-soft); color: var(--bad); border-color: #efb5b5; }
    .grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
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
    .metric {
      min-height: 88px;
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
      letter-spacing: 0;
      overflow-wrap: anywhere;
    }
    .metric .detail {
      margin-top: 4px;
      color: var(--muted);
      font-size: 12px;
    }
    .split {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
      gap: 18px;
      align-items: start;
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
    .event-list {
      display: grid;
      gap: 10px;
    }
    .event {
      border: 1px solid var(--line);
      border-left: 4px solid var(--warn);
      border-radius: 6px;
      padding: 10px;
      background: #fffaf3;
    }
    .event .title {
      font-weight: 700;
      margin-bottom: 4px;
    }
    .empty {
      color: var(--muted);
      border: 1px dashed var(--line);
      border-radius: 8px;
      padding: 18px;
      background: #fbfcfd;
      font-size: 13px;
    }
    @media (max-width: 980px) {
      header { padding: 18px; }
      main { padding: 18px; }
      .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .split { grid-template-columns: 1fr; }
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
    <h1>Reef Arena Operator Report</h1>
    <p class="subtle">${escapeHtml(report.runId ?? "unknown-run")} · ${escapeHtml(mode.modeId ?? report.modeId ?? "unknown-mode")}</p>
    <div class="toolbar">
      <span class="pill ${statusClass}">${escapeHtml(report.status ?? "unknown")}</span>
      <span class="pill">seed ${escapeHtml(String(mode.seed ?? "n/a"))}</span>
      <span class="pill">score ${escapeHtml(mode.scoringPolicyVersion ?? report.scoringPolicyVersion ?? "n/a")}</span>
      <span class="pill">risk ${escapeHtml(mode.riskPolicyVersion ?? "n/a")}</span>
      <span class="pill ${persisted ? "ok" : "warn"}">${persisted ? "persisted" : "not persisted"}</span>
      <span class="pill ${projectionDrained ? "ok" : "warn"}">${projectionDrained ? "projection drained" : "projection unknown"}</span>
    </div>
  </header>
  <main>
    <section class="grid">
      ${metric("Bots", botResults.length, "registered in report")}
      ${metric("Ticks", totals.ticks ?? 0, `${totals.failedTicks ?? 0} failed`)}
      ${metric("Venue commands", totals.venueCommands ?? 0, `${totals.submittedCommands ?? 0} submitted`)}
      ${metric("Accounting gap", accounting.accountingGap ?? 0, `${accounting.terminalCommands ?? 0} terminal`)}
    </section>

    <section class="split">
      <div class="section">
        <h2>Leaderboard</h2>
        ${leaderboardTable(leaderboard)}
      </div>
      <div class="section">
        <h2>Run Evidence</h2>
        <table>
          <tbody>
            ${kv("Report schema", report.schemaVersion)}
            ${kv("Generated", report.generatedAt)}
            ${kv("Rendered", context.generatedAt)}
            ${kv("Source", context.sourcePath)}
            ${kv("Submit mode", report.runnerProfile?.submitMode)}
            ${kv("Data calls", totals.dataCalls)}
            ${kv("Rejected commands", totals.rejectedCommands)}
            ${kv("Timed out commands", totals.timedOutCommands)}
          </tbody>
        </table>
      </div>
    </section>

    <section class="section">
      <h2>Bot Results</h2>
      ${botResultsTable(botResults)}
    </section>

    <section class="section">
      <h2>Trading Metrics</h2>
      ${tradingMetricsTable(botResults)}
    </section>

    <section class="section">
      <h2>Market Quality</h2>
      ${marketQualityTable(marketQualitySummary)}
    </section>

    <section class="section">
      <h2>Scoring Assumptions</h2>
      <table>
        <tbody>
          ${kv("Policy", scoringAssumptions.scoringPolicyVersion ?? mode.scoringPolicyVersion)}
          ${kv("Score basis", scoringAssumptions.scoreBasis)}
          ${kv("Leaderboard scope", scoringAssumptions.leaderboardScope)}
          ${kv("House bots", scoringAssumptions.houseBots)}
          ${kv("P&L", scoringAssumptions.pnl?.status)}
          ${kv("Trading metrics", scoringAssumptions.tradingMetrics?.status)}
        </tbody>
      </table>
    </section>

    <section class="section">
      <h2>Enforcement</h2>
      ${enforcementEvents.length === 0 ? `<div class="empty">No freeze or disqualification events.</div>` : `<div class="event-list">${enforcementEvents.map(enforcementEvent).join("")}</div>`}
    </section>

    <section class="section">
      <h2>Persistence Readback</h2>
      <table>
        <tbody>
          ${kv("Enabled", persistence.enabled)}
          ${kv("Operations", Array.isArray(persistence.operations) ? persistence.operations.length : 0)}
          ${kv("Results status", persistence.rawResults?.statusCode)}
          ${kv("Enforcement status", persistence.rawEnforcementEvents?.statusCode)}
          ${kv("Leaderboard status", persistence.leaderboard?.statusCode)}
          ${kv("Leaderboard entry", persistence.leaderboardEntry === undefined ? "missing/not required" : `${persistence.leaderboardEntry.botId} rank ${persistence.leaderboardEntry.rank}`)}
        </tbody>
      </table>
    </section>
  </main>
</body>
</html>`;
}

function metric(label, value, detail) {
  return `<div class="section metric"><div class="label">${escapeHtml(label)}</div><div class="value">${escapeHtml(String(value))}</div><div class="detail">${escapeHtml(detail)}</div></div>`;
}

function leaderboardTable(entries) {
  if (entries.length === 0) {
    return `<div class="empty">No public eligible leaderboard entries.</div>`;
  }
  return `<table>
    <thead><tr><th style="width: 64px;">Rank</th><th>Bot</th><th class="num">Score</th><th class="num">Orders</th><th>Status</th></tr></thead>
    <tbody>
      ${entries.map((entry) => `<tr>
        <td class="mono">${escapeHtml(entry.rank)}</td>
        <td>${botLabel(entry)}</td>
        <td class="num">${formatNumber(entry.score ?? entry.finalEquity)}</td>
        <td class="num">${formatNumber(entry.venueCommands ?? entry.orderActionsProposed ?? 0)}</td>
        <td>${entry.disqualified ? `<span class="pill bad">disqualified</span>` : `<span class="pill ok">eligible</span>`}</td>
      </tr>`).join("")}
    </tbody>
  </table>`;
}

function botResultsTable(results) {
  if (results.length === 0) {
    return `<div class="empty">No bot result rows.</div>`;
  }
  return `<table>
    <thead>
      <tr>
        <th>Bot</th>
        <th>Version</th>
        <th class="num">Score</th>
        <th class="num">Actions</th>
        <th class="num">Commands</th>
        <th class="num">Data</th>
        <th>Status</th>
      </tr>
    </thead>
    <tbody>
      ${results.map((result) => `<tr>
        <td>${botLabel(result)}</td>
        <td class="mono">${escapeHtml(result.versionId)}</td>
        <td class="num">${formatNumber(result.score ?? result.finalEquity)}</td>
        <td class="num">${formatNumber(result.actionsProposed ?? 0)}</td>
        <td class="num">${formatNumber(result.venueCommands ?? result.orderActionsProposed ?? 0)}</td>
        <td class="num">${formatNumber(result.dataCalls ?? 0)}</td>
        <td>${result.disqualified ? `<span class="pill bad">disqualified</span>` : result.scoreEligible === false ? `<span class="pill warn">diagnostic</span>` : `<span class="pill ok">eligible</span>`}</td>
      </tr>`).join("")}
    </tbody>
  </table>`;
}

function tradingMetricsTable(results) {
  if (results.length === 0) {
    return `<div class="empty">No trading metric rows.</div>`;
  }
  return `<table>
    <thead>
      <tr>
        <th>Bot</th>
        <th class="num">Submits</th>
        <th class="num">Cancels</th>
        <th class="num">Buy Qty</th>
        <th class="num">Sell Qty</th>
        <th class="num">Gross Notional</th>
        <th>P&L</th>
      </tr>
    </thead>
    <tbody>
      ${results.map((result) => {
        const metrics = result.tradingMetrics ?? {};
        const orderFlow = metrics.orderFlow ?? {};
        const pnl = metrics.pnl ?? {};
        return `<tr>
          <td>${botLabel(result)}</td>
          <td class="num">${formatNumber(orderFlow.submittedLimitOrders ?? 0)}</td>
          <td class="num">${formatNumber(orderFlow.cancelCommands ?? 0)}</td>
          <td class="num">${formatNumber(orderFlow.buyQuantity ?? 0)}</td>
          <td class="num">${formatNumber(orderFlow.sellQuantity ?? 0)}</td>
          <td class="num">${formatNumber(orderFlow.grossSubmittedNotional ?? 0)}</td>
          <td>${pnl.available === false ? `<span class="pill warn">pending attribution</span>` : formatNumber(pnl.total ?? 0)}</td>
        </tr>`;
      }).join("")}
    </tbody>
  </table>`;
}

function marketQualityTable(summary) {
  const instruments = summary.instruments ?? [];
  if (instruments.length === 0) {
    return `<div class="empty">No per-instrument market quality rows.</div>`;
  }
  return `<table>
    <thead>
      <tr>
        <th>Instrument</th>
        <th>Status</th>
        <th class="num">TOB %</th>
        <th class="num">Depth %</th>
        <th class="num">Median Spread</th>
        <th class="num">P95 Spread</th>
        <th class="num">Crossed</th>
        <th>Failures</th>
      </tr>
    </thead>
    <tbody>
      ${instruments.map((instrument) => `<tr>
        <td class="mono">${escapeHtml(instrument.instrumentId)}</td>
        <td>${instrument.status === "pass" ? `<span class="pill ok">pass</span>` : `<span class="pill warn">warn</span>`}</td>
        <td class="num">${formatNumber(instrument.topOfBookPct ?? 0)}</td>
        <td class="num">${formatNumber(instrument.depthPct ?? 0)}</td>
        <td class="num">${formatNumber(instrument.medianQuotedSpreadBps ?? 0)}</td>
        <td class="num">${formatNumber(instrument.p95QuotedSpreadBps ?? 0)}</td>
        <td class="num">${formatNumber(instrument.crossedBookCount ?? 0)}</td>
        <td>${escapeHtml((instrument.failures ?? []).join("; ") || "none")}</td>
      </tr>`).join("")}
    </tbody>
  </table>`;
}

function enforcementEvent(event) {
  return `<div class="event">
    <div class="title">${escapeHtml(event.botId)} · ${escapeHtml(event.decision)} · ${escapeHtml(event.reasonCode ?? "policy")}</div>
    <div class="subtle">${escapeHtml(event.reason ?? "")}</div>
    <div class="subtle mono">${escapeHtml(event.policyVersion ?? "")} · ${escapeHtml(event.occurredAt ?? "")}</div>
  </div>`;
}

function kv(key, value) {
  return `<tr><th>${escapeHtml(key)}</th><td class="mono">${escapeHtml(value === undefined ? "n/a" : String(value))}</td></tr>`;
}

function botLabel(bot) {
  const displayName = bot.displayName ?? bot.botId;
  if (displayName === bot.botId) {
    return `<span>${escapeHtml(bot.botId)}</span>`;
  }
  return `<span>${escapeHtml(displayName)}</span><div class="subtle mono">${escapeHtml(bot.botId)}</div>`;
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
