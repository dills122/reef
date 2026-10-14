package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random

/**
 * Research prototype shared by the S3c and S3d spikes (test code, not product code): an in-memory O3a-semantics ledger with O(touched) decide, aggregates and local
 * invariants, and a path-compressed binary trie over SHA-256 of the entity key as the incremental commitment. See S3cLedgerScaleSpikeTest for what it is and is not.
 */
private fun scaleSha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()
private fun scaleFrame(s: String): ByteArray { val b = s.toByteArray(); return java.nio.ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array() }
private fun scaleHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Path-compressed binary trie over the 256-bit key hash; the leaf digest is the node hash; shape depends only on the key set. */
internal class ScaleTrie {
    sealed class N { abstract var hash: ByteArray; abstract val any: ByteArray }
    class Leaf(val key: ByteArray, override var hash: ByteArray) : N() { override val any get() = key }
    class Branch(val bit: Int, var left: N, var right: N) : N() { override var hash = ByteArray(0); var dirty = true; var rep: ByteArray = left.any; override val any get() = rep }
    var root: N? = null
    var size = 0L
    private fun bit(k: ByteArray, i: Int) = (k[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
    private fun firstDiff(a: ByteArray, b: ByteArray): Int { for (i in 0 until 32) { val x = (a[i].toInt() xor b[i].toInt()) and 0xff; if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24 }; return -1 }
    /** Hashes are computed lazily: a put only marks the path dirty, and [rootHash] rehashes each dirty branch once, so puts that share a path (and decisions between roots) share the work. */
    private fun settle(n: N) { if (n is Branch && n.dirty) { settle(n.left); settle(n.right); n.hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(2, (n.bit ushr 8).toByte(), n.bit.toByte()) + n.left.hash + n.right.hash); n.dirty = false } }
    fun put(key: ByteArray, leaf: ByteArray) { root = insert(root, key, leaf) }
    private fun insert(n: N?, key: ByteArray, leaf: ByteArray): N {
        if (n == null) { size++; return Leaf(key, leaf) }
        val d = firstDiff(n.any, key)
        if (n is Leaf) { if (d < 0) { n.hash = leaf; return n }; size++; val nl = Leaf(key, leaf); return if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl) }
        n as Branch
        if (d in 0 until n.bit) { size++; val nl = Leaf(key, leaf); return if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl) }
        if (bit(key, n.bit) == 0) { n.left = insert(n.left, key, leaf); n.rep = n.left.any } else n.right = insert(n.right, key, leaf)
        n.dirty = true; return n
    }
    fun rootHash(): ByteArray { root?.let { settle(it) }; return root?.hash ?: ByteArray(32) }
}

/** The result the differential compares: disposition, recorded or not, and legs. */
internal data class ScaleResult(val disposition: Disposition, val recorded: Boolean, val legs: List<Leg>)

internal class ScalePlan(val updated: Map<Pair<AccountId, AssetId>, Long>, val legs: List<Leg>)

internal class ScaleBudgets(val captures: Int = Int.MAX_VALUE, val obligations: Int = Int.MAX_VALUE, val attempts: Int = Int.MAX_VALUE, val controls: Int = Int.MAX_VALUE)

internal class ScaleLedger(val accounts: Set<AccountId>, val cash: AssetId, val security: AssetId, val opening: AccountId, val budgets: ScaleBudgets = ScaleBudgets(), val commit: Boolean = true, val rootEvery: Int = 1) {
    val positions = HashMap<Pair<AccountId, AssetId>, Long>()
    val executions = HashMap<ExecutionKey, Captured>(); val obligations = HashMap<ExecutionKey, Obligation>()
    val attempts = HashMap<Pair<ExecutionKey, Int>, Disposition>(); val actions = HashMap<ActionKey, ActionRecord>()
    var sequence = 0L; var controls = 0; var chain = "0".repeat(64)
    var settledObligations = 0L; var captureActions = 0L; var settleActions = 0L; var fundedActions = 0L
    var sumCash = BigInteger.ZERO; var sumSecurity = BigInteger.ZERO
    val trie = ScaleTrie()
    var touchedLastDecision = 0
    /** The (key, value) of every entity written by the last decision, for a caller that persists them. */
    val touched = ArrayList<Pair<String, String>>()

    /** With rootEvery > 1 the entity writes wait here (last write per key wins) and reach the trie only when a root is needed, so repeated writes to one entity and the per-entity hashing are paid once per root. */
    private val pending = LinkedHashMap<String, Pair<String, String>>()
    private fun put(tag: String, key: String, value: String) {
        touchedLastDecision++; touched += key to value
        if (!commit) return
        if (rootEvery > 1) pending[key] = tag to value else applyToTrie(tag, key, value)
    }
    private fun applyToTrie(tag: String, key: String, value: String) { val k = scaleSha(key.toByteArray()); trie.put(k, scaleSha(byteArrayOf(0), k, scaleSha(scaleFrame(tag), scaleFrame(key), scaleFrame(value)))) }
    /** The commitment root after every write so far. */
    fun root(): ByteArray { if (pending.isNotEmpty()) { for ((key, tv) in pending) applyToTrie(tv.first, key, tv.second); pending.clear() }; return trie.rootHash() }
    private fun ek(k: ExecutionKey) = "${k.runId}\u0000${k.venueSessionId}\u0000${k.instrumentId}\u0000${k.executionId}"
    private fun pos(a: AccountId, s: AssetId) = positions[a to s] ?: 0L

    /** Computes the post-leg positions without writing anything; null on overflow (positions untouched). */
    private fun planLegs(legs: List<Leg>): ScalePlan? {
        val nonZero = legs.filter { it.amount != 0L }
        val updated = LinkedHashMap<Pair<AccountId, AssetId>, Long>()
        try { for (l in nonZero) { val k = l.account to l.asset; updated[k] = Math.addExact(updated[k] ?: pos(l.account, l.asset), l.amount) } } catch (_: ArithmeticException) { return null }
        for ((k, v) in updated) {
            check(k.first == opening || v >= 0) { "negative non-opening position" }; check(k.first != opening || v <= 0) { "opening account position is positive" }
        }
        return ScalePlan(updated, nonZero)
    }

    private fun commitLegs(plan: ScalePlan): List<Leg> {
        for ((k, v) in plan.updated) { positions[k] = v; put("P", "p/${k.first.value}/${k.second.value}", v.toString()) }
        for (l in plan.legs) if (l.asset == cash) sumCash += BigInteger.valueOf(l.amount) else sumSecurity += BigInteger.valueOf(l.amount)
        return plan.legs
    }

    fun decide(input: FinanceInput): ScaleResult {
        touchedLastDecision = 0; touched.clear()
        val digest = input.digest()
        actions[input.action]?.let { prior -> return if (prior.digest == digest) ScaleResult(Disposition.PRIOR_RESULT, false, emptyList()) else ScaleResult(Disposition.ACTION_CONFLICT, false, emptyList()) }
        return when (input) {
            is FinanceInput.Capture -> capture(input, digest)
            is FinanceInput.Fund -> fund(input, digest)
            is FinanceInput.Settle -> settle(input, digest)
        }
    }

    private fun record(input: FinanceInput, digest: String, disposition: Disposition, legs: List<Leg>): ScaleResult {
        sequence++
        actions[input.action] = ActionRecord(digest, disposition)
        put("A", "a/${input.action.namespace}\u0000${input.action.domain}\u0000${input.action.actionId}", digest + disposition.name)
        // rootEvery > 1 models a decision that commits to the state only every K decisions (a design change, not O3a): the chain still covers every decision.
        val stateDigest = if (commit && sequence % rootEvery == 0L) scaleHex(root()) else "0".repeat(64)
        val bytes = Canon.bytes { str("calcify-finance-decision-v1"); str(chain); long(sequence); str(stateDigest); str(input.action.namespace); str(input.action.domain); str(input.action.actionId); str(digest); str(disposition.name); int(legs.size); legs.forEach { leg(it) } }
        chain = Canon.sha(bytes)
        // Local invariants and counters in place of the whole-state check (design 5.5).
        check(sumCash.signum() == 0 && sumSecurity.signum() == 0) { "legs do not balance" }
        check(sequence == actions.size.toLong()) { "action count differs from sequence" }
        return ScaleResult(disposition, true, legs)
    }

    private fun capture(i: FinanceInput.Capture, digest: String): ScaleResult {
        if (i.buyer !in accounts || i.seller !in accounts) return ScaleResult(Disposition.UNKNOWN_ACCOUNT, false, emptyList())
        if (i.buyer == i.seller || i.quantity <= 0 || i.priceNanos < 0) return ScaleResult(Disposition.INVALID_INPUT, false, emptyList())
        val amount = try { Math.multiplyExact(i.quantity, i.priceNanos) } catch (_: ArithmeticException) { return ScaleResult(Disposition.AMOUNT_OVERFLOW, false, emptyList()) }
        val captured = Captured(i.buyer, i.seller, i.quantity, i.priceNanos)
        executions[i.execution]?.let { old -> return ScaleResult(if (old == captured) Disposition.EXECUTION_ALREADY_CAPTURED else Disposition.EXECUTION_CONFLICT, false, emptyList()) }
        if (executions.size >= budgets.captures || obligations.size >= budgets.obligations) return ScaleResult(Disposition.BUDGET_EXCEEDED, false, emptyList())
        executions[i.execution] = captured; obligations[i.execution] = Obligation(ObligationStatus.PENDING, amount, i.quantity); captureActions++
        put("X", "x/${ek(i.execution)}", "${i.buyer.value}|${i.seller.value}|${i.quantity}|${i.priceNanos}")
        put("O", "o/${ek(i.execution)}", "PENDING|$amount|${i.quantity}")
        return record(i, digest, Disposition.CAPTURED, emptyList())
    }

    private fun fund(i: FinanceInput.Fund, digest: String): ScaleResult {
        if (i.authority != Authority.OPERATOR) return ScaleResult(Disposition.UNAUTHORIZED, false, emptyList())
        if (i.account !in accounts) return ScaleResult(Disposition.UNKNOWN_ACCOUNT, false, emptyList())
        if ((i.asset != cash && i.asset != security) || i.amount <= 0) return ScaleResult(Disposition.INVALID_INPUT, false, emptyList())
        val plan = planLegs(listOf(Leg(opening, i.asset, -i.amount), Leg(i.account, i.asset, i.amount))) ?: return ScaleResult(Disposition.BALANCE_OVERFLOW, false, emptyList())
        if (controls >= budgets.controls) return ScaleResult(Disposition.BUDGET_EXCEEDED, false, emptyList())
        val applied = commitLegs(plan)
        controls++; fundedActions++
        return record(i, digest, Disposition.FUNDED, applied)
    }

    private fun settle(i: FinanceInput.Settle, digest: String): ScaleResult {
        if (i.authority != Authority.OPERATOR) return ScaleResult(Disposition.UNAUTHORIZED, false, emptyList())
        val obligation = obligations[i.execution] ?: return ScaleResult(Disposition.UNKNOWN_EXECUTION, false, emptyList())
        if (i.attempt < 1) return ScaleResult(Disposition.INVALID_INPUT, false, emptyList())
        if (obligation.status == ObligationStatus.SETTLED) return ScaleResult(Disposition.ALREADY_SETTLED, false, emptyList())
        val attemptKey = i.execution to i.attempt
        if (attemptKey in attempts) return ScaleResult(Disposition.PRIOR_ATTEMPT, false, emptyList())
        val execution = executions.getValue(i.execution)
        val status = when {
            pos(execution.buyer, cash) < obligation.cashResidual -> Disposition.INSUFFICIENT_CASH
            pos(execution.seller, security) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES
            else -> null
        }
        if (attempts.size >= budgets.attempts) return ScaleResult(Disposition.BUDGET_EXCEEDED, false, emptyList())
        if (status != null) { attempts[attemptKey] = status; settleActions++; put("T", "t/${ek(i.execution)}/${i.attempt}", status.name); return record(i, digest, status, emptyList()) }
        val legs = listOf(Leg(execution.buyer, cash, -obligation.cashResidual), Leg(execution.seller, cash, obligation.cashResidual), Leg(execution.seller, security, -obligation.shareResidual), Leg(execution.buyer, security, obligation.shareResidual))
        val plan = planLegs(legs)
        if (plan == null) { attempts[attemptKey] = Disposition.BALANCE_OVERFLOW; settleActions++; put("T", "t/${ek(i.execution)}/${i.attempt}", Disposition.BALANCE_OVERFLOW.name); return record(i, digest, Disposition.BALANCE_OVERFLOW, emptyList()) }
        val applied = commitLegs(plan)
        attempts[attemptKey] = Disposition.SETTLED; settleActions++; obligations[i.execution] = Obligation(ObligationStatus.SETTLED, 0, 0); settledObligations++
        put("T", "t/${ek(i.execution)}/${i.attempt}", Disposition.SETTLED.name); put("O", "o/${ek(i.execution)}", "SETTLED|0|0")
        return record(i, digest, Disposition.SETTLED, applied)
    }

    /** Rebuilds the commitment from the entity tables only, in a fresh trie and a shuffled order (restore, and the order-independence check). */
    fun rebuildRoot(seed: Long): ByteArray {
        val fresh = ScaleTrie(); val items = ArrayList<Triple<String, String, String>>()
        for ((k, v) in positions) items += Triple("P", "p/${k.first.value}/${k.second.value}", v.toString())
        for ((k, v) in executions) items += Triple("X", "x/${ek(k)}", "${v.buyer.value}|${v.seller.value}|${v.quantity}|${v.priceNanos}")
        for ((k, v) in obligations) items += Triple("O", "o/${ek(k)}", "${v.status.name}|${v.cashResidual}|${v.shareResidual}")
        for ((k, v) in attempts) items += Triple("T", "t/${ek(k.first)}/${k.second}", v.name)
        for ((k, v) in actions) items += Triple("A", "a/${k.namespace}\u0000${k.domain}\u0000${k.actionId}", v.digest + v.disposition.name)
        items.shuffle(Random(seed))
        for ((tag, key, value) in items) { val k = scaleSha(key.toByteArray()); fresh.put(k, scaleSha(byteArrayOf(0), k, scaleSha(scaleFrame(tag), scaleFrame(key), scaleFrame(value)))) }
        return fresh.rootHash()
    }
    fun entityCount() = positions.size + executions.size + obligations.size + attempts.size + actions.size
}
