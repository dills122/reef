import { collectBenchmarkRuntimeSnapshot, buildRuntimeConfigurationEvidence } from "./benchmark-runtime-signature.mjs";
import assert from "node:assert/strict";
import { test } from "node:test";
import { buildInstrumentationPerturbationComparison } from "./instrumentation-perturbation.mjs";

const snapshot = await collectBenchmarkRuntimeSnapshot({ run: async (_, args) => {
  const names = ["postgres", "boundary-postgres", "projection-postgres", "nats", "matching-engine", "platform-api", "platform-worker-0", "platform-projector-0", "platform-materializer"];
  if (args.includes("ps")) return names.join("\n");
  if (args[0] === "info") return JSON.stringify({ NCPU: 8, MemTotal: 16000, Architecture: "aarch64", KernelVersion: "6", ServerVersion: "27" });
  if (args[0] === "exec") return '[{"name":"max_connections","setting":"200"}]';
  return JSON.stringify(names.map((service) => ({ Id: service, Image: `sha256:${"a".repeat(64)}`, State: { Running: true }, Config: { Labels: { "com.docker.compose.service": service }, Env: [], Cmd: ["server"] }, HostConfig: {}, Mounts: [] })));
} });
const runtimeEvidence = buildRuntimeConfigurationEvidence(snapshot, snapshot);

test("accepts matched three-sample arms within the one-percent limit", () => {
  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: [
      report({ acceptedRps: 2500, projectedRps: 2498, p95: 40, p99: 70, enabled: false }),
      report({ acceptedRps: 2498, projectedRps: 2496, p95: 40.1, p99: 70.1, enabled: false }),
      report({ acceptedRps: 2502, projectedRps: 2500, p95: 39.9, p99: 69.9, enabled: false }),
    ],
    instrumentedReports: [
      report({ acceptedRps: 2490, projectedRps: 2488, p95: 40.2, p99: 70.4, enabled: true }),
      report({ acceptedRps: 2488, projectedRps: 2486, p95: 40.3, p99: 70.5, enabled: true }),
      report({ acceptedRps: 2492, projectedRps: 2490, p95: 40.1, p99: 70.3, enabled: true }),
    ],
  });

  assert.equal(comparison.pass, true);
  assert.equal(comparison.limitPercent, 1);
  assert.equal(comparison.checks.minimumSamples, true);
  assert.equal(comparison.checks.matchedConfiguration, true);
  assert.equal(comparison.checks.instrumentedCohortAuthoritative, true);
  assert.ok(comparison.maxPerturbationPercent < 1);
});

test("fails closed on excessive perturbation and missing cohort authority", () => {
  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: Array.from({ length: 3 }, () =>
      report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false })),
    instrumentedReports: Array.from({ length: 3 }, () =>
      report({ acceptedRps: 2400, projectedRps: 2380, p95: 42, p99: 74, enabled: true, cohortPass: false })),
  });

  assert.equal(comparison.pass, false);
  assert.equal(comparison.checks.instrumentedCohortAuthoritative, false);
  assert.equal(comparison.checks.perturbationWithinLimit, false);
  assert.ok(comparison.maxPerturbationPercent > 1);
});

test("fails closed when the arms do not use the same workload", () => {
  const controls = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  const instrumented = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: true }));
  instrumented[1].config.Workers = 512;

  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: controls,
    instrumentedReports: instrumented,
  });

  assert.equal(comparison.pass, false);
  assert.equal(comparison.checks.matchedConfiguration, false);
});

test("matches configured duration instead of measured wall-clock jitter", () => {
  const controls = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  const instrumented = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: true }));
  controls[0].durationSeconds = 60.001;
  instrumented[0].durationSeconds = 60.009;

  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: controls,
    instrumentedReports: instrumented,
  });

  assert.equal(comparison.checks.matchedConfiguration, true);
});

test("does not treat an unobserved instrumentation state as a valid control", () => {
  const controls = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  controls[0].projectionInstrumentation.state = "unknown";
  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: controls,
    instrumentedReports: Array.from({ length: 3 }, () =>
      report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: true })),
  });

  assert.equal(comparison.pass, false);
  assert.equal(comparison.checks.controlInstrumentationDisabled, false);
});

test("rejects an arm with recovered projector retries", () => {
  const controls = Array.from({ length: 3 }, () =>
    report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  controls[1].streamAckProjector.delta.retryDelta = 1;
  const comparison = buildInstrumentationPerturbationComparison({
    controlReports: controls,
    instrumentedReports: Array.from({ length: 3 }, () =>
      report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: true })),
  });

  assert.equal(comparison.pass, false);
  assert.equal(comparison.checks.benchmarkHealthGreen, false);
});

function report({ acceptedRps, projectedRps, p95, p99, enabled, cohortPass = true }) {
  return {
    runtimeConfigurationEvidence: structuredClone(runtimeEvidence),
    config: {
      Seed: 727272,
      HasSessionConfig: true,
      SessionActors: [{ actorId: "mm", weight: 1 }],
      MarketEquities: [{ instrumentId: "STK001", startingPriceNanos: 100 }],
      RateSchedule: "precise",
      RatePerSecond: 2500,
      Workers: 256,
      Mode: "strict-lifecycle",
      SubmitPct: 68,
      ModifyPct: 24,
      CancelPct: 8,
      Duration: 60_000_000_000,
    },
    stressRunMetadata: { sessionConfig: { path: "/session.yaml", sha256: "a".repeat(64) }, diagnostics: { hotPath: true } },
    downstreamDiagnosticSampler: { configuredIntervalMs: 1000, maxAllowedGapMs: 2000 },
    loadSchedule: { mode: "precise", targetRatePerSecond: 2500, targetRequests: 150000, scheduled: 150000, enqueued: 150000, dropped: 0, completed: 150000, scheduleDeficit: 0, completionDeficit: 0, enqueuedPerSecond: 2500, completedPerSecond: 2500, completionToTargetPct: 100 },
    durationSeconds: 60,
    totalRequests: 150000,
    totalSuccess: 150000,
    totalFailures: 0,
    acceptedBusinessOpsRps: acceptedRps,
    latencyMs: { p95, p99 },
    unitMetrics: { projectedWorkItemsPerSecond: projectedRps },
    projectionInstrumentation: { enabled, state: enabled ? "enabled" : "disabled" },
    upstreamSourceCohort: { pass: true },
    venueEventMaterializer: { delta: { materializedDelta: 150000, failedDelta: 0, ackFailedDelta: 0 } },
    streamAckProjector: {
      before: { projectors: [legacyProjector(1, 0)] },
      after: { projectors: [legacyProjector(20, 100)] },
      delta: {
        projectedDelta: 150000,
        failedDelta: 0,
        retryDelta: 0,
        retryExhaustedDelta: 0,
        afterLag: 0,
      },
    },
    ...(enabled ? { downstreamProjectionCohort: { pass: cohortPass } } : {}),
  };
}

test("rejects historical reports without runtime evidence", () => {
  const controls = Array.from({ length: 3 }, () => report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  const instrumented = controls.map((value) => ({ ...structuredClone(value), projectionInstrumentation: { enabled: true, state: "enabled" }, downstreamProjectionCohort: { pass: true } }));
  for (const value of [...controls, ...instrumented]) delete value.runtimeConfigurationEvidence;
  assert.equal(buildInstrumentationPerturbationComparison({ controlReports: controls, instrumentedReports: instrumented }).pass, false);
});

test("cannot relax frozen one-percent or three-sample gates", () => {
  for (const options of [{ limitPercent: 50 }, { limitPercent: Infinity }, { limitPercent: "1" }, { minimumSamples: 1 }, { minimumSamples: null }, { minimumSamples: NaN }]) {
    const controls = Array.from({ length: 3 }, () => report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
    const instrumented = controls.map((value) => ({ ...structuredClone(value), projectionInstrumentation: { enabled: true, state: "enabled" }, downstreamProjectionCohort: { pass: true } }));
    assert.equal(buildInstrumentationPerturbationComparison({ controlReports: controls, instrumentedReports: instrumented, ...options }).pass, false);
  }
});


test("different or changing effective runtime rejects comparison", () => {
  for (const mutate of [e => { e.after.hostHash = "b".repeat(64); }, e => { e.before.services.pop(); }, e => { e.complete = false; }]) {
    const controls = Array.from({ length: 3 }, () => report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
    const instrumented = controls.map((value) => ({ ...structuredClone(value), projectionInstrumentation: { enabled: true, state: "enabled" }, downstreamProjectionCohort: { pass: true } }));
    mutate(instrumented[1].runtimeConfigurationEvidence);
    const result = buildInstrumentationPerturbationComparison({ controlReports: controls, instrumentedReports: instrumented });
    assert.equal(result.pass, false);
    assert.equal(result.checks.runtimeConfigurationComplete, false);
  }
});

test("rejects two individually valid runs with different runtime settings", async () => {
  const changed = await collectBenchmarkRuntimeSnapshot({ run: async (_, args) => {
    if (args.includes("ps")) return snapshot.services.map((value) => value.service).join("\n");
    if (args[0] === "info") return JSON.stringify({ NCPU: 16, MemTotal: 16000, Architecture: "aarch64", KernelVersion: "6", ServerVersion: "27" });
    if (args[0] === "exec") return '[{"name":"max_connections","setting":"200"}]';
    return JSON.stringify(snapshot.services.map(({ service }) => ({ Id: service, Image: `sha256:${"a".repeat(64)}`, State: { Running: true }, Config: { Labels: { "com.docker.compose.service": service }, Env: [], Cmd: ["server"] }, HostConfig: {}, Mounts: [] })));
  } });
  const controls = Array.from({ length: 3 }, () => report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: false }));
  const instrumented = controls.map((value) => ({ ...structuredClone(value), runtimeConfigurationEvidence: buildRuntimeConfigurationEvidence(changed, changed), projectionInstrumentation: { enabled: true, state: "enabled" }, downstreamProjectionCohort: { pass: true } }));
  const result = buildInstrumentationPerturbationComparison({ controlReports: controls, instrumentedReports: instrumented });
  assert.equal(result.checks.runtimeConfigurationComplete, true);
  assert.equal(result.checks.matchedRuntimeConfiguration, false);
  assert.equal(result.pass, false);
});

// Missing diagnostics must never turn into a healthy comparison.
test("missing or malformed health counters fail closed in either arm", () => {
  const paths = [
    ["totalRequests"], ["totalSuccess"], ["totalFailures"],
    ["venueEventMaterializer", "delta", "materializedDelta"],
    ["venueEventMaterializer", "delta", "failedDelta"],
    ["venueEventMaterializer", "delta", "ackFailedDelta"],
    ...["projectedDelta", "failedDelta", "retryDelta", "retryExhaustedDelta", "afterLag"]
      .map(key => ["streamAckProjector", "delta", key]),
  ];
  for (const arm of ["controlReports", "instrumentedReports"]) {
    for (const path of paths) {
      for (const value of [undefined, null, "", false, -1, 0.5, "NaN", Infinity]) {
        const options = Object.fromEntries(["controlReports", "instrumentedReports"].map(name =>
          [name, Array.from({length:3}, () => report({acceptedRps:2500, projectedRps:2500, p95:40, p99:70, enabled:name==="instrumentedReports"}))]));
        const target = path.slice(0,-1).reduce((obj,key)=>obj[key], options[arm][0]);
        target[path.at(-1)] = value;
        const result = buildInstrumentationPerturbationComparison(options);
        assert.equal(result.checks.benchmarkHealthGreen, false, `${arm}/${path.join(".")}/${String(value)}`);
        assert.equal(result.pass, false);
      }
    }
  }
});


function legacyProjector(cycles, processedRows) {
  return { index: 0, orderLifecycleProjectorEnabled: true, marketDataProjectorEnabled: true,
    ...Object.fromEntries(["orderLifecycleProjector", "marketDataProjector"].map(name => [name, { enabled: true, metrics: { cycles, processedRows, lastProcessedRows: 0, failed: 0 } }])) };
}
function healthyArms() {
  return Object.fromEntries(["controlReports", "instrumentedReports"].map(name => [name, Array.from({ length: 3 }, () => report({ acceptedRps: 2500, projectedRps: 2500, p95: 40, p99: 70, enabled: name === "instrumentedReports" }))]));
}

test("matches complete workload, session content, and diagnostic settings", () => {
  for (const change of [
    r => { r.config.Seed++; }, r => { r.config.SessionActors[0].weight++; },
    r => { r.config.MarketEquities[0].startingPriceNanos++; }, r => { r.config.RequestTimeout = 1; },
    r => { r.config.TraceCheckLimit = 10; }, r => { r.stressRunMetadata.sessionConfig.sha256 = "b".repeat(64); },
    r => { r.stressRunMetadata.diagnostics.hotPath = false; },
    r => { r.downstreamDiagnosticSampler.configuredIntervalMs = 500; },
  ]) {
    const arms = healthyArms(); change(arms.instrumentedReports[1]);
    assert.equal(buildInstrumentationPerturbationComparison(arms).checks.matchedConfiguration, false);
  }
  const arms = healthyArms();
  arms.controlReports[0].config.SessionConfigPath = "/control/session.yaml";
  arms.instrumentedReports[0].config.SessionConfigPath = "/instrumented/session.yaml";
  arms.controlReports[0].config.ReportOut = "/control/report.json";
  arms.instrumentedReports[0].config.ReportOut = "/instrumented/report.json";
  arms.instrumentedReports[0].stressRunMetadata.sessionConfig.path = "/different/session.yaml";
  assert.equal(buildInstrumentationPerturbationComparison(arms).pass, true);
});

test("requires explicit valid schedule evidence and at least 99 percent completion", () => {
  for (const change of [r => { delete r.loadSchedule; }, ...["targetRequests", "scheduled", "completed", "enqueued", "dropped", "scheduleDeficit", "completionDeficit", "targetRatePerSecond", "completionToTargetPct", "enqueuedPerSecond", "completedPerSecond"].flatMap(key => [undefined, null, false, -1, "bad", Infinity].map(value => r => { r.loadSchedule[key] = value; })),
    r => { r.loadSchedule.targetRequests = 300000; r.loadSchedule.completionDeficit = 150000; r.loadSchedule.completionToTargetPct = 50; },
  ]) {
    const arms = healthyArms(); change(arms.controlReports[0]);
    assert.equal(buildInstrumentationPerturbationComparison(arms).checks.benchmarkHealthGreen, false);
  }
  for (const completed of [148500, 148499]) {
    const arms = healthyArms(); const r = arms.controlReports[0];
    r.totalRequests = r.totalSuccess = completed;
    r.venueEventMaterializer.delta.materializedDelta = r.streamAckProjector.delta.projectedDelta = completed;
    Object.assign(r.loadSchedule, { scheduled: completed, enqueued: completed, completed, scheduleDeficit: 150000 - completed, completionDeficit: 150000 - completed, completionToTargetPct: completed / 150000 * 100 });
    assert.equal(buildInstrumentationPerturbationComparison(arms).checks.benchmarkHealthGreen, completed === 148500);
  }
});

test("control legacy downstream counters prove successful work and drained tails", () => {
  for (const stage of ["orderLifecycleProjector", "marketDataProjector"]) {
    for (const change of [
      p => { p[stage].metrics.failed = 1; }, p => { p[stage].metrics.lastProcessedRows = 1; },
      p => { p[stage].metrics.processedRows = 0; }, p => { p[stage].metrics.cycles = 0; },
      ...["failed", "processedRows", "cycles", "lastProcessedRows"].flatMap(key => [undefined, null, false, -1, "bad"].map(value => p => { p[stage].metrics[key] = value; })),
    ]) {
      const arms = healthyArms(); change(arms.controlReports[0].streamAckProjector.after.projectors[0]);
      assert.equal(buildInstrumentationPerturbationComparison(arms).checks.benchmarkHealthGreen, false);
    }
  }
});

test("precise scheduler overshoot is valid when deficits clamp at zero", () => {
  const arms = healthyArms();
  Object.assign(arms.controlReports[0].loadSchedule, { scheduled: 150002, enqueued: 150002 });
  assert.equal(buildInstrumentationPerturbationComparison(arms).checks.benchmarkHealthGreen, true);
});

test("missing workload/session/diagnostic evidence fails closed even across identical arms", () => {
  for (const change of [r => { delete r.config; }, r => { delete r.config.Seed; }, r => { delete r.config.SessionActors; }, r => { delete r.stressRunMetadata.sessionConfig.sha256; }, r => { delete r.stressRunMetadata.diagnostics; }, r => { delete r.downstreamDiagnosticSampler.configuredIntervalMs; }]) {
    const arms = healthyArms();
    [...arms.controlReports, ...arms.instrumentedReports].forEach(change);
    assert.equal(buildInstrumentationPerturbationComparison(arms).checks.matchedConfiguration, false);
  }
});

test("invalid schedule mode/rate and incomplete legacy snapshots fail closed", () => {
  for (const change of [
    r => { r.loadSchedule.mode = r.config.RateSchedule = "unknown"; },
    r => { r.loadSchedule.targetRatePerSecond = r.config.RatePerSecond = 2500.5; r.loadSchedule.targetRequests = 150030; r.loadSchedule.scheduleDeficit = r.loadSchedule.completionDeficit = 30; r.loadSchedule.completionToTargetPct = 150000 / 150030 * 100; },
    r => { delete r.streamAckProjector.before; },
    r => { delete r.streamAckProjector.before.projectors[0].orderLifecycleProjector.metrics.failed; },
    r => { r.streamAckProjector.after.projectors = []; },
    r => { r.streamAckProjector.after.projectors[0].index = 1; },
  ]) {
    const arms = healthyArms(); change(arms.controlReports[0]);
    assert.equal(buildInstrumentationPerturbationComparison(arms).checks.benchmarkHealthGreen, false);
  }
});

test("any dropped scheduled request fails despite complete target demand", () => {
  for (const arm of ["controlReports", "instrumentedReports"]) {
    const arms = healthyArms();
    Object.assign(arms[arm][0].loadSchedule, { scheduled: 150001, enqueued: 150000, dropped: 1 });
    const comparison = buildInstrumentationPerturbationComparison(arms);
    assert.equal(comparison.checks.benchmarkHealthGreen, false);
    assert.equal(comparison.pass, false);
  }
});
