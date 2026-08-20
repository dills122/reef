import assert from "node:assert/strict";
import test from "node:test";
import { composePsqlArgs } from "./compose-psql.mjs";

test("composePsqlArgs includes the repository compose files", () => {
  assert.deepEqual(
    composePsqlArgs("postgres", "SELECT 1", {
      DEV_VENUE_EVENT_MATERIALIZER_DB_USER: "reef_user",
      DEV_VENUE_EVENT_MATERIALIZER_DB_NAME: "reef_db",
    }),
    [
      "compose",
      "-f",
      "compose.base.yml",
      "-f",
      "compose.local.yml",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "reef_user",
      "-d",
      "reef_db",
      "-At",
      "-F",
      "\t",
      "-c",
      "SELECT 1",
    ],
  );
});

test("composePsqlArgs honors an explicit compose overlay", () => {
  const args = composePsqlArgs("projection-postgres", "SELECT 2", {
    DEV_COMPOSE_FILES: "compose.base.yml,compose.local.yml,compose.test.yml",
  });

  assert.deepEqual(args.slice(0, 7), [
    "compose",
    "-f",
    "compose.base.yml",
    "-f",
    "compose.local.yml",
    "-f",
    "compose.test.yml",
  ]);
  assert.deepEqual(args.slice(7, 11), ["exec", "-T", "projection-postgres", "psql"]);
});
