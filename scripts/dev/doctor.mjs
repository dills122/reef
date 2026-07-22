import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export function compareVersions(left, right) {
  const a = String(left).split(".").map((part) => Number.parseInt(part, 10) || 0);
  const b = String(right).split(".").map((part) => Number.parseInt(part, 10) || 0);
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index += 1) {
    const delta = (a[index] ?? 0) - (b[index] ?? 0);
    if (delta !== 0) return Math.sign(delta);
  }
  return 0;
}

export function parseGoVersion(output) {
  return output.match(/\bgo(\d+(?:\.\d+){1,2})\b/)?.[1] ?? "";
}

export function parseJavaMajor(output) {
  const raw = output.match(/version\s+"(\d+)(?:\.(\d+))?/)?.[1];
  return raw ? Number.parseInt(raw, 10) : 0;
}

export function repoRequirements(root = repoRoot) {
  const rootPackage = JSON.parse(readFileSync(resolve(root, "package.json"), "utf8"));
  const bun = rootPackage.packageManager.match(/^bun@(.+)$/)?.[1] ?? "";
  const goModules = [
    "services/matching-engine/go.mod",
    "services/simulator/go.mod",
  ].map((path) => readFileSync(resolve(root, path), "utf8").match(/^go\s+(\S+)/m)?.[1] ?? "0");
  const go = goModules.sort(compareVersions).at(-1);
  const node = readFileSync(resolve(root, ".node-version"), "utf8").trim();
  const java = readFileSync(resolve(root, ".java-version"), "utf8").trim();
  return { bun, go, java, node };
}

export function runDoctor(argv = process.argv.slice(2), root = repoRoot) {
  if (argv.includes("--help") || argv.includes("-h")) {
    console.log("usage: bun scripts/dev/doctor.mjs [--full]");
    console.log("  default  check the Docker-first local stack prerequisites");
    console.log("  --full   also check native module toolchains and JS dependencies");
    return 0;
  }

  const full = argv.includes("--full");
  const expected = repoRequirements(root);
  let failures = 0;

  console.log(`Reef developer doctor (${full ? "full contributor" : "core stack"})`);

  function report(status, label, detail = "") {
    const suffix = detail ? ` - ${detail}` : "";
    console.log(`[${status}] ${label}${suffix}`);
    if (status === "fail") failures += 1;
  }

  function command(label, executable, args, validate = () => true) {
    const result = spawnSync(executable, args, { cwd: root, encoding: "utf8" });
    if (result.error || result.status !== 0) {
      report("fail", label, result.error?.code === "ENOENT" ? `${executable} not found` : "command failed");
      return "";
    }
    const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.trim();
    const validation = validate(output);
    if (validation === true) report("ok", label, output.split(/\r?\n/)[0]);
    else report("fail", label, validation);
    return output;
  }

  command("Git", "git", ["--version"]);
  command("Make", "make", ["--version"]);
  command("curl", "curl", ["--version"]);
  command("Docker CLI", "docker", ["--version"]);
  command("Docker Compose v2", "docker", ["compose", "version"]);
  command("Docker daemon", "docker", ["info", "--format", "{{.ServerVersion}}"]);
  command("Bun", "bun", ["--version"], (output) => {
    const actual = output.trim();
    return actual === expected.bun || `found ${actual || "unknown"}; repository pins ${expected.bun}`;
  });

  report(existsSync(resolve(root, ".env")) ? "ok" : "fail", ".env", existsSync(resolve(root, ".env")) ? "present" : "run make dev-bootstrap or copy .env.example");

  if (full) {
    command("Node.js", "node", ["--version"], (output) => {
      const actual = Number.parseInt(output.trim().replace(/^v/, "").split(".")[0], 10);
      const expectedMajor = Number.parseInt(expected.node.split(".")[0], 10);
      return actual === expectedMajor || `found major ${actual || "unknown"}; expected ${expectedMajor}`;
    });
    command("npm", "npm", ["--version"]);
    command("npm Node runtime", "npm", ["exec", "--", "node", "--version"], (output) => {
      const actual = Number.parseInt(output.trim().replace(/^v/, "").split(".")[0], 10);
      const expectedMajor = Number.parseInt(expected.node.split(".")[0], 10);
      return actual === expectedMajor || `npm launches Node major ${actual || "unknown"}; expected ${expectedMajor}`;
    });
    command("Go", "go", ["version"], (output) => {
      const actual = parseGoVersion(output);
      return compareVersions(actual, expected.go) >= 0 || `found ${actual || "unknown"}; need ${expected.go}+`;
    });
    checkJavaToolchain();

    const dependencies = [
      ["root JS dependencies", "node_modules/typescript"],
      ["Arena admin dependencies", "apps/arena-admin/node_modules/.bin/svelte-check"],
      ["docs-site dependencies", "apps/docs-site/node_modules/.bin/astro"],
    ];
    for (const [label, path] of dependencies) {
      report(existsSync(resolve(root, path)) ? "ok" : "fail", label, existsSync(resolve(root, path)) ? "installed" : "run make dev-bootstrap");
    }
  } else {
    console.log("[info] native Go/Java and app dependency checks skipped; use ARGS=--full");
  }

  if (failures > 0) {
    console.log(`doctor found ${failures} blocking issue${failures === 1 ? "" : "s"}`);
    return 1;
  }
  console.log("doctor ok");
  return 0;

  function checkJavaToolchain() {
    const defaultResult = spawnSync("java", ["-version"], { cwd: root, encoding: "utf8" });
    const defaultOutput = `${defaultResult.stdout ?? ""}\n${defaultResult.stderr ?? ""}`.trim();
    const defaultMajor = parseJavaMajor(defaultOutput);
    const expectedMajor = Number.parseInt(expected.java, 10);
    if (defaultResult.status === 0 && defaultMajor === expectedMajor) {
      report("ok", "Java toolchain", defaultOutput.split(/\r?\n/)[0]);
      return;
    }

    const candidates = [
      process.env.JAVA_HOME_21_X64,
      process.env.JAVA_HOME_21_ARM64,
      process.env.JDK21_HOME,
    ].filter(Boolean);
    if (process.platform === "darwin") {
      const javaHome = spawnSync("/usr/libexec/java_home", ["-v", expected.java], {
        cwd: root,
        encoding: "utf8",
      });
      if (javaHome.status === 0 && javaHome.stdout.trim()) candidates.push(javaHome.stdout.trim());
    }

    for (const home of candidates) {
      const result = spawnSync(join(home, "bin/java"), ["-version"], { cwd: root, encoding: "utf8" });
      const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.trim();
      if (result.status === 0 && parseJavaMajor(output) === expectedMajor) {
        const defaultNote = defaultMajor ? `; shell default is Java ${defaultMajor}` : "";
        report("ok", "Java toolchain", `${expected.java} available at ${home}${defaultNote}`);
        return;
      }
    }

    report("fail", "Java toolchain", `found default major ${defaultMajor || "unknown"}; need JDK ${expected.java}`);
  }
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  process.exitCode = runDoctor();
}
