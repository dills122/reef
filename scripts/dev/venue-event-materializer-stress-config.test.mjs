import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

test("settlement diagnostic rejects a missing dedicated database before stack startup", () => {
  const directory = mkdtempSync(join(tmpdir(), "reef-settlement-config-"));
  const marker = join(directory, "docker-called");
  const fakeDocker = join(directory, "docker");
  try {
    writeFileSync(fakeDocker, '#!/bin/sh\n: > "$REEF_FAKE_DOCKER_MARKER"\nexit 99\n');
    chmodSync(fakeDocker, 0o755);
    const result = spawnSync(process.execPath, ["scripts/dev/venue-event-materializer-stress.mjs"], {
      encoding: "utf8",
      env: { ...process.env, PATH: `${directory}:${process.env.PATH}`,
        REEF_FAKE_DOCKER_MARKER: marker,
        REEF_DO_POSTMATCH_SETTLEMENT_DIAGNOSTIC: "1",
        REEF_SETTLEMENT_POSTGRES_MIGRATIONS: "0" },
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /settlement diagnostic requires REEF_SETTLEMENT_POSTGRES_MIGRATIONS=1/);
    assert.equal(existsSync(marker), false, "configuration check must run before Docker");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
