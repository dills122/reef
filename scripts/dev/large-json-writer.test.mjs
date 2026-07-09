import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { writeJsonFileStreaming } from "./lib/large-json-writer.mjs";

const dir = mkdtempSync(join(tmpdir(), "reef-large-json-writer-"));

const report = {
  schemaVersion: "reef.test.v0",
  generatedAt: new Date("2026-07-09T00:00:00.000Z"),
  omitted: undefined,
  totals: {
    ticks: 3,
    failed: false,
    nullable: null,
  },
  sessions: [
    {
      botId: "builtin-mm-refreshing",
      ticks: [
        { tick: 1, commands: [{ commandId: "cmd-1", route: "/api/v1/orders/submit" }] },
        { tick: 2, commands: [undefined, { commandId: "cmd-2", route: "/api/v1/orders/cancel" }] },
      ],
    },
  ],
};

const reportPath = join(dir, "report.json");
await writeJsonFileStreaming(reportPath, report, { space: 2 });
const streamed = readFileSync(reportPath, "utf8");
assert.equal(streamed, `${JSON.stringify(report, null, 2)}\n`);
assert.deepEqual(JSON.parse(streamed), JSON.parse(JSON.stringify(report)));

const compactPath = join(dir, "compact.json");
await writeJsonFileStreaming(compactPath, { a: 1, b: [true, null, "x"] }, { space: 0 });
assert.equal(readFileSync(compactPath, "utf8"), "{\"a\":1,\"b\":[true,null,\"x\"]}\n");

const largePath = join(dir, "large.json");
await writeJsonFileStreaming(
  largePath,
  {
    rows: Array.from({ length: 25_000 }, (_, index) => ({
      index,
      commandId: `cmd-${index}`,
      status: index % 7 === 0 ? "REJECTED" : "COMPLETED",
    })),
  },
  { space: 0 },
);
const large = JSON.parse(readFileSync(largePath, "utf8"));
assert.equal(large.rows.length, 25_000);
assert.equal(large.rows[24_999].commandId, "cmd-24999");

const circular = {};
circular.self = circular;
await assert.rejects(
  () => writeJsonFileStreaming(join(dir, "circular.json"), circular),
  /Converting circular structure to JSON/,
);
await assert.rejects(
  () => writeJsonFileStreaming(join(dir, "undefined.json"), undefined),
  /Cannot serialize undefined as a JSON document/,
);

console.log("large json writer checks passed");
