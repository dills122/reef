// Impure fact gathering for the preflight. Every probe goes through `run` so tests can inject fakes
// and so a missing tool degrades to "unavailable" instead of throwing.
import { spawnSync } from "node:child_process";
import os from "node:os";

export function defaultRun(command, args, options = {}) {
  const r = spawnSync(command, args, { encoding: "utf8", timeout: options.timeout ?? 15000, cwd: options.cwd, env: options.env });
  if (r.error) return { status: null, stdout: "", stderr: String(r.error.message ?? r.error) };
  return { status: r.status, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
}

export function gatherGit(run, cwd) {
  const sha = run("git", ["rev-parse", "HEAD"], { cwd });
  if (sha.status !== 0) return { available: false, sha: null, branch: null, dirtyTracked: [] };
  const branch = run("git", ["rev-parse", "--abbrev-ref", "HEAD"], { cwd });
  const status = run("git", ["status", "--porcelain", "--untracked-files=no"], { cwd });
  return {
    available: true,
    sha: sha.stdout.trim(),
    branch: branch.stdout.trim(),
    dirtyTracked: status.stdout.split("\n").filter(Boolean),
  };
}

export function gatherHost() {
  const cpus = os.cpus();
  return {
    platform: os.platform(),
    arch: os.arch(),
    cpus: cpus.length,
    cpuModel: cpus[0]?.model ?? "",
    memGiB: Number((os.totalmem() / 1024 ** 3).toFixed(1)),
    osRelease: os.release(),
  };
}

export function gatherDocker(run) {
  const info = run("docker", ["info", "--format", "{{json .}}"], { timeout: 20000 });
  if (info.status !== 0) {
    return { available: false, reason: (info.stderr || "docker CLI not found").trim().split("\n")[0], containers: [] };
  }
  let parsed;
  try {
    parsed = JSON.parse(info.stdout);
  } catch {
    return { available: false, reason: "unparseable docker info", containers: [] };
  }
  const ps = run("docker", ["ps", "--format", "{{json .}}"], { timeout: 20000 });
  const containers = ps.stdout.split("\n").filter(Boolean).flatMap((line) => {
    try {
      const row = JSON.parse(line);
      const project = /com\.docker\.compose\.project=([^,]*)/.exec(row.Labels ?? "")?.[1] ?? null;
      return [{ name: row.Names, ports: row.Ports ?? "", image: row.Image ?? "", project }];
    } catch {
      return [];
    }
  });
  return {
    available: true,
    ncpu: Number(parsed.NCPU),
    memBytes: Number(parsed.MemTotal),
    serverVersion: parsed.ServerVersion ?? null,
    operatingSystem: parsed.OperatingSystem ?? null,
    containers,
  };
}

// Returns null when no Redpanda container is running (broker not reachable), else [{ name, configs }].
export function gatherTopics(run, containers) {
  const broker = containers.find((c) => /redpanda/i.test(c.name) && !/console/i.test(c.name));
  if (!broker) return null;
  const list = run("docker", ["exec", broker.name, "rpk", "topic", "list"], { timeout: 20000 });
  if (list.status !== 0) return null;
  const names = list.stdout.split("\n").slice(1).map((l) => l.trim().split(/\s+/)[0]).filter((n) => n && !n.startsWith("_"));
  return names.map((name) => {
    const d = run("docker", ["exec", broker.name, "rpk", "topic", "describe", name, "-c"], { timeout: 20000 });
    const configs = {};
    for (const line of d.stdout.split("\n").slice(1)) {
      const [key, value] = line.trim().split(/\s+/);
      if (key && value !== undefined) configs[key] = value;
    }
    return { name, configs };
  });
}

// `docker compose config` works without a daemon. Returns { ok, services } or { ok:false, reason }.
export function gatherComposeConfig(run, files, cwd, env, { extraFiles = [], defaultsOnly = false } = {}) {
  const args = ["compose", ...(defaultsOnly ? ["--env-file", "/dev/null"] : []), ...[...files, ...extraFiles].flatMap((f) => ["-f", f]), "config", "--format", "json"];
  const r = run("docker", args, { cwd, env, timeout: 30000 });
  if (r.status !== 0) return { ok: false, reason: (r.stderr || "docker compose unavailable").trim().split("\n")[0] };
  try {
    return { ok: true, services: JSON.parse(r.stdout).services ?? {} };
  } catch {
    return { ok: false, reason: "unparseable docker compose config output" };
  }
}

export function gatherTools(run, goModText) {
  const goRequired = /^go\s+(\S+)/m.exec(goModText ?? "")?.[1] ?? null;
  const go = run("go", ["version"]);
  const java = run("java", ["-version"]);
  return {
    goRequired,
    go: go.status === 0 ? (/go(\d+\.\d+(?:\.\d+)?)/.exec(go.stdout)?.[1] ?? go.stdout.trim()) : null,
    java: java.status === 0 || java.stderr ? (/version "(\d+(?:\.\d+)*)/.exec(java.stderr + java.stdout)?.[1] ?? null) : null,
  };
}
