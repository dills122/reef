#!/usr/bin/env node
// Mutation check for the Calcify O3a finance ledger (docs/work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md, S1-A1).
// Applies one source mutation at a time under --root, runs the finance tests, and reports which mutants the tests kill.
// Test-support tooling only: one Gradle run per mutant (reuses a Gradle daemon; run it on a scratch worktree), local, never required in CI.
//   node scripts/dev/calcify-financial/research/finance-mutation-check.mjs --root <scratch checkout> [--min-kill 0.95] [--only <substring>] [--list]
import { readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join, resolve } from 'node:path';

const MAIN = 'services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/';
const LEDGER = MAIN + 'FinanceLedger.kt';
const MODEL = MAIN + 'FinanceModel.kt';
const STORE = MAIN + 'FinanceStore.kt';
const PROC = MAIN + 'FinanceProcessor.kt';
const CUT = MAIN + 'FinanceRestoreCut.kt';
const MAP = MAIN + 'FinanceCaptureMapping.kt';

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
  ['restore skips invariants', LEDGER, '        checkInvariants(state)\n', ''],
  ['restore skips head binding', LEDGER, 'require(state.chain == trustedHead.checksum && state.sequence == trustedHead.sequence)', 'require(true)'],
  ['restore skips checksum', LEDGER, 'require(Canon.sha(stateBytes(state, includeChain = true)) == checkpoint.checksum)', 'require(true)'],
  ['restore skips state commitment', LEDGER, 'require(stateDigest(state) == trustedHead.stateDigest)', 'require(true)'],
  ['restore skips head verification', LEDGER, 'require(trustedHead.checksum == Canon.sha(', 'require(true || trustedHead.checksum == Canon.sha('],
  ['restore accepts any state at genesis', LEDGER, 'require(state == genesis())', 'require(true)'],
  ['duplicate compare ignores price', LEDGER, 'if (old == captured)', 'if (old.quantity == captured.quantity)'],
  ['insufficient attempt not recorded', LEDGER, 'd.attempts[attemptKey] = status; return', 'return'],
  ['no asset check on fund', LEDGER, '(i.asset != cash && i.asset != security) || ', ''],
  ['no fund authority check', LEDGER, 'if (i.authority != Authority.OPERATOR) return Eval(Disposition.UNAUTHORIZED)\n        if (i.account', 'if (i.account'],
  ['no settle authority check', LEDGER, 'Eval {\n        if (i.authority != Authority.OPERATOR) return Eval(Disposition.UNAUTHORIZED)\n        val obligation', 'Eval {\n        val obligation'],
  ['no ALREADY_SETTLED', LEDGER, 'if (obligation.status == ObligationStatus.SETTLED) return Eval(Disposition.ALREADY_SETTLED)', ''],
  ['no per-asset balance check', LEDGER, 'check(sum.signum() == 0) { "legs do not balance for asset" }', ''],
  ['settle leg amount off by one', LEDGER, 'Leg(execution.seller, cash, obligation.cashResidual)', 'Leg(execution.seller, cash, obligation.cashResidual - 1)'],
  ['digest omits authority', MODEL, 'str(i.authority.name); ', ''],
  ['digest omits attempt', MODEL, 'execution(i.execution); int(i.attempt)', 'execution(i.execution)'],
  ['settle digest omits authority', MODEL, 'str("SETTLE"); str(i.authority.name); ', 'str("SETTLE"); '],
  ['unchecked add', LEDGER, 'Math.addExact(updated[key] ?: 0L, leg.amount)', '(updated[key] ?: 0L) + leg.amount'],
  ['unchecked multiply', LEDGER, 'Math.multiplyExact(i.quantity, i.priceNanos)', 'i.quantity * i.priceNanos'],
  ['no negative-position check', LEDGER, 'check(key.first == config.openingAccount || value >= 0)', 'check(true)'],
  ['settled flag not set', LEDGER, 'if (result.disposition == Disposition.SETTLED) d.obligations[i.execution] = Obligation(ObligationStatus.SETTLED, 0, 0)', ''],
  ['no unknown-account check on capture', LEDGER, 'if (i.buyer !in config.accounts || i.seller !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)', ''],
  ['no unknown-account check on fund', LEDGER, 'if (i.account !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)', ''],
  ['decision checksum omits legs', LEDGER, 'int(legs.size); legs.forEach { leg(it) }', 'int(0)'],
  ['state bytes omit actions', LEDGER, 'int(actions.size); actions.forEach', 'int(0); actions.take(0).forEach'],
  ['state bytes omit attempts', LEDGER, 'int(attempts.size); attempts.forEach', 'int(0); attempts.take(0).forEach'],
  ['state bytes omit chain', LEDGER, 'if (includeChain) str(s.chain)', ''],
  ['state digest includes chain', LEDGER, 'stateBytes(s, includeChain = false)', 'stateBytes(s, includeChain = true)'],
  ['decision omits state digest', LEDGER, 'long(sequence); str(stateDigest)', 'long(sequence)'],
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
  ['no position-asset invariant', LEDGER, 'check(key.second == cash || key.second == security)', 'check(true)'],
  ['no never-recorded action invariant', LEDGER, 'check(state.actions.values.all { it.disposition in recordedDispositions })', 'check(true)'],
  ['no captures==executions invariant', LEDGER, 'check(state.actions.values.count { it.disposition == Disposition.CAPTURED } == state.executions.size)', 'check(true)'],
  ['no settle-actions==attempts invariant', LEDGER, 'check(state.actions.values.count { it.disposition in settleDispositions } == state.attempts.size)', 'check(true)'],
  ['no attempt-execution invariant', LEDGER, 'check(attempt.second >= 1 && attempt.first in state.executions)', 'check(true)'],
  ['no attempt-disposition invariant', LEDGER, 'check(disposition in settleDispositions)', 'check(true)'],
  ['no settled-attempt invariant', LEDGER, 'check(settledAttempts == if (obligation.status == ObligationStatus.SETTLED) 1 else 0)', 'check(true)'],
  ['verifyChain allows unrecorded dispositions', LEDGER, 'd.disposition !in recordedDispositions || ', ''],
  ['verifyChain allows repeated actions', LEDGER, '!actions.add(d.action)', 'false'],
  ['restore skips history verification', LEDGER, 'require(verifyChain(history))', 'require(true)'],
  ['no opening-account sign invariant', LEDGER, 'check(key.first != config.openingAccount || value <= 0)', 'check(true)'],
  ['UTF-16 order instead of UTF-8 bytes', MODEL, 'java.util.Arrays.compareUnsigned(strictUtf8(x), strictUtf8(y))', 'x.compareTo(y)'],
  ['fund overflow recorded', LEDGER, 'if (it.disposition == Disposition.FUNDED) it.copy(recorded = true) else it', 'it.copy(recorded = true)'],
  // Added with S2-A2 (store, processor, restore cut, mapping).
  ['S2A2 entry chain omits offset', STORE, 'long(position); str(role); int(partition); long(offset); int(ordinal)', 'long(position); str(role); int(partition); int(ordinal)'],
  ['S2A2 history link check removed', STORE, 'require(e.position == i + 1L && e.prevEntryChain == prev) { "history position/link" }', ''],
  ['S2A2 history chain recompute removed', STORE, 'require(e.entryChain == entryChain(prev, e.position, e.sourceRole, e.sourcePartition, e.sourceOffset, e.memberOrdinal, e.kind, e.inputDigest, e.outcome, e.decisionChecksum)) { "history entry chain" }', ''],
  ['S2A2 history head vs meta removed', STORE, 'require(prev == meta.entryChainHead) { "history head differs from meta" }', ''],
  ['S2A2 recorded entries vs sequence removed', STORE, 'require(recorded == meta.sequence) { "recorded history entries differ from the ledger sequence" }', ''],
  ['S2A2 last recorded vs head removed', STORE, 'require(lastChecksum == (head?.checksum ?: "")) { "last recorded entry is not the head decision" }', ''],
  ['S2A2 history length vs meta removed', STORE, 'require(entries.size.toLong() == meta.historyPosition && entries.size <= MAX_HISTORY_ENTRIES) { "history length differs from meta" }', ''],
  ['S2A2 binding check removed', STORE, 'require(meta.bindingDigest == bindingDigest) { "finance store belongs to a different binding" }', ''],
  ['S2A2 restore skips committed-head check', STORE, 'ledger.restoreAt(ledger.checkpoint(state), head)', ''],
  // 'head presence' in FinanceStore.verifyAndRestore is deliberately redundant with restoreAt (genesis-vs-head); removing it is an equivalent mutant, so it is not listed.
  ['S2A2 orphan entities accepted', STORE, 'require(kv.scan("").isEmpty()) { "finance store has entities but no meta" }', ''],
  ['S2A2 attempts not persisted', STORE, 'for ((k, v) in after.attempts) if (before.attempts[k] != v) out[attemptKey(k.first, k.second)] = encodeAttempt(v)', ''],
  ['S2A2 positions not persisted', STORE, 'for ((k, v) in after.positions) if (before.positions[k] != v) out[positionKey(k.first, k.second)] = encodePosition(v)', ''],
  ['S2A2 history bound off by one', STORE, 'const val MAX_HISTORY_ENTRIES = 312', 'const val MAX_HISTORY_ENTRIES = 313'],
  ['S2A2 trailing entity bytes accepted', STORE, 'require(input.available() == 0) { "trailing entity bytes" }', ''],
  ['S2A2 entity version unchecked', STORE, 'require(str() == version) { "unknown entity version" }', 'str()'],
  ['S2A2 history bound removed', PROC, 'if (position + 1 > FinanceStore.MAX_HISTORY_ENTRIES) throw FinanceHistoryBudgetExceeded()', ''],
  ['S2A2 capture body accepted on control', PROC, 'if (input is FinanceInput.Capture) return bad', ''],
  ['S2A2 binding digest unchecked', PROC, 'envelope.finiteBindingDigest != config.captureBinding.digest ||', ''],
  ['S2A2 content digest unchecked', PROC, ' ||\n            envelope.contentDigest != FiniteLifecycleContract.digest(envelope)', ''],
  ['S2A2 schema unchecked', PROC, 'envelope.schema != FiniteLifecycleContract.CAPTURE_SCHEMA || ', ''],
  ['S2A2 head not persisted', PROC, 'headDecision?.let { writes[FinanceStore.HEAD] = FinanceWire.toProto(it).toByteArray() }', ''],
  ['S2A2 cached state not advanced', PROC, 'state = staged; historyPosition = position; entryHead = head', 'historyPosition = position; entryHead = head'],
  ['S2A2 cached history not advanced', PROC, 'state = staged; historyPosition = position; entryHead = head', 'state = staged; entryHead = head'],
  ['S2A2 NO_INPUT entry dropped', PROC, 'if (mapped.isEmpty()) return listOf(Item.None)', 'if (mapped.isEmpty()) return emptyList()'],
  ['S2A2 offset-ahead refusal removed', CUT, 'if (committed > last + 1 || committed > end) throw', 'if (false) throw'],
  ['S2A2 history-ahead refusal removed', CUT, 'if (committed < last + 1) throw', 'if (false) throw'],
  ['S2A2 reset committed offset not refused', CUT, 'if (committed == null || committed == 0L) throw', 'if (committed == null) throw'],
  ['S2A2 no-history offset rule removed', CUT, 'if ((committed ?: 0L) != 0L) throw', 'if (false) throw'],
  ['S2A2 cross-check removed', CUT, 'if (recorded.size != log.size || recorded.zip(log).any { (e, d) -> e.decisionChecksum != d.checksum || e.inputDigest != d.inputDigest })', 'if (false)'],
  ['S2A2 cross-check ignores input digest', CUT, ' || e.inputDigest != d.inputDigest', ''],
  ['S2A2 store-lost refusal removed', CUT, 'if (log.isNotEmpty()) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_STORE_LOST', 'if (false) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_STORE_LOST'],
  ['S2A2 decision chain unverified', CUT, 'if (!ledger.verifyChain(log))', 'if (false)'],
  ['S2A2 store/decision head check removed', CUT, 'if (state.sequence != log.size.toLong() || state.chain != (head?.checksum ?: ledger.genesisChain))', 'if (false)'],
  ['S2A2 replay members mapped', MAP, 'if (member.disposition != LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION) continue', ''],
  ['S2A2 mapping skips account check', MAP, 'require(buyFact.accountId == buyer.account && buyFact.participantId == buyer.participant &&\n            sellFact.accountId == seller.account && sellFact.participantId == seller.participant) { "dependency accounts differ from the bound parties" }', ''],
  ['S2A2 mapping skips currency check', MAP, 'require(fact.price.currency == binding.currency) { "trade currency differs from the bound quote currency" }', ''],
  ['S2A2 mapping skips run/instrument check', MAP, 'require(trade.runId == binding.run && fact.instrumentId == binding.instrument) { "trade outside the bound run/instrument" }', ''],
  ['S2A2 mapping skips dependency order match', MAP, 'require(buy.orderId == fact.buyOrderId && sell.orderId == fact.sellOrderId && buy.orderId != sell.orderId) { "dependencies do not match the trade orders" }', ''],
  ['S2A2 mapping allows leading zeros', MAP, ' && (text.length == 1 || text[0] != \'0\')', ''],
  ['S2A2 mapping allows zero amount', MAP, 'require(value > 0) { "amount must be positive" }', ''],
  ['S2A2 mapping ordinal counts all members', MAP, 'val at = ordinal++', 'val at = envelope.membersList.indexOf(member)'],
];

function arg(name, fallback) { const i = process.argv.indexOf(name); return i > -1 ? process.argv[i + 1] : fallback; }

function classify(r) {
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  if (r.status === 0) return 'SURVIVED';
  if (/^e: /m.test(out) || /compilation error/i.test(out)) return 'COMPILE_ERROR';
  return /FAILED/.test(out) ? 'KILLED' : 'OTHER_FAILURE';
}

function main() {
  const rootArg = arg('--root', '');
  if (!rootArg && !process.argv.includes('--list')) { console.error('--root <scratch checkout> is required: mutants edit files in place'); process.exit(2); }
  const root = resolve(rootArg || process.cwd());
  const only = arg('--only', '');
  const minKill = Number(arg('--min-kill', '0.95'));
  const list = MUTANTS.filter(([name]) => name.includes(only));
  if (process.argv.includes('--list')) { for (const [name] of list) console.log(name); return; }
  const results = [];
  const gradle = (cwd) => spawnSync('./services/platform-runtime/gradlew',
    ['-p', 'services/platform-runtime', 'test', '--tests', 'com.reef.platform.calcify.finance.*', '-x', 'jacocoTestReport', '-q'],
    { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const baseline = gradle(root);
  if (baseline.status !== 0) { console.error('baseline (unmutated) run is not green; refusing to score mutants'); console.error(`${baseline.stdout ?? ''}${baseline.stderr ?? ''}`.slice(-2000)); process.exit(2); }
  console.log('baseline green');
  for (const [name, file, from, to] of list) {
    const path = join(root, file);
    const original = readFileSync(path, 'utf8');
    if (!original.includes(from)) { results.push({ name, status: 'NOT_APPLICABLE' }); console.log('NOT_APPLICABLE', name); continue; }
    try {
      writeFileSync(path, original.replace(from, to));
      const r = gradle(root);
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
  process.exitCode = rate >= minKill && !results.some((r) => r.status === 'NOT_APPLICABLE' || r.status === 'OTHER_FAILURE' || r.status === 'COMPILE_ERROR') ? 0 : 1;
}

if (import.meta.url === `file://${process.argv[1]}`) main();
