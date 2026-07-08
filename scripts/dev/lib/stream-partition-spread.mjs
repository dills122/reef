import { createHash } from "node:crypto";

const LONG_MAX = (1n << 63n) - 1n;

export function streamRoutingPartition({ runId, venueSessionId, instrumentId, partitionCount }) {
  const partitions = positiveInteger(partitionCount, "partitionCount");
  const source = `${requiredString(runId, "runId")}|${requiredString(venueSessionId, "venueSessionId")}|${requiredString(instrumentId, "instrumentId")}`;
  const bytes = createHash("sha256").update(source).digest();
  let value = 0n;
  for (let index = 0; index < 8; index += 1) {
    value = (value << 8n) | BigInt(bytes[index]);
  }
  value &= LONG_MAX;
  return Number(value % BigInt(partitions));
}

export function selectPartitionSpreadInstruments({
  runId,
  venueSessionId,
  requestedCount,
  partitionCount,
  symbolPrefix = "STK",
  startAt = 1,
  maxCandidateNumber,
}) {
  const count = positiveInteger(requestedCount, "requestedCount");
  const partitions = positiveInteger(partitionCount, "partitionCount");
  const start = positiveInteger(startAt, "startAt");
  const maxNumber = maxCandidateNumber ?? Math.max(start + count * partitions * 128, start + partitions * 4096);
  const buckets = Array.from({ length: partitions }, () => []);
  const selected = [];

  for (let number = start; selected.length < count && number <= maxNumber; number += 1) {
    const symbol = `${symbolPrefix}${String(number).padStart(3, "0")}`;
    const partition = streamRoutingPartition({ runId, venueSessionId, instrumentId: symbol, partitionCount: partitions });
    const minBucketSize = Math.min(...buckets.map((bucket) => bucket.length));
    if (buckets[partition].length !== minBucketSize) continue;

    const instrument = { symbol, instrumentId: symbol, number, partition };
    buckets[partition].push(instrument);
    selected.push(instrument);
  }

  if (selected.length !== count) {
    throw new Error(
      `unable to select ${count} partition-spread instruments across ${partitions} partitions before candidate ${maxNumber}`,
    );
  }

  return selected;
}

export function partitionCounts(instruments, partitionCount) {
  const partitions = positiveInteger(partitionCount, "partitionCount");
  const counts = Array(partitions).fill(0);
  for (const instrument of instruments) {
    const partition = positiveInteger(instrument.partition + 1, "instrument.partition") - 1;
    if (partition >= partitions) {
      throw new Error(`instrument partition ${partition} is outside partitionCount ${partitions}`);
    }
    counts[partition] += 1;
  }
  return counts;
}

function requiredString(value, name) {
  const out = String(value ?? "").trim();
  if (!out) {
    throw new Error(`${name} is required`);
  }
  return out;
}

function positiveInteger(value, name) {
  const out = Number(value);
  if (!Number.isInteger(out) || out < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return out;
}
