import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const projectionName = env("DEV_VENUE_EVENT_REPLAY_CHECK_PROJECTION_NAME", "");
const eventStream = env("DEV_VENUE_EVENT_REPLAY_CHECK_EVENT_STREAM", "");
const commandIds = env("DEV_VENUE_EVENT_REPLAY_CHECK_COMMAND_IDS", "")
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);
const allowEmpty = env("DEV_VENUE_EVENT_REPLAY_CHECK_ALLOW_EMPTY", "false").toLowerCase() === "true";
const requireWatermarks = env("DEV_VENUE_EVENT_REPLAY_CHECK_REQUIRE_WATERMARKS", "false").toLowerCase() === "true";
const reportOut = env("DEV_VENUE_EVENT_REPLAY_CHECK_REPORT_OUT", "");
const visibilityTimeline = await loadVisibilityTimeline();

const report = JSON.parse(await queryReplayReport());
const failures = evaluateReport(report, { projectionName, allowEmpty, requireWatermarks });
const output = {
  pass: failures.length === 0,
  checkedAt: new Date().toISOString(),
  projectionName,
  eventStream,
  commandIds,
  report,
  failures,
};
if (visibilityTimeline) {
  output.visibilityTimeline = visibilityTimeline;
}

const encodedOutput = `${JSON.stringify(output, null, 2)}\n`;
if (reportOut) {
  await mkdir(dirname(reportOut), { recursive: true });
  await writeFile(reportOut, encodedOutput);
}
console.log(encodedOutput.trimEnd());

if (failures.length > 0) {
  process.exitCode = 1;
}

async function queryReplayReport() {
  return (await runRuntimePsql(replayCheckSql())).trim();
}

function replayCheckSql() {
  const commandIDList = commandIds.map((id) => `'${sqlLiteral(id)}'`).join(", ");
  const batchConditions = [];
  if (eventStream) {
    batchConditions.push(`batch.event_stream = '${sqlLiteral(eventStream)}'`);
  }
  if (commandIds.length > 0) {
    batchConditions.push(`
      EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE
            WHEN jsonb_typeof(batch.payload_json->'outcomes') = 'array'
            THEN batch.payload_json->'outcomes'
            ELSE '[]'::jsonb
          END
        ) AS outcome
        WHERE outcome->>'commandId' IN (${commandIDList})
      )
    `);
  }
  const outcomeConditions = [];
  if (eventStream) {
    outcomeConditions.push(`outcome.event_stream = '${sqlLiteral(eventStream)}'`);
  }
  if (commandIds.length > 0) {
    outcomeConditions.push(`outcome.command_id IN (${commandIDList})`);
  }
  const projectableConditions = [`command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')`];
  if (eventStream) {
    projectableConditions.push(`event_stream = '${sqlLiteral(eventStream)}'`);
  }
  if (commandIds.length > 0) {
    projectableConditions.push(`command_id IN (${commandIDList})`);
  }
  const projectionConditions = ["watermark.partition_id >= 0"];
  if (projectionName) {
    projectionConditions.push(`watermark.projection_name = '${sqlLiteral(projectionName)}'`);
  }
  const batchFilter = batchConditions.length > 0 ? `WHERE ${batchConditions.join(" AND ")}` : "";
  const outcomeFilter = outcomeConditions.length > 0 ? `WHERE ${outcomeConditions.join(" AND ")}` : "";
  const expectedOutcomeFilter = commandIds.length > 0 ? `WHERE outcome->>'commandId' IN (${commandIDList})` : "";
  const projectableFilter = `WHERE ${projectableConditions.join(" AND ")}`;
  const projectionFilter = `WHERE ${projectionConditions.join(" AND ")}`;
  return `
    WITH duplicate_replay AS (
      SELECT COALESCE(SUM(runtime.runtime_materialize_venue_event_batch(payload_json)), 0)::BIGINT AS inserted
      FROM runtime.canonical_venue_event_batches batch
      ${batchFilter}
    ),
    batches AS (
      SELECT
        batch.event_stream,
        batch.batch_id,
        batch.partition_id,
        batch.first_sequence,
        batch.last_sequence,
        batch.command_count,
        batch.payload_checksum,
        batch.payload_json,
        CASE
          WHEN jsonb_typeof(batch.payload_json->'outcomes') = 'array'
          THEN jsonb_array_length(batch.payload_json->'outcomes')
          ELSE 0
        END AS payload_outcome_count
      FROM runtime.canonical_venue_event_batches batch
      ${batchFilter}
    ),
    expected_outcomes AS (
      SELECT
        batch.event_stream,
        batch.batch_id,
        batch.partition_id,
        outcome->>'commandId' AS command_id,
        COALESCE((outcome->>'streamSequence')::BIGINT, 0) AS stream_sequence,
        COALESCE(outcome->>'payloadHash', '') AS payload_hash,
        COALESCE(outcome->>'commandType', '') AS command_type
      FROM batches batch
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE
          WHEN jsonb_typeof(batch.payload_json->'outcomes') = 'array'
          THEN batch.payload_json->'outcomes'
          ELSE '[]'::jsonb
        END
      ) AS outcome
      ${expectedOutcomeFilter}
    ),
    actual_outcomes AS (
      SELECT
        outcome.event_stream,
        outcome.batch_id,
        outcome.partition_id,
        outcome.command_id,
        outcome.stream_sequence,
        outcome.payload_hash,
        outcome.command_type
      FROM runtime.canonical_command_outcomes outcome
      ${outcomeFilter}
    ),
    batch_actual_counts AS (
      SELECT
        event_stream,
        batch_id,
        COUNT(*)::BIGINT AS canonical_outcome_count
      FROM actual_outcomes
      GROUP BY event_stream, batch_id
    ),
    batch_command_count_mismatches AS (
      SELECT batch.event_stream, batch.batch_id
      FROM batches batch
      LEFT JOIN batch_actual_counts actual
        ON actual.event_stream = batch.event_stream
       AND actual.batch_id = batch.batch_id
      WHERE batch.command_count <> batch.payload_outcome_count
         OR batch.command_count <> COALESCE(actual.canonical_outcome_count, 0)
    ),
    checksum_mismatches AS (
      SELECT event_stream, batch_id
      FROM batches
      WHERE payload_checksum <> COALESCE(payload_json->>'payloadChecksum', '')
    ),
    payload_hash_mismatches AS (
      SELECT expected.event_stream, expected.batch_id, expected.command_id
      FROM expected_outcomes expected
      JOIN actual_outcomes actual
        ON actual.command_id = expected.command_id
      WHERE actual.payload_hash <> expected.payload_hash
         OR actual.stream_sequence <> expected.stream_sequence
         OR actual.command_type <> expected.command_type
    ),
    missing_outcomes AS (
      SELECT expected.event_stream, expected.batch_id, expected.command_id
      FROM expected_outcomes expected
      LEFT JOIN actual_outcomes actual
        ON actual.command_id = expected.command_id
      WHERE actual.command_id IS NULL
    ),
    extra_outcomes AS (
      SELECT actual.event_stream, actual.batch_id, actual.command_id
      FROM actual_outcomes actual
      LEFT JOIN expected_outcomes expected
        ON expected.command_id = actual.command_id
      WHERE expected.command_id IS NULL
    ),
    ordered_batches AS (
      SELECT
        event_stream,
        partition_id,
        batch_id,
        first_sequence,
        last_sequence,
        LAG(last_sequence) OVER (
          PARTITION BY event_stream, partition_id
          ORDER BY first_sequence, last_sequence, batch_id
        ) AS previous_last_sequence
      FROM batches
    ),
    stream_gaps AS (
      SELECT event_stream, partition_id, batch_id, previous_last_sequence, first_sequence
      FROM ordered_batches
      WHERE previous_last_sequence IS NOT NULL
        AND first_sequence > previous_last_sequence + 1
    ),
    stream_overlaps AS (
      SELECT event_stream, partition_id, batch_id, previous_last_sequence, first_sequence
      FROM ordered_batches
      WHERE previous_last_sequence IS NOT NULL
        AND first_sequence <= previous_last_sequence
    ),
    projectable_max AS (
      SELECT partition_id, MAX(stream_sequence)::BIGINT AS max_stream_sequence
      FROM runtime.canonical_command_outcomes
      ${projectableFilter}
      GROUP BY partition_id
    ),
    watermark_rows AS (
      SELECT
        watermark.projection_name,
        watermark.partition_id,
        watermark.last_partition_seq,
        COALESCE(projectable_max.max_stream_sequence, 0) AS canonical_max_sequence,
        GREATEST(COALESCE(projectable_max.max_stream_sequence, 0) - watermark.last_partition_seq, 0) AS lag
      FROM runtime.projection_watermarks watermark
      LEFT JOIN projectable_max
        ON projectable_max.partition_id = watermark.partition_id
      ${projectionFilter}
    ),
    watermark_lag AS (
      SELECT projection_name, partition_id, last_partition_seq, canonical_max_sequence, lag
      FROM watermark_rows
      WHERE lag > 0
    )
    SELECT jsonb_build_object(
      'batchCount', (SELECT COUNT(*) FROM batches),
      'storedCommandCount', COALESCE((SELECT SUM(command_count) FROM batches), 0),
      'payloadOutcomeCount', (SELECT COUNT(*) FROM expected_outcomes),
      'canonicalOutcomeCount', (SELECT COUNT(*) FROM actual_outcomes),
      'duplicateReplayInserted', (SELECT inserted FROM duplicate_replay),
      'checksumMismatchCount', (SELECT COUNT(*) FROM checksum_mismatches),
      'batchCommandCountMismatchCount', (SELECT COUNT(*) FROM batch_command_count_mismatches),
      'payloadHashMismatchCount', (SELECT COUNT(*) FROM payload_hash_mismatches),
      'missingOutcomeCount', (SELECT COUNT(*) FROM missing_outcomes),
      'extraOutcomeCount', (SELECT COUNT(*) FROM extra_outcomes),
      'streamGapCount', (SELECT COUNT(*) FROM stream_gaps),
      'streamOverlapCount', (SELECT COUNT(*) FROM stream_overlaps),
      'watermarkCount', (SELECT COUNT(*) FROM watermark_rows),
      'watermarkLagCount', (SELECT COUNT(*) FROM watermark_lag),
      'samples', jsonb_build_object(
        'checksumMismatches', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, batch_id) FROM (SELECT * FROM checksum_mismatches LIMIT 10) row), '[]'::jsonb),
        'batchCommandCountMismatches', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, batch_id) FROM (SELECT * FROM batch_command_count_mismatches LIMIT 10) row), '[]'::jsonb),
        'payloadHashMismatches', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, batch_id, command_id) FROM (SELECT * FROM payload_hash_mismatches LIMIT 10) row), '[]'::jsonb),
        'missingOutcomes', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, batch_id, command_id) FROM (SELECT * FROM missing_outcomes LIMIT 10) row), '[]'::jsonb),
        'extraOutcomes', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, batch_id, command_id) FROM (SELECT * FROM extra_outcomes LIMIT 10) row), '[]'::jsonb),
        'streamGaps', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, partition_id, first_sequence) FROM (SELECT * FROM stream_gaps LIMIT 10) row), '[]'::jsonb),
        'streamOverlaps', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY event_stream, partition_id, first_sequence) FROM (SELECT * FROM stream_overlaps LIMIT 10) row), '[]'::jsonb),
        'watermarkLag', COALESCE((SELECT jsonb_agg(to_jsonb(row) ORDER BY projection_name, partition_id) FROM (SELECT * FROM watermark_lag LIMIT 10) row), '[]'::jsonb)
      )
    )::TEXT
  `;
}

function evaluateReport(report, options) {
  const failures = [];
  if (!options.allowEmpty && Number(report.batchCount ?? 0) === 0) {
    failures.push("no canonical venue event batches found");
  }
  assertZero(failures, report, "duplicateReplayInserted", "stored batch payload replay inserted rows");
  assertZero(failures, report, "checksumMismatchCount", "batch payload checksum mismatches");
  assertZero(failures, report, "batchCommandCountMismatchCount", "batch command count mismatches");
  assertZero(failures, report, "payloadHashMismatchCount", "command outcome payload hash mismatches");
  assertZero(failures, report, "missingOutcomeCount", "missing canonical command outcomes");
  assertZero(failures, report, "extraOutcomeCount", "extra canonical command outcomes");
  assertZero(failures, report, "streamGapCount", "event stream sequence gaps");
  assertZero(failures, report, "streamOverlapCount", "event stream sequence overlaps");
  if (options.projectionName) {
    if (options.requireWatermarks && Number(report.watermarkCount ?? 0) === 0) {
      failures.push(`no projection watermarks found for ${options.projectionName}`);
    }
    assertZero(failures, report, "watermarkLagCount", "projection watermark lag rows");
  }
  return failures;
}

async function loadVisibilityTimeline() {
  const inline = env("DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_JSON", "");
  const path = env("DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_PATH", "");
  if (inline && path) {
    throw new Error("set only one of DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_JSON or DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_PATH");
  }
  if (inline) {
    return JSON.parse(inline);
  }
  if (path) {
    return JSON.parse(await readFile(path, "utf8"));
  }
  return null;
}

function assertZero(failures, report, key, label) {
  const value = Number(report[key] ?? 0);
  if (value !== 0) {
    failures.push(`${label}: ${value}`);
  }
}

async function runRuntimePsql(sql) {
  return await runCapture("docker", [
    "compose",
    "exec",
    "-T",
    "postgres",
    "psql",
    "-U",
    env("DEV_VENUE_EVENT_REPLAY_CHECK_DB_USER", "reef"),
    "-d",
    env("DEV_VENUE_EVENT_REPLAY_CHECK_DB_NAME", "reef"),
    "-At",
    "-c",
    sql,
  ]);
}

function runCapture(cmd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}: ${stderr || stdout}`));
      }
    });
  });
}

function sqlLiteral(value) {
  return String(value).replaceAll("'", "''");
}
