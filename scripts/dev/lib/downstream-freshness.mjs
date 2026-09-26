import { scheduledDemandHealthy } from "./instrumentation-perturbation.mjs";

const LIMITS_MS = Object.freeze({ p95: 5000, p99: 10000, max: 30000 });
const MINIMUM_DURATION_SECONDS = 300;
const DURATION_TOLERANCE_MS = 2;
const STAGES = ["sourceToCanonical", "sourceToLifecycle", "sourceToMarketData"];
const UPSTREAM_CHECKS = [
  "acceptedCountMatchesReport", "acceptedSourceExclusive", "durableIntakeJoined",
  "materializerMembershipJoined", "checksumMembershipComplete", "canonicalCommitObserved",
  "sourceCommitObserved", "sourceCommitFrontiersComplete", "canonicalSourceTimingComplete", "clocksValid",
];
const DOWNSTREAM_CHECKS = [
  "databaseGenerationStable", "completeCohortResidence", "upstreamCohortAuthoritative",
  "downstreamInstrumentationEnabled", "coveringMarkersPresent", "stageMarkerIdentityMatches",
  "lifecycleMarkerCoversExclusiveCohort", "marketDataMarkerCoversExclusiveCohort", "finalQueuesDrained",
  "postCommitObservationsComplete", "callerTopologyVisibleAndIdle", "clocksValid",
  "diagnosticSamplerComplete", "projectedCohortReconciled",
];

// Thresholds intentionally have no configuration argument. Observation bounds
// describe every accepted command, not HTTP latency or the last drained batch.
export function buildDownstreamFreshnessGate(report) {
  const upstream = report?.upstreamSourceCohort;
  const downstream = report?.downstreamProjectionCohort;
  const residence = downstream?.cohortResidence;
  const accepted = exactCount(report?.totalSuccess);
  const matchedCount = value => accepted !== null && accepted > 0n && exactCount(value) === accepted;
  const stageChecks = Object.fromEntries(STAGES.map(stage => {
    const value = residence?.stages?.[stage];
    const valid = value && matchedCount(value.commandCount) &&
      [value.meanMs, value.p95Ms, value.p99Ms, value.maxMs].every(nonNegative) &&
      value.meanMs <= value.maxMs && value.p95Ms <= value.p99Ms && value.p99Ms <= value.maxMs;
    return [stage, Boolean(valid && value.p95Ms <= LIMITS_MS.p95 &&
      value.p99Ms <= LIMITS_MS.p99 && value.maxMs <= LIMITS_MS.max)];
  }));
  const checks = {
    upstreamAuthoritative: upstream?.pass === true && upstream.authority === "exclusive-kafka-offset-frontier-v1" && allChecks(upstream.checks, UPSTREAM_CHECKS),
    downstreamAuthoritative: downstream?.pass === true && downstream.authority === "post-commit-downstream-covering-marker-v1" && allChecks(downstream.checks, DOWNSTREAM_CHECKS),
    completeCommandWeightedResidence: residence?.pass === true &&
      residence.authority === "source-membership-to-sampled-commit-upper-bound-v1" && matchedCount(residence.commandCount),
    acceptedCountsReconciled: [report?.totalRequests, upstream?.totals?.accepted,
      upstream?.totals?.directAcked, upstream?.totals?.materializedMembership,
      report?.streamAckProjector?.delta?.projectedDelta].every(matchedCount) && zero(report?.totalFailures),
    sustainedDurationComplete: sustainedDurationComplete(report),
    scheduledDemandComplete: report != null && scheduledDemandHealthy(report),
    finalQueuesDrained: finalQueuesDrained(report),
    marketProjectionSourceBound: marketProjectionSourceBound(report),
    finalCallersIdle: callersIdle(downstream?.callers?.lifecycle) && callersIdle(downstream?.callers?.marketData),
    finalCanonicalLagZero: zero(report?.streamAckProjector?.delta?.afterLag),
    ...stageChecks,
  };
  return {
    schemaVersion: "reef.sustainedDownstreamFreshness.v1",
    authority: "source-membership-to-sampled-commit-upper-bound-v1",
    pass: Object.values(checks).every(value => value === true),
    limitsMs: { ...LIMITS_MS },
    minimumDurationSeconds: MINIMUM_DURATION_SECONDS,
    durationToleranceMs: DURATION_TOLERANCE_MS,
    checks,
    stages: Object.fromEntries(STAGES.map(stage => [stage, residence?.stages?.[stage] ?? null])),
  };
}

function sustainedDurationComplete(report) {
  const planned = report?.config?.Duration;
  const observed = report?.durationSeconds;
  const start = typeof report?.startedAt === "string" ? Date.parse(report.startedAt) : NaN;
  const finish = typeof report?.finishedAt === "string" ? Date.parse(report.finishedAt) : NaN;
  return Number.isSafeInteger(planned) && planned >= MINIMUM_DURATION_SECONDS * 1e9 &&
    nonNegative(observed) && observed >= MINIMUM_DURATION_SECONDS &&
    Number.isFinite(start) && Number.isFinite(finish) && finish >= start &&
    Math.abs(finish - start - observed * 1000) <= DURATION_TOLERANCE_MS;
}

function allChecks(checks, names) {
  return checks != null && names.every(name => checks[name] === true) && Object.values(checks).every(value => value === true);
}
function finalQueuesDrained(report) {
  const projectors = report?.streamAckProjector?.after?.projectors;
  if (!Array.isArray(projectors)) return false;
  return ["orderLifecycleProjector", "marketDataProjector"].every(name => {
    const enabled = projectors.filter(value => value?.[`${name}Enabled`] === true);
    if (enabled.length !== 1) return false;
    const instrumentation = enabled[0]?.[name]?.instrumentation;
    return instrumentation?.enabled === true && instrumentation.stageEnabled === true &&
      zero(instrumentation.dirtyQueues?.orderLifecyclePending) && zero(instrumentation.dirtyQueues?.marketDataPending);
  });
}
function marketProjectionSourceBound(report) {
  const after = report?.streamAckProjector?.after;
  const canonical = after?.projectionName;
  if (typeof canonical !== "string" || canonical.trim().length === 0 || !Array.isArray(after.projectors)) return false;
  const enabled = after.projectors.filter(value => value?.marketDataProjectorEnabled === true);
  const markers = report?.downstreamProjectionCohort?.markers;
  return enabled.length === 1 && enabled[0].marketDataProjector?.sourceProjectionName === canonical &&
    markers?.marketData?.sourceProjectionName === canonical && markers?.lifecycle?.sourceProjectionName === canonical;
}

function callersIdle(stats) {
  const calls = exactCount(stats?.calls), completed = exactCount(stats?.completed);
  const callerCount = exactCount(stats?.callerCount), concurrency = exactCount(stats?.maxConcurrent);
  return calls !== null && calls > 0n && completed === calls && zero(stats?.failed) && zero(stats?.active) &&
    concurrency !== null && concurrency > 0n && stats?.callers != null && typeof stats.callers === "object" &&
    !Array.isArray(stats.callers) && callerCount !== null && callerCount > 0n && BigInt(Object.keys(stats.callers).length) === callerCount;
}
function exactCount(value) {
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value)) return BigInt(value);
  return null;
}
function zero(value) { return exactCount(value) === 0n; }
function nonNegative(value) { return typeof value === "number" && Number.isFinite(value) && value >= 0; }
