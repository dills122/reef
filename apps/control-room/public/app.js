const state = {
  selectedRunId: "",
  selectedRun: null,
  events: [],
  trend: [],
  source: null,
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
  runtimeUrl: document.querySelector("#runtime-url"),
  lastProbe: document.querySelector("#last-probe"),
  probeError: document.querySelector("#probe-error"),
  refreshRuns: document.querySelector("#refresh-runs"),
  connect: document.querySelector("#connect"),
  pause: document.querySelector("#pause"),
};

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
  ids.runtimeUrl.textContent = config.runtimeUrl || "unknown";
}

async function refreshAll(options = {}) {
  if (!state.connected && !options.allowPaused) return;
  const runsPayload = await getJson("/api/runs");
  const runs = runsPayload.runs || [];
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
  } else if (state.selectedRunId) {
    const live = liveRuntimeMetrics(snapshot, metrics);
    setMetricLabels({
      accepted: ["Runtime Commands", "live platform"],
      completed: ["Submit Commands", "live platform"],
      gap: ["Modify Commands", "live platform"],
      "worker-lag": ["Cancel Commands", "live platform"],
      materialized: ["DB Active", "pool usage"],
      projected: ["Projected", "read-model work"],
    });
    setMetric("accepted", live.commands);
    setMetric("completed", live.submits);
    setMetric("gap", live.modifies);
    setMetric("worker-lag", live.cancels);
    setMetric("materialized", live.dbActive);
    setMetric("projected", live.projected);
  } else {
    setMetricLabels({
      accepted: ["Accepted", "diagnostics"],
      completed: ["Completed", "diagnostics"],
      gap: ["Accounting Gap", "accepted minus terminal"],
      "worker-lag": ["Worker Lag", "stream lag"],
      materialized: ["Materialized", "canonical outcomes"],
      projected: ["Projected", "read-model work"],
    });
    setMetric("accepted", metrics.accepted);
    setMetric("completed", metrics.completed);
    setMetric("gap", metrics.accountingGap);
    setMetric("worker-lag", metrics.workerLag);
    setMetric("materialized", metrics.materialized);
    setMetric("projected", metrics.projected);
  }
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
  } else {
    const live = liveRuntimeMetrics(snapshot, metrics);
    state.trend.push({
      at: Date.now(),
      accepted: live.commands,
      completed: live.submits,
      materialized: live.dbActive,
      projected: live.projected,
    });
    state.trend = state.trend.slice(-90);
    renderTrend("platform");
  }
}

function renderRuns(runs) {
  ids.runList.innerHTML = "";
  for (const run of runs.slice(0, 12)) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = `run-row ${run.runId === state.selectedRunId ? "active" : ""}`;
    row.innerHTML = `
      <strong>${escapeHtml(run.runId)}</strong>
      <span class="muted">${escapeHtml(run.kind)} · ${escapeHtml(run.status)}</span>
      <span class="kv">
        <span>exit ${run.exitCode ?? ""}</span>
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
        <span class="field-label">Accepted</span>
        <strong>${fmt(latest.accepted)} / ${fmt(latest.attempted)}</strong>
      </div>
      <div>
        <span class="field-label">Trace Checks</span>
        <strong>${fmt(latest.tracePass)} / ${fmt(latest.traceChecked)}</strong>
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
        <span><b>${fmt(ev.p95LatencyMs)}ms</b><small>p95</small></span>
        <span><b>${fmt(ev.p99LatencyMs)}ms</b><small>p99</small></span>
        <span><b>${fmt(traces.pass)} / ${fmt(traces.checked)}</b><small>trace</small></span>
      </div>
      <div class="kv">
        <span>materialized ${fmt(ev.materialized)}</span>
        <span>projected ${fmt(ev.projected)}</span>
        <span>mat gap ${fmt(gaps.acceptedToMaterialized)}</span>
        <span>proj gap ${fmt(gaps.materializedToProjected)}</span>
      </div>
    `;
    ids.evidence.append(el);
  }
}

function selectRun(runId) {
  state.selectedRunId = runId;
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
  const width = 900;
  const height = 220;
  const pad = 24;
  const maxValue = Math.max(1, ...state.trend.flatMap((point) => series.map(([key]) => Number(point[key] || 0))));
  const paths = series
    .map(([key, color]) => {
      const points = state.trend
        .map((point, index) => {
          const x = pad + (index / Math.max(1, state.trend.length - 1)) * (width - pad * 2);
          const y = height - pad - (Number(point[key] || 0) / maxValue) * (height - pad * 2);
          return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(" ");
      return `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="3" vector-effect="non-scaling-stroke" />`;
    })
    .join("");
  const legend = series
    .map(([, color, label], index) => {
      const x = pad + index * 105;
      return `
        <line x1="${x}" y1="${height - 10}" x2="${x + 18}" y2="${height - 10}" stroke="${color}" stroke-width="3" />
        <text x="${x + 24}" y="${height - 6}" fill="#c7d1d5" font-size="12">${label}</text>
      `;
    })
    .join("");
  ids.trend.innerHTML = `
    <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#323a41" />
    <line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#323a41" />
    ${paths}
    <text x="${pad}" y="18" fill="#9ba8ad" font-size="12">max ${maxValue}</text>
    ${legend}
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
    commands: hotPathPhaseCount(snapshot, "api.mutation.total"),
    submits: hotPathPhaseCount(snapshot, "api.orderService.submitOrder"),
    modifies: hotPathPhaseCount(snapshot, "runtime.engine.modify"),
    cancels: hotPathPhaseCount(snapshot, "runtime.engine.cancel"),
    dbActive: Number(metrics.dbActive || 0),
    projected: Number(metrics.projected || 0),
  };
}

function hotPathPhaseCount(snapshot, phaseName) {
  return Number(snapshot.probes?.hotPath?.json?.metrics?.phases?.[phaseName]?.count || 0);
}

function autoSelectRun(runs, options = {}) {
  const newest = runs[0]?.runId || "";
  if (!options.preferNewest && state.selectedRunId && runs.some((run) => run.runId === state.selectedRunId)) return;
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
    successRatePct: Number(quality.endToEndSuccessRatePct || 0),
    p95LatencyMs: Number(ev.p95LatencyMs || row.latencyMs?.p95 || 0),
    p99LatencyMs: Number(ev.p99LatencyMs || row.latencyMs?.p99 || 0),
    systemFailures: Number(quality.systemFailureCount || 0),
    traceChecked: Number(trace.checked || 0),
    tracePass: Number(trace.pass || 0),
  };
}

function buildRunTrend(run) {
  return sortEvidenceRows(run?.evidence || []).map((row) => {
    const ev = row.evidence || {};
    const quality = row.quality || {};
    return {
      attempted: Number(ev.attempted || quality.totalRequests || 0),
      accepted: Number(ev.accepted || quality.totalSuccess || 0),
      failures: Number(quality.totalFailures || Math.max(0, Number(ev.attempted || 0) - Number(ev.accepted || 0))),
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
