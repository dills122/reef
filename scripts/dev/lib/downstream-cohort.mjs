import { reconcileCohortResidence } from "./cohort-residence.mjs";
const STREAM_SEQUENCE_PARTITION_SHIFT = 48n;

export function buildDownstreamProjectionCohort(report) {
  const upstream = report?.upstreamSourceCohort;
  const projectors = report?.streamAckProjector?.after?.projectors ?? [];
  const lifecycle = enabledInstrumentation(projectors, "orderLifecycleProjectorEnabled", "orderLifecycleProjector");
  const marketData = enabledInstrumentation(projectors, "marketDataProjectorEnabled", "marketDataProjector");
  const lifecycleMarker = lifecycle?.coverage?.lastMarker;
  const marketDataMarker = marketData?.coverage?.lastMarker;
  const sourceWorkFinishedAt = report?.venueEventMaterializer?.after?.metrics?.lastSourceWorkFinishedAt ?? "";
  const lifecycleObservedAt = lifecycleMarker?.postCommitObservedAt ?? "";
  const marketDataObservedAt = marketDataMarker?.postCommitObservedAt ?? "";
  const sourceToLifecycleMs = elapsedMs(sourceWorkFinishedAt, lifecycleObservedAt);
  const sourceToMarketDataMs = elapsedMs(sourceWorkFinishedAt, marketDataObservedAt);
  const lifecycleToMarketDataMs = signedElapsedMs(lifecycleObservedAt, marketDataObservedAt);
  const lifecycleCallerStats = lifecycle?.callerStats;
  const marketDataCallerStats = marketData?.callerStats;
  const sampler = report?.downstreamDiagnosticSampler;

  const cohortResidence = reconcileCohortResidence({
    partitions: upstream?.partitions,
    before: report?.materializerTiming?.before, after: report?.materializerTiming?.after,
    markers: { lifecycle: [...(report?.downstreamCoverageObservations?.lifecycle ?? []), lifecycleMarker].filter(Boolean), marketData: [...(report?.downstreamCoverageObservations?.marketData ?? []), marketDataMarker].filter(Boolean) },
  });
  const checks = {
    databaseGenerationStable: databaseGenerationStable(report, lifecycle, marketData),
    completeCohortResidence: cohortResidence.pass === true,
    upstreamCohortAuthoritative: upstream?.pass === true,
    downstreamInstrumentationEnabled:
      lifecycle?.enabled === true && lifecycle?.stageEnabled === true &&
      marketData?.enabled === true && marketData?.stageEnabled === true,
    coveringMarkersPresent: Boolean(lifecycleMarker?.markerId && marketDataMarker?.markerId),
    stageMarkerIdentityMatches:
      Boolean(lifecycleMarker?.markerId) && lifecycleMarker?.markerId === marketDataMarker?.markerId,
    lifecycleMarkerCoversExclusiveCohort: markerExactlyCovers(lifecycleMarker, upstream?.partitions),
    marketDataMarkerCoversExclusiveCohort: markerExactlyCovers(marketDataMarker, upstream?.partitions),
    finalQueuesDrained: queuesDrained(lifecycle) && queuesDrained(marketData),
    postCommitObservationsComplete:
      postCommitObservationValid(lifecycleMarker) && postCommitObservationValid(marketDataMarker),
    callerTopologyVisibleAndIdle:
      callerTopologyValid(lifecycleCallerStats) && callerTopologyValid(marketDataCallerStats),
    clocksValid:
      validZero(lifecycle?.coverage?.clockGuardFailures) &&
      validZero(marketData?.coverage?.clockGuardFailures) &&
      sourceToLifecycleMs !== null && sourceToMarketDataMs !== null && lifecycleToMarketDataMs !== null,
    diagnosticSamplerComplete: diagnosticSamplerValid(sampler),
    projectedCohortReconciled:
      big(report?.streamAckProjector?.delta?.projectedDelta) === big(report?.totalSuccess) &&
      validZero(report?.streamAckProjector?.delta?.afterLag ?? report?.streamAckProjector?.after?.lag),
  };

  return {
    schemaVersion: 1,
    authority: "post-commit-downstream-covering-marker-v1",
    pass: Object.values(checks).every(Boolean),
    checks,
    markers: {
      lifecycle: lifecycleMarker ?? null,
      marketData: marketDataMarker ?? null,
    },
    cohortResidence,
    tailDrainObservation: {
      sourceWorkFinishedAt,
      lifecyclePostCommitObservedAt: lifecycleObservedAt,
      marketDataPostCommitObservedAt: marketDataObservedAt,
      sourceToLifecycleMs,
      sourceToMarketDataMs,
      lifecycleToMarketDataMs,
    },
    callers: {
      lifecycle: lifecycleCallerStats ?? null,
      marketData: marketDataCallerStats ?? null,
    },
    sampler: sampler ?? null,
  };
}

export function downstreamInstrumentationDrained(projectorStatus) {
  const projectors = projectorStatus?.projectors ?? [];
  const lifecycle = enabledInstrumentation(projectors, "orderLifecycleProjectorEnabled", "orderLifecycleProjector");
  const marketData = enabledInstrumentation(projectors, "marketDataProjectorEnabled", "marketDataProjector");
  const configured = [lifecycle, marketData].filter(Boolean);
  if (configured.length === 0 || configured.every((stage) => stage.enabled === false)) return true;
  if (configured.length !== 2 || configured.some((stage) => stage.enabled !== true || stage.stageEnabled !== true)) {
    return false;
  }

  const lifecycleMarker = lifecycle.coverage?.lastMarker;
  const marketDataMarker = marketData.coverage?.lastMarker;
  const projectedPartitions = new Set(
    (projectorStatus?.watermarks ?? []).map((watermark) => number(watermark?.partition ?? watermark?.partitionId)),
  );
  return generationValid(lifecycle) && generationValid(marketData) &&
    queuesDrained(lifecycle) && queuesDrained(marketData) &&
    callersIdle(lifecycle.callerStats) && callersIdle(marketData.callerStats) &&
    Boolean(lifecycleMarker?.markerId) && lifecycleMarker.markerId === marketDataMarker?.markerId &&
    markerPartitionsMatch(lifecycleMarker, projectedPartitions) &&
    markerPartitionsMatch(marketDataMarker, projectedPartitions);
}

function generationValid(stage, requireMarker = true) {
  const generation = stage?.coverage?.databaseGeneration;
  return typeof generation === "string" && Number.isFinite(Date.parse(generation)) &&
    validZero(stage?.coverage?.generationGuardFailures) &&
    stage?.dirtyQueues?.databaseGeneration === generation &&
    (!requireMarker || stage?.coverage?.lastMarker?.databaseGeneration === generation);
}

function databaseGenerationStable(report, lifecycle, marketData) {
  const before = report?.streamAckProjector?.before?.projectors ?? [];
  const initialLifecycle = enabledInstrumentation(before, "orderLifecycleProjectorEnabled", "orderLifecycleProjector");
  const initialMarket = enabledInstrumentation(before, "marketDataProjectorEnabled", "marketDataProjector");
  const stages = [initialLifecycle, initialMarket, lifecycle, marketData];
  return stages.every((stage, index) => generationValid(stage, index >= 2)) &&
    new Set(stages.map(stage => stage.coverage.databaseGeneration)).size === 1;
}

function enabledInstrumentation(projectors, enabledField, statusField) {
  const enabled = projectors.filter((projector) => projector?.[enabledField] === true);
  if (enabled.length !== 1) return null;
  return enabled[0]?.[statusField]?.instrumentation ?? null;
}

function markerExactlyCovers(marker, partitions) {
  if (!marker || !Array.isArray(partitions) || partitions.length === 0) return false;
  const watermarks = new Map(
    (marker.sourceWatermarks ?? []).map((row) => [number(row?.partition), big(row?.lastPartitionSequence)]),
  );
  if (watermarks.size !== partitions.length) return false;
  return partitions.every((partition) => {
    const partitionId = number(partition?.partition);
    const expected = (BigInt(partitionId) << STREAM_SEQUENCE_PARTITION_SHIFT) + big(partition?.endOffsetExclusive);
    return watermarks.get(partitionId) === expected;
  });
}

function queuesDrained(instrumentation) {
  const queues = instrumentation?.dirtyQueues;
  const empty = (marker, pending) => marker === false ? false :
    marker === true ? pending === undefined || validZero(pending) : validZero(pending);
  return Boolean(queues) &&
    empty(queues.orderLifecycleEmpty, queues.orderLifecyclePending) &&
    empty(queues.marketDataEmpty, queues.marketDataPending);
}

function postCommitObservationValid(marker) {
  if (marker?.postCommitObserved !== true) return false;
  const databaseSnapshotAt = instantMs(marker.databaseSnapshotAt);
  const observedAt = instantMs(marker.postCommitObservedAt);
  return databaseSnapshotAt !== null && observedAt !== null && observedAt >= databaseSnapshotAt;
}

function callerTopologyValid(stats) {
  if (!stats) return false;
  const callers = Object.keys(stats.callers ?? {});
  return callers.length > 0 &&
    callers.length === number(stats.callerCount) &&
    number(stats.calls) > 0 &&
    number(stats.completed) + number(stats.failed) === number(stats.calls) &&
    validZero(stats.failed) &&
    validZero(stats.active) &&
    number(stats.maxConcurrent) >= 1;
}

function callersIdle(stats) {
  return Boolean(stats) && validZero(stats.active) &&
    number(stats.completed) + number(stats.failed) === number(stats.calls);
}

function markerPartitionsMatch(marker, projectedPartitions) {
  if (!marker || projectedPartitions.size === 0) return false;
  const markerPartitions = new Set(
    (marker.sourceWatermarks ?? []).map((watermark) => number(watermark?.partition ?? watermark?.partitionId)),
  );
  return markerPartitions.size === projectedPartitions.size &&
    [...projectedPartitions].every((partition) => markerPartitions.has(partition));
}

function diagnosticSamplerValid(sampler) {
  if (!sampler) return false;
  const { configuredIntervalMs, sampleCount, successfulSampleCount, maxGapMs, maxAllowedGapMs } = sampler;
  // Promotional evidence uses the frozen cadence, not caller-selected tolerances.
  return configuredIntervalMs === 1000 && maxAllowedGapMs === 2000 &&
    Number.isSafeInteger(sampleCount) && sampleCount >= 2 &&
    Number.isSafeInteger(successfulSampleCount) && successfulSampleCount === sampleCount &&
    Number.isFinite(maxGapMs) && maxGapMs >= 0 && maxGapMs <= 2000;
}

function elapsedMs(start, end) {
  const startMs = instantMs(start);
  const endMs = instantMs(end);
  if (startMs === null || endMs === null || endMs < startMs) return null;
  return endMs - startMs;
}

function signedElapsedMs(start, end) {
  const startMs = instantMs(start);
  const endMs = instantMs(end);
  if (startMs === null || endMs === null) return null;
  return endMs - startMs;
}

function instantMs(value) {
  if (!value) return null;
  const parsed = Date.parse(String(value));
  return Number.isFinite(parsed) ? parsed : null;
}

function big(value) {
  try {
    return BigInt(String(value ?? 0));
  } catch {
    return 0n;
  }
}

function number(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function validZero(value) {
  return value === 0 || value === "0";
}
