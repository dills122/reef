import assert from "node:assert/strict";
import test from "node:test";
import { buildContainer, isMavenRateLimit, runCommand } from "./build-container.mjs";

const throttled = `#15 16.45 > Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.4.20/kotlin-gradle-plugins-bom-2.4.20.pom'.
#15 16.45 > Received status code 429 from server: Too Many Requests`;
const failure = { code: 1, signal: null, log: throttled };

test("recognizes observed Maven request throttling and plugin-portal equivalent", () => {
  assert.equal(isMavenRateLimit(throttled), true);
  assert.equal(isMavenRateLimit(throttled.replace("repo.maven.apache.org/maven2", "plugins.gradle.org/m2")), true);
  for (const log of ["compiler error: 429", "Received status code 429 from server", throttled.replace("429", "404"), throttled.replace("repo.maven.apache.org", "example.com")]) {
    assert.equal(isMavenRateLimit(log), false);
  }
});

test("successful first build runs once without waiting", async () => {
  let attempts = 0;
  const args = ["-f", "path with spaces/Dockerfile", "-t", "reef/stock-data:ci", "."];
  const code = await buildContainer(args, {
    run: async (received) => {
      assert.deepEqual(received, args);
      attempts += 1;
      return { code: 0, log: "" };
    },
    wait: () => assert.fail("success must not wait"),
  });
  assert.equal(code, 0);
  assert.equal(attempts, 1);
});

test("throttled builds retry with bounded backoff and require actual success", async () => {
  const results = [failure, failure, { code: 0, log: "built" }];
  const waits = [];
  const reports = [];
  assert.equal(await buildContainer([], {
    run: async () => results.shift(),
    wait: async (ms) => waits.push(ms),
    report: (message) => reports.push(message),
  }), 0);
  assert.deepEqual(waits, [15000, 30000]);
  assert.equal(results.length, 0);
  assert.equal(reports.length, 2);
});

test("persistent throttling stays red after three attempts", async () => {
  let attempts = 0;
  const waits = [];
  assert.equal(await buildContainer([], {
    run: async () => { attempts += 1; return failure; },
    wait: async (ms) => waits.push(ms),
    report: () => {},
  }), 1);
  assert.equal(attempts, 3);
  assert.deepEqual(waits, [15000, 30000]);
});

test("compile errors, interrupted builds and spawn errors do not retry", async () => {
  for (const result of [{ code: 7, log: "compilation failed" }, { code: null, signal: "SIGTERM", log: throttled }]) {
    let attempts = 0;
    assert.equal(await buildContainer([], {
      run: async () => { attempts += 1; return result; },
      wait: () => assert.fail("must not retry"),
    }), result.code || 1);
    assert.equal(attempts, 1);
  }
  await assert.rejects(buildContainer([], {
    run: async () => { throw new Error("spawn ENOENT"); },
    wait: () => assert.fail("must not retry"),
  }), /ENOENT/);
});

test("subprocess runner captures both streams and preserves exit code", async () => {
  const result = await runCommand(process.execPath, ["-e", "process.stdout.write('build output\\n'); process.stderr.write('dependency error\\n'); process.exitCode = 7;"]);
  assert.equal(result.code, 7);
  assert.equal(result.signal, null);
  assert.match(result.log, /build output/);
  assert.match(result.log, /dependency error/);
  assert.ok(result.log.length <= 65536);
  await assert.rejects(runCommand("/nonexistent/reef-ci-docker", []), /ENOENT/);
});
