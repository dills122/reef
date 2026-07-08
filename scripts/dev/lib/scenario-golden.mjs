import { readFileSync, writeFileSync } from "node:fs";

export function compareGoldenJsonFiles(expectedPath, actualPath, options = {}) {
  const expected = JSON.parse(readFileSync(expectedPath, "utf8"));
  const actual = JSON.parse(readFileSync(actualPath, "utf8"));
  const failures = [];
  collectJsonDiffs("", expected, actual, failures, options.maxFailures ?? 50);
  return {
    pass: failures.length === 0,
    expectedPath,
    actualPath,
    failures,
  };
}

export function writeDiffText(path, check) {
  const lines = [
    `expected: ${check.expectedPath}`,
    `actual  : ${check.actualPath}`,
    "",
    ...check.failures.map((failure) => `- ${failure}`),
    "",
  ];
  writeFileSync(path, lines.join("\n"));
}

function collectJsonDiffs(path, expected, actual, failures, maxFailures) {
  if (failures.length >= maxFailures) return;

  const expectedType = jsonType(expected);
  const actualType = jsonType(actual);
  const label = path || "root";
  if (expectedType !== actualType) {
    failures.push(`${label} type changed: expected ${expectedType}, got ${actualType}`);
    return;
  }

  if (Array.isArray(expected)) {
    if (expected.length !== actual.length) {
      failures.push(`${label} length changed: expected ${expected.length}, got ${actual.length}`);
      if (failures.length >= maxFailures) return;
    }
    const limit = Math.min(expected.length, actual.length);
    for (let index = 0; index < limit; index++) {
      collectJsonDiffs(`${label}[${index}]`, expected[index], actual[index], failures, maxFailures);
      if (failures.length >= maxFailures) return;
    }
    return;
  }

  if (isPlainObject(expected)) {
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
    for (const key of [...keys].sort()) {
      if (!Object.hasOwn(expected, key)) {
        failures.push(`${label}.${key} added: ${JSON.stringify(actual[key])}`);
      } else if (!Object.hasOwn(actual, key)) {
        failures.push(`${label}.${key} removed: expected ${JSON.stringify(expected[key])}`);
      } else {
        collectJsonDiffs(`${label}.${key}`, expected[key], actual[key], failures, maxFailures);
      }
      if (failures.length >= maxFailures) return;
    }
    return;
  }

  if (expected !== actual) {
    failures.push(`${label} changed: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function jsonType(value) {
  if (Array.isArray(value)) return "array";
  if (value === null) return "null";
  return typeof value;
}

function isPlainObject(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}
