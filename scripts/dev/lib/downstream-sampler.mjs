export function summarizeDownstreamDiagnosticSamples({
  samples,
  startAt,
  endAt,
  configuredIntervalMs,
  projectorIndices,
}) {
  const startMs = instantMs(startAt);
  const endMs = instantMs(endAt);
  const indices = [...new Set((projectorIndices ?? []).map(Number).filter(Number.isFinite))].sort((a, b) => a - b);
  const inWindow = (samples ?? [])
    .filter((sample) => {
      const sampledMs = instantMs(sample?.sampledAt);
      return sampledMs !== null && startMs !== null && endMs !== null && sampledMs >= startMs && sampledMs <= endMs;
    })
    .sort((left, right) => instantMs(left.sampledAt) - instantMs(right.sampledAt));
  const successful = inWindow.filter((sample) => sampleCoversProjectors(sample, indices)).length;
  const timestamps = [startMs, ...inWindow.map((sample) => instantMs(sample.sampledAt)), endMs]
    .filter((value) => value !== null);
  let maxGapMs = 0;
  for (let index = 1; index < timestamps.length; index += 1) {
    maxGapMs = Math.max(maxGapMs, timestamps[index] - timestamps[index - 1]);
  }
  const interval = Math.max(1, number(configuredIntervalMs));
  return {
    configuredIntervalMs: interval,
    maxAllowedGapMs: interval * 2,
    sampleCount: inWindow.length,
    successfulSampleCount: successful,
    maxGapMs,
    startedAt: startAt,
    finishedAt: endAt,
    projectorIndices: indices,
  };
}

function sampleCoversProjectors(sample, projectorIndices) {
  if (projectorIndices.length === 0) return false;
  const probes = new Map((sample?.app?.probes ?? []).map((probe) => [probe?.name, probe]));
  return projectorIndices.every((index) => [
    `streamAckProjector.${index}.orderLifecycleStatus`,
    `streamAckProjector.${index}.marketDataStatus`,
  ].every((name) => {
    const probe = probes.get(name);
    return probe?.ok === true && probe?.json?.instrumentation?.enabled === true;
  }));
}

function instantMs(value) {
  const parsed = Date.parse(String(value ?? ""));
  return Number.isFinite(parsed) ? parsed : null;
}

function number(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}
