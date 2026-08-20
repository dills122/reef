import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { spawnSync } from "node:child_process";

const helper = "scripts/dev/lib/benchmark-stage.sh";

test("benchmark_run_stage preserves a failed command status", () => {
  const logDir = mkdtempSync(join(tmpdir(), "reef-benchmark-stage-"));
  const result = runStage(logDir, "failure", "exit 7");

  assert.equal(result.status, 7, result.stderr);
  assert.match(result.stderr, /stage failed: failure status=7/);
  assert.equal(readFileSync(join(logDir, "stage-failure.log"), "utf8"), "failed output\n");
});

test("benchmark_run_stage returns zero for a successful command", () => {
  const logDir = mkdtempSync(join(tmpdir(), "reef-benchmark-stage-"));
  const result = runStage(logDir, "success", "exit 0");

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /stage complete: success/);
  assert.equal(readFileSync(join(logDir, "stage-success.log"), "utf8"), "successful output\n");
});

function runStage(logDir, name, exitCommand) {
  const output = name === "failure" ? "failed output" : "successful output";
  return spawnSync(
    "bash",
    [
      "-c",
      `source "$1"; benchmark_run_stage "$2" 10 "$3" bash -c "$4"`,
      "reef-benchmark-stage-test",
      helper,
      logDir,
      name,
      `printf "%s\\n" "${output}"; ${exitCommand}`,
    ],
    { cwd: process.cwd(), encoding: "utf8" },
  );
}
