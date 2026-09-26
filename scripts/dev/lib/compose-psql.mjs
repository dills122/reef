import { composeArgs } from "./compose-utils.mjs";

export function composePsqlArgs(service, sql, processEnv = process.env, credentials = {}) {
  return composeArgs([
    "exec",
    "-T",
    service,
    "psql",
    "-U",
    credentials.user ?? readEnv(processEnv, "DEV_VENUE_EVENT_MATERIALIZER_DB_USER", "reef"),
    "-d",
    credentials.database ?? readEnv(processEnv, "DEV_VENUE_EVENT_MATERIALIZER_DB_NAME", "reef"),
    "-At",
    "-F",
    "\t",
    "-c",
    sql,
  ], processEnv);
}

function readEnv(processEnv, name, fallback) {
  const value = processEnv[name];
  return value == null || value === "" ? fallback : value;
}
