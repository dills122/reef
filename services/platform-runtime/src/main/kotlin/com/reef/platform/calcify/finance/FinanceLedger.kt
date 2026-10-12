package com.reef.platform.calcify.finance

import java.math.BigInteger

/**
 * Pure decision function over [FinanceState]. Every input is evaluated against an immutable state and either returns an
 * [Outcome] with a new state and a hash-chained [Decision], or leaves the state untouched (prior result, conflict, budget).
 */
internal class FinanceLedger(val config: FinanceConfig) {
    private val recordedDispositions = setOf(Disposition.CAPTURED, Disposition.FUNDED, Disposition.SETTLED, Disposition.INSUFFICIENT_CASH,
        Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)
    private val settleDispositions = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)
    private val cash get() = config.cashAsset
    private val security get() = config.securityAsset

    /** Chain start, bound to the configuration so histories from a different config never verify. */
    val genesisChain: String = Canon.sha(Canon.bytes { str("calcify-finance-genesis-v1"); str(config.digest()) })

    fun genesis(): FinanceState = FinanceState(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0, genesisChain)

    fun decide(state: FinanceState, input: FinanceInput): Outcome {
        val digest = input.digest()
        state.actions[input.action]?.let { prior ->
            return if (prior.digest == digest) Outcome(state, Disposition.PRIOR_RESULT, null, prior.disposition)
            else Outcome(state, Disposition.ACTION_CONFLICT, null)
        }
        val draft = Draft(state)
        val result = evaluate(draft, input)
        // Refusals and no-ops are neither recorded nor counted: junk cannot consume a budget class (review F2). By the same policy
        // they do not claim their action key: the same key may later be reused with different content (documented, review 2 P3).
        if (!result.recorded) return Outcome(state, result.disposition, null)
        val exceeded = when (input) {
            is FinanceInput.Capture -> state.executions.size >= config.maxCaptures || state.obligations.size >= config.maxObligations
            is FinanceInput.Fund -> state.controls >= config.maxControls
            is FinanceInput.Settle -> state.attempts.size >= config.maxSettleAttempts
        }
        if (exceeded) return Outcome(state, Disposition.BUDGET_EXCEEDED, null)
        val sequence = state.sequence + 1
        val unchained = FinanceState(
            draft.positions, draft.executions, draft.obligations, draft.attempts,
            state.actions + (input.action to ActionRecord(digest, result.disposition)), sequence, "",
            state.controls + if (input is FinanceInput.Fund) 1 else 0,
        )
        val stateDigest = stateDigest(unchained)
        val checksum = Canon.sha(decisionBytes(sequence, input.action, digest, result.disposition, result.legs, state.chain, stateDigest))
        val decision = Decision(sequence, input.action, digest, result.disposition, result.legs, state.chain, stateDigest, checksum)
        val next = unchained.copy(chain = checksum)
        checkInvariants(next)
        return Outcome(next, result.disposition, decision)
    }

    private class Draft(s: FinanceState) {
        var positions: Map<Pair<AccountId, AssetId>, Long> = s.positions
        val executions = s.executions.toMutableMap()
        val obligations = s.obligations.toMutableMap()
        val attempts = s.attempts.toMutableMap()
        fun position(account: AccountId, asset: AssetId): Long = positions[account to asset] ?: 0L
    }
    private data class Eval(val disposition: Disposition, val legs: List<Leg> = emptyList(), val recorded: Boolean = false)

    private fun evaluate(d: Draft, input: FinanceInput): Eval = when (input) {
        is FinanceInput.Capture -> capture(d, input)
        is FinanceInput.Fund -> fund(d, input)
        is FinanceInput.Settle -> settle(d, input)
    }

    private fun capture(d: Draft, i: FinanceInput.Capture): Eval {
        if (i.buyer !in config.accounts || i.seller !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)
        if (i.buyer == i.seller || i.quantity <= 0 || i.priceNanos < 0) return Eval(Disposition.INVALID_INPUT)
        val amount = try { Math.multiplyExact(i.quantity, i.priceNanos) } catch (_: ArithmeticException) { return Eval(Disposition.AMOUNT_OVERFLOW) }
        val captured = Captured(i.buyer, i.seller, i.quantity, i.priceNanos)
        d.executions[i.execution]?.let { old ->
            return Eval(if (old == captured) Disposition.EXECUTION_ALREADY_CAPTURED else Disposition.EXECUTION_CONFLICT)
        }
        d.executions[i.execution] = captured
        d.obligations[i.execution] = Obligation(ObligationStatus.PENDING, amount, i.quantity)
        return Eval(Disposition.CAPTURED, recorded = true)
    }

    private fun fund(d: Draft, i: FinanceInput.Fund): Eval {
        if (i.authority != Authority.OPERATOR) return Eval(Disposition.UNAUTHORIZED)
        if (i.account !in config.accounts) return Eval(Disposition.UNKNOWN_ACCOUNT)
        if ((i.asset != cash && i.asset != security) || i.amount <= 0) return Eval(Disposition.INVALID_INPUT)
        val legs = listOf(Leg(config.openingAccount, i.asset, -i.amount), Leg(i.account, i.asset, i.amount))
        return apply(d, legs, Disposition.FUNDED).let { if (it.disposition == Disposition.FUNDED) it.copy(recorded = true) else it }
    }

    private fun settle(d: Draft, i: FinanceInput.Settle): Eval {
        if (i.authority != Authority.OPERATOR) return Eval(Disposition.UNAUTHORIZED)
        val obligation = d.obligations[i.execution] ?: return Eval(Disposition.UNKNOWN_EXECUTION)
        if (i.attempt < 1) return Eval(Disposition.INVALID_INPUT)
        if (obligation.status == ObligationStatus.SETTLED) return Eval(Disposition.ALREADY_SETTLED)
        val attemptKey = i.execution to i.attempt
        if (attemptKey in d.attempts) return Eval(Disposition.PRIOR_ATTEMPT)
        val execution = d.executions.getValue(i.execution)
        val status = when {
            d.position(execution.buyer, cash) < obligation.cashResidual -> Disposition.INSUFFICIENT_CASH
            d.position(execution.seller, security) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES
            else -> null
        }
        if (status != null) { d.attempts[attemptKey] = status; return Eval(status, recorded = true) }
        val legs = listOf(
            Leg(execution.buyer, cash, -obligation.cashResidual), Leg(execution.seller, cash, obligation.cashResidual),
            Leg(execution.seller, security, -obligation.shareResidual), Leg(execution.buyer, security, obligation.shareResidual),
        )
        val result = apply(d, legs, Disposition.SETTLED)
        d.attempts[attemptKey] = result.disposition
        if (result.disposition == Disposition.SETTLED) d.obligations[i.execution] = Obligation(ObligationStatus.SETTLED, 0, 0)
        return result.copy(recorded = true)
    }

    /** Applies all legs or none: overflow leaves positions untouched and yields no legs. */
    private fun apply(d: Draft, legs: List<Leg>, success: Disposition): Eval {
        val updated = d.positions.toMutableMap()
        try {
            for (leg in legs.filter { it.amount != 0L }) {
                val key = leg.account to leg.asset
                updated[key] = Math.addExact(updated[key] ?: 0L, leg.amount)
            }
        } catch (_: ArithmeticException) { return Eval(Disposition.BALANCE_OVERFLOW) }
        d.positions = updated
        return Eval(success, legs.filter { it.amount != 0L })
    }

    /** Throws [IllegalStateException] if any packet invariant (C4) is violated; a violation is a bug, not an input error. */
    fun checkInvariants(state: FinanceState) {
        for ((key, value) in state.positions) {
            check(key.first == config.openingAccount || value >= 0) { "negative non-opening position" }
            check(key.first != config.openingAccount || value <= 0) { "opening account position is positive" }
            check(key.first == config.openingAccount || key.first in config.accounts) { "position for unknown account" }
            check(key.second == cash || key.second == security) { "position in unknown asset" }
        }
        for (asset in listOf(cash, security)) {
            val sum = state.positions.filterKeys { it.second == asset }.values.fold(BigInteger.ZERO) { a, v -> a + BigInteger.valueOf(v) }
            check(sum.signum() == 0) { "legs do not balance for asset" }
        }
        check(state.obligations.keys.all { it in state.executions }) { "obligation without execution" }
        for ((key, obligation) in state.obligations) {
            val e = state.executions.getValue(key)
            when (obligation.status) {
                ObligationStatus.PENDING -> check(obligation.cashResidual == (try { Math.multiplyExact(e.quantity, e.priceNanos) } catch (_: ArithmeticException) { error("pending residual overflow") }) && obligation.shareResidual == e.quantity) { "pending residual drift" }
                ObligationStatus.SETTLED -> check(obligation.cashResidual == 0L && obligation.shareResidual == 0L) { "settled residual not zero" }
            }
        }
        check(state.sequence == state.actions.size.toLong()) { "action count differs from sequence" }
        check(state.executions.size <= config.maxCaptures && state.obligations.size <= config.maxObligations) { "capture budget exceeded" }
        check(state.attempts.size <= config.maxSettleAttempts) { "settle attempt budget exceeded" }
        check(state.controls <= config.maxControls && state.controls == state.actions.values.count { it.disposition == Disposition.FUNDED }) { "control budget or count drift" }
        check(state.sequence <= config.maxCaptures + config.maxSettleAttempts + config.maxControls) { "record budget exceeded" }
        // Cross-structure consistency: every entity is accounted for by exactly the recorded actions that created it.
        check(state.actions.values.all { it.disposition in recordedDispositions }) { "action with a disposition that is never recorded" }
        check(state.actions.values.count { it.disposition == Disposition.CAPTURED } == state.executions.size) { "captures differ from executions" }
        check(state.actions.values.count { it.disposition in settleDispositions } == state.attempts.size) { "settle actions differ from attempts" }
        for ((attempt, disposition) in state.attempts) {
            check(attempt.second >= 1 && attempt.first in state.executions) { "attempt for unknown execution or ordinal" }
            check(disposition in settleDispositions) { "attempt with an illegal disposition" }
        }
        for ((key, obligation) in state.obligations) {
            val settledAttempts = state.attempts.count { it.key.first == key && it.value == Disposition.SETTLED }
            check(settledAttempts == if (obligation.status == ObligationStatus.SETTLED) 1 else 0) { "settled attempt differs from obligation status" }
        }
    }

    /** True if every decision's checksum recomputes and links to its predecessor starting from this ledger's [genesisChain]. */
    fun verifyChain(decisions: List<Decision>): Boolean {
        var prior = genesisChain
        var sequence = 0L
        val actions = HashSet<ActionKey>()
        for (d in decisions) {
            if (d.sequence != sequence + 1 || d.priorChain != prior) return false
            if (d.disposition !in recordedDispositions || !actions.add(d.action)) return false
            if (d.checksum != Canon.sha(decisionBytes(d.sequence, d.action, d.inputDigest, d.disposition, d.legs, d.priorChain, d.stateDigest))) return false
            prior = d.checksum; sequence = d.sequence
        }
        return true
    }

    fun checkpoint(state: FinanceState): FinanceCheckpoint = FinanceCheckpoint(state, Canon.sha(stateBytes(state, includeChain = true)))

    /**
     * Restores a checkpoint against the trusted history head ([trustedHead] = the last decision of a history the caller has
     * verified with [verifyChain], or null for genesis). The head decision commits to the full state through its
     * [Decision.stateDigest], so a state that is internally consistent but was not produced by that history (moved shares,
     * minted cash, extra entities) is refused. A recomputed checkpoint checksum alone is not authority.
     */
    /**
     * Public restore: verifies the complete decision [history] from this ledger's genesis ([verifyChain]) and then restores at its
     * head. This is an **integrity** check (hash linkage and state commitment), not authenticity: whoever can write a self-consistent
     * history and checkpoint can make them verify, so the history source must itself be trusted or anchored (keyed MAC, signature or
     * an externally held chain root; an O3b requirement).
     */
    fun restore(checkpoint: FinanceCheckpoint, history: List<Decision>): FinanceState {
        require(verifyChain(history)) { "decision history does not verify" }
        return restoreAt(checkpoint, history.lastOrNull())
    }

    internal fun restoreAt(checkpoint: FinanceCheckpoint, trustedHead: Decision?): FinanceState {
        val state = checkpoint.state
        if (trustedHead == null) {
            require(state == genesis()) { "checkpoint is not the genesis state" }
        } else {
            require(trustedHead.checksum == Canon.sha(decisionBytes(trustedHead.sequence, trustedHead.action, trustedHead.inputDigest,
                trustedHead.disposition, trustedHead.legs, trustedHead.priorChain, trustedHead.stateDigest))) { "trusted head does not verify" }
            require(state.chain == trustedHead.checksum && state.sequence == trustedHead.sequence) { "checkpoint is not at the trusted history head" }
            require(stateDigest(state) == trustedHead.stateDigest) { "checkpoint state is not the state committed by the trusted head" }
        }
        require(Canon.sha(stateBytes(state, includeChain = true)) == checkpoint.checksum) { "checkpoint tamper" }
        checkInvariants(state)
        return state
    }

    private fun decisionBytes(sequence: Long, action: ActionKey, inputDigest: String, disposition: Disposition, legs: List<Leg>, priorChain: String, stateDigest: String) =
        Canon.bytes {
            str("calcify-finance-decision-v1"); str(priorChain); long(sequence); str(stateDigest)
            str(action.namespace); str(action.domain); str(action.actionId)
            str(inputDigest); str(disposition.name); int(legs.size); legs.forEach { leg(it) }
        }

    internal fun stateDigest(s: FinanceState) = Canon.sha(stateBytes(s, includeChain = false))

    private fun stateBytes(s: FinanceState, includeChain: Boolean) = Canon.bytes {
        str("calcify-finance-state-v1"); long(s.sequence); if (includeChain) str(s.chain); int(s.controls)
        val positions = s.positions.entries.sortedWith(Canon.utf8Order({ it.key.first.value }, { it.key.second.value }))
        int(positions.size); positions.forEach { str(it.key.first.value); str(it.key.second.value); long(it.value) }
        val executions = s.executions.entries.sortedWith(Canon.utf8Order({ it.key.runId }, { it.key.venueSessionId }, { it.key.instrumentId }, { it.key.executionId }))
        int(executions.size)
        executions.forEach {
            execution(it.key); str(it.value.buyer.value); str(it.value.seller.value); long(it.value.quantity); long(it.value.priceNanos)
            val o = s.obligations[it.key] ?: throw IllegalArgumentException("execution without obligation"); str(o.status.name); long(o.cashResidual); long(o.shareResidual)
        }
        val attempts = s.attempts.entries.sortedWith(Canon.utf8Order({ it.key.first.runId }, { it.key.first.venueSessionId }, { it.key.first.instrumentId }, { it.key.first.executionId }, { it.key.second }))
        int(attempts.size); attempts.forEach { execution(it.key.first); int(it.key.second); str(it.value.name) }
        val actions = s.actions.entries.sortedWith(Canon.utf8Order({ it.key.namespace }, { it.key.domain }, { it.key.actionId }))
        int(actions.size); actions.forEach { str(it.key.namespace); str(it.key.domain); str(it.key.actionId); str(it.value.digest); str(it.value.disposition.name) }
    }

}
