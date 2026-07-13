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

export function canonicalEvidenceSummary(report) {
  const unitMetrics = report?.unitMetrics ?? {};
  const attempted = unitMetrics.attemptedCommands ?? report?.totalRequests;
  const accepted = unitMetrics.acceptedCommands ?? report?.totalSuccess;
  const directAcked = unitMetrics.directAckedCommands ?? report?.streamDirect?.delta?.ackedDelta;
  const materialized =
    unitMetrics.durableCanonicalCompletedItems ?? report?.venueEventMaterializer?.delta?.materializedDelta;
  const projected = unitMetrics.projectedWorkItems ?? report?.streamAckProjector?.delta?.projectedDelta;
  const lag = unitMetrics.projectionLagAfter ?? report?.streamAckProjector?.delta?.afterLag;
  const materializedToProjectedGap = nonNegativeGap(materialized, projected);
  const throughput = canonicalThroughput(report);
  return {
    attempted: numberOrZero(attempted),
    accepted: numberOrZero(accepted),
    directAcked: numberOrZero(directAcked),
    materialized: numberOrZero(materialized),
    projected: numberOrZero(projected),
    lag: numberOrZero(lag),
    p95LatencyMs: numberOrZero(report?.latencyMs?.p95),
    p99LatencyMs: numberOrZero(report?.latencyMs?.p99),
    rates: {
      attemptedPerSecond: numberOrZero(unitMetrics.attemptedCommandsPerSecond ?? throughput.attemptedPerSecond),
      acceptedPerSecond: numberOrZero(unitMetrics.acceptedCommandsPerSecond ?? throughput.acceptedPerSecond),
      directAckedPerSecond: numberOrZero(unitMetrics.directAckedCommandsPerSecond),
      materializedPerSecond: numberOrZero(unitMetrics.durableCanonicalCompletedPerSecond),
      projectedPerSecond: numberOrZero(unitMetrics.projectedWorkItemsPerSecond ?? throughput.projectedPerSecond),
    },
    gaps: {
      acceptedToDirectAcked: nonNegativeGap(accepted, directAcked),
      acceptedToMaterialized: nonNegativeGap(accepted, materialized),
      materializedToProjected: materializedToProjectedGap,
    },
    projectionFreshness: {
      source: report?.projectionFreshness?.source ?? "venue-event-batch-projector",
      freshnessModel:
        report?.projectionFreshness?.freshnessModel ??
        "async read-model projection from durable canonical venue-event materialization",
      materialized: numberOrZero(materialized),
      projected: numberOrZero(projected),
      materializedToProjectedGap,
      lag: numberOrZero(lag),
      projectedPerSecond: numberOrZero(unitMetrics.projectedWorkItemsPerSecond ?? throughput.projectedPerSecond),
      caughtUp:
        Boolean(report?.projectionFreshness?.caughtUp) ||
        (materializedToProjectedGap === 0 && numberOrZero(lag) === 0 && numberOrZero(projected) > 0),
    },
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
    evidence: canonicalEvidenceSummary(report.data),
    p95LatencyMs: numberOrZero(report.data?.latencyMs?.p95),
    p99LatencyMs: numberOrZero(report.data?.latencyMs?.p99),
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
    p99LatencyMs: aggregateNumber(rows.map((row) => row.p99LatencyMs)),
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

function nonNegativeGap(left, right) {
  return Math.max(numberOrZero(left) - numberOrZero(right), 0);
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}
