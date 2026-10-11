#!/usr/bin/env node
// Mutation check for the Calcify O3a finance ledger (docs/work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md, S1-A1).
// Applies one source mutation at a time under --root, runs the finance tests, and reports which mutants the tests kill.
// Test-support tooling only: one Gradle run per mutant (reuses a Gradle daemon; run it on a scratch worktree), local, never required in CI.
//   node scripts/dev/calcify-financial/research/finance-mutation-check.mjs [--root <checkout>] [--min-kill 0.95] [--only <substring>] [--list]
import { readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join, resolve } from 'node:path';

const MAIN = 'services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/';
const LEDGER = MAIN + 'FinanceLedger.kt';
const MODEL = MAIN + 'FinanceModel.kt';

// [name, file, from, to]. 'from' must occur in the file; the first occurrence is replaced.
export const MUTANTS = [
  ['no PRIOR_ATTEMPT', LEDGER, 'if (attemptKey in d.attempts) return Eval(Disposition.PRIOR_ATTEMPT)', ''],
  ['no attempt<1 guard', LEDGER, 'if (i.attempt < 1) return Eval(Disposition.INVALID_INPUT)', ''],
  ['no buyer==seller guard', LEDGER, 'i.buyer == i.seller || ', ''],
  ['no negative price guard', LEDGER, ' || i.priceNanos < 0', ''],
  ['quantity <0 instead of <=0', LEDGER, 'i.quantity <= 0', 'i.quantity < 0'],
  ['settle attempt budget off by one', LEDGER, 'state.attempts.size >= config.maxSettleAttempts', 'state.attempts.size > config.maxSettleAttempts'],
  ['control budget off by one', LEDGER, 'state.controls >= config.maxControls', 'state.controls > config.maxControls'],
  ['controls increment omitted', LEDGER, 'state.controls + if (input is FinanceInput.Fund) 1 else 0', 'state.controls'],
  ['refusals are recorded and counted', LEDGER, 'if (!result.recorded) return Outcome(state, result.disposition, null)', ''],
  ['no capture budget', LEDGER, 'state.executions.size >= config.maxCaptures || state.obligations.size >= config.maxObligations', 'false'],
  ['conflict becomes prior', LEDGER, 'else Outcome(state, Disposition.ACTION_CONFLICT, null)', 'else Outcome(state, Disposition.PRIOR_RESULT, null, prior.disposition)'],
  ['cash <= instead of <', LEDGER, '< obligation.cashResidual', '<= obligation.cashResidual'],
  ['no share check', LEDGER, 'd.position(execution.seller, security) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES', 'false -> Disposition.INSUFFICIENT_SHARES'],
  ['no priorChain verify', LEDGER, 'd.priorChain != prior', 'false'],
  ['no sequence verify', LEDGER, 'd.sequence != sequence + 1 || ', ''],
  ['restore skips invariants', LEDGER, '        checkInvariants(checkpoint.state)\n', ''],
  ['restore skips head binding', LEDGER, 'require(checkpoint.state.chain == expectedChainHead)', 'require(true)'],
  ['restore skips checksum', LEDGER, 'require(Canon.sha(stateBytes(checkpoint.state)) == checkpoint.checksum)', 'require(true)'],
  ['duplicate compare ignores price', LEDGER, 'if (old == captured)', 'if (old.quantity == captured.quantity)'],
  ['insufficient attempt not recorded', LEDGER, 'd.attempts[attemptKey] = status; return', 'return'],
  ['no asset check on fund', LEDGER, '(i.asset != cash && i.asset != security) || ', ''],
  ['no authority check', LEDGER, 'if (i.authority != Authority.OPERATOR) return Eval(Disposition.UNAUTHORIZED)', ''],
  ['no ALREADY_SETTLED', LEDGER, 'if (obligation.status == ObligationStatus.SETTLED) return Eval(Disposition.ALREADY_SETTLED)', ''],
  ['no per-asset balance check', LEDGER, 'check(sum.signum() == 0) { "legs do not balance for asset" }', ''],
  ['settle leg amount off by one', LEDGER, 'Leg(execution.seller, cash, obligation.cashResidual)', 'Leg(execution.seller, cash, obligation.cashResidual - 1)'],
  ['digest omits authority', MODEL, 'str(i.authority.name); ', ''],
  ['digest omits attempt', MODEL, 'str("SETTLE"); execution(i.execution); int(i.attempt)', 'str("SETTLE"); execution(i.execution)'],
  ['unchecked add', LEDGER, 'Math.addExact(updated[key] ?: 0L, leg.amount)', '(updated[key] ?: 0L) + leg.amount'],
  ['unchecked multiply', LEDGER, 'Math.multiplyExact(i.quantity, i.priceNanos)', 'i.quantity * i.priceNanos'],
  ['no negative-position check', LEDGER, 'check(key.first == config.openingAccount || value >= 0)', 'check(true)'],
  ['settled flag not set', LEDGER, 'if (result.disposition == Disposition.SETTLED) d.obligations[i.execution] = Obligation(ObligationStatus.SETTLED, 0, 0)', ''],
  ['no unknown-account check on capture', LEDGER, 'if (i.buyer !in config.accounts || i.seller !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)', ''],
  ['no unknown-account check on fund', LEDGER, 'if (i.account !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)', ''],
  ['decision checksum omits legs', LEDGER, 'int(legs.size); legs.forEach { leg(it) }', 'int(0)'],
  ['state bytes omit actions', LEDGER, 'int(actions.size); actions.forEach', 'int(0); actions.take(0).forEach'],
  ['state bytes omit attempts', LEDGER, 'int(attempts.size); attempts.forEach', 'int(0); attempts.take(0).forEach'],
  ['state bytes omit chain', LEDGER, 'long(s.sequence); str(s.chain)', 'long(s.sequence)'],
  ['canonical framing removed', MODEL, 'out.writeInt(b.size); out.write(b)', 'out.write(b)'],
  ['config allows shared opening account', MODEL, 'require(openingAccount !in accounts) { "opening account must be distinct" }', ''],
  ['config allows identical assets', MODEL, 'require(cashAsset != securityAsset) { "distinct assets" }', ''],
  // Added with the S1-A1 assertions.
  ['fund amount <0 instead of <=0', LEDGER, 'i.amount <= 0', 'i.amount < 0'],
  ['no verifyChain checksum check', LEDGER, 'if (d.checksum != Canon.sha(', 'if (false && d.checksum != Canon.sha('],
  ['no obligation-has-execution check', LEDGER, 'check(state.obligations.keys.all { it in state.executions })', 'check(true)'],
  ['no pending residual drift check', LEDGER, 'obligation.cashResidual == Math.multiplyExact(e.quantity, e.priceNanos) && obligation.shareResidual == e.quantity', 'true'],
  ['no sequence==actions check', LEDGER, 'check(state.sequence == state.actions.size.toLong())', 'check(true)'],
  ['no capture-budget invariant', LEDGER, 'check(state.executions.size <= config.maxCaptures && state.obligations.size <= config.maxObligations)', 'check(true)'],
  ['no settle-budget invariant', LEDGER, 'check(state.attempts.size <= config.maxSettleAttempts)', 'check(true)'],
  ['no control-budget invariant', LEDGER, 'check(state.controls <= config.maxControls && state.controls == state.actions.values.count { it.disposition == Disposition.FUNDED })', 'check(true)'],
  ['no record-budget invariant', LEDGER, 'check(state.sequence <= config.maxCaptures + config.maxSettleAttempts + config.maxControls)', 'check(true)'],
  ['state bytes omit controls', LEDGER, 'str(s.chain); int(s.controls)', 'str(s.chain)'],
  ['strict UTF-8 replaced by lossy', MODEL, 'onMalformedInput(CodingErrorAction.REPORT)', 'onMalformedInput(CodingErrorAction.REPLACE)'],
  ['control characters allowed in text', MODEL, ' || value.any { it.isISOControl() }', ''],
  ['zero-amount legs emitted', LEDGER, 'Eval(success, legs.filter { it.amount != 0L })', 'Eval(success, legs)'],
  ['zero-amount legs applied', LEDGER, 'for (leg in legs.filter { it.amount != 0L }) {', 'for (leg in legs) {'],
  ['genesis not bound to config', LEDGER, 'str("calcify-finance-genesis-v1"); str(config.digest())', 'str("calcify-finance-genesis-v1")'],
  ['decision accepts non-recorded disposition', MODEL, 'init { require(!disposition.neverRecorded) { "non-recorded disposition in decision" } }', ''],
  ['action record accepts non-recorded disposition', MODEL, 'init { require(!disposition.neverRecorded) { "non-recorded disposition in action record" } }', ''],
  ['fund overflow recorded', LEDGER, 'if (it.disposition == Disposition.FUNDED) it.copy(recorded = true) else it', 'it.copy(recorded = true)'],
];

function arg(name, fallback) { const i = process.argv.indexOf(name); return i > -1 ? process.argv[i + 1] : fallback; }

function classify(r) {
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  if (r.status === 0) return 'SURVIVED';
  if (/^e: /m.test(out) || /compilation error/i.test(out)) return 'COMPILE_ERROR';
  return /FAILED/.test(out) ? 'KILLED' : 'OTHER_FAILURE';
}

function main() {
  const root = resolve(arg('--root', process.cwd()));
  const only = arg('--only', '');
  const minKill = Number(arg('--min-kill', '0.95'));
  const list = MUTANTS.filter(([name]) => name.includes(only));
  if (process.argv.includes('--list')) { for (const [name] of list) console.log(name); return; }
  const results = [];
  for (const [name, file, from, to] of list) {
    const path = join(root, file);
    const original = readFileSync(path, 'utf8');
    if (!original.includes(from)) { results.push({ name, status: 'NOT_APPLICABLE' }); console.log('NOT_APPLICABLE', name); continue; }
    try {
      writeFileSync(path, original.replace(from, to));
      const r = spawnSync('./services/platform-runtime/gradlew',
        ['-p', 'services/platform-runtime', 'test', '--tests', 'com.reef.platform.calcify.finance.*', '-x', 'jacocoTestReport', '-q'],
        { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const status = classify(r);
      results.push({ name, status }); console.log(status.padEnd(14), name);
    } finally { writeFileSync(path, original); }
  }
  const valid = results.filter((r) => r.status === 'KILLED' || r.status === 'SURVIVED');
  const killed = valid.filter((r) => r.status === 'KILLED').length;
  const rate = valid.length ? killed / valid.length : 0;
  console.log(JSON.stringify({ mutants: results.length, valid: valid.length, killed, survived: valid.length - killed,
    killRate: Number(rate.toFixed(3)), minKill, survivors: valid.filter((r) => r.status === 'SURVIVED').map((r) => r.name),
    notApplicable: results.filter((r) => r.status === 'NOT_APPLICABLE').map((r) => r.name) }, null, 2));
  process.exitCode = rate >= minKill && !results.some((r) => r.status === 'NOT_APPLICABLE' || r.status === 'OTHER_FAILURE') ? 0 : 1;
}

if (import.meta.url === `file://${process.argv[1]}`) main();
