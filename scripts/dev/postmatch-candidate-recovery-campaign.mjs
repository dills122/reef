#!/usr/bin/env node
/** Closed-cohort writer crash, V2 snapshot recovery, and external-lease takeover proof. */
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { composeArgs } from "./lib/compose-utils.mjs";

const WRITER = "platform-postmatch-settlement-0";
const SAFE_STREAM = /^[A-Za-z0-9_-]+$/;

async function command(program, args, timeoutMs = 600_000) {
  const child = spawn(program, args, { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  const limit = 256_000;
  child.stdout.on("data", (chunk) => { stdout = (stdout + chunk).slice(-limit); });
  child.stderr.on("data", (chunk) => { stderr = (stderr + chunk).slice(-limit); });
  const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
  try {
    const code = await new Promise((accept, reject) => {
      child.on("error", reject);
      child.on("close", accept);
    });
    if (code !== 0) throw new Error(`${program} ${args[0] ?? ""} exited ${code}: ${stderr.trim() || stdout.trim()}`);
    return stdout.trim();
  } finally {
    clearTimeout(timer);
  }
}

async function sql(service, query) {
  const output = await command("docker", composeArgs(["exec", "-T", service, "psql",
    "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-U", "reef", "-d", "reef",
    "-c", query]));
  if (!output || output.includes("\n")) throw new Error(`${service} returned ambiguous recovery state`);
  return JSON.parse(output);
}

async function state(stream) {
  const s = `'${stream}'`;
  const [finality, journal] = await Promise.all([
    sql("finality-postgres", `SELECT row_to_json(f) FROM (
      SELECT acknowledged_batch_sequence,acknowledged_batch_digest,journal_incarnation_id,
        control_sequence,control_digest,source_binding_digest,lease_epoch,
        snapshot_batch_sequence,snapshot_batch_digest,snapshot_state_version,snapshot_state_digest
      FROM finality.settlement_finality_anchors WHERE event_stream=${s}) f`),
    sql("settlement-postgres", `SELECT row_to_json(j) FROM (
      SELECT next_batch_sequence,last_batch_digest,owner_epoch,incarnation_id,
        last_control_sequence,last_control_digest
      FROM settlement.settlement_journal_heads WHERE event_stream=${s}) j`),
  ]);
  if (!finality || !journal) throw new Error("journal or external finality head missing");
  return { finality, journal };
}

async function container() {
  const expectedProject = process.env.RECOVERY_EXPECTED_COMPOSE_PROJECT;
  if (!expectedProject || expectedProject !== process.env.COMPOSE_PROJECT_NAME) {
    throw new Error("recovery requires an explicit matching Compose project identity");
  }
  const id = await command("docker", composeArgs(["--profile", "postmatch-workers",
    "ps", "-a", "-q", WRITER]), 30_000);
  if (!/^[a-f0-9]{12,64}$/.test(id)) throw new Error("candidate writer container missing or ambiguous");
  const raw = await command("docker", ["inspect", "--format",
    "{{json .State}}|{{index .Config.Labels \"com.docker.compose.project\"}}|{{index .Config.Labels \"com.docker.compose.service\"}}", id], 30_000);
  const [stateJson, project, service] = raw.split("|");
  if (project !== expectedProject || service !== WRITER) {
    throw new Error("writer container does not belong to the expected Compose project and service");
  }
  const state = JSON.parse(stateJson);
  return { id, running: state.Running, startedAt: state.StartedAt };
}

async function cohort(stream, path) {
  await command(process.execPath, [resolve("scripts/dev/settlement-candidate-cohort-check.mjs"),
    stream, "auto", path], 1_800_000);
  const report = JSON.parse(readFileSync(path, "utf8"));
  if (report.status !== "pass") throw new Error(`closed cohort failed: ${path}`);
  return report;
}

export function assessRecovery(before, after) {
  const failures = [];
  const b = before.state;
  const a = after.state;
  if (!b || !a || before.cohort?.status !== "pass" || after.cohort?.status !== "pass") {
    return ["both closed-cohort checks must pass"];
  }
  const f = b.finality;
  const j = b.journal;
  const n = a.finality;
  const k = a.journal;
  for (const [label, sample] of [["before", before], ["after", after]]) {
    if (sample.cohort.journal.next_batch_sequence !== sample.state.journal.next_batch_sequence ||
        sample.cohort.journal.last_batch_digest !== sample.state.journal.last_batch_digest ||
        sample.cohort.finality.acknowledged_batch_sequence !==
          sample.state.finality.acknowledged_batch_sequence ||
        sample.cohort.finality.acknowledged_batch_digest !==
          sample.state.finality.acknowledged_batch_digest) {
      failures.push(`${label} cohort differs from recovery authority snapshot`);
    }
  }
  if (Number(f.snapshot_state_version) !== 2 || !f.snapshot_state_digest ||
      BigInt(f.snapshot_batch_sequence ?? 0) < 1n ||
      BigInt(f.acknowledged_batch_sequence) - BigInt(f.snapshot_batch_sequence ?? 0) > 256n) {
    failures.push("pre-crash writer lacks externally anchored V2 snapshot with bounded replay tail");
  }
  if (BigInt(j.owner_epoch) !== BigInt(f.lease_epoch) ||
      BigInt(k.owner_epoch) !== BigInt(n.lease_epoch) ||
      BigInt(n.lease_epoch) <= BigInt(f.lease_epoch)) {
    failures.push("external lease did not fence old owner and advance journal owner epoch");
  }
  for (const key of ["acknowledged_batch_sequence", "acknowledged_batch_digest",
    "journal_incarnation_id", "control_sequence", "control_digest", "source_binding_digest",
    "snapshot_batch_sequence", "snapshot_batch_digest", "snapshot_state_version",
    "snapshot_state_digest"]) {
    if (f[key] !== n[key]) failures.push(`closed external finality ${key} changed across takeover`);
  }
  for (const key of ["next_batch_sequence", "last_batch_digest", "incarnation_id",
    "last_control_sequence", "last_control_digest"]) {
    if (j[key] !== k[key]) failures.push(`closed journal ${key} changed across takeover`);
  }
  for (const key of ["sourceGeneration", "sourceHeads", "financial", "market", "coverage",
    "firstTrades", "marketTrades", "tradeProjectionParity", "balanceParity", "duplicates"]) {
    if (JSON.stringify(before.cohort[key]) !== JSON.stringify(after.cohort[key])) {
      failures.push(`closed cohort ${key} changed across takeover`);
    }
  }
  if (before.container?.id !== after.container?.id ||
      before.container?.startedAt === after.container?.startedAt || !after.container?.running) {
    failures.push("candidate writer process did not restart after SIGKILL");
  }
  return failures;
}

async function main() {
  const [stream, reportPath] = process.argv.slice(2);
  if (!SAFE_STREAM.test(stream ?? "") || !reportPath) {
    throw new Error("usage: postmatch-candidate-recovery-campaign.mjs EVENT_STREAM REPORT_JSON");
  }
  const path = resolve(reportPath);
  const report = { scope: "closed-cohort-writer-snapshot-takeover", eventStream: stream,
    status: "fail", failures: [], limitations: [
      "SIGKILL occurs after the cohort is closed, not during an in-flight append",
      "writer V2 snapshot hydration is tested; PostgreSQL database backup restore is not",
      "business-value parity is limited to the existing closed-cohort checker",
    ] };
  try {
    report.before = { cohort: await cohort(stream, `${path}.before-cohort.json`),
      state: await state(stream), container: await container() };
    if (!report.before.container.running) throw new Error("candidate writer is not running");
    // Read a stable closed head before injecting the fault; fail rather than misattribute new work.
    await new Promise((accept) => setTimeout(accept, 2_000));
    const stable = await state(stream);
    if (JSON.stringify(stable) !== JSON.stringify(report.before.state)) {
      throw new Error("journal or external finality moved before closed-cohort crash");
    }
    await command("docker", ["kill", "--signal=KILL", report.before.container.id], 30_000);
    const deadline = Date.now() + 600_000;
    for (;;) {
      const current = await container();
      if (!current.running) await command("docker", ["start", current.id], 30_000);
      const after = await state(stream);
      if (current.running && current.startedAt !== report.before.container.startedAt &&
          BigInt(after.finality.lease_epoch) > BigInt(report.before.state.finality.lease_epoch)) break;
      if (Date.now() >= deadline) throw new Error("candidate writer did not recover and acquire a new lease");
      await new Promise((accept) => setTimeout(accept, 2_000));
    }
    report.after = { cohort: await cohort(stream, `${path}.after-cohort.json`),
      state: await state(stream), container: await container() };
    report.failures = assessRecovery(report.before, report.after);
    report.status = report.failures.length ? "fail" : "pass";
  } catch (error) {
    report.failures.push(error.message);
  }
  writeFileSync(path, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({ status: report.status, scope: report.scope,
    failures: report.failures, report: path }));
  if (report.status !== "pass") process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main();
}
