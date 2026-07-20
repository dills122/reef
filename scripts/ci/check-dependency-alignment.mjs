import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";

const root = new URL("../../", import.meta.url);

async function read(path) {
  return readFile(new URL(path, root), "utf8");
}

function versions(label, entries, pattern) {
  const found = [];
  for (const [path, content] of entries) {
    for (const match of content.matchAll(pattern)) {
      found.push([path, match[1]]);
    }
  }
  assert.ok(found.length > 1, `${label}: expected versions in multiple files`);
  const distinct = new Set(found.map(([, version]) => version));
  assert.equal(
    distinct.size,
    1,
    `${label} must stay aligned: ${found.map(([path, version]) => `${path}=${version}`).join(", ")}`,
  );
  return found[0][1];
}

const gradlePaths = [
  "services/platform-runtime/build.gradle.kts",
  "services/arena-control-plane/build.gradle.kts",
  "services/stock-data/build.gradle.kts",
];
const gradle = await Promise.all(gradlePaths.map(async (path) => [path, await read(path)]));

versions("Kotlin JVM plugin", gradle, /kotlin\("jvm"\) version "([^"]+)"/g);
versions("Jackson", gradle, /com\.fasterxml\.jackson[^:"]*:[^:"]+:([^"]+)"/g);
versions("PostgreSQL JDBC", gradle, /org\.postgresql:postgresql:([^"]+)"/g);
versions("HikariCP", gradle, /com\.zaxxer:HikariCP:([^"]+)"/g);

const serviceDockerPaths = [
  "services/platform-runtime/Dockerfile",
  "services/arena-control-plane/Dockerfile",
  "services/stock-data/Dockerfile",
];
const serviceDocker = await Promise.all(serviceDockerPaths.map(async (path) => [path, await read(path)]));
versions("Gradle builder image", serviceDocker, /^FROM gradle:([^\s]+) AS build$/gm);

const wrapperPaths = [
  "services/platform-runtime/gradle/wrapper/gradle-wrapper.properties",
  "services/stock-data/gradle/wrapper/gradle-wrapper.properties",
];
const wrappers = await Promise.all(wrapperPaths.map(async (path) => [path, await read(path)]));
versions("Gradle wrapper", wrappers, /gradle-([0-9.]+)-bin\.zip/g);

const goDockerPaths = ["services/matching-engine/Dockerfile", "services/simulator/Dockerfile"];
const goDocker = await Promise.all(goDockerPaths.map(async (path) => [path, await read(path)]));
versions("Go builder image", goDocker, /^FROM golang:([^\s]+) AS build$/gm);
versions("Go debug runtime image", goDocker, /^FROM alpine:([^\s]+) AS debug$/gm);

const arenaPackage = JSON.parse(await read("apps/arena-admin/package.json"));
assert.equal(
  arenaPackage.devDependencies["@tailwindcss/vite"],
  arenaPackage.devDependencies.tailwindcss,
  "arena-admin Tailwind core and Vite plugin ranges must stay aligned",
);

const rootPackage = JSON.parse(await read("package.json"));
const expectedBun = rootPackage.packageManager.match(/^bun@(.+)$/)?.[1];
assert.ok(expectedBun, "root packageManager must pin Bun");

const workflowDirectory = new URL(".github/workflows/", root);
const workflowNames = (await readdir(workflowDirectory)).filter((name) => name.endsWith(".yml"));
const workflowEntries = await Promise.all(
  workflowNames.map(async (name) => [`.github/workflows/${name}`, await read(`.github/workflows/${name}`)]),
);
const workflowBun = [];
for (const [path, content] of workflowEntries) {
  for (const match of content.matchAll(/bun-version:\s*['"]?([^'"\s]+)/g)) {
    workflowBun.push([path, match[1]]);
  }
}
assert.ok(workflowBun.length > 0, "expected at least one CI Bun pin");
for (const [path, version] of workflowBun) {
  assert.equal(version, expectedBun, `${path} Bun version must match packageManager bun@${expectedBun}`);
}

console.log("dependency alignment checks passed");
