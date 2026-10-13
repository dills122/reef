#!/usr/bin/env node
// Compares a Calcify S0 metrics file against the recorded baseline and prints a markdown table.
// Warn-only by default (laptop and runner noise is about 15%); `--strict` exits 1 on any regression beyond the threshold.
// Usage: node scripts/dev/calcify-throughput-check.mjs <metrics.properties> [baseline.json] [--strict]
import { readFileSync, appendFileSync } from "node:fs";

export function parseProperties(text) {
  const out = {};
  for (const line of text.split("\n")) {
    const m = /^([A-Za-z0-9_.-]+)=(-?[0-9.]+)\s*$/.exec(line.trim());
    if (m) out[m[1]] = Number(m[2]);
  }
  return out;
}

/** Returns one row per baseline metric: status is ok, regression, improved or missing. */
export function compare(metrics, baseline) {
  const warn = baseline.warnFraction ?? 0.3;
  return Object.entries(baseline.metrics).map(([key, spec]) => {
    const now = metrics[key];
    if (now === undefined) return { key, baseline: spec.value, now: null, change: null, status: "missing", unit: spec.unit };
    const change = (now - spec.value) / spec.value;
    const worse = spec.better === "higher" ? -change : change;
    const status = worse > warn ? "regression" : worse < -warn ? "improved" : "ok";
    return { key, baseline: spec.value, now, change, status, unit: spec.unit };
  });
}

export function render(rows, baseline) {
  const lines = [`### Calcify throughput check (warn at ${(baseline.warnFraction ?? 0.3) * 100}% worse than ${baseline.recorded})`, "", "| Metric | Baseline | Now | Change | Status |", "| --- | ---: | ---: | ---: | --- |"];
  for (const r of rows) {
    lines.push(`| ${r.key} (${r.unit}) | ${r.baseline} | ${r.now ?? "n/a"} | ${r.change === null ? "n/a" : (r.change * 100).toFixed(0) + "%"} | ${r.status} |`);
  }
  return lines.join("\n");
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = process.argv.slice(2).filter((a) => !a.startsWith("--"));
  const strict = process.argv.includes("--strict");
  const [metricsPath, baselinePath = "docs/work/calcify-throughput-baseline.json"] = args;
  if (!metricsPath) { console.error("usage: calcify-throughput-check.mjs <metrics.properties> [baseline.json] [--strict]"); process.exit(2); }
  const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
  const rows = compare(parseProperties(readFileSync(metricsPath, "utf8")), baseline);
  const table = render(rows, baseline);
  console.log(table);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, table + "\n");
  const bad = rows.filter((r) => r.status === "regression" || r.status === "missing");
  if (bad.length) console.error(`${bad.length} metric(s) regressed or missing: ${bad.map((r) => r.key).join(", ")}`);
  if (strict && bad.length) process.exit(1);
}
