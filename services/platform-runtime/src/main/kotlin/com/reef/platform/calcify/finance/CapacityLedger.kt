package com.reef.platform.calcify.finance

import java.math.BigInteger

/**
 * Configuration of the capacity profile's ledger (docs/work/CALCIFY_CAPACITY_PROFILE.md). Same fields as the finite [FinanceConfig] but without its pinned
 * ceilings (16 accounts, 128 captures, 256 obligations, 160 attempts, 8 controls): the budgets are the profile's own, per slice. The instantiation label of the
 * state commitment is bound into the digest, so a chain built under one commitment can never verify as another's.
 */
internal data class CapacityConfig(
    /** [securities] are the tradable instruments' security assets: the asset of an execution is `AssetId(execution.instrumentId)`, which must be in the set. */
    /** The fixed account set (as in the finite profile), or null: accounts are then admitted by the first recorded Fund and exist as state, so a configuration never lists thousands of them. */
    val accounts: Set<AccountId>?, val cashAsset: AssetId, val securities: Set<AssetId>, val openingAccount: AccountId,
    val maxObligations: Int, val maxCaptures: Int, val maxSettleAttempts: Int, val maxControls: Int,
) {
    init {
        require(accounts == null || accounts.size >= 2) { "at least two accounts" }
        require(accounts == null || openingAccount !in accounts) { "opening account must be distinct" }
        require(securities.isNotEmpty() && cashAsset !in securities) { "at least one security asset, distinct from cash" }
        require(maxObligations >= 1 && maxCaptures >= 1 && maxSettleAttempts >= 1 && maxControls >= 1) { "budgets are positive" }
        require(maxCaptures <= maxObligations) { "captures cannot exceed obligations" }
    }

    fun digest(): String = Canon.sha(Canon.bytes {
        str("calcify-finance-config-v2-capacity"); str(StateCommitment.LABEL)
        if (accounts == null) str("accounts-admitted-by-funding") else {
            str("accounts-fixed"); val sorted = accounts.map { it.value }.sortedWith(Canon.utf8Order<String>({ it })); int(sorted.size); sorted.forEach { str(it) }
        }
        str(cashAsset.value); val secs = securities.map { it.value }.sortedWith(Canon.utf8Order<String>({ it })); int(secs.size); secs.forEach { str(it) }; str(openingAccount.value)
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
    val genesisChain: String = CapacityChain.genesisOf(config)

    private val positions = HashMap<Pair<AccountId, AssetId>, Long>()
    private val executions = HashMap<ExecutionKey, Captured>()
    private val obligations = HashMap<ExecutionKey, Obligation>()
    private val attempts = HashMap<Pair<ExecutionKey, Int>, Disposition>()
    private val actions = HashMap<ActionKey, ActionRecord>()
    /** Accounts admitted by funding (only used when the configuration has no fixed account set); derived from the positions at restore, so it is not separate state. */
    private val admitted = HashSet<AccountId>()
    private val trie = StateTrie()
    var sequence = 0L; private set
    var controls = 0; private set
    var chain: String = genesisChain; private set

    val entityCount: Int get() = trie.size

    /** The abstract state as an immutable [FinanceState] (O(n); tests, restore checks and the reference commitment). */
    fun snapshot(): FinanceState = FinanceState(HashMap(positions), HashMap(executions), HashMap(obligations), HashMap(attempts), HashMap(actions), sequence, chain, controls)

    /** Branch hashes the commitment trie has computed so far (a metric: the cost of a commitment update is the stale branches it re-hashes). */
    val branchHashes: Long get() = trie.branchHashes

    fun position(account: AccountId, asset: AssetId): Long = positions[account to asset] ?: 0L

    // ---- entity writes: the maps hold the state, the trie holds its commitment ----

    private val writes = ArrayList<StateCommitment.Entity>()

    /** The entities the last recorded decision wrote, in write order (an entity written twice appears twice; the last one wins). Empty after a refusal. A caller that persists the ledger stores exactly these. */
    val lastWrites: List<StateCommitment.Entity> get() = writes

    private fun commit(e: StateCommitment.Entity) { writes += e; val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)) }
    private fun commitPosition(k: Pair<AccountId, AssetId>, v: Long) = commit(StateCommitment.Entity("P", Canon.bytes { str(k.first.value); str(k.second.value) }, Canon.bytes { long(v) }))
    private fun commitExecution(k: ExecutionKey, c: Captured) = commit(StateCommitment.Entity("X", Canon.bytes { execution(k) }, Canon.bytes { str(c.buyer.value); str(c.seller.value); long(c.quantity); long(c.priceNanos) }))
    private fun commitObligation(k: ExecutionKey, o: Obligation) = commit(StateCommitment.Entity("O", Canon.bytes { execution(k) }, Canon.bytes { str(o.status.name); long(o.cashResidual); long(o.shareResidual) }))
    private fun commitAttempt(k: ExecutionKey, n: Int, d: Disposition) = commit(StateCommitment.Entity("A", Canon.bytes { execution(k); int(n) }, Canon.bytes { str(d.name) }))
    private fun commitAction(k: ActionKey, r: ActionRecord) = commit(StateCommitment.Entity("K", Canon.bytes { str(k.namespace); str(k.domain); str(k.actionId) }, Canon.bytes { str(r.digest); str(r.disposition.name) }))

    private fun pos(a: AccountId, s: AssetId) = positions[a to s] ?: 0L
    /** May [a] take part in a capture: in the fixed set, or admitted by funding. */
    private fun known(a: AccountId): Boolean = config.accounts?.contains(a) ?: (a in admitted)
    /** May [a] be funded: in the fixed set, or (admit mode) any account other than the opening account. */
    private fun fundable(a: AccountId): Boolean = config.accounts?.contains(a) ?: (a != config.openingAccount)
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
        writes.clear()
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
        chain = checksumOf(sequence, input.action, digest, disposition, legs, priorChain, stateDigest)
        // Local invariants in place of the whole-state check (design 5.5).
        val net = HashMap<AssetId, BigInteger>()
        for (l in legs) net.merge(l.asset, BigInteger.valueOf(l.amount), BigInteger::add)
        check(net.values.all { it.signum() == 0 }) { "legs do not balance" }
        check(sequence == actions.size.toLong()) { "action count differs from sequence" }
        return CapacityOutcome(disposition, Decision(sequence, input.action, digest, disposition, legs, priorChain, stateDigest, chain))
    }

    private fun capture(i: FinanceInput.Capture, digest: String): CapacityOutcome {
        if (!known(i.buyer) || !known(i.seller)) return refused(Disposition.UNKNOWN_ACCOUNT)
        if (AssetId(i.execution.instrumentId) !in config.securities) return refused(Disposition.INVALID_INPUT)
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
        if (!fundable(i.account)) return refused(Disposition.UNKNOWN_ACCOUNT)
        if ((i.asset != config.cashAsset && i.asset !in config.securities) || i.amount <= 0) return refused(Disposition.INVALID_INPUT)
        val plan = planLegs(listOf(Leg(config.openingAccount, i.asset, -i.amount), Leg(i.account, i.asset, i.amount))) ?: return refused(Disposition.BALANCE_OVERFLOW)
        if (controls >= config.maxControls) return refused(Disposition.BUDGET_EXCEEDED)
        val applied = apply(plan)
        controls++; if (config.accounts == null) admitted += i.account
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
        val security = AssetId(i.execution.instrumentId)
        val shortfall = when {
            pos(execution.buyer, config.cashAsset) < obligation.cashResidual -> Disposition.INSUFFICIENT_CASH
            pos(execution.seller, security) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES
            else -> null
        }
        if (attempts.size >= config.maxSettleAttempts) return refused(Disposition.BUDGET_EXCEEDED)
        if (shortfall != null) { attempts[attemptKey] = shortfall; commitAttempt(i.execution, i.attempt, shortfall); return record(i, digest, shortfall, emptyList()) }
        val cash = config.cashAsset
        val plan = planLegs(listOf(
            Leg(execution.buyer, cash, -obligation.cashResidual), Leg(execution.seller, cash, obligation.cashResidual),
            Leg(execution.seller, security, -obligation.shareResidual), Leg(execution.buyer, security, obligation.shareResidual)))
        if (plan == null) { attempts[attemptKey] = Disposition.BALANCE_OVERFLOW; commitAttempt(i.execution, i.attempt, Disposition.BALANCE_OVERFLOW); return record(i, digest, Disposition.BALANCE_OVERFLOW, emptyList()) }
        val applied = apply(plan)
        attempts[attemptKey] = Disposition.SETTLED; commitAttempt(i.execution, i.attempt, Disposition.SETTLED)
        val settled = Obligation(ObligationStatus.SETTLED, 0, 0); obligations[i.execution] = settled; commitObligation(i.execution, settled)
        return record(i, digest, Disposition.SETTLED, applied)
    }

    companion object {
        /** The decision checksum (the same layout as [FinanceLedger]'s, so the finite chain verifier accepts capacity decisions). */
        fun checksumOf(sequence: Long, action: ActionKey, inputDigest: String, disposition: Disposition, legs: List<Leg>, priorChain: String, stateDigest: String): String = Canon.sha(Canon.bytes {
            str("calcify-finance-decision-v1"); str(priorChain); long(sequence); str(stateDigest)
            str(action.namespace); str(action.domain); str(action.actionId); str(inputDigest); str(disposition.name); int(legs.size); legs.forEach { leg(it) }
        })

        /**
         * Rebuilds a ledger from its entity set and scalars (restore): decodes each entity by class, rebuilds the maps and the trie, and refuses anything that is not
         * a well-formed, duplicate-free state. It does NOT decide that the state is the one a decision committed to: the caller compares
         * [commitment] with the head decision's `stateDigest` and the chain head with the head decision's checksum (see [CapacityStore]).
         */
        fun restore(config: CapacityConfig, entities: Iterable<StateCommitment.Entity>, sequence: Long, controls: Int, chain: String): CapacityLedger {
            require(sequence >= 0 && controls >= 0) { "negative scalar" }
            val ledger = CapacityLedger(config)
            for (e in entities) ledger.load(e)
            require(ledger.actions.size.toLong() == sequence) { "action count differs from sequence" }
            require(ledger.actions.values.count { it.disposition == Disposition.FUNDED } == controls) { "funded action count differs from controls" }
            require(ledger.executions.keys == ledger.obligations.keys) { "an execution without an obligation or the reverse" }
            ledger.sequence = sequence; ledger.controls = controls; ledger.chain = chain
            if (config.accounts == null) for (k in ledger.positions.keys) if (k.first != config.openingAccount) ledger.admitted += k.first
            ledger.checkRestoredPositions()
            return ledger
        }
    }

    /** `C(S)` of the current state (the value the head decision's `stateDigest` must equal in the synchronous profile). */
    fun commitment(): String = StateCommitment.commitment(sequence, controls, trie.root())

    private fun load(e: StateCommitment.Entity) {
        val key = DecodedFields(e.keyBytes); val value = DecodedFields(e.valueBytes)
        fun execution() = ExecutionKey(key.text(), key.text(), key.text(), key.text())
        val before = trie.size
        when (e.tag) {
            "P" -> { val k = AccountId(key.text()) to AssetId(key.text()); key.end(); positions[k] = value.long().also { value.end() } }
            "X" -> { val k = execution(); key.end(); executions[k] = Captured(AccountId(value.text()), AccountId(value.text()), value.long(), value.long()).also { value.end() } }
            "O" -> { val k = execution(); key.end(); obligations[k] = Obligation(ObligationStatus.valueOf(value.text()), value.long(), value.long()).also { value.end() } }
            "A" -> { val k = execution() to key.int(); key.end(); attempts[k] = Disposition.valueOf(value.text()).also { value.end() } }
            "K" -> {
                val k = ActionKey(key.text(), key.text(), key.text()); key.end()
                val digest = value.text(); require(Regex("^[0-9a-f]{64}$").matches(digest)) { "action digest is not 64 lowercase hex" }
                actions[k] = ActionRecord(digest, Disposition.valueOf(value.text())).also { value.end() }
            }
            else -> throw IllegalArgumentException("unknown entity class ${e.tag}")
        }
        val k = StateCommitment.keyOf(e)
        require(trie.get(k) == null) { "two entities have the same trie key" }
        trie.put(k, StateCommitment.leafOf(e, k))
        require(trie.size == before + 1) { "entity not added" }
    }

    private fun checkRestoredPositions() {
        val net = HashMap<AssetId, BigInteger>()
        for ((k, v) in positions) {
            require(k.first == config.openingAccount || v >= 0) { "negative non-opening position" }
            require(k.first != config.openingAccount || v <= 0) { "opening account position is positive" }
            require(k.first == config.openingAccount || config.accounts == null || k.first in config.accounts) { "position for an unknown account" }
            require(k.second == config.cashAsset || k.second in config.securities) { "position in an unknown asset" }
            net.merge(k.second, BigInteger.valueOf(v), BigInteger::add)
        }
        require(net.values.all { it.signum() == 0 }) { "positions do not balance per asset" }
    }

    /** Strict reader of the Canon framing: text is int32 length plus strict UTF-8, integers are big-endian; trailing bytes are an error. */
    private class DecodedFields(bytes: ByteArray) {
        private val buffer = java.nio.ByteBuffer.wrap(bytes)
        fun text(): String {
            require(buffer.remaining() >= 4) { "truncated entity" }
            val n = buffer.int; require(n in 0..4096 && buffer.remaining() >= n) { "corrupt frame" }
            val raw = ByteArray(n); buffer.get(raw)
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString()
        }
        fun long(): Long { require(buffer.remaining() >= 8) { "truncated entity" }; return buffer.long }
        fun int(): Int { require(buffer.remaining() >= 4) { "truncated entity" }; return buffer.int }
        fun end() { require(!buffer.hasRemaining()) { "trailing entity bytes" } }
    }
}
