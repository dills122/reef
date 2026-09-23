const supportedMetrics = new Set(["end-to-end", "valid-intent"]);

export function successRateForGuardrail(report, metric) {
  if (!supportedMetrics.has(metric)) {
    throw new Error(`unsupported stress success guardrail metric: ${metric}`);
  }

  if (metric === "valid-intent") {
    return finiteRate(report?.quality?.validIntentSuccessRatePct);
  }

  const reportedRate = Number(report?.quality?.endToEndSuccessRatePct);
  if (Number.isFinite(reportedRate)) return reportedRate;

  const totalRequests = Number(report?.totalRequests ?? 0);
  const totalSuccess = Number(report?.totalSuccess ?? 0);
  return totalRequests > 0 ? (totalSuccess / totalRequests) * 100 : 0;
}

function finiteRate(value) {
  const rate = Number(value);
  return Number.isFinite(rate) ? rate : 0;
}
