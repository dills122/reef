export function canonicalThroughput(report) {
  const throughput = report?.throughput ?? {};
  return {
    attemptedPerSecond: numberOrZero(throughput.attemptedPerSecond ?? report?.throughputRps),
    acceptedPerSecond: numberOrZero(throughput.acceptedPerSecond ?? report?.acceptedBusinessOpsRps),
    completedPerSecond: numberOrZero(throughput.completedPerSecond),
    projectedPerSecond: numberOrZero(throughput.projectedPerSecond),
    visiblePerSecond: numberOrZero(throughput.visiblePerSecond),
  };
}

export function tracePassRatePct(report) {
  const checked = numberOrZero(report?.traceChecks?.checked);
  if (checked === 0) return 0;
  return (numberOrZero(report?.traceChecks?.pass) / checked) * 100;
}

export function stableReportFingerprint(report) {
  return {
    scenarioId: report?.config?.ScenarioID ?? report?.config?.scenarioId ?? "",
    seed: numberOrZero(report?.config?.Seed ?? report?.config?.seed),
    mode: report?.config?.Mode ?? report?.config?.mode ?? "",
    totalRequests: numberOrZero(report?.totalRequests),
    totalSuccess: numberOrZero(report?.totalSuccess),
    totalFailures: numberOrZero(report?.totalFailures),
    statusCodes: sortObject(report?.statusCodes ?? {}),
    byAction: summarizeActionCounts(report?.byAction ?? {}),
    rejectTaxonomy: summarizeRejectTaxonomy(report?.rejectTaxonomy ?? []),
    quality: {
      invalidIntentRejectCount: numberOrZero(report?.quality?.invalidIntentRejectCount),
      systemFailureCount: numberOrZero(report?.quality?.systemFailureCount),
    },
    traceChecks: {
      checked: numberOrZero(report?.traceChecks?.checked),
      pass: numberOrZero(report?.traceChecks?.pass),
      fail: numberOrZero(report?.traceChecks?.fail),
    },
  };
}

export function aggregateReports(reports) {
  const rows = reports.map((report) => ({
    path: report.path,
    seed: numberOrZero(report.data?.config?.Seed ?? report.data?.config?.seed),
    throughput: canonicalThroughput(report.data),
    p95LatencyMs: numberOrZero(report.data?.latencyMs?.p95),
    tracePassRatePct: tracePassRatePct(report.data),
    totalRequests: numberOrZero(report.data?.totalRequests),
    totalSuccess: numberOrZero(report.data?.totalSuccess),
    totalFailures: numberOrZero(report.data?.totalFailures),
  }));
  return {
    runCount: rows.length,
    totals: {
      requests: sum(rows, "totalRequests"),
      success: sum(rows, "totalSuccess"),
      failures: sum(rows, "totalFailures"),
    },
    throughput: aggregateThroughput(rows),
    p95LatencyMs: aggregateNumber(rows.map((row) => row.p95LatencyMs)),
    tracePassRatePct: aggregateNumber(rows.map((row) => row.tracePassRatePct)),
    runs: rows,
  };
}

function aggregateThroughput(rows) {
  const keys = [
    "attemptedPerSecond",
    "acceptedPerSecond",
    "completedPerSecond",
    "projectedPerSecond",
    "visiblePerSecond",
  ];
  const out = {};
  for (const key of keys) {
    out[key] = aggregateNumber(rows.map((row) => row.throughput[key]));
  }
  return out;
}

function summarizeActionCounts(byAction) {
  const out = {};
  for (const [action, row] of Object.entries(byAction)) {
    out[action] = {
      requests: numberOrZero(row?.requests),
      success: numberOrZero(row?.success),
      failures: numberOrZero(row?.failures),
    };
  }
  return sortObject(out);
}

function summarizeRejectTaxonomy(rows) {
  return rows
    .map((row) => ({ code: row.code, count: numberOrZero(row.count) }))
    .sort((left, right) => left.code.localeCompare(right.code));
}

function aggregateNumber(values) {
  const numbers = values.filter((value) => Number.isFinite(value));
  if (numbers.length === 0) {
    return { min: 0, avg: 0, max: 0 };
  }
  return {
    min: Math.min(...numbers),
    avg: numbers.reduce((acc, value) => acc + value, 0) / numbers.length,
    max: Math.max(...numbers),
  };
}

function sortObject(value) {
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
}

function sum(rows, key) {
  return rows.reduce((acc, row) => acc + numberOrZero(row[key]), 0);
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}
