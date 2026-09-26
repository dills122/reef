const SUM_MATERIALIZER_METRICS = [
  "checksumValidatedBatches",
  "legacyUncheckedBatches",
  "validatedMembershipOutcomes",
  "canonicalCommitObservedBatches",
  "sourceCommitObservedBatches",
  "canonicalCommitElapsedNanos",
  "sourceCommitElapsedNanos",
  "sourceResidenceSamples",
  "sourceResidenceElapsedMs",
  "clockGuardFailures",
];

const MAX_MATERIALIZER_METRICS = [
  "canonicalCommitMaxNanos",
  "sourceCommitMaxNanos",
  "sourceResidenceMaxMs",
];

const LAST_MATERIALIZER_TIMESTAMPS = [
  "lastSourceWorkFinishedAt",
  "lastCanonicalCommitObservedAt",
  "lastSourceCommitObservedAt",
];

export function mergeMaterializerTelemetry(instances) {
  const metricsRows = instances.map((instance) => instance?.metrics ?? instance?.stats?.metrics ?? {});
  const result = {};
  for (const key of SUM_MATERIALIZER_METRICS) {
    result[key] = metricsRows.reduce((sum, metrics) => sum + number(metrics[key]), 0);
  }
  for (const key of MAX_MATERIALIZER_METRICS) {
    result[key] = metricsRows.reduce((maximum, metrics) => Math.max(maximum, number(metrics[key])), 0);
  }
  for (const key of LAST_MATERIALIZER_TIMESTAMPS) {
    result[key] = metricsRows.reduce((latest, metrics) => maxIso(latest, metrics[key]), "");
  }
  result.materializedSourceFrontiers = mergeMaterializedSourceFrontiers(
    metricsRows.flatMap((metrics) => metrics.materializedSourceFrontiers ?? []),
  );
  result.sourcePartitions = mergeSourcePartitions(metricsRows.flatMap((metrics) => metrics.sourcePartitions ?? []));
  return result;
}

export function buildUpstreamSourceCohort(report) {
  const topicBefore = byPartition(report?.streamAckHealth?.before?.sourceTopicFrontiers);
  const topicAfter = byPartition(report?.streamAckHealth?.after?.sourceTopicFrontiers);
  const acceptedBefore = byPartition(report?.streamAckHealth?.before?.acceptedSourceFrontiers);
  const acceptedAfter = byPartition(report?.streamAckHealth?.after?.acceptedSourceFrontiers);
  const materializedBefore = byPartition(report?.venueEventMaterializer?.before?.metrics?.materializedSourceFrontiers);
  const materializedAfter = byPartition(report?.venueEventMaterializer?.after?.metrics?.materializedSourceFrontiers);
  const sourceCommitAuthority = buildSourceCommitAuthority(
    report?.venueEventMaterializer?.before?.metrics?.sourcePartitions,
    report?.venueEventMaterializer?.after?.metrics?.sourcePartitions,
  );
  const direct = new Map(
    (report?.streamDirect?.delta?.partitionDeltas ?? []).map((row) => [number(row.partition), big(row.ackedDelta)]),
  );

  const partitionIds = new Set([...topicBefore.keys(), ...topicAfter.keys(), ...acceptedBefore.keys(), ...acceptedAfter.keys()]);
  const partitions = [...partitionIds]
    .map((partition) => buildPartitionCohort({
      partition,
      topicBefore: topicBefore.get(partition),
      topicAfter: topicAfter.get(partition),
      acceptedBefore: acceptedBefore.get(partition),
      acceptedAfter: acceptedAfter.get(partition),
      materializedBefore: materializedBefore.get(partition),
      materializedAfter: materializedAfter.get(partition),
      directAcked: direct.get(partition) ?? 0n,
    }))
    .filter((partition) => big(partition.accepted) > 0n || big(partition.sourceOffsetSpan) > 0n)
    .sort((left, right) => left.partition - right.partition);

  const totals = {
    accepted: sumStrings(partitions, "accepted"),
    sourceOffsetSpan: sumStrings(partitions, "sourceOffsetSpan"),
    directAcked: sumStrings(partitions, "directAcked"),
    materializedMembership: sumStrings(partitions, "materializedMembership"),
  };
  const materializerDelta = report?.venueEventMaterializer?.delta ?? {};
  const materializedBatches = big(materializerDelta.materializedBatchesDelta);
  const acceptedReportCount = big(report?.totalSuccess);
  const checks = {
    acceptedCountMatchesReport: big(totals.accepted) === acceptedReportCount,
    acceptedSourceExclusive: partitions.length > 0 && partitions.every((partition) => partition.exclusive),
    durableIntakeJoined:
      big(totals.directAcked) === acceptedReportCount &&
      big(report?.streamDirect?.delta?.ackedDelta) === acceptedReportCount &&
      big(report?.streamAckHealth?.delta?.publishCompletedDelta) === acceptedReportCount,
    materializerMembershipJoined:
      partitions.length > 0 &&
      partitions.every((partition) => partition.joined) &&
      big(totals.materializedMembership) === acceptedReportCount &&
      big(materializerDelta.materializedDelta) === acceptedReportCount,
    checksumMembershipComplete:
      big(materializerDelta.checksumValidatedBatchesDelta) === materializedBatches &&
      validZero(materializerDelta.legacyUncheckedBatchesDelta) &&
      big(materializerDelta.validatedMembershipOutcomesDelta) === acceptedReportCount,
    canonicalCommitObserved: big(materializerDelta.canonicalCommitObservedBatchesDelta) === materializedBatches,
    sourceCommitObserved: big(materializerDelta.sourceCommitObservedBatchesDelta) === materializedBatches,
    sourceCommitFrontiersComplete:
      sourceCommitAuthority.complete && sourceCommitAuthority.committed === materializedBatches,
    canonicalSourceTimingComplete: big(materializerDelta.sourceResidenceSamplesDelta) === materializedBatches,
    clocksValid: validZero(materializerDelta.clockGuardFailuresDelta),
  };

  return {
    schemaVersion: 1,
    authority: "exclusive-kafka-offset-frontier-v1",
    pass: Object.values(checks).every(Boolean),
    totals,
    partitions,
    checks,
  };
}

function buildPartitionCohort({
  partition,
  topicBefore,
  topicAfter,
  acceptedBefore,
  acceptedAfter,
  materializedBefore,
  materializedAfter,
  directAcked,
}) {
  const acceptedBeforeCount = big(acceptedBefore?.accepted);
  const acceptedAfterCount = big(acceptedAfter?.accepted);
  const accepted = nonNegativeDelta(acceptedBeforeCount, acceptedAfterCount);
  const acceptedStart = acceptedBeforeCount > 0n
    ? big(acceptedBefore?.lastOffsetExclusive)
    : big(acceptedAfter?.firstOffsetInclusive);
  const acceptedEnd = big(acceptedAfter?.lastOffsetExclusive);
  const topicFrontiersObserved = topicBefore !== undefined && topicAfter !== undefined;
  const start = big(topicBefore?.lastOffsetExclusive);
  const end = big(topicAfter?.lastOffsetExclusive);
  const span = distance(start, end);

  const beforeBatches = big(materializedBefore?.observedBatches);
  const materializedBeforeOutcomes = big(materializedBefore?.observedOutcomes);
  const materializedAfterOutcomes = big(materializedAfter?.observedOutcomes);
  const materializedStart = beforeBatches > 0n
    ? big(materializedBefore?.lastOffsetExclusive)
    : big(materializedAfter?.firstOffsetInclusive);
  const materializedEnd = big(materializedAfter?.lastOffsetExclusive);
  const materializedMembership = nonNegativeDelta(materializedBeforeOutcomes, materializedAfterOutcomes);
  const exactCanonicalMembership = coversIntervalExactly(
    materializedAfter?.coveredRanges,
    start,
    end,
  );
  const exclusive =
    topicFrontiersObserved &&
    span === accepted &&
    acceptedStart === start &&
    acceptedEnd === end;
  const joined =
    exclusive &&
    directAcked === accepted &&
    materializedStart === start &&
    materializedEnd === end &&
    materializedMembership === accepted &&
    exactCanonicalMembership;

  return {
    partition,
    startOffsetInclusive: start.toString(),
    endOffsetExclusive: end.toString(),
    sourceOffsetSpan: span.toString(),
    accepted: accepted.toString(),
    directAcked: directAcked.toString(),
    materializedStartOffsetInclusive: materializedStart.toString(),
    materializedEndOffsetExclusive: materializedEnd.toString(),
    materializedMembership: materializedMembership.toString(),
    exactCanonicalMembership,
    exclusive,
    joined,
  };
}

function mergeMaterializedSourceFrontiers(rows) {
  const merged = new Map();
  for (const row of rows) {
    const partition = number(row?.partition);
    const current = merged.get(partition) ?? {
      partition,
      observedBatches: 0n,
      observedOutcomes: 0n,
      firstOffsetInclusive: null,
      lastOffsetExclusive: 0n,
      coveredRanges: [],
    };
    current.observedBatches += big(row?.observedBatches);
    current.observedOutcomes += big(row?.observedOutcomes);
    const first = big(row?.firstOffsetInclusive);
    current.firstOffsetInclusive = current.firstOffsetInclusive === null ? first : minBig(current.firstOffsetInclusive, first);
    current.lastOffsetExclusive = maxBig(current.lastOffsetExclusive, big(row?.lastOffsetExclusive));
    current.coveredRanges.push(...(row?.coveredRanges ?? []).map((range) => ({
      firstOffsetInclusive: big(range?.firstOffsetInclusive),
      lastOffsetExclusive: big(range?.lastOffsetExclusive),
    })));
    merged.set(partition, current);
  }
  return [...merged.values()]
    .sort((left, right) => left.partition - right.partition)
    .map((row) => ({
      partition: row.partition,
      observedBatches: row.observedBatches.toString(),
      observedOutcomes: row.observedOutcomes.toString(),
      firstOffsetInclusive: (row.firstOffsetInclusive ?? 0n).toString(),
      lastOffsetExclusive: row.lastOffsetExclusive.toString(),
      coveredRanges: mergeOffsetRanges(row.coveredRanges).map((range) => ({
        firstOffsetInclusive: range.firstOffsetInclusive.toString(),
        lastOffsetExclusive: range.lastOffsetExclusive.toString(),
      })),
    }));
}

function buildSourceCommitAuthority(beforeRows, afterRows) {
  const before = byPartition(beforeRows);
  const after = byPartition(afterRows);
  const partitionIds = new Set([...before.keys(), ...after.keys()]);
  let committed = 0n;
  let observed = false;
  let complete = true;
  for (const partition of partitionIds) {
    const beforeRow = before.get(partition);
    const afterRow = after.get(partition);
    const fetchedDelta = nonNegativeDelta(big(beforeRow?.fetched), big(afterRow?.fetched));
    const committedDelta = nonNegativeDelta(big(beforeRow?.commitObserved), big(afterRow?.commitObserved));
    if (fetchedDelta === 0n && committedDelta === 0n) continue;
    observed = true;
    committed += committedDelta;
    complete = complete &&
      committedDelta === fetchedDelta &&
      big(afterRow?.lag) === 0n &&
      big(afterRow?.lastCommitObservedOffsetExclusive) >= big(afterRow?.lastFetchedOffsetExclusive);
  }
  return { committed, complete: observed && complete };
}

function coversIntervalExactly(ranges, start, end) {
  if (end <= start) return false;
  const merged = mergeOffsetRanges((ranges ?? []).map((range) => ({
    firstOffsetInclusive: big(range?.firstOffsetInclusive),
    lastOffsetExclusive: big(range?.lastOffsetExclusive),
  })));
  let covered = 0n;
  for (const range of merged) {
    const overlapStart = maxBig(range.firstOffsetInclusive, start);
    const overlapEnd = minBig(range.lastOffsetExclusive, end);
    if (overlapEnd > overlapStart) covered += overlapEnd - overlapStart;
  }
  return covered === end - start;
}

function mergeOffsetRanges(ranges) {
  const sorted = ranges
    .filter((range) => range.lastOffsetExclusive > range.firstOffsetInclusive)
    .sort((left, right) => left.firstOffsetInclusive < right.firstOffsetInclusive
      ? -1
      : left.firstOffsetInclusive > right.firstOffsetInclusive ? 1 : 0);
  const merged = [];
  for (const range of sorted) {
    const previous = merged.at(-1);
    if (!previous || range.firstOffsetInclusive > previous.lastOffsetExclusive) {
      merged.push({ ...range });
    } else if (range.lastOffsetExclusive > previous.lastOffsetExclusive) {
      previous.lastOffsetExclusive = range.lastOffsetExclusive;
    }
  }
  return merged;
}

function mergeSourcePartitions(rows) {
  const merged = new Map();
  for (const row of rows) {
    const partition = number(row?.partition);
    const current = merged.get(partition) ?? {
      partition,
      fetched: 0n,
      commitObserved: 0n,
      firstFetchedOffsetInclusive: null,
      lastFetchedOffsetExclusive: 0n,
      lastCommitObservedOffsetExclusive: 0n,
    };
    current.fetched += big(row?.fetched);
    current.commitObserved += big(row?.commitObserved);
    const first = big(row?.firstFetchedOffsetInclusive);
    current.firstFetchedOffsetInclusive = current.firstFetchedOffsetInclusive === null
      ? first
      : minBig(current.firstFetchedOffsetInclusive, first);
    current.lastFetchedOffsetExclusive = maxBig(current.lastFetchedOffsetExclusive, big(row?.lastFetchedOffsetExclusive));
    current.lastCommitObservedOffsetExclusive = maxBig(
      current.lastCommitObservedOffsetExclusive,
      big(row?.lastCommitObservedOffsetExclusive),
    );
    merged.set(partition, current);
  }
  return [...merged.values()]
    .sort((left, right) => left.partition - right.partition)
    .map((row) => {
      const first = row.firstFetchedOffsetInclusive ?? 0n;
      const committed = row.lastCommitObservedOffsetExclusive > 0n ? row.lastCommitObservedOffsetExclusive : first;
      return {
        partition: row.partition,
        fetched: row.fetched.toString(),
        commitObserved: row.commitObserved.toString(),
        firstFetchedOffsetInclusive: first.toString(),
        lastFetchedOffsetExclusive: row.lastFetchedOffsetExclusive.toString(),
        lastCommitObservedOffsetExclusive: row.lastCommitObservedOffsetExclusive.toString(),
        lag: distance(committed, row.lastFetchedOffsetExclusive).toString(),
      };
    });
}

function byPartition(rows) {
  return new Map((rows ?? []).map((row) => [number(row?.partition), row]));
}

function nonNegativeDelta(before, after) {
  return after >= before ? after - before : 0n;
}

function distance(start, end) {
  return end >= start ? end - start : 0n;
}

function sumStrings(rows, key) {
  return rows.reduce((sum, row) => sum + big(row[key]), 0n).toString();
}

function big(value) {
  try {
    if (typeof value === "bigint") return value;
    if (typeof value === "number") return BigInt(Math.trunc(Number.isFinite(value) ? value : 0));
    const normalized = String(value ?? "0").trim();
    return BigInt(normalized || "0");
  } catch {
    return 0n;
  }
}

function number(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function minBig(left, right) {
  return left <= right ? left : right;
}

function maxBig(left, right) {
  return left >= right ? left : right;
}

function maxIso(left, right) {
  if (!left) return right || "";
  if (!right) return left;
  return right > left ? right : left;
}

function validZero(value) { return value === 0 || value === "0"; }
