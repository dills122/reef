import assert from "node:assert/strict";
import { test } from "node:test";
import { collectBenchmarkRuntimeSnapshot, buildRuntimeConfigurationEvidence, runtimeConfigurationSignature } from "./benchmark-runtime-signature.mjs";

const names = ["postgres", "boundary-postgres", "projection-postgres", "nats", "matching-engine", "platform-api", "platform-worker-0", "platform-projector-0", "platform-materializer"];
function fixture({ toggle = "true", change = () => {}, failDb = false } = {}) {
  const containers = names.map((name) => ({ Id: name, Image: `sha256:${"a".repeat(64)}`, State: { Running: true }, Config: { Labels: { "com.docker.compose.service": name }, Env: [`PASSWORD=secret`, `PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED=${toggle}`], Cmd: ["server"], Entrypoint: [], WorkingDir: "/app", User: "reef" }, HostConfig: { Memory: 1024 }, Mounts: [] }));
  change(containers);
  return async (command, args) => {
    assert.equal(command, "docker");
    if (args[0] === "info") return JSON.stringify({ NCPU: 8, MemTotal: 16000, Architecture: "aarch64", OSType: "linux", ServerVersion: "27", KernelVersion: "6", OperatingSystem: "Linux" });
    if (args.includes("ps")) return containers.map((value) => value.Id).join("\n");
    if (args[0] === "inspect") return JSON.stringify(containers);
    if (args[0] === "exec") { if (failDb) throw Error("PASSWORD=secret"); return '[{"name":"max_connections","setting":"200"}]'; }
    throw Error(`unexpected command ${args}`);
  };
}

test("hashes effective config without secrets and excludes only instrumentation toggle", async () => {
  const a = await collectBenchmarkRuntimeSnapshot({ run: fixture({ toggle: "false" }) });
  const b = await collectBenchmarkRuntimeSnapshot({ run: fixture() });
  assert.equal(a.complete, true);
  assert.equal(a.fingerprint, b.fingerprint);
  assert.equal(JSON.stringify(a).includes("secret"), false);
  assert.ok(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(a, b)));
});

test("runtime changes affect fingerprint", async () => {
  const baseline = await collectBenchmarkRuntimeSnapshot({ run: fixture() });
  for (const change of [c => c[5].Config.Env.push("PROJECTION_BATCH_SIZE=1"), c => c[5].Config.Env.push("POOL_SIZE=2"), c => c[5].Image = `sha256:${"b".repeat(64)}`, c => c[5].HostConfig.Memory = 2048, c => c[5].Config.Cmd = ["other"], c => c.push({ ...structuredClone(c[7]), Id: "extra", Config: { ...c[7].Config, Labels: { "com.docker.compose.service": "platform-projector-1" } } })]) {
    const changed = await collectBenchmarkRuntimeSnapshot({ run: fixture({ change }) });
    assert.equal(changed.complete, true);
    assert.notEqual(changed.fingerprint, baseline.fingerprint);
    assert.equal(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(baseline, changed)), null);
  }
});

test("missing roles or database settings fail closed without leaking errors", async () => {
  for (const run of [fixture({ change: c => c.splice(7, 1) }), fixture({ failDb: true }), async () => { throw Error("PASSWORD=secret"); }]) {
    const snapshot = await collectBenchmarkRuntimeSnapshot({ run });
    assert.equal(snapshot.complete, false);
    assert.equal(JSON.stringify(snapshot).includes("secret"), false);
    assert.equal(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(snapshot, snapshot)), null);
  }
});


test("direct venue-core profile does not require stream-ack workers", async () => {
  const snapshot = await collectBenchmarkRuntimeSnapshot({ run: fixture({ change: c => c.splice(6, 1) }) });
  assert.equal(snapshot.complete, true);
});

test("host and effective database changes reject equality", async () => {
  const baseline = await collectBenchmarkRuntimeSnapshot({ run: fixture() });
  for (const target of ["host", "database"]) {
    const baseRun = fixture();
    const snapshot = await collectBenchmarkRuntimeSnapshot({ run: async (command, args) => {
      const output = await baseRun(command, args);
      if (target === "host" && args[0] === "info") return JSON.stringify({ ...JSON.parse(output), NCPU: 16 });
      if (target === "database" && args[0] === "exec") return '[{"name":"max_connections","setting":"400"}]';
      return output;
    } });
    assert.equal(snapshot.complete, true);
    assert.notEqual(snapshot.fingerprint, baseline.fingerprint);
  }
});

test("mount order is irrelevant but mount source and permissions remain authoritative", async () => {
  const mounts = [
    { Type: "volume", Name: "boundary-data", Source: "/volumes/boundary-data", Destination: "/var/lib/postgresql/data", Mode: "rw", RW: true, Propagation: "" },
    { Type: "bind", Source: "/repo/postgres/init", Destination: "/docker-entrypoint-initdb.d", Mode: "ro", RW: false, Propagation: "rprivate" },
  ];
  const collect = (values) => collectBenchmarkRuntimeSnapshot({ run: fixture({ change: containers => { containers[1].Mounts = values; } }) });
  const baseline = await collect(mounts);
  const reordered = await collect([...mounts].reverse());
  assert.equal(baseline.complete, true);
  assert.equal(reordered.complete, true);
  assert.equal(reordered.fingerprint, baseline.fingerprint);
  assert.ok(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(baseline, reordered)));
  for (const update of [{ Source: "/volumes/different-data" }, { Mode: "ro", RW: false }, { Propagation: "rshared" }]) {
    const changed = await collect([{ ...mounts[0], ...update }, mounts[1]]);
    assert.equal(changed.complete, true);
    assert.notEqual(changed.fingerprint, baseline.fingerprint);
    assert.equal(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(baseline, changed)), null);
  }
});

test("bind permutation is stable while every bind path option and other resource stays significant", async () => {
  const binds = ["/host_mnt/volumes/db:/var/lib/postgresql/data:rw", "/repo/init:/docker-entrypoint-initdb.d:ro"];
  const collect = (Binds, extra = {}) => collectBenchmarkRuntimeSnapshot({ run: fixture({ change: containers => {
    containers[1].HostConfig = { ...containers[1].HostConfig, Binds, ...extra };
  } }) });
  const baseline = await collect(binds);
  const reordered = await collect([...binds].reverse());
  assert.equal(baseline.complete, true);
  assert.equal(reordered.fingerprint, baseline.fingerprint);
  assert.ok(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(baseline, reordered)));
  for (const [values, extra] of [
    [["/different/db:/var/lib/postgresql/data:rw", binds[1]], {}],
    [["/volumes/db:/var/lib/postgresql/data:rw", binds[1]], {}],
    [[binds[0], "/repo/init:/docker-entrypoint-initdb.d:rw"], {}],
    [[...binds, binds[0]], {}],
    [binds, { Memory: 4096 }],
    [binds, { SecurityOpt: ["option-b", "option-a"] }],
  ]) {
    const changed = await collect(values, extra);
    assert.equal(changed.complete, true);
    assert.notEqual(changed.fingerprint, baseline.fingerprint);
    assert.equal(runtimeConfigurationSignature(buildRuntimeConfigurationEvidence(baseline, changed)), null);
  }
  const otherOrderA = await collect(binds, { SecurityOpt: ["a", "b"] });
  const otherOrderB = await collect(binds, { SecurityOpt: ["b", "a"] });
  assert.notEqual(otherOrderA.fingerprint, otherOrderB.fingerprint);
});

test("bind absent null and empty retain distinct signatures and malformed values fail closed", async () => {
  const signatures = [];
  for (const value of [undefined, null, []]) {
    const snapshot = await collectBenchmarkRuntimeSnapshot({ run: fixture({ change: containers => { containers[1].HostConfig.Binds = value; } }) });
    assert.equal(snapshot.complete, true);
    signatures.push(snapshot.fingerprint);
  }
  assert.equal(new Set(signatures).size, 3);
  for (const value of ["source:dest:ro", false, 42, {}, [null], ["source:dest:ro", 1]]) {
    const snapshot = await collectBenchmarkRuntimeSnapshot({ run: fixture({ change: containers => { containers[1].HostConfig.Binds = value; } }) });
    assert.equal(snapshot.complete, false);
  }
});
