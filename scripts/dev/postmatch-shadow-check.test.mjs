import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { assess } from "./postmatch-shadow-check.mjs";

const generation = "source-generation";
const source = new Map([[0, { count: "2", sequence: "2" }]]);
const target = new Map([
  ["live-v1:0", { generation, sequence: "2" }],
  ["live-market-v1:0", { generation, sequence: "2" }],
]);

test("closed cohort requires both independently advanced frontiers", () => {
  assert.deepEqual(assess(source, target, [0], generation).failures, []);
  const lagging = new Map(target);
  lagging.set("live-market-v1:0", { generation, sequence: "1" });
  assert.match(assess(source, lagging, [0], generation).failures.join(" "), /market frontier differs from source/);
  const ahead = new Map(target);
  ahead.set("live-v1:0", { generation, sequence: "3" });
  assert.match(assess(source, ahead, [0], generation).failures.join(" "), /live frontier differs from source/);
});

test("missing partition and rebuilt source generation fail", () => {
  const result = assess(source, target, [0, 1], generation);
  assert.match(result.failures.join(" "), /live frontier missing for partition 1/);
  const rebuilt = new Map(target);
  rebuilt.set("live-v1:0", { generation: "old-generation", sequence: "2" });
  assert.match(assess(source, rebuilt, [0], generation).failures.join(" "), /source generation mismatch/);
});

test("empty source cohort cannot pass from initialized frontiers", () => {
  assert.match(assess(new Map(), target, [0], generation).failures.join(" "), /source cohort is empty/);
});

test("active source partition outside worker assignment fails", () => {
  const withUnassigned = new Map(source);
  withUnassigned.set(15, { count: "1", sequence: (15n << 48n | 1n).toString() });
  assert.match(assess(withUnassigned, target, [0], generation).failures.join(" "), /source partition 15 is not assigned/);
});

test("CLI compares complete source and receipt streams and writes a failure artifact", () => {
  const dir = mkdtempSync(join(tmpdir(), "reef-shadow-check-"));
  const docker = join(dir, "docker");
  const calls = join(dir, "calls");
  const polls = join(dir, "polls");
  writeFileSync(docker, `#!/usr/bin/env node
const fs = require("node:fs");
const args = process.argv.slice(2);
const sql = args.at(-1);
const target = args.includes("postmatch-postgres");
const kind = sql.includes("consumer_frontiers") ? "frontiers" : sql.startsWith("COPY (") ? "membership"
  : sql.includes("postmatch_source_generation") ? "generation" : "source";
fs.appendFileSync(process.env.REEF_FAKE_CALLS, kind + "\\n");
if (process.env.REEF_FAKE_FAIL === "1") process.exit(2);
if (sql.includes("postmatch_source_generation")) process.stdout.write("gen\\n");
else if (sql.includes("count(*)::text")) process.stdout.write(process.env.REEF_FAKE_UNCOVERED === "1" && !sql.includes("partition_id IN")
  ? "0\\t2\\t2\\n15\\t1\\t4222124650659841\\n" : "0\\t2\\t2\\n");
else if (sql.includes("consumer_frontiers")) {
  const poll = fs.existsSync(process.env.REEF_FAKE_POLLS)
    ? Number(fs.readFileSync(process.env.REEF_FAKE_POLLS, "utf8")) + 1 : 1;
  fs.writeFileSync(process.env.REEF_FAKE_POLLS, String(poll));
  process.stdout.write("live-v1\\t0\\tgen\\t2\\n" +
    (process.env.REEF_FAKE_LAG === "1" && poll === 1 ? "" : "live-market-v1\\t0\\tgen\\t2\\n"));
}
else if (sql.startsWith("COPY (")) process.stdout.write(target && process.env.REEF_FAKE_MISMATCH === "1"
  ? "0,1,b1,c1,changed\\n0,2,b2,c2,hash\\n"
  : target && process.env.REEF_FAKE_GAP === "1" ? "0,1,b1,c1,hash\\n"
  : "0,1,b1,c1,hash\\n0,2,b2,c2,hash\\n");
else process.exit(2);
`);
  chmodSync(docker, 0o755);
  const run = (mismatch, commandFailure = false, uncovered = false, lag = false, gap = false) => {
    writeFileSync(calls, "");
    writeFileSync(polls, "0");
    const output = join(dir, uncovered ? "uncovered.json" : commandFailure ? "command-failure.json" :
      mismatch ? "mismatch.json" : gap ? "gap.json" : "pass.json");
    const result = spawnSync(process.execPath, ["scripts/dev/postmatch-shadow-check.mjs", "REEF_EVENTS_TEST", "0", output, lag ? "2" : "0"], {
      encoding: "utf8", env: { ...process.env, PATH: `${dir}:${process.env.PATH}`,
        REEF_FAKE_MISMATCH: mismatch ? "1" : "0", REEF_FAKE_FAIL: commandFailure ? "1" : "0",
        REEF_FAKE_UNCOVERED: uncovered ? "1" : "0", REEF_FAKE_LAG: lag ? "1" : "0",
        REEF_FAKE_GAP: gap ? "1" : "0",
        REEF_FAKE_CALLS: calls, REEF_FAKE_POLLS: polls },
    });
    return { result, report: JSON.parse(readFileSync(output, "utf8")),
      calls: readFileSync(calls, "utf8").trim().split("\n") };
  };
  const pass = run(false);
  assert.equal(pass.result.status, 0, pass.result.stderr);
  assert.equal(pass.report.status, "pass");
  const fail = run(true);
  assert.equal(fail.result.status, 1);
  assert.match(fail.report.failures.join(" "), /receipt membership differs/);
  const gap = run(false, false, false, false, true);
  assert.equal(gap.result.status, 1);
  assert.match(gap.report.failures.join(" "), /receipt membership differs/);
  const commandFailure = run(false, true);
  assert.equal(commandFailure.result.status, 1);
  assert.match(commandFailure.report.failures.join(" "), /query failed/);
  const uncovered = run(false, false, true);
  assert.equal(uncovered.result.status, 1);
  assert.match(uncovered.report.failures.join(" "), /source partition 15 is not assigned/);
  const lagged = run(false, false, false, true);
  assert.equal(lagged.result.status, 0, lagged.result.stderr);
  assert.equal(lagged.calls.filter((kind) => kind === "frontiers").length, 2);
  assert.equal(lagged.calls.filter((kind) => kind === "membership").length, 3);
  assert.ok(lagged.calls.indexOf("membership") > lagged.calls.lastIndexOf("frontiers"));
});
