import { delimiter } from "node:path";

const defaultComposeFiles = ["compose.base.yml", "compose.local.yml"];

export function composeFiles(env = process.env) {
  const raw = env.REEF_COMPOSE_FILES || env.DEV_COMPOSE_FILES || "";
  if (!raw.trim()) {
    return defaultComposeFiles;
  }
  return raw
    .split(/[,\n]/)
    .flatMap((part) => part.split(delimiter))
    .map((part) => part.trim())
    .filter(Boolean);
}

export function composeFileArgs(env = process.env) {
  return composeFiles(env).flatMap((file) => ["-f", file]);
}

export function composeArgs(args = [], env = process.env) {
  return ["compose", ...composeFileArgs(env), ...args];
}
