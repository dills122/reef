package com.reef.platform.calcify.finance

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Finite Calcify P3 O3a: typed, pure unreserved gross DvP model. No I/O, clock or randomness.
 * Not wired to any runtime, not financial authority, not a capacity claim. See docs/work/CALCIFY_FINITE_P3_O3_PACKET.md.
 */
private fun boundedText(value: String) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128

@JvmInline internal value class AccountId(val value: String) { init { require(boundedText(value)) { "invalid account id" } } }
@JvmInline internal value class AssetId(val value: String) { init { require(boundedText(value)) { "invalid asset id" } } }

/** Business execution identity; physical source provenance is audit data and never part of this key. */
internal data class ExecutionKey(val runId: String, val venueSessionId: String, val instrumentId: String, val executionId: String) {
    init { require(listOf(runId, venueSessionId, instrumentId, executionId).all(::boundedText)) { "invalid execution key" } }
}

internal data class ActionKey(val namespace: String, val domain: String, val actionId: String) {
    init { require(listOf(namespace, domain, actionId).all(::boundedText)) { "invalid action key" } }
}

internal data class Leg(val account: AccountId, val asset: AssetId, val amount: Long)

/** Funding and repair are operator controls (owner decision D4); trading principals can never fund. */
internal enum class Authority { OPERATOR, TRADING }

internal enum class Disposition {
    CAPTURED, EXECUTION_ALREADY_CAPTURED, EXECUTION_CONFLICT, FUNDED, SETTLED, ALREADY_SETTLED, PRIOR_ATTEMPT,
    INSUFFICIENT_CASH, INSUFFICIENT_SHARES, UNAUTHORIZED, UNKNOWN_ACCOUNT, UNKNOWN_EXECUTION, INVALID_INPUT,
    AMOUNT_OVERFLOW, BALANCE_OVERFLOW,
    /** Not recorded: same action key, same digest. */ PRIOR_RESULT,
    /** Not recorded: same action key, different digest. */ ACTION_CONFLICT,
    /** Not recorded: a pinned budget is exhausted. */ BUDGET_EXCEEDED,
}

internal sealed interface FinanceInput {
    val action: ActionKey

    data class Capture(override val action: ActionKey, val execution: ExecutionKey, val buyer: AccountId, val seller: AccountId,
                       val quantity: Long, val priceNanos: Long) : FinanceInput
    data class Fund(override val action: ActionKey, val authority: Authority, val account: AccountId, val asset: AssetId,
                    val amount: Long) : FinanceInput
    /** [attempt] is a caller-chosen ordinal; each (execution, attempt) is evaluated at most once. */
    data class Settle(override val action: ActionKey, val execution: ExecutionKey, val attempt: Int) : FinanceInput

    fun canonical(): ByteArray = Canon.bytes {
        str("calcify-finance-input-v1")
        str(action.namespace); str(action.domain); str(action.actionId)
        when (val i = this@FinanceInput) {
            is Capture -> { str("CAPTURE"); execution(i.execution); str(i.buyer.value); str(i.seller.value); long(i.quantity); long(i.priceNanos) }
            is Fund -> { str("FUND"); str(i.authority.name); str(i.account.value); str(i.asset.value); long(i.amount) }
            is Settle -> { str("SETTLE"); execution(i.execution); int(i.attempt) }
        }
    }
    fun digest(): String = Canon.sha(canonical())
}

/** Length-framed canonical encoding; never delimiter concatenation. */
internal object Canon {
    class Writer(private val out: DataOutputStream) {
        fun str(v: String) { val b = v.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
        fun long(v: Long) = out.writeLong(v)
        fun int(v: Int) = out.writeInt(v)
        fun execution(k: ExecutionKey) { str(k.runId); str(k.venueSessionId); str(k.instrumentId); str(k.executionId) }
        fun leg(l: Leg) { str(l.account.value); str(l.asset.value); long(l.amount) }
    }
    fun bytes(block: Writer.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { Writer(it).block() }
        return buffer.toByteArray()
    }
    fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

internal data class Captured(val buyer: AccountId, val seller: AccountId, val quantity: Long, val priceNanos: Long)
internal enum class ObligationStatus { PENDING, SETTLED }
internal data class Obligation(val status: ObligationStatus, val cashResidual: Long, val shareResidual: Long)
internal data class ActionRecord(val digest: String, val disposition: Disposition)

/** Immutable. Entries are keyed per entity so a persistence adapter can store them individually (packet constraint C1). */
internal data class FinanceState(
    val positions: Map<Pair<AccountId, AssetId>, Long>,
    val executions: Map<ExecutionKey, Captured>,
    val obligations: Map<ExecutionKey, Obligation>,
    val attempts: Map<Pair<ExecutionKey, Int>, Disposition>,
    val actions: Map<ActionKey, ActionRecord>,
    val sequence: Long,
    val chain: String,
)

internal data class Decision(
    val sequence: Long, val action: ActionKey, val inputDigest: String, val disposition: Disposition,
    val legs: List<Leg>, val priorChain: String, val checksum: String,
)

internal data class Outcome(val state: FinanceState, val disposition: Disposition, val decision: Decision?, val originalDisposition: Disposition? = null)

internal data class FinanceCheckpoint(val state: FinanceState, val checksum: String)

internal data class FinanceConfig(
    val accounts: Set<AccountId>, val cashAsset: AssetId, val securityAsset: AssetId, val openingAccount: AccountId,
    val maxObligations: Int = 256, val maxAttempts: Int = 160,
) {
    init {
        require(accounts.size in 2..16) { "2..16 accounts" }
        require(openingAccount !in accounts) { "opening account must be distinct" }
        require(cashAsset != securityAsset) { "distinct assets" }
        require(maxObligations in 1..256 && maxAttempts in 1..160) { "budgets exceed pinned limits" }
    }
}
