export function buildProjectionDrainReport({
  startedAt,
  finishedAt,
  projectorUrls,
  samples,
  timedOut = false,
}) {
  const normalizedSamples = Array.isArray(samples) ? samples : [];
  const first = normalizedSamples[0];
  const last = normalizedSamples.at(-1);
  const initialLag = number(first?.status?.lag);
  const finalLag = number(last?.status?.lag);
  const initialCanonicalCeiling = canonicalCeiling(first?.status?.watermarks);
  const finalCanonicalCeiling = canonicalCeiling(last?.status?.watermarks);
  const canonicalCeilingFingerprints = normalizedSamples.map(
    (sample) => canonicalCeilingFingerprint(sample?.status?.watermarks),
  );
  const initialCanonicalCeilingFingerprint = canonicalCeilingFingerprints[0] ?? "";
  const finalCanonicalCeilingFingerprint = canonicalCeilingFingerprints.at(-1) ?? "";
  const canonicalCeilingStable = canonicalCeilingFingerprints.length > 0 && canonicalCeilingFingerprints.every(
    (fingerprint) => fingerprint === initialCanonicalCeilingFingerprint,
  );
  const elapsedMs = Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt));
  const drainedWorkItems = Math.max(0, initialLag - finalLag);
  const failures = [];

  if (normalizedSamples.length === 0) failures.push("no projection drain samples were captured");
  if (initialLag <= 0) failures.push(`initial fixed backlog must be > 0; observed ${initialLag}`);
  if (!canonicalCeilingStable) {
    failures.push(
      `canonical ceiling changed during drain: initial total=${initialCanonicalCeiling} final total=${finalCanonicalCeiling}`,
    );
  }
  if (finalLag !== 0) failures.push(`projection did not fully drain: final lag=${finalLag}`);
  if (timedOut) failures.push("projection drain timed out");

  for (const sample of normalizedSamples) {
    for (const [probeType, probes] of Object.entries(sample?.probes ?? {})) {
      const failed = (probes ?? []).filter((probe) => !probe?.ok);
      if (failed.length > 0) {
        failures.push(`${probeType} probes failed at ${sample.sampledAt}: ${failed.length}`);
      }
    }
  }

  return {
    schemaVersion: "reef.projectionDrainBenchmark.v1",
    startedAt,
    finishedAt,
    projectorUrls,
    result: {
      initialLag,
      finalLag,
      maxLag: Math.max(0, ...normalizedSamples.map((sample) => number(sample?.status?.lag))),
      drainedWorkItems,
      elapsedMs,
      drainRps: elapsedMs > 0 ? drainedWorkItems / (elapsedMs / 1_000) : 0,
      lagAreaWorkItemSeconds: lagArea(normalizedSamples),
      initialCanonicalCeiling,
      finalCanonicalCeiling,
      initialCanonicalCeilingFingerprint,
      finalCanonicalCeilingFingerprint,
      canonicalCeilingStable,
      timedOut,
    },
    failures: [...new Set(failures)],
    samples: normalizedSamples,
  };
}

function canonicalCeiling(watermarks) {
  return (watermarks ?? []).reduce(
    (sum, watermark) => sum + number(watermark?.canonicalMaxPartitionSequence),
    0,
  );
}

function canonicalCeilingFingerprint(watermarks) {
  return (watermarks ?? [])
    .map((watermark) => ({
      projectionName: String(watermark?.projectionName ?? ""),
      partition: number(watermark?.partition ?? watermark?.partitionId),
      canonicalMaxPartitionSequence: number(watermark?.canonicalMaxPartitionSequence),
    }))
    .sort((left, right) => {
      const projectionComparison = left.projectionName.localeCompare(right.projectionName);
      return projectionComparison !== 0 ? projectionComparison : left.partition - right.partition;
    })
    .map((watermark) =>
      `${watermark.projectionName}:${watermark.partition}:${watermark.canonicalMaxPartitionSequence}`,
    )
    .join("|");
}

function lagArea(samples) {
  let area = 0;
  for (let index = 1; index < samples.length; index += 1) {
    const before = samples[index - 1];
    const after = samples[index];
    const elapsedSeconds = Math.max(0, Date.parse(after.sampledAt) - Date.parse(before.sampledAt)) / 1_000;
    area += ((number(before?.status?.lag) + number(after?.status?.lag)) / 2) * elapsedSeconds;
  }
  return area;
}

function number(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}
