const state = {
  selectedRunId: "",
  selectedRun: null,
  runs: [],
  runSort: "newest",
  events: [],
  trend: [],
  source: null,
  userSelectedRun: false,
  config: null,
};

const ids = {
  runtimeStatus: document.querySelector("#runtime-status"),
  sampleTime: document.querySelector("#sample-time"),
  activeRunLabel: document.querySelector("#active-run-label"),
  runList: document.querySelector("#run-list"),
  evidence: document.querySelector("#evidence"),
  log: document.querySelector("#log"),
  logRun: document.querySelector("#log-run"),
  trend: document.querySelector("#trend"),
  throughputTrend: document.querySelector("#throughput-trend"),
  latencyTrend: document.querySelector("#latency-trend"),
  containerStatus: document.querySelector("#container-status"),
  runtimeUrl: document.querySelector("#runtime-url"),
  profileName: document.querySelector("#profile-name"),
  profileAlerts: document.querySelector("#profile-alerts"),
  lastProbe: document.querySelector("#last-probe"),
  probeError: document.querySelector("#probe-error"),
  runSort: document.querySelector("#run-sort"),
  refreshRuns: document.querySelector("#refresh-runs"),
  connect: document.querySelector("#connect"),
  pause: document.querySelector("#pause"),
};

ids.runSort.addEventListener("change", () => {
  state.runSort = ids.runSort.value;
  renderRuns(state.runs);
});
ids.refreshRuns.addEventListener("click", () => refreshRunsNow());
ids.connect.addEventListener("click", () => reconnect());
ids.pause.addEventListener("click", () => pause());

init();

async function init() {
  state.connected = true;
  await loadConfig();
  await reconnect();
}

async function loadConfig() {
  const config = await getJson("/api/config");
  state.config = config;
  ids.runtimeUrl.textContent = config.runtimeUrl || "unknown";
  ids.profileName.textContent = config.profile?.name || "unknown";
}

async function refreshAll(options = {}) {
  if (!state.connected && !options.allowPaused) return;
  const runsPayload = await getJson("/api/runs");
  const runs = runsPayload.runs || [];
  state.runs = runs;
  autoSelectRun(runs, { preferNewest: Boolean(options.preferNewest) });
  const [snapshot, selected] = await Promise.all([
    getJson(`/api/snapshot?runId=${encodeURIComponent(state.selectedRunId)}`),
    state.selectedRunId ? getJson(`/api/runs/${encodeURIComponent(state.selectedRunId)}`) : null,
  ]);
  state.selectedRun = selected;
  renderSnapshot(snapshot, selected);
  renderRuns(runs);
  renderEvidence(selected);
}

async function refreshRunsNow() {
  ids.refreshRuns.disabled = true;
  ids.refreshRuns.textContent = "Refreshing...";
  ids.probeError.textContent = "none";
  state.userSelectedRun = false;
  try {
    await refreshAll({ allowPaused: true, preferNewest: true });
  } catch (error) {
    markDisconnected(error);
  } finally {
    ids.refreshRuns.disabled = false;
    ids.refreshRuns.textContent = "Refresh Runs";
  }
}

async function reconnect() {
  state.connected = true;
  ids.connect.textContent = "Reconnect";
  ids.pause.textContent = "Pause";
  ids.probeError.textContent = "none";
  if (state.pollTimer) clearInterval(state.pollTimer);
  if (state.source && state.selectedRunId) {
    state.source.close();
    state.source = null;
    openRunEventStream(state.selectedRunId);
  }
  state.trend = [];
  try {
    await refreshAll();
  } catch (error) {
    markDisconnected(error);
  }
  state.pollTimer = setInterval(() => refreshAll().catch(markDisconnected), 2000);
}

function pause() {
  state.connected = false;
  ids.pause.textContent = "Paused";
  ids.connect.textContent = "Connect";
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = null;
  if (state.source) state.source.close();
  state.source = null;
}

function markDisconnected(error) {
  state.connected = false;
  ids.runtimeStatus.textContent = "monitor disconnected";
  ids.runtimeStatus.className = "pill bad";
  ids.probeError.textContent = String(error?.message || error).slice(0, 90);
  ids.connect.textContent = "Reconnect";
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = null;
}

function renderSnapshot(snapshot, selectedRun) {
  const runtime = snapshot.probes?.runtime;
  const internalBlocked = Object.values(snapshot.probes || {}).some((probe) => probe?.status === 403);
  ids.runtimeStatus.textContent = runtime?.ok
    ? internalBlocked
      ? "internal diagnostics blocked"
      : "runtime online"
    : "runtime offline";
  ids.runtimeStatus.className = `pill ${runtime?.ok && !internalBlocked ? "ok" : "bad"}`;
  ids.sampleTime.textContent = snapshot.sampledAt || "not sampled";
  ids.lastProbe.textContent = snapshot.sampledAt || "not connected";
  ids.probeError.textContent = firstProbeError(snapshot) || "none";
  const metrics = snapshot.metrics || {};
  const selectedMetrics = runDashboardMetrics(selectedRun);
  if (selectedMetrics) {
    if (isMaterializerReport(selectedMetrics)) {
      setMetricLabels({
        accepted: ["Attempted", "selected run"],
        completed: ["Accepted", "selected run"],
        gap: ["Direct Acked", "durable broker"],
        "worker-lag": ["Materialized", "canonical rows"],
        materialized: ["Mat Gap", "accepted to materialized"],
        projected: ["System Failures", "latest report"],
      });
      setMetric("accepted", selectedMetrics.attempted);
      setMetric("completed", selectedMetrics.accepted);
      setMetric("gap", selectedMetrics.directAcked);
      setMetric("worker-lag", selectedMetrics.materialized);
      setMetric("materialized", selectedMetrics.acceptedToMaterialized);
      setMetric("projected", selectedMetrics.systemFailures);
    } else {
      setMetricLabels({
        accepted: ["Attempted", "selected run"],
        completed: ["Accepted", "selected run"],
        gap: ["Success Rate", "end-to-end"],
        "worker-lag": ["P95 Latency", "latest report"],
        materialized: ["P99 Latency", "latest report"],
        projected: ["System Failures", "latest report"],
      });
      setMetric("accepted", selectedMetrics.attempted);
      setMetric("completed", selectedMetrics.accepted);
      setMetric("gap", `${fmt(selectedMetrics.successRatePct)}%`);
      setMetric("worker-lag", `${fmt(selectedMetrics.p95LatencyMs)}ms`);
      setMetric("materialized", `${fmt(selectedMetrics.p99LatencyMs)}ms`);
      setMetric("projected", selectedMetrics.systemFailures);
    }
  } else if (state.selectedRunId) {
    const live = liveRuntimeMetrics(snapshot, metrics);
    setMetricLabels({
      accepted: ["Stream Messages", "live platform"],
      completed: ["Worker Completed", "container totals"],
      gap: ["Worker Lag", "stream backlog"],
      "worker-lag": ["Projector Lag", "read-model backlog"],
      materialized: ["Materialized", "canonical outcomes"],
      projected: ["Projected", "read-model work"],
    });
    setMetric("accepted", live.commands);
    setMetric("completed", live.workerCompleted);
    setMetric("gap", live.workerLag);
    setMetric("worker-lag", live.projectorLag);
    setMetric("materialized", live.materialized);
    setMetric("projected", live.projected);
  } else {
    setMetricLabels({
      accepted: ["Stream Messages", "diagnostics"],
      completed: ["Worker Completed", "container totals"],
      gap: ["Worker Lag", "stream backlog"],
      "worker-lag": ["Projector Lag", "read-model backlog"],
      materialized: ["Materialized", "canonical outcomes"],
      projected: ["Projected", "read-model work"],
    });
    setMetric("accepted", metrics.streamMessages);
    setMetric("completed", metrics.workerCompleted);
    setMetric("gap", metrics.workerLag);
    setMetric("worker-lag", metrics.projectorLag);
    setMetric("materialized", metrics.materialized);
    setMetric("projected", metrics.projected);
  }
  renderProfileAlerts(snapshot.profile || state.config?.profile || {});
  renderContainers(snapshot.containers || {});
  const active = state.selectedRunId || "";
  const reportCount = selectedRun?.evidence?.length || 0;
  ids.activeRunLabel.textContent = active
    ? reportCount > 0
      ? `selected ${active} · ${reportCount} reports`
      : `selected ${active} · waiting for first report`
    : "no run selected";
  if (selectedMetrics) {
    state.trend = buildRunTrend(selectedRun);
    renderTrend("run");
    renderThroughputTrend();
    renderLatencyTrend();
  } else {
    const live = liveRuntimeMetrics(snapshot, metrics);
    state.trend.push({
      at: Date.now(),
      accepted: live.commands,
      completed: live.workerCompleted,
      materialized: live.materialized,
      projected: live.projected,
    });
    state.trend = state.trend.slice(-90);
    renderTrend("platform");
    renderEmptyChart(ids.throughputTrend, "throughput appears after report evidence");
    renderEmptyChart(ids.latencyTrend, "latency appears after report evidence");
  }
}

function renderRuns(runs) {
  ids.runList.innerHTML = "";
  for (const run of sortRuns(runs, state.runSort).slice(0, 12)) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = `run-row ${run.runId === state.selectedRunId ? "active" : ""}`;
    row.innerHTML = `
      <strong>${escapeHtml(run.runId)}</strong>
      <span class="muted">${escapeHtml(run.kind)} · ${escapeHtml(run.status)}${run.profile ? ` · ${escapeHtml(run.profile)}` : ""}</span>
      <span class="kv">
        <span>exit ${run.exitCode ?? ""}</span>
        <span>${fmt((run.evidence || []).length)} reports</span>
        <span>${escapeHtml(run.startedAt || "")}</span>
      </span>
    `;
    row.addEventListener("click", () => selectRun(run.runId));
    ids.runList.append(row);
  }
}

function renderEvidence(run) {
  ids.evidence.innerHTML = "";
  if (!run) {
    ids.evidence.innerHTML = `<p class="muted">No run selected.</p>`;
    return;
  }
  const rows = sortEvidenceRows(run.evidence || []);
  if (rows.length === 0) {
    const artifactCount = run.artifacts?.length || 0;
    ids.evidence.innerHTML = `
      <div class="empty-state">
        <strong>${escapeHtml(run.runId)}</strong>
        <span>${artifactCount > 0 ? `${artifactCount} artifacts observed; waiting for the first report JSON.` : "No report evidence yet."}</span>
      </div>
    `;
    return;
  }
  const latest = runDashboardMetrics(run);
  if (latest) {
    const summary = document.createElement("div");
    summary.className = "evidence-summary";
    summary.innerHTML = `
      <div>
        <span class="field-label">Latest Report</span>
        <strong>${escapeHtml(latest.name)}</strong>
      </div>
      <div>
        <span class="field-label">Profile</span>
        <strong>${escapeHtml(latest.runProfile || "unknown")}</strong>
      </div>
      <div>
        <span class="field-label">Accepted</span>
        <strong>${fmt(latest.accepted)} / ${fmt(latest.attempted)}</strong>
      </div>
      <div>
        <span class="field-label">System Failures</span>
        <strong>${fmt(latest.systemFailures)}</strong>
      </div>
    `;
    ids.evidence.append(summary);
  }
  for (const row of rows) {
    const ev = row.evidence || {};
    const rates = ev.rates || {};
    const gaps = ev.gaps || {};
    const quality = row.quality || {};
    const traces = row.traceChecks || {};
    const el = document.createElement("div");
    el.className = "evidence-row";
    el.innerHTML = `
      <div class="evidence-head">
        <strong>${escapeHtml(row.name)}</strong>
        <span>${escapeHtml(row.modifiedAt || "")}</span>
      </div>
      <div class="stat-grid">
        <span><b>${fmt(ev.attempted)}</b><small>attempted</small></span>
        <span><b>${fmt(ev.accepted)}</b><small>accepted</small></span>
        <span><b>${fmt(quality.totalFailures)}</b><small>rejects</small></span>
        <span><b>${fmt(quality.systemFailureCount)}</b><small>system fail</small></span>
        <span><b>${fmt(rates.acceptedPerSecond)}</b><small>accepted/s</small></span>
        <span><b>${fmt(ev.directAcked)}</b><small>direct acked</small></span>
        <span><b>${fmt(ev.materialized)}</b><small>materialized</small></span>
        <span><b>${fmt(gaps.acceptedToMaterialized)}</b><small>mat gap</small></span>
      </div>
      <div class="kv">
        <span>direct ack gap ${fmt(gaps.acceptedToDirectAcked)}</span>
        <span>profile ${escapeHtml(row.stressRunMetadata?.runProfile || "unknown")}</span>
        <span>trace ${fmt(traces.pass)} / ${fmt(traces.checked)}</span>
        <span>projected ${fmt(ev.projected)}</span>
        <span>proj gap ${fmt(gaps.materializedToProjected)}</span>
        <span>p95 ${fmt(ev.p95LatencyMs)}ms</span>
        <span>p99 ${fmt(ev.p99LatencyMs)}ms</span>
      </div>
    `;
    ids.evidence.append(el);
  }
}

function renderProfileAlerts(profile) {
  if (!ids.profileAlerts) return;
  const warnings = profile.warnings || [];
  const expected = profile.expectedRoles || {};
  const observed = profile.observedRoles || {};
  const summary = [
    `expected workers: ${expected.workers || "unknown"}`,
    `materializers: ${expected.materializers || "unknown"}`,
    `projectors: ${expected.projectors || "unknown"}`,
    `observed ${fmt(observed.workers)} / ${fmt(observed.materializers)} / ${fmt(observed.projectors)}`,
  ];
  ids.profileAlerts.hidden = false;
  ids.profileAlerts.className = `profile-alerts ${warnings.length ? "bad-row" : ""}`;
  ids.profileAlerts.innerHTML = `
    <strong>${escapeHtml(profile.name || state.config?.profile?.name || "unknown profile")}</strong>
    <span>${escapeHtml(summary.join(" · "))}</span>
    ${warnings.map((warning) => `<span class="profile-warning">${escapeHtml(warning)}</span>`).join("")}
  `;
}

function renderContainers(containers) {
  if (!ids.containerStatus) return;
  const rows = [
    ...(containers.workers || []).map((row) => ({
      role: "worker",
      name: row.name,
      status: row.enabled ? "enabled" : row.ok ? "idle" : "offline",
      work: row.completed,
      lag: row.streamLag,
      failed: row.failed + row.ackFailed,
      url: row.url,
    })),
    ...(containers.materializers || []).map((row) => ({
      role: "materializer",
      name: row.name,
      status: row.enabled ? "enabled" : row.ok ? "idle" : "offline",
      work: row.materialized,
      lag: row.lag,
      failed: row.failed + row.ackFailed,
      url: row.url,
    })),
    ...(containers.projectors || []).map((row) => ({
      role: "projector",
      name: row.name,
      status: row.running ? "running" : row.ok ? "idle" : "offline",
      work: row.projected,
      lag: row.lag,
      failed: row.failed,
      url: row.url,
    })),
  ];
  if (rows.length === 0) {
    ids.containerStatus.innerHTML = `<p class="muted">No container endpoints configured.</p>`;
    return;
  }
  ids.containerStatus.innerHTML = rows
    .map(
      (row) => `
        <div class="container-row ${row.status === "offline" ? "bad-row" : ""}">
          <div>
            <span class="field-label">${escapeHtml(row.role)}</span>
            <strong>${escapeHtml(row.name)}</strong>
            <small>${escapeHtml(row.url || "")}</small>
          </div>
          <span class="container-state">${escapeHtml(row.status)}</span>
          <span><b>${fmt(row.work)}</b><small>work</small></span>
          <span><b>${fmt(row.lag)}</b><small>lag</small></span>
          <span><b>${fmt(row.failed)}</b><small>fail</small></span>
        </div>
      `,
    )
    .join("");
}

function selectRun(runId) {
  state.selectedRunId = runId;
  state.userSelectedRun = true;
  state.selectedRun = null;
  state.trend = [];
  ids.logRun.textContent = runId;
  state.events = [];
  ids.log.textContent = "";
  openRunEventStream(runId);
  refreshAll();
}

function openRunEventStream(runId) {
  if (state.source) state.source.close();
  if (!state.connected) return;
  state.source = new EventSource(`/api/runs/${encodeURIComponent(runId)}/events`);
  state.source.onmessage = (event) => {
    const row = JSON.parse(event.data);
    state.events.push(row);
    state.events = state.events.slice(-300);
    ids.log.textContent = state.events.map((item) => `${item.at} ${item.stream}: ${item.message}`).join("\n");
    ids.log.scrollTop = ids.log.scrollHeight;
  };
  state.source.onerror = () => {
    if (state.connected) ids.probeError.textContent = "run log stream disconnected";
  };
}

function renderTrend(mode) {
  const series =
    mode === "run"
      ? [
          ["attempted", "var(--amber)", "attempted"],
          ["accepted", "var(--green)", "accepted"],
          ["failures", "var(--red)", "rejects"],
        ]
      : [
          ["accepted", "var(--blue)", "accepted"],
          ["completed", "var(--green)", "completed"],
          ["materialized", "var(--cyan)", "materialized"],
          ["projected", "var(--amber)", "projected"],
        ];
  renderLineChart(ids.trend, state.trend, series);
}

function renderThroughputTrend() {
  renderLineChart(ids.throughputTrend, state.trend, [
    ["attemptedPerSecond", "var(--blue)", "attempted/s"],
    ["acceptedPerSecond", "var(--green)", "accepted/s"],
  ]);
}

function renderLatencyTrend() {
  renderLineChart(ids.latencyTrend, state.trend, [
    ["p95LatencyMs", "var(--amber)", "p95 ms"],
    ["p99LatencyMs", "var(--red)", "p99 ms"],
  ]);
}

function renderLineChart(target, points, series) {
  const width = 900;
  const height = 220;
  const pad = 24;
  if (!target) return;
  if (!points.length) {
    renderEmptyChart(target, "waiting for data");
    return;
  }
  const maxValue = Math.max(1, ...points.flatMap((point) => series.map(([key]) => Number(point[key] || 0))));
  const paths = series
    .map(([key, color]) => {
      const pathPoints = points
        .map((point, index) => {
          const x = pad + (index / Math.max(1, points.length - 1)) * (width - pad * 2);
          const y = height - pad - (Number(point[key] || 0) / maxValue) * (height - pad * 2);
          return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(" ");
      return `<polyline points="${pathPoints}" fill="none" stroke="${color}" stroke-width="3" vector-effect="non-scaling-stroke" />`;
    })
    .join("");
  const legend = series
    .map(([, color, label], index) => {
      const x = pad + index * 120;
      return `
        <line x1="${x}" y1="${height - 10}" x2="${x + 18}" y2="${height - 10}" stroke="${color}" stroke-width="3" />
        <text x="${x + 24}" y="${height - 6}" fill="#c7d1d5" font-size="12">${escapeHtml(label)}</text>
      `;
    })
    .join("");
  target.innerHTML = `
    <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#323a41" />
    <line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#323a41" />
    ${paths}
    <text x="${pad}" y="18" fill="#9ba8ad" font-size="12">max ${fmt(maxValue)}</text>
    ${legend}
  `;
}

function renderEmptyChart(target, message) {
  if (!target) return;
  target.innerHTML = `
    <line x1="24" y1="196" x2="876" y2="196" stroke="#323a41" />
    <line x1="24" y1="24" x2="24" y2="196" stroke="#323a41" />
    <text x="450" y="112" text-anchor="middle" fill="#9ba8ad" font-size="14">${escapeHtml(message)}</text>
  `;
}

function setMetric(name, value) {
  document.querySelector(`#metric-${name}`).textContent = typeof value === "string" ? value : fmt(value);
}

function setMetricLabels(labels) {
  for (const [name, [label, hint]] of Object.entries(labels)) {
    const labelEl = document.querySelector(`#label-${name}`);
    const hintEl = document.querySelector(`#hint-${name}`);
    if (labelEl) labelEl.textContent = label;
    if (hintEl) hintEl.textContent = hint;
  }
}

function liveRuntimeMetrics(snapshot, metrics) {
  return {
    commands: Number(metrics.streamMessages || hotPathPhaseCount(snapshot, "api.mutation.total") || 0),
    workerCompleted: Number(metrics.workerCompleted || 0),
    workerLag: Number(metrics.workerLag || 0),
    materialized: Number(metrics.materialized || 0),
    projected: Number(metrics.projected || 0),
    projectorLag: Number(metrics.projectorLag || 0),
  };
}

function hotPathPhaseCount(snapshot, phaseName) {
  return Number(snapshot.probes?.hotPath?.json?.metrics?.phases?.[phaseName]?.count || 0);
}

function autoSelectRun(runs, options = {}) {
  const newest = runs[0]?.runId || "";
  if (!options.preferNewest && state.userSelectedRun && state.selectedRunId && runs.some((run) => run.runId === state.selectedRunId)) {
    return;
  }
  if (!newest) {
    state.selectedRunId = "";
    state.selectedRun = null;
    if (state.source) state.source.close();
    state.source = null;
    return;
  }
  if (state.selectedRunId === newest) return;
  state.selectedRunId = newest;
  state.selectedRun = null;
  state.events = [];
  state.trend = [];
  ids.log.textContent = "";
  ids.logRun.textContent = newest;
  openRunEventStream(newest);
}

function runDashboardMetrics(run) {
  const row = sortEvidenceRows(run?.evidence || []).at(-1);
  if (!row) return null;
  const ev = row.evidence || {};
  const quality = row.quality || {};
  const trace = row.traceChecks || {};
  return {
    name: row.name,
    attempted: Number(ev.attempted || quality.totalRequests || 0),
    accepted: Number(ev.accepted || quality.totalSuccess || 0),
    directAcked: Number(ev.directAcked || 0),
    materialized: Number(ev.materialized || 0),
    projected: Number(ev.projected || 0),
    runProfile: row.stressRunMetadata?.runProfile || "",
    acceptedToDirectAcked: Number(ev.gaps?.acceptedToDirectAcked || 0),
    acceptedToMaterialized: Number(ev.gaps?.acceptedToMaterialized || 0),
    successRatePct: Number(quality.endToEndSuccessRatePct || 0),
    p95LatencyMs: Number(ev.p95LatencyMs || row.latencyMs?.p95 || 0),
    p99LatencyMs: Number(ev.p99LatencyMs || row.latencyMs?.p99 || 0),
    systemFailures: Number(quality.systemFailureCount || 0),
    traceChecked: Number(trace.checked || 0),
    tracePass: Number(trace.pass || 0),
  };
}

function isMaterializerReport(metrics) {
  return Number(metrics?.directAcked || 0) > 0 || Number(metrics?.materialized || 0) > 0;
}

function buildRunTrend(run) {
  return sortEvidenceRows(run?.evidence || []).map((row) => {
    const ev = row.evidence || {};
    const quality = row.quality || {};
    return {
      attempted: Number(ev.attempted || quality.totalRequests || 0),
      accepted: Number(ev.accepted || quality.totalSuccess || 0),
      failures: Number(quality.totalFailures || Math.max(0, Number(ev.attempted || 0) - Number(ev.accepted || 0))),
      attemptedPerSecond: Number(ev.rates?.attemptedPerSecond || 0),
      acceptedPerSecond: Number(ev.rates?.acceptedPerSecond || 0),
      p95LatencyMs: Number(ev.p95LatencyMs || row.latencyMs?.p95 || 0),
      p99LatencyMs: Number(ev.p99LatencyMs || row.latencyMs?.p99 || 0),
    };
  });
}

function sortEvidenceRows(rows) {
  return rows.slice().sort((left, right) => {
    const byModifiedAt = String(left.modifiedAt || "").localeCompare(String(right.modifiedAt || ""));
    if (byModifiedAt !== 0) return byModifiedAt;
    return String(left.name || "").localeCompare(String(right.name || ""));
  });
}

function sortRuns(runs, mode) {
  return runs.slice().sort((left, right) => {
    switch (mode) {
      case "oldest":
        return runSortTime(left) - runSortTime(right);
      case "reports":
        return (right.evidence?.length || 0) - (left.evidence?.length || 0) || runSortTime(right) - runSortTime(left);
      case "failed":
        return failureRank(left) - failureRank(right) || runSortTime(right) - runSortTime(left);
      case "kind":
        return String(left.kind || "").localeCompare(String(right.kind || "")) || runSortTime(right) - runSortTime(left);
      case "name":
        return String(left.runId || "").localeCompare(String(right.runId || ""));
      case "newest":
      default:
        return runSortTime(right) - runSortTime(left);
    }
  });
}

function runSortTime(run) {
  return Date.parse(run.completedAt || run.startedAt || "") || 0;
}

function failureRank(run) {
  if (run.status === "failed" || Number(run.exitCode) > 0) return 0;
  if (run.status === "running") return 1;
  return 2;
}

async function getJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.json();
}

function fmt(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "0";
  if (Math.abs(number) >= 1000) return number.toLocaleString(undefined, { maximumFractionDigits: 0 });
  if (!Number.isInteger(number)) return number.toFixed(1);
  return String(number);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function firstProbeError(snapshot) {
  for (const probe of Object.values(snapshot.probes || {})) {
    if (probe?.error) return probe.error;
  }
  return "";
}
