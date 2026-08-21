import assert from "node:assert/strict";

import {
  diagnosticCapabilityFlags,
  normalizePgStatStatementRow,
  summarizePgStatStatementsDelta,
} from "./db-diagnostics.mjs";

assert.deepEqual(
  diagnosticCapabilityFlags({
    checkpointerRows: [{ count: "1" }],
    ioRows: [{ count: "1" }],
    pgStatStatementsRows: [{ count: "0" }],
  }),
  {
    hasCheckpointer: true,
    hasIo: true,
    hasPgStatStatements: false,
  },
);

assert.deepEqual(
  diagnosticCapabilityFlags({
    checkpointerRows: [{ count: "0" }],
    ioRows: [{ count: "0" }],
    pgStatStatementsRows: [{ count: "1" }],
  }),
  {
    hasCheckpointer: false,
    hasIo: false,
    hasPgStatStatements: true,
  },
);

assert.equal(
  normalizePgStatStatementRow({
    queryId: "-7566920420081591111",
    topLevel: "f",
    query: "insert into runtime.runtime_events ...",
    calls: "2",
    totalExecTime: "12.5",
  }).queryId,
  "-7566920420081591111",
);

const deltas = summarizePgStatStatementsDelta(
  [
    {
      queryId: "101",
      topLevel: "t",
      query: "select runtime.runtime_persist_submit_outcomes($1, $2)",
      calls: 2,
      totalPlanTime: 1,
      totalExecTime: 10,
      rows: 500,
      sharedBlocksRead: 8,
      tempBlocksRead: 2,
      tempBlocksWritten: 4,
      blockReadTime: 0.5,
      blockWriteTime: 0.25,
      walRecords: 20,
      walFpi: 1,
      walBytes: 100,
    },
  ],
  [
    {
      queryId: "101",
      topLevel: "t",
      query: "select runtime.runtime_persist_submit_outcomes($1, $2)",
      calls: 5,
      totalPlanTime: 2.5,
      totalExecTime: 42,
      rows: 1250,
      sharedBlocksRead: 28,
      tempBlocksRead: 10,
      tempBlocksWritten: 20,
      blockReadTime: 3.5,
      blockWriteTime: 1.25,
      walRecords: 80,
      walFpi: 4,
      walBytes: 460,
    },
    {
      queryId: "202",
      topLevel: "f",
      query: "insert into runtime.runtime_events ...",
      calls: 3,
      totalPlanTime: 0,
      totalExecTime: 12,
      rows: 750,
      sharedBlocksRead: 12,
      tempBlocksRead: 3,
      tempBlocksWritten: 0,
      blockReadTime: 1.25,
      blockWriteTime: 0.75,
      walRecords: 40,
      walFpi: 2,
      walBytes: 220,
    },
  ],
);

assert.deepEqual(deltas, [
  {
    queryId: "101",
    topLevel: "t",
    query: "select runtime.runtime_persist_submit_outcomes($1, $2)",
    callsDelta: 3,
    totalPlanTimeDelta: 1.5,
    totalExecTimeDelta: 32,
    rowsDelta: 750,
    sharedBlocksReadDelta: 20,
    tempBlocksReadDelta: 8,
    tempBlocksWrittenDelta: 16,
    blockReadTimeDelta: 3,
    blockWriteTimeDelta: 1,
    walRecordsDelta: 60,
    walFpiDelta: 3,
    walBytesDelta: 360,
  },
  {
    queryId: "202",
    topLevel: "f",
    query: "insert into runtime.runtime_events ...",
    callsDelta: 3,
    totalPlanTimeDelta: 0,
    totalExecTimeDelta: 12,
    rowsDelta: 750,
    sharedBlocksReadDelta: 12,
    tempBlocksReadDelta: 3,
    tempBlocksWrittenDelta: 0,
    blockReadTimeDelta: 1.25,
    blockWriteTimeDelta: 0.75,
    walRecordsDelta: 40,
    walFpiDelta: 2,
    walBytesDelta: 220,
  },
]);
