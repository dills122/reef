import { posix } from "node:path";

export const isScriptTest = (path) => /(?:^|\/)[^/]+\.(?:test|spec)\.[cm]?[jt]s$/.test(path);
const isEvidence = (path) => /^(?:docs\/(?:evidence|research|work)\/|\.planning\/)/.test(path);
const isControl = (path) => /(?:^|\/)(?:Makefile|package\.json)$/.test(path) || /^\.github\/workflows\/.*\.ya?ml$/.test(path);
const isSource = (path) => /\.(?:[cm]?[jt]s|sh|kt|go)$/.test(path);

// Literal, importer-relative paths only: a same-basename file elsewhere cannot
// keep a script alive. This is static wiring evidence, not execution coverage.
function references(path, text, scripts) {
  const refs = new Set();
  for (const match of text.matchAll(/(?:scripts\/|\.\.?\/)[\w./-]+\.(?:mjs|js|ts|sh)\b/g)) {
    const token = match[0];
    const target = (token.startsWith("scripts/") || token.startsWith("./scripts/")) ? token.replace(/^\.\//, "") : posix.normalize(posix.join(posix.dirname(path), token));
    if (target !== path && scripts.has(target)) refs.add(target);
  }
  return refs;
}

function commandText(path, text) {
  if (path.endsWith("package.json")) {
    try { return Object.values(JSON.parse(text).scripts ?? {}).join("\n"); } catch { return ""; }
  }
  // Exclude comments and prose. Markdown must show an invocation, not merely
  // list the filename in a catalog or link to a historic result.
  const lines = text.split("\n").filter((line) => !/^\s*(?:#|\/\/|\*)/.test(line));
  if (path.endsWith(".md")) return lines.filter((line) => /(?:^|[\s`])(?:node|bun|bash|sh|\.\/scripts\/)\s*[^\n]*/.test(line)).join("\n");
  return lines.join("\n");
}

export function classifyScriptSurface(scriptFiles, repoText) {
  const scripts = new Set(scriptFiles);
  const rows = new Map(scriptFiles.map((path) => [path, { path, category: "unreferenced", references: [] }]));
  const edges = new Map();
  const roots = new Set();
  const record = (target, path, kind) => rows.get(target).references.push({ path, kind });
  for (const [path, text] of repoText) {
    const kind = isEvidence(path) ? "evidence" : isScriptTest(path) ? "test" : isControl(path) ? "runnable" : path.endsWith(".md") ? "operational-doc" : isSource(path) ? "imported" : "evidence";
    const refs = references(path, ["runnable", "operational-doc", "imported"].includes(kind) ? commandText(path, text) : text, scripts);
    for (const target of refs) {
      record(target, path, kind);
      if (kind === "runnable" || kind === "operational-doc" || (kind === "imported" && !scripts.has(path))) roots.add(target);
    }
    if (kind === "imported") edges.set(path, refs);
  }
  const live = new Set();
  const pending = [...roots];
  while (pending.length) {
    const path = pending.pop();
    if (live.has(path) || isScriptTest(path)) continue;
    live.add(path);
    pending.push(...(edges.get(path) ?? []));
  }
  for (const [path, row] of rows) {
    if (isScriptTest(path)) {
      row.category = row.references.some((ref) => ref.kind === "runnable") ? "test" : "unwired-test";
    } else if (live.has(path)) {
      row.category = row.references.some((ref) => ref.kind === "runnable") ? "runnable" : row.references.some((ref) => ref.kind === "operational-doc") ? "operational-doc" : "imported";
    } else if (row.references.some((ref) => ref.kind === "test")) {
      row.category = "test-only";
    } else if (row.references.length) {
      row.category = row.references.every((ref) => ref.kind === "evidence") ? "evidence-only" : "unreachable";
    }
  }
  return [...rows.values()];
}
