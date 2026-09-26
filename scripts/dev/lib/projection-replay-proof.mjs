export function assertStableProjectionReplaySnapshot(before, after) {
  const beforeStable = stableStringify(before);
  const afterStable = stableStringify(after);
  if (beforeStable !== afterStable) {
    throw new Error(
      [
        "projection replay changed stable read-model output",
        `before=${beforeStable}`,
        `after=${afterStable}`,
      ].join("\n"),
    );
  }
}

export function assertClaimGuardedProjectionReplay({
  projectedRows,
  partition,
  streamSequence,
  watermarkRows,
  claimRows,
}) {
  if (Number(projectedRows) !== 0) {
    throw new Error(
      `claim-guarded duplicate replay projected ${projectedRows} rows for partition ${partition} sequence ${streamSequence}`,
    );
  }
  if (
    watermarkRows.length !== 1 ||
    Number(watermarkRows[0].last_partition_seq) >= Number(streamSequence)
  ) {
    throw new Error(
      `claim-guarded duplicate replay unexpectedly advanced its rewound watermark: ${JSON.stringify(watermarkRows)}`,
    );
  }
  if (claimRows.length !== 1) {
    throw new Error(
      `expected one completed projection batch claim for partition ${partition} sequence ${streamSequence}, got ${claimRows.length}`,
    );
  }
  const claim = claimRows[0];
  if (
    claim.status !== "completed" ||
    Number(claim.candidate_count) <= 0 ||
    Number(claim.result_count) !== Number(claim.candidate_count) ||
    Number(claim.max_stream_sequence) !== Number(streamSequence)
  ) {
    throw new Error(`projection batch claim did not prove the exact completed replay membership: ${JSON.stringify(claim)}`);
  }
}

export function stableStringify(value) {
  return JSON.stringify(sortKeys(value));
}

function sortKeys(value) {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, nested]) => [key, sortKeys(nested)]),
    );
  }
  return value;
}
