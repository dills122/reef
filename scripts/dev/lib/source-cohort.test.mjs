import assert from "node:assert/strict";
import test from "node:test";
import { buildUpstreamSourceCohort, mergeMaterializerTelemetry } from "./source-cohort.mjs";

test("builds an exclusive accepted-to-canonical cohort with exact offset joins", () => {
  const report = exactReport();

  const cohort = buildUpstreamSourceCohort(report);

  assert.equal(cohort.schemaVersion, 1);
  assert.equal(cohort.authority, "exclusive-kafka-offset-frontier-v1");
  assert.equal(cohort.pass, true);
  assert.deepEqual(cohort.totals, {
    accepted: "5",
    sourceOffsetSpan: "5",
    directAcked: "5",
    materializedMembership: "5",
  });
  assert.deepEqual(cohort.partitions, [
    {
      partition: 0,
      startOffsetInclusive: "10",
      endOffsetExclusive: "13",
      sourceOffsetSpan: "3",
      accepted: "3",
      directAcked: "3",
      materializedStartOffsetInclusive: "10",
      materializedEndOffsetExclusive: "13",
      materializedMembership: "3",
      exactCanonicalMembership: true,
      exclusive: true,
      joined: true,
    },
    {
      partition: 2,
      startOffsetInclusive: "20",
      endOffsetExclusive: "22",
      sourceOffsetSpan: "2",
      accepted: "2",
      directAcked: "2",
      materializedStartOffsetInclusive: "20",
      materializedEndOffsetExclusive: "22",
      materializedMembership: "2",
      exactCanonicalMembership: true,
      exclusive: true,
      joined: true,
    },
  ]);
  assert.deepEqual(cohort.checks, {
    acceptedCountMatchesReport: true,
    acceptedSourceExclusive: true,
    durableIntakeJoined: true,
    materializerMembershipJoined: true,
    checksumMembershipComplete: true,
    canonicalCommitObserved: true,
    sourceCommitObserved: true,
    sourceCommitFrontiersComplete: true,
    canonicalSourceTimingComplete: true,
    clocksValid: true,
  });
});

test("rejects an accepted frontier containing an interleaved source record", () => {
  const report = exactReport();
  report.streamAckHealth.after.sourceTopicFrontiers[0].lastOffsetExclusive = "14";

  const cohort = buildUpstreamSourceCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.acceptedSourceExclusive, false);
  assert.equal(cohort.partitions[0].sourceOffsetSpan, "4");
  assert.equal(cohort.partitions[0].accepted, "3");
});

test("rejects missing source timing and clock guard failures", () => {
  const report = exactReport();
  report.venueEventMaterializer.delta.sourceResidenceSamplesDelta = 1;
  report.venueEventMaterializer.delta.clockGuardFailuresDelta = 1;

  const cohort = buildUpstreamSourceCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.canonicalSourceTimingComplete, false);
  assert.equal(cohort.checks.clocksValid, false);
});

test("rejects a partial Kafka source commit even when batch counts match", () => {
  const report = exactReport();
  report.venueEventMaterializer.after.metrics.sourcePartitions[0].lastCommitObservedOffsetExclusive = "1";
  report.venueEventMaterializer.after.metrics.sourcePartitions[0].lag = "1";

  const cohort = buildUpstreamSourceCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.sourceCommitObserved, true);
  assert.equal(cohort.checks.sourceCommitFrontiersComplete, false);
});

test("rejects a duplicate plus missing canonical source offset with balanced count and bounds", () => {
  const report = exactReport();
  report.venueEventMaterializer.after.metrics.materializedSourceFrontiers[0] = frontier(
    0,
    6,
    7,
    13,
    2,
    [[7, 11], [12, 13]],
  );

  const cohort = buildUpstreamSourceCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.partitions[0].materializedMembership, "3");
  assert.equal(cohort.partitions[0].exactCanonicalMembership, false);
  assert.equal(cohort.checks.materializerMembershipJoined, false);
});

test("merges materializer source frontiers without lossy composite sequence math", () => {
  const merged = mergeMaterializerTelemetry([
    materializerInstance({
      materializedSourceFrontiers: [frontier(0, 4, 10, 14)],
      sourcePartitions: [sourcePartition(1, 10, 11)],
    }),
    materializerInstance({
      materializedSourceFrontiers: [frontier(0, 3, 14, 17), frontier(2, 2, 20, 22)],
      sourcePartitions: [sourcePartition(1, 11, 12)],
    }),
  ]);

  assert.deepEqual(merged.materializedSourceFrontiers, [
    frontier(0, 7, 10, 17, 2),
    frontier(2, 2, 20, 22),
  ]);
  assert.deepEqual(merged.sourcePartitions, [
    {
      partition: 1,
      fetched: "2",
      commitObserved: "2",
      firstFetchedOffsetInclusive: "10",
      lastFetchedOffsetExclusive: "12",
      lastCommitObservedOffsetExclusive: "12",
      lag: "0",
    },
  ]);
});

function exactReport() {
  return {
    totalSuccess: 5,
    streamAckHealth: {
      before: {
        sourceTopicFrontiers: [topicFrontier(0, 10), topicFrontier(2, 20)],
        acceptedSourceFrontiers: [acceptedFrontier(0, 7, 7, 10)],
      },
      after: {
        sourceTopicFrontiers: [topicFrontier(0, 13), topicFrontier(2, 22)],
        acceptedSourceFrontiers: [acceptedFrontier(0, 10, 7, 13), acceptedFrontier(2, 2, 20, 22)],
      },
      delta: { publishCompletedDelta: 5 },
    },
    streamDirect: {
      delta: {
        ackedDelta: 5,
        partitionDeltas: [
          { partition: 0, ackedDelta: 3 },
          { partition: 2, ackedDelta: 2 },
        ],
      },
    },
    venueEventMaterializer: {
      before: {
        metrics: {
          materializedSourceFrontiers: [frontier(0, 3, 7, 10)],
          sourcePartitions: [],
        },
      },
      after: {
        metrics: {
          materializedSourceFrontiers: [frontier(0, 6, 7, 13), frontier(2, 2, 20, 22)],
          sourcePartitions: [
            sourcePartition(0, 0, 2, 2, 2),
          ],
        },
      },
      delta: {
        materializedDelta: 5,
        materializedBatchesDelta: 2,
        checksumValidatedBatchesDelta: 2,
        legacyUncheckedBatchesDelta: 0,
        validatedMembershipOutcomesDelta: 5,
        canonicalCommitObservedBatchesDelta: 2,
        sourceCommitObservedBatchesDelta: 2,
        sourceResidenceSamplesDelta: 2,
        clockGuardFailuresDelta: 0,
      },
    },
  };
}

function acceptedFrontier(partition, accepted, first, last) {
  return {
    partition,
    accepted: String(accepted),
    firstOffsetInclusive: String(first),
    lastOffsetExclusive: String(last),
  };
}

function topicFrontier(partition, last) {
  return {
    partition,
    lastOffsetExclusive: String(last),
  };
}

function frontier(partition, outcomes, first, last, batches = 1, coveredRanges = [[first, last]]) {
  return {
    partition,
    observedBatches: String(batches),
    observedOutcomes: String(outcomes),
    firstOffsetInclusive: String(first),
    lastOffsetExclusive: String(last),
    coveredRanges: coveredRanges.map(([rangeStart, rangeEnd]) => ({
      firstOffsetInclusive: String(rangeStart),
      lastOffsetExclusive: String(rangeEnd),
    })),
  };
}

function sourcePartition(partition, first, last, fetched = 1, commitObserved = 1) {
  return {
    partition,
    fetched: String(fetched),
    commitObserved: String(commitObserved),
    firstFetchedOffsetInclusive: String(first),
    lastFetchedOffsetExclusive: String(last),
    lastCommitObservedOffsetExclusive: String(last),
    lag: "0",
  };
}

function materializerInstance(overrides) {
  return {
    metrics: {
      materializedSourceFrontiers: [],
      sourcePartitions: [],
      ...overrides,
    },
  };
}

test("missing clock and unchecked-batch counters cannot establish source authority", () => {
  for (const key of ["clockGuardFailuresDelta", "legacyUncheckedBatchesDelta"]) {
    const report = exactReport(); delete report.venueEventMaterializer.delta[key];
    assert.equal(buildUpstreamSourceCohort(report).pass, false);
  }
});
