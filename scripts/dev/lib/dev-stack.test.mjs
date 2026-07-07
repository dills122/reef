import test from "node:test";
import assert from "node:assert/strict";
import { devReset, devUp } from "./dev-stack.mjs";

const databaseServices = ["postgres", "boundary-postgres", "projection-postgres", "arena-postgres"];
const compose = ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml"];

test("devUp starts postgres, runs migrations, then starts full stack", async () => {
  const calls = [];
  const processEnv = {};

  await devUp({
    env: envFrom({
      JS_RUNTIME: "node",
      DEV_COMPOSE_PROFILES: "redis",
      DEV_COMPOSE_WAIT_TIMEOUT_SECONDS: "120",
    }),
    processEnv,
    log: () => {},
    run: async (cmd, args) => {
      calls.push([cmd, args]);
    },
  });

  assert.equal(processEnv.COMPOSE_PROFILES, "redis");
  assert.deepEqual(calls, [
    ["docker", [...compose, "up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "120", ...databaseServices]],
    ["node", ["scripts/dev/db/migrate.mjs"]],
    ["docker", [...compose, "up", "-d", "--build", "--remove-orphans", "--wait", "--wait-timeout", "120"]],
  ]);
});

test("devUp fails before full stack when migrations fail", async () => {
  const calls = [];

  await assert.rejects(
    devUp({
      env: envFrom({ JS_RUNTIME: "node" }),
      processEnv: {},
      log: () => {},
      run: async (cmd, args) => {
        calls.push([cmd, args]);
        if (args[0] === "scripts/dev/db/migrate.mjs") {
          throw new Error("migration failed");
        }
      },
    }),
    /migration failed/,
  );

  assert.deepEqual(calls, [
    ["docker", [...compose, "up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "300", ...databaseServices]],
    ["node", ["scripts/dev/db/migrate.mjs"]],
  ]);
});

test("devReset wipes volumes, migrates, starts stack, and can run smoke", async () => {
  const calls = [];
  const logs = [];
  const processEnv = {};

  await devReset({
    env: envFrom({
      JS_RUNTIME: "node",
      DEV_RESET_RUN_SMOKE: "1",
      DEV_RESET_SMOKE_WAIT_TIMEOUT_SECONDS: "15",
    }),
    processEnv,
    log: (message) => logs.push(message),
    run: async (cmd, args) => {
      calls.push([cmd, args]);
    },
  });

  assert.equal(processEnv.DEV_WAIT_TIMEOUT_SECONDS, "15");
  assert.deepEqual(calls, [
    ["docker", [...compose, "down", "--volumes", "--remove-orphans"]],
    ["docker", [...compose, "up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "300", ...databaseServices]],
    ["node", ["scripts/dev/db/migrate.mjs"]],
    ["docker", [...compose, "up", "-d", "--build", "--remove-orphans", "--wait", "--wait-timeout", "300"]],
    ["node", ["scripts/dev/smoke.mjs"]],
  ]);
  assert.ok(logs.some((message) => message.includes("running smoke verification")));
});

test("devReset skips smoke by default", async () => {
  const calls = [];
  const logs = [];

  await devReset({
    env: envFrom({ JS_RUNTIME: "node" }),
    processEnv: {},
    log: (message) => logs.push(message),
    run: async (cmd, args) => {
      calls.push([cmd, args]);
    },
  });

  assert.deepEqual(calls, [
    ["docker", [...compose, "down", "--volumes", "--remove-orphans"]],
    ["docker", [...compose, "up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "300", ...databaseServices]],
    ["node", ["scripts/dev/db/migrate.mjs"]],
    ["docker", [...compose, "up", "-d", "--build", "--remove-orphans", "--wait", "--wait-timeout", "300"]],
  ]);
  assert.ok(logs.some((message) => message.includes("skipping smoke verification")));
});

test("devUp can use an explicit compatibility compose file", async () => {
  const calls = [];
  const processEnv = {
    REEF_COMPOSE_FILES: "docker-compose.yml",
  };

  await devUp({
    env: envFrom({ JS_RUNTIME: "node" }),
    processEnv,
    log: () => {},
    run: async (cmd, args) => {
      calls.push([cmd, args]);
    },
  });

  const layeredCompose = ["compose", "-f", "docker-compose.yml"];
  assert.deepEqual(calls, [
    ["docker", [...layeredCompose, "up", "-d", "--remove-orphans", "--wait", "--wait-timeout", "300", ...databaseServices]],
    ["node", ["scripts/dev/db/migrate.mjs"]],
    ["docker", [...layeredCompose, "up", "-d", "--build", "--remove-orphans", "--wait", "--wait-timeout", "300"]],
  ]);
});

function envFrom(values) {
  return (name, fallback = "") => {
    const value = values[name];
    return value == null || value === "" ? fallback : value;
  };
}
