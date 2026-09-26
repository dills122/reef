import { createHash } from "node:crypto";
import { runtimeConfigurationSignature } from "./benchmark-runtime-signature.mjs";

const DEFAULT_LIMIT_PERCENT = 1;
const DEFAULT_MINIMUM_SAMPLES = 3;

export function buildInstrumentationPerturbationComparison({
  controlReports,
  instrumentedReports,
  limitPercent = DEFAULT_LIMIT_PERCENT,
  minimumSamples = DEFAULT_MINIMUM_SAMPLES,
}) {
  const controls = Array.isArray(controlReports) ? controlReports : [];
  const instrumented = Array.isArray(instrumentedReports) ? instrumentedReports : [];
  const allReports = [...controls, ...instrumented];
  const controlMetrics = aggregateArm(controls);
  const instrumentedMetrics = aggregateArm(instrumented);
  const perturbationPercent = {
    acceptedThroughput: degradationPercent(controlMetrics.acceptedRps, instrumentedMetrics.acceptedRps),
    projectedThroughput: degradationPercent(controlMetrics.projectedRps, instrumentedMetrics.projectedRps),
    p95Latency: increasePercent(controlMetrics.p95Ms, instrumentedMetrics.p95Ms),
    p99Latency: increasePercent(controlMetrics.p99Ms, instrumentedMetrics.p99Ms),
  };
  const perturbations = Object.values(perturbationPercent);
  const maxPerturbationPercent = perturbations.every(Number.isFinite) ? Math.max(...perturbations) : null;
  const configurationSignatures = new Set(allReports.map(configurationSignature));
  const runtimeSignatures = allReports.map((report) => runtimeConfigurationSignature(report.runtimeConfigurationEvidence));
  const checks = {
    frozenPolicy: limitPercent === DEFAULT_LIMIT_PERCENT && minimumSamples === DEFAULT_MINIMUM_SAMPLES,
    runtimeConfigurationComplete: allReports.length > 0 && runtimeSignatures.every((signature) => signature !== null),
    matchedRuntimeConfiguration: runtimeSignatures.length > 0 && runtimeSignatures.every((signature) => signature !== null) && new Set(runtimeSignatures).size === 1,
    minimumSamples: controls.length >= DEFAULT_MINIMUM_SAMPLES && instrumented.length >= DEFAULT_MINIMUM_SAMPLES,
    matchedConfiguration: allReports.length > 0 && !configurationSignatures.has(null) && configurationSignatures.size === 1,
    metricsComplete: perturbations.every(Number.isFinite),
    controlInstrumentationDisabled:
      controls.length > 0 && controls.every((report) =>
        report.projectionInstrumentation?.enabled === false && report.projectionInstrumentation?.state === "disabled"),
    instrumentedInstrumentationEnabled:
      instrumented.length > 0 && instrumented.every((report) =>
        report.projectionInstrumentation?.enabled === true && report.projectionInstrumentation?.state === "enabled"),
    upstreamCohortAuthoritative:
      allReports.length > 0 && allReports.every((report) => report.upstreamSourceCohort?.pass === true),
    instrumentedCohortAuthoritative:
      instrumented.length > 0 && instrumented.every((report) => report.downstreamProjectionCohort?.pass === true),
    benchmarkHealthGreen: allReports.length > 0 && allReports.every(benchmarkHealthy),
    perturbationWithinLimit:
      maxPerturbationPercent !== null && maxPerturbationPercent <= DEFAULT_LIMIT_PERCENT,
  };

  return {
    schemaVersion: "reef.projectionInstrumentationPerturbation.v1",
    pass: Object.values(checks).every(Boolean),
    limitPercent: DEFAULT_LIMIT_PERCENT,
    minimumSamples: DEFAULT_MINIMUM_SAMPLES,
    sampleCounts: { control: controls.length, instrumented: instrumented.length },
    checks,
    controlMedian: controlMetrics,
    instrumentedMedian: instrumentedMetrics,
    perturbationPercent,
    maxPerturbationPercent,
    configurationSignatures: [...configurationSignatures],
  };
}

function aggregateArm(reports) {
  return {
    acceptedRps: median(reports.map((report) => metric(report, "accepted"))),
    projectedRps: median(reports.map((report) => metric(report, "projected"))),
    p95Ms: median(reports.map((report) => metric(report, "p95"))),
    p99Ms: median(reports.map((report) => metric(report, "p99"))),
  };
}

function metric(report, name) {
  if (name === "accepted") {
    return Number(report.unitMetrics?.acceptedCommandsPerSecond ?? report.acceptedBusinessOpsRps);
  }
  if (name === "projected") {
    return Number(report.unitMetrics?.projectedWorkItemsPerSecond);
  }
  return Number(report.latencyMs?.[name]);
}

function configurationSignature(report) {
  const config = report.config;
  const metadata = report.stressRunMetadata;
  const sampler = report.downstreamDiagnosticSampler;
  if (!config || typeof config !== "object" || Array.isArray(config) ||
      !Number.isSafeInteger(config.Seed) || typeof config.HasSessionConfig !== "boolean" ||
      !Array.isArray(config.SessionActors) || !Array.isArray(config.MarketEquities) ||
      !metadata?.diagnostics || typeof metadata.diagnostics !== "object" || Array.isArray(metadata.diagnostics) ||
      Object.keys(metadata.diagnostics).length === 0 ||
      !Object.values(metadata.diagnostics).every((value) => typeof value === "boolean") ||
      !positive(sampler?.configuredIntervalMs) || !positive(sampler?.maxAllowedGapMs) ||
      (config.HasSessionConfig && !/^[a-f0-9]{64}$/.test(metadata.sessionConfig?.sha256))) return null;
  // These two paths locate artifacts; session bytes are identified separately.
  const { SessionConfigPath, ReportOut, ...effectiveConfig } = config;
  const value = {
    config: effectiveConfig,
    sessionConfigSha256: config.HasSessionConfig ? metadata.sessionConfig.sha256 : null,
    diagnostics: metadata.diagnostics,
    diagnosticSampler: { configuredIntervalMs: sampler.configuredIntervalMs, maxAllowedGapMs: sampler.maxAllowedGapMs },
    endpoints: metadata.endpoints ?? null,
    runProfile: metadata.runProfile ?? null,
    actionProfile: metadata.actionProfile ?? null,
    knobs: metadata.knobs ?? null,
  };
  return createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  return value;
}

function benchmarkHealthy(report) {
  const total = counter(report.totalRequests);
  const accepted = counter(report.totalSuccess);
  const materializer = report.venueEventMaterializer?.delta;
  const projector = report.streamAckProjector?.delta;
  return scheduledDemandHealthy(report) && legacyDownstreamHealthy(report) && total > 0 && accepted === total && counter(report.totalFailures) === 0 &&
    counter(materializer?.materializedDelta) === accepted &&
    counter(materializer?.failedDelta) === 0 && counter(materializer?.ackFailedDelta) === 0 &&
    counter(projector?.projectedDelta) === accepted && counter(projector?.afterLag) === 0 &&
    counter(projector?.failedDelta) === 0 && counter(projector?.retryDelta) === 0 &&
    counter(projector?.retryExhaustedDelta) === 0;
}

export function scheduledDemandHealthy(report) {
  const schedule = report.loadSchedule;
  if (!schedule || !["drop", "precise"].includes(schedule.mode) || schedule.mode !== report.config?.RateSchedule) return false;
  const keys = ["targetRequests", "scheduled", "enqueued", "dropped", "completed", "scheduleDeficit", "completionDeficit"];
  const counts = Object.fromEntries(keys.map((key) => [key, counter(schedule[key])]));
  if (Object.values(counts).some((value) => value === null)) return false;
  const { targetRequests, scheduled, enqueued, dropped, completed, scheduleDeficit, completionDeficit } = counts;
  const rate = schedule.targetRatePerSecond;
  const duration = report.config?.Duration;
  return positive(rate) && Number.isSafeInteger(rate) && rate === report.config?.RatePerSecond &&
    positive(duration) && Number.isSafeInteger(duration) &&
    targetRequests > 0 && targetRequests === Math.floor(duration / 1e9 * rate) &&
    enqueued <= scheduled && completed <= enqueued && dropped === 0 &&
    completed === counter(report.totalRequests) && completed >= targetRequests * 0.99 &&
    scheduleDeficit === Math.max(targetRequests - scheduled, 0) && completionDeficit === Math.max(targetRequests - completed, 0) &&
    positive(schedule.enqueuedPerSecond) && positive(schedule.completedPerSecond) &&
    typeof schedule.completionToTargetPct === "number" && Number.isFinite(schedule.completionToTargetPct) &&
    Math.abs(schedule.completionToTargetPct - completed / targetRequests * 100) < 1e-9;
}

function legacyDownstreamHealthy(report) {
  const before = report.streamAckProjector?.before?.projectors;
  const after = report.streamAckProjector?.after?.projectors;
  if (!Array.isArray(before) || !Array.isArray(after) || before.length === 0 || before.length !== after.length ||
      !before.every((value) => value && counter(value.index) !== null) ||
      !after.every((value) => value && counter(value.index) !== null) ||
      new Set(before.map((value) => value.index)).size !== before.length ||
      new Set(after.map((value) => value.index)).size !== after.length) return false;
  for (const stage of ["orderLifecycleProjector", "marketDataProjector"]) {
    let enabledCount = 0;
    let work = 0;
    for (const end of after) {
      const start = before.find((value) => value.index === end.index);
      if (!start) return false;
      const startStage = start[stage];
      const endStage = end[stage];
      if (typeof startStage?.enabled !== "boolean" || endStage?.enabled !== startStage.enabled ||
          start[`${stage}Enabled`] !== startStage.enabled || end[`${stage}Enabled`] !== endStage.enabled) return false;
      const startMetrics = startStage.metrics;
      const endMetrics = endStage.metrics;
      if (!["cycles", "processedRows", "lastProcessedRows", "failed"].every((key) =>
        counter(startMetrics?.[key]) !== null && counter(endMetrics?.[key]) !== null)) return false;
      if (counter(endMetrics.failed) !== counter(startMetrics.failed) ||
          counter(endMetrics.lastProcessedRows) !== 0 ||
          counter(endMetrics.processedRows) < counter(startMetrics.processedRows) ||
          counter(endMetrics.cycles) < counter(startMetrics.cycles)) return false;
      if (endStage.enabled) {
        enabledCount++;
        if (counter(endMetrics.cycles) <= counter(startMetrics.cycles)) return false;
        work += counter(endMetrics.processedRows) - counter(startMetrics.processedRows);
      }
    }
    if (enabledCount === 0 || work <= 0) return false;
  }
  return true;
}

function positive(value) { return typeof value === "number" && Number.isFinite(value) && value > 0; }

function counter(value) {
  if (typeof value !== "number" && !(typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value))) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

function median(values) {
  if (values.length === 0 || values.some((value) => !Number.isFinite(value) || value <= 0)) return null;
  const sorted = values.slice().sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function degradationPercent(control, observed) {
  if (!Number.isFinite(control) || !Number.isFinite(observed) || control <= 0) return Number.NaN;
  return Math.max(((control - observed) / control) * 100, 0);
}

function increasePercent(control, observed) {
  if (!Number.isFinite(control) || !Number.isFinite(observed) || control <= 0) return Number.NaN;
  return Math.max(((observed - control) / control) * 100, 0);
}
