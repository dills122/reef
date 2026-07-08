const state = {
  selectedRunId: "",
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
};

document.querySelector("#refresh").addEventListener("click", () => refreshAll());
document.querySelector("#local-form").addEventListener("submit", (event) => {
  event.preventDefault();
  startRun("/api/runs/local-stress", formData(event.currentTarget));
});
document.querySelector("#remote-form").addEventListener("submit", (event) => {
  event.preventDefault();
  startRun("/api/runs/remote-simulation", formData(event.currentTarget));
});

init();
setInterval(refreshAll, 2000);

async function init() {
  await loadConfig();
  await refreshAll();
}

async function loadConfig() {
  const config = await getJson("/api/config");
  fillForm("#local-form", config.localStressDefaults || {});
  fillForm("#remote-form", config.remoteDefaults || {});
}

async function refreshAll() {
  const [snapshot, runs] = await Promise.all([
    getJson(`/api/snapshot?runId=${encodeURIComponent(state.selectedRunId)}`),
    getJson("/api/runs"),
  ]);
  renderSnapshot(snapshot);
  renderRuns(runs.runs || []);
  const selected = state.selectedRunId ? await getJson(`/api/runs/${encodeURIComponent(state.selectedRunId)}`) : null;
  renderEvidence(selected);
}

async function startRun(path, body) {
  setBusy(true);
  try {
    const run = await postJson(path, body);
    selectRun(run.runId);
  } finally {
    setBusy(false);
    await refreshAll();
  }
}

function renderSnapshot(snapshot) {
  const runtime = snapshot.probes?.runtime;
  const internalBlocked = Object.values(snapshot.probes || {}).some((probe) => probe?.status === 403);
  ids.runtimeStatus.textContent = runtime?.ok
    ? internalBlocked
      ? "internal diagnostics blocked"
      : "runtime online"
    : "runtime offline";
  ids.runtimeStatus.className = `pill ${runtime?.ok && !internalBlocked ? "ok" : "bad"}`;
  ids.sampleTime.textContent = snapshot.sampledAt || "not sampled";
  const metrics = snapshot.metrics || {};
  setMetric("accepted", metrics.accepted);
  setMetric("completed", metrics.completed);
  setMetric("gap", metrics.accountingGap);
  setMetric("worker-lag", metrics.workerLag);
  setMetric("materialized", metrics.materialized);
  setMetric("projected", metrics.projected);
  const active = snapshot.activeRuns?.[0] || state.selectedRunId || "";
  ids.activeRunLabel.textContent = active ? `selected ${active}` : "no active run";
  state.trend.push({
    at: Date.now(),
    accepted: Number(metrics.accepted || 0),
    completed: Number(metrics.completed || 0),
    materialized: Number(metrics.materialized || 0),
    projected: Number(metrics.projected || 0),
  });
  state.trend = state.trend.slice(-90);
  renderTrend();
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
  const rows = run.evidence || [];
  if (rows.length === 0) {
    ids.evidence.innerHTML = `<p class="muted">No report evidence yet.</p>`;
    return;
  }
  for (const row of rows) {
    const ev = row.evidence || {};
    const rates = ev.rates || {};
    const gaps = ev.gaps || {};
    const el = document.createElement("div");
    el.className = "evidence-row";
    el.innerHTML = `
      <strong>${escapeHtml(row.name)}</strong>
      <span class="kv">
        <span>attempted ${fmt(ev.attempted)}</span>
        <span>accepted ${fmt(ev.accepted)}</span>
        <span>materialized ${fmt(ev.materialized)}</span>
        <span>projected ${fmt(ev.projected)}</span>
      </span>
      <span class="kv">
        <span>accepted/s ${fmt(rates.acceptedPerSecond)}</span>
        <span>mat gap ${fmt(gaps.acceptedToMaterialized)}</span>
        <span>proj gap ${fmt(gaps.materializedToProjected)}</span>
        <span>p95 ${fmt(ev.p95LatencyMs)}ms</span>
        <span>p99 ${fmt(ev.p99LatencyMs)}ms</span>
      </span>
    `;
    ids.evidence.append(el);
  }
}

function selectRun(runId) {
  state.selectedRunId = runId;
  ids.logRun.textContent = runId;
  state.events = [];
  ids.log.textContent = "";
  if (state.source) state.source.close();
  state.source = new EventSource(`/api/runs/${encodeURIComponent(runId)}/events`);
  state.source.onmessage = (event) => {
    const row = JSON.parse(event.data);
    state.events.push(row);
    state.events = state.events.slice(-300);
    ids.log.textContent = state.events.map((item) => `${item.at} ${item.stream}: ${item.message}`).join("\n");
    ids.log.scrollTop = ids.log.scrollHeight;
  };
  refreshAll();
}

function renderTrend() {
  const series = [
    ["accepted", "var(--blue)"],
    ["completed", "var(--green)"],
    ["materialized", "var(--cyan)"],
    ["projected", "var(--amber)"],
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
  ids.trend.innerHTML = `
    <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#323a41" />
    <line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#323a41" />
    ${paths}
    <text x="${pad}" y="18" fill="#9ba8ad" font-size="12">max ${maxValue}</text>
  `;
}

function setMetric(name, value) {
  document.querySelector(`#metric-${name}`).textContent = fmt(value);
}

function formData(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function fillForm(selector, values) {
  const form = document.querySelector(selector);
  for (const [key, value] of Object.entries(values)) {
    const input = form.elements.namedItem(key);
    if (input) input.value = value;
  }
}

function setBusy(value) {
  document.querySelectorAll("button").forEach((button) => {
    button.disabled = value;
  });
}

async function getJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.json();
}

async function postJson(path, body) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
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
