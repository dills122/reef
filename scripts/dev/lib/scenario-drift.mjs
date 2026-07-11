import { canonicalThroughput, stableReportFingerprint, tracePassRatePct } from "./report-taxonomy.mjs";

export function evaluateReportDrift(report, baseline, context = {}) {
  if (baseline.stableReportFingerprint) {
    return compareStableFingerprint(report, baseline, context);
  }
  return evaluateThresholdBaseline(report, baseline, context);
}

export function buildDriftBaseline(report, metadata = {}) {
  return {
    schemaVersion: 1,
    kind: "reef-scenario-report-baseline",
    createdAt: new Date().toISOString(),
    metadata,
    stableReportFingerprint: stableReportFingerprint(report),
  };
}

function compareStableFingerprint(report, baseline, context) {
  const actual = stableReportFingerprint(report);
  const expected = baseline.stableReportFingerprint;
  const failures = [];
  collectDiffs("", expected, actual, failures);
  return {
    pass: failures.length === 0,
    baselinePath: context.baselinePath ?? "",
    reportPath: context.reportPath ?? "",
    evaluatedAt: new Date().toISOString(),
    expected,
    actual,
    failures,
  };
}

function evaluateThresholdBaseline(report, baseline, context) {
  const failures = [];
  const warnings = [];
  const thresholds = baseline.thresholds ?? {};
  const throughput = canonicalThroughput(report);
  const performanceTolerancePct = Number(thresholds.performanceTolerancePct ?? 0);

  checkMinimumThreshold(
    failures,
    warnings,
    "attemptedPerSecond",
    throughput.attemptedPerSecond,
    thresholds.minThroughputRps,
    performanceTolerancePct,
  );
  checkMinimumThreshold(
    failures,
    warnings,
    "acceptedPerSecond",
    throughput.acceptedPerSecond,
    thresholds.minAcceptedBusinessOpsRps,
    performanceTolerancePct,
  );
  if (Number(report.latencyMs?.p95 ?? 0) > Number(thresholds.maxP95LatencyMs ?? Number.POSITIVE_INFINITY)) {
    failures.push(`latencyMs.p95 ${report.latencyMs?.p95} > ${thresholds.maxP95LatencyMs}`);
  }

  const tracePassRate = tracePassRatePct(report);
  if (tracePassRate < Number(thresholds.minTracePassRatePct ?? 0)) {
    failures.push(`trace pass rate ${tracePassRate.toFixed(2)}% < ${thresholds.minTracePassRatePct}%`);
  }

  const requiredAttribution = baseline.requiredAttribution ?? [];
  for (const key of requiredAttribution) {
    const bucket = report[key];
    if (!bucket || Object.keys(bucket).length === 0) {
      failures.push(`missing or empty attribution bucket: ${key}`);
    }
  }

  const presentRejectCodes = new Set((report.rejectTaxonomy ?? []).map((row) => row.code));
  for (const code of baseline.requiredRejectCodes ?? []) {
    if (!presentRejectCodes.has(code)) {
      failures.push(`missing reject taxonomy code: ${code}`);
    }
  }

  return {
    pass: failures.length === 0,
    baselinePath: context.baselinePath ?? "",
    reportPath: context.reportPath ?? "",
    sessionConfigPath: baseline.sessionConfig ?? "",
    evaluatedAt: new Date().toISOString(),
    metrics: {
      attemptedPerSecond: throughput.attemptedPerSecond,
      acceptedPerSecond: throughput.acceptedPerSecond,
      p95LatencyMs: report.latencyMs?.p95,
      tracePassRatePct: tracePassRate,
    },
    warnings,
    failures,
  };
}

function checkMinimumThreshold(failures, warnings, metric, actual, threshold, tolerancePct) {
  if (threshold == null) return;
  const expected = Number(threshold);
  const effectiveMinimum = expected * (1 - Math.max(0, tolerancePct) / 100);
  if (actual < effectiveMinimum) {
    const suffix =
      tolerancePct > 0 ? ` (effective minimum ${formatNumber(effectiveMinimum)} with ${tolerancePct}% tolerance)` : "";
    failures.push(`${metric} ${actual} < ${threshold}${suffix}`);
    return;
  }
  if (actual < expected) {
    warnings.push(`${metric} ${actual} < ${threshold} but within ${tolerancePct}% tolerance`);
  }
}

function formatNumber(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

function collectDiffs(path, expected, actual, failures) {
  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (JSON.stringify(expected) !== JSON.stringify(actual)) {
      failures.push(`${path || "root"} changed: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
    return;
  }
  if (isPlainObject(expected) || isPlainObject(actual)) {
    const keys = new Set([...Object.keys(expected ?? {}), ...Object.keys(actual ?? {})]);
    for (const key of [...keys].sort()) {
      collectDiffs(path ? `${path}.${key}` : key, expected?.[key], actual?.[key], failures);
    }
    return;
  }
  if (expected !== actual) {
    failures.push(`${path || "root"} changed: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function isPlainObject(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}
