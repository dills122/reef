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
