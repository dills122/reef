package com.reef.platform.calcify.finance

import java.math.BigInteger

/**
 * Configuration of the capacity profile's ledger (docs/work/CALCIFY_CAPACITY_PROFILE.md). Same fields as the finite [FinanceConfig] but without its pinned
 * ceilings (16 accounts, 128 captures, 256 obligations, 160 attempts, 8 controls): the budgets are the profile's own, per slice. The instantiation label of the
 * state commitment is bound into the digest, so a chain built under one commitment can never verify as another's.
 */
internal data class CapacityConfig(
    val accounts: Set<AccountId>, val cashAsset: AssetId, val securityAsset: AssetId, val openingAccount: AccountId,
    val maxObligations: Int, val maxCaptures: Int, val maxSettleAttempts: Int, val maxControls: Int,
) {
    init {
        require(accounts.size >= 2) { "at least two accounts" }
        require(openingAccount !in accounts) { "opening account must be distinct" }
        require(cashAsset != securityAsset) { "distinct assets" }
        require(maxObligations >= 1 && maxCaptures >= 1 && maxSettleAttempts >= 1 && maxControls >= 1) { "budgets are positive" }
        require(maxCaptures <= maxObligations) { "captures cannot exceed obligations" }
    }

    fun digest(): String = Canon.sha(Canon.bytes {
        str("calcify-finance-config-v2-capacity"); str(StateCommitment.LABEL)
        val sorted = accounts.map { it.value }.sortedWith(Canon.utf8Order<String>({ it })); int(sorted.size); sorted.forEach { str(it) }
        str(cashAsset.value); str(securityAsset.value); str(openingAccount.value)
        int(maxObligations); int(maxCaptures); int(maxSettleAttempts); int(maxControls)
    })
}

/** What one input did: its disposition, and the decision when it was recorded (refusals are not recorded and change nothing). */
internal data class CapacityOutcome(val disposition: Disposition, val decision: Decision?)

/**
 * The capacity profile's ledger (design stage S3): the O3a decision semantics for capture, fund and settle with O(touched) work per decision. State lives in
 * maps; every written entity updates the incremental [StateTrie], so the commitment `C(S')` each recorded decision carries costs the touched paths, not the state.
 * The decision order, dispositions, legs and budgets are those of [FinanceLedger] (the differential test runs both on random inputs). One writer; not thread-safe.
 *
 * Local invariants replace the whole-state check (design 5.5): the legs of a decision balance per asset, no position other than the opening account's goes
 * negative, the opening account never goes positive, and the action count equals the sequence. The full reference check is [snapshot] with
 * [FinanceLedger.verifyChain]/[StateCommitment.reference] and is run in tests and at restore, not per decision.
 *
 * This commits synchronously (the root after every decision). The lagged option of design 5.6 is a separate, gated stage.
 */
internal class CapacityLedger(val config: CapacityConfig) {
    val genesisChain: String = Canon.sha(Canon.bytes { str("calcify-finance-genesis-v1"); str(config.digest()) })

    private val positions = HashMap<Pair<AccountId, AssetId>, Long>()
    private val executions = HashMap<ExecutionKey, Captured>()
    private val obligations = HashMap<ExecutionKey, Obligation>()
    private val attempts = HashMap<Pair<ExecutionKey, Int>, Disposition>()
    private val actions = HashMap<ActionKey, ActionRecord>()
    private val trie = StateTrie()
    var sequence = 0L; private set
    var controls = 0; private set
    var chain: String = genesisChain; private set

    val entityCount: Int get() = trie.size

    /** The abstract state as an immutable [FinanceState] (O(n); tests, restore checks and the reference commitment). */
    fun snapshot(): FinanceState = FinanceState(HashMap(positions), HashMap(executions), HashMap(obligations), HashMap(attempts), HashMap(actions), sequence, chain, controls)

    fun position(account: AccountId, asset: AssetId): Long = positions[account to asset] ?: 0L

    // ---- entity writes: the maps hold the state, the trie holds its commitment ----

    private fun commit(e: StateCommitment.Entity) { val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)) }
    private fun commitPosition(k: Pair<AccountId, AssetId>, v: Long) = commit(StateCommitment.Entity("P", Canon.bytes { str(k.first.value); str(k.second.value) }, Canon.bytes { long(v) }))
    private fun commitExecution(k: ExecutionKey, c: Captured) = commit(StateCommitment.Entity("X", Canon.bytes { execution(k) }, Canon.bytes { str(c.buyer.value); str(c.seller.value); long(c.quantity); long(c.priceNanos) }))
    private fun commitObligation(k: ExecutionKey, o: Obligation) = commit(StateCommitment.Entity("O", Canon.bytes { execution(k) }, Canon.bytes { str(o.status.name); long(o.cashResidual); long(o.shareResidual) }))
    private fun commitAttempt(k: ExecutionKey, n: Int, d: Disposition) = commit(StateCommitment.Entity("A", Canon.bytes { execution(k); int(n) }, Canon.bytes { str(d.name) }))
    private fun commitAction(k: ActionKey, r: ActionRecord) = commit(StateCommitment.Entity("K", Canon.bytes { str(k.namespace); str(k.domain); str(k.actionId) }, Canon.bytes { str(r.digest); str(r.disposition.name) }))

    private fun pos(a: AccountId, s: AssetId) = positions[a to s] ?: 0L
    private fun refused(d: Disposition) = CapacityOutcome(d, null)

    private class Plan(val updated: Map<Pair<AccountId, AssetId>, Long>, val legs: List<Leg>)

    /** The post-leg positions without writing anything; null on overflow. Zero-amount legs are dropped, as in O3a. */
    private fun planLegs(legs: List<Leg>): Plan? {
        val nonZero = legs.filter { it.amount != 0L }
        val updated = LinkedHashMap<Pair<AccountId, AssetId>, Long>()
        try { for (l in nonZero) { val k = l.account to l.asset; updated[k] = Math.addExact(updated[k] ?: pos(l.account, l.asset), l.amount) } } catch (_: ArithmeticException) { return null }
        for ((k, v) in updated) {
            check(k.first == config.openingAccount || v >= 0) { "negative non-opening position" }
            check(k.first != config.openingAccount || v <= 0) { "opening account position is positive" }
        }
        return Plan(updated, nonZero)
    }

    private fun apply(plan: Plan): List<Leg> {
        for ((k, v) in plan.updated) { positions[k] = v; commitPosition(k, v) }
        return plan.legs
    }

    fun decide(input: FinanceInput): CapacityOutcome {
        val digest = input.digest()
        actions[input.action]?.let { prior -> return refused(if (prior.digest == digest) Disposition.PRIOR_RESULT else Disposition.ACTION_CONFLICT) }
        return when (input) {
            is FinanceInput.Capture -> capture(input, digest)
            is FinanceInput.Fund -> fund(input, digest)
            is FinanceInput.Settle -> settle(input, digest)
        }
    }

    /** Records the decision: the action, the sequence, the commitment of the post-state, the chain link. Everything before it has already been checked, so a decision either happens whole or not at all. */
    private fun record(input: FinanceInput, digest: String, disposition: Disposition, legs: List<Leg>): CapacityOutcome {
        sequence++
        val rec = ActionRecord(digest, disposition); actions[input.action] = rec; commitAction(input.action, rec)
        val stateDigest = StateCommitment.commitment(sequence, controls, trie.root())
        val priorChain = chain
        val bytes = Canon.bytes {
            str("calcify-finance-decision-v1"); str(priorChain); long(sequence); str(stateDigest)
            str(input.action.namespace); str(input.action.domain); str(input.action.actionId); str(digest); str(disposition.name); int(legs.size); legs.forEach { leg(it) }
        }
        chain = Canon.sha(bytes)
        // Local invariants in place of the whole-state check (design 5.5).
        var cash = BigInteger.ZERO; var security = BigInteger.ZERO
        for (l in legs) if (l.asset == config.cashAsset) cash += BigInteger.valueOf(l.amount) else security += BigInteger.valueOf(l.amount)
        check(cash.signum() == 0 && security.signum() == 0) { "legs do not balance" }
        check(sequence == actions.size.toLong()) { "action count differs from sequence" }
        return CapacityOutcome(disposition, Decision(sequence, input.action, digest, disposition, legs, priorChain, stateDigest, chain))
    }

    private fun capture(i: FinanceInput.Capture, digest: String): CapacityOutcome {
        if (i.buyer !in config.accounts || i.seller !in config.accounts) return refused(Disposition.UNKNOWN_ACCOUNT)
        if (i.buyer == i.seller || i.quantity <= 0 || i.priceNanos < 0) return refused(Disposition.INVALID_INPUT)
        val amount = try { Math.multiplyExact(i.quantity, i.priceNanos) } catch (_: ArithmeticException) { return refused(Disposition.AMOUNT_OVERFLOW) }
        val captured = Captured(i.buyer, i.seller, i.quantity, i.priceNanos)
        executions[i.execution]?.let { old -> return refused(if (old == captured) Disposition.EXECUTION_ALREADY_CAPTURED else Disposition.EXECUTION_CONFLICT) }
        if (executions.size >= config.maxCaptures || obligations.size >= config.maxObligations) return refused(Disposition.BUDGET_EXCEEDED)
        val obligation = Obligation(ObligationStatus.PENDING, amount, i.quantity)
        executions[i.execution] = captured; obligations[i.execution] = obligation
        commitExecution(i.execution, captured); commitObligation(i.execution, obligation)
        return record(i, digest, Disposition.CAPTURED, emptyList())
    }

    private fun fund(i: FinanceInput.Fund, digest: String): CapacityOutcome {
        if (i.authority != Authority.OPERATOR) return refused(Disposition.UNAUTHORIZED)
        if (i.account !in config.accounts) return refused(Disposition.UNKNOWN_ACCOUNT)
        if ((i.asset != config.cashAsset && i.asset != config.securityAsset) || i.amount <= 0) return refused(Disposition.INVALID_INPUT)
        val plan = planLegs(listOf(Leg(config.openingAccount, i.asset, -i.amount), Leg(i.account, i.asset, i.amount))) ?: return refused(Disposition.BALANCE_OVERFLOW)
        if (controls >= config.maxControls) return refused(Disposition.BUDGET_EXCEEDED)
        val applied = apply(plan)
        controls++
        return record(i, digest, Disposition.FUNDED, applied)
    }

    private fun settle(i: FinanceInput.Settle, digest: String): CapacityOutcome {
        if (i.authority != Authority.OPERATOR) return refused(Disposition.UNAUTHORIZED)
        val obligation = obligations[i.execution] ?: return refused(Disposition.UNKNOWN_EXECUTION)
        if (i.attempt < 1) return refused(Disposition.INVALID_INPUT)
        if (obligation.status == ObligationStatus.SETTLED) return refused(Disposition.ALREADY_SETTLED)
        val attemptKey = i.execution to i.attempt
        if (attemptKey in attempts) return refused(Disposition.PRIOR_ATTEMPT)
        val execution = executions.getValue(i.execution)
        val shortfall = when {
            pos(execution.buyer, config.cashAsset) < obligation.cashResidual -> Disposition.INSUFFICIENT_CASH
            pos(execution.seller, config.securityAsset) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES
            else -> null
        }
        if (attempts.size >= config.maxSettleAttempts) return refused(Disposition.BUDGET_EXCEEDED)
        if (shortfall != null) { attempts[attemptKey] = shortfall; commitAttempt(i.execution, i.attempt, shortfall); return record(i, digest, shortfall, emptyList()) }
        val cash = config.cashAsset; val security = config.securityAsset
        val plan = planLegs(listOf(
            Leg(execution.buyer, cash, -obligation.cashResidual), Leg(execution.seller, cash, obligation.cashResidual),
            Leg(execution.seller, security, -obligation.shareResidual), Leg(execution.buyer, security, obligation.shareResidual)))
        if (plan == null) { attempts[attemptKey] = Disposition.BALANCE_OVERFLOW; commitAttempt(i.execution, i.attempt, Disposition.BALANCE_OVERFLOW); return record(i, digest, Disposition.BALANCE_OVERFLOW, emptyList()) }
        val applied = apply(plan)
        attempts[attemptKey] = Disposition.SETTLED; commitAttempt(i.execution, i.attempt, Disposition.SETTLED)
        val settled = Obligation(ObligationStatus.SETTLED, 0, 0); obligations[i.execution] = settled; commitObligation(i.execution, settled)
        return record(i, digest, Disposition.SETTLED, applied)
    }
}
