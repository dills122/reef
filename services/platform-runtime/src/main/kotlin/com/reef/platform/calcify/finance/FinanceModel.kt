package com.reef.platform.calcify.finance

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Finite Calcify P3 O3a: typed, pure unreserved gross DvP model. No I/O, clock or randomness.
 * Not wired to any runtime, not financial authority, not a capacity claim. See docs/work/CALCIFY_FINITE_P3_O3_PACKET.md.
 */
/** Strict UTF-8: unpaired surrogates are an error, never silently replaced (two distinct strings must not share bytes). */
private fun strictUtf8(value: String): ByteArray {
    // ASCII is its own UTF-8 and cannot be malformed: the common case needs no encoder.
    val length = value.length
    var ascii = true
    for (i in 0 until length) if (value[i].code >= 0x80) { ascii = false; break }
    if (ascii) { val out = ByteArray(length); for (i in 0 until length) out[i] = value[i].code.toByte(); return out }
    val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = try { encoder.encode(CharBuffer.wrap(value)) } catch (e: CharacterCodingException) { throw IllegalArgumentException("invalid UTF-16 text", e) }
    return ByteArray(buffer.remaining()).also(buffer::get)
}
private fun boundedText(value: String): Boolean {
    // Every id is validated on every construction (decoders, entity keys, settle inputs), so the common ASCII case must not allocate: for ASCII the UTF-8 size is the
    // length, a control character is below 0x20 or 0x7f, and the only blank ASCII text without a control character is spaces alone (the other ASCII whitespace is control).
    val length = value.length
    if (length == 0) return false
    var allSpaces = true
    for (i in 0 until length) {
        val c = value[i].code
        if (c >= 0x80) return boundedTextGeneral(value)
        if (c < 0x20 || c == 0x7f) return false
        if (c != 0x20) allSpaces = false
    }
    return !allSpaces && length <= 128
}
private fun boundedTextGeneral(value: String): Boolean {
    if (value.isBlank() || value.any { it.isISOControl() }) return false
    return try { strictUtf8(value).size <= 128 } catch (_: IllegalArgumentException) { false }
}

@JvmInline internal value class AccountId(val value: String) { init { require(boundedText(value)) { "invalid account id" } } }
@JvmInline internal value class AssetId(val value: String) { init { require(boundedText(value)) { "invalid asset id" } } }

/** Business execution identity; physical source provenance is audit data and never part of this key. */
internal data class ExecutionKey(val runId: String, val venueSessionId: String, val instrumentId: String, val executionId: String) {
    init { require(boundedText(runId) && boundedText(venueSessionId) && boundedText(instrumentId) && boundedText(executionId)) { "invalid execution key" } }

    // A trade's key is framed up to six times (capture input, settle input, three entity keys, the attempt): the framed fields are remembered from the second use on.
    // Not part of equality (it is a pure function of the four fields); a key used once never pays for the copy.
    // Single-thread by contract: the lane builds its keys on its own thread and the projector decodes fresh ones from bytes, so nothing shares a key across threads
    // (these two fields are deliberately plain, not volatile; sharing a key would need @Volatile on both).
    private var seenOnce = false
    private var framed: ByteArray? = null
    internal fun cachedFrame(): ByteArray? {
        framed?.let { return it }
        if (!seenOnce) { seenOnce = true; return null }
        return Canon.Writer().also { it.str(runId); it.str(venueSessionId); it.str(instrumentId); it.str(executionId) }.toByteArray().also { framed = it }
    }
}

internal data class ActionKey(val namespace: String, val domain: String, val actionId: String) {
    init { require(boundedText(namespace) && boundedText(domain) && boundedText(actionId)) { "invalid action key" } }
}

internal data class Leg(val account: AccountId, val asset: AssetId, val amount: Long)

/** Funding and repair are operator controls (owner decision D4); trading principals can never fund. */
internal enum class Authority { OPERATOR, TRADING }

internal enum class Disposition {
    CAPTURED, EXECUTION_ALREADY_CAPTURED, EXECUTION_CONFLICT, FUNDED, SETTLED, ALREADY_SETTLED, PRIOR_ATTEMPT,
    INSUFFICIENT_CASH, INSUFFICIENT_SHARES, UNAUTHORIZED, UNKNOWN_ACCOUNT, UNKNOWN_EXECUTION, INVALID_INPUT,
    AMOUNT_OVERFLOW, BALANCE_OVERFLOW,
    /** Never recorded: same action key, same digest. */ PRIOR_RESULT,
    /** Never recorded: same action key, different digest. */ ACTION_CONFLICT,
    /** Never recorded: a pinned per-class budget is exhausted. */ BUDGET_EXCEEDED;

    /** True for outcomes that can never appear in a decision or action record. */
    val neverRecorded: Boolean get() = this == PRIOR_RESULT || this == ACTION_CONFLICT || this == BUDGET_EXCEEDED
}

internal sealed interface FinanceInput {
    val action: ActionKey

    data class Capture(override val action: ActionKey, val execution: ExecutionKey, val buyer: AccountId, val seller: AccountId,
                       val quantity: Long, val priceNanos: Long) : FinanceInput {
        private var digestMemo: String? = null
        override fun digest(): String = digestMemo ?: super.digest().also { digestMemo = it }
    }
    /**
     * Capacity profile, decision mode `capture-settle-v1` (docs/work/CALCIFY_CAPACITY_PROFILE.md): a captured execution AND its first settlement attempt (authority
     * OPERATOR, attempt 1, both implied) as one input, hence one decision, one state commitment and one chain link per booked trade. The fields are [Capture]'s. Only the
     * capture source produces it; the finite profile and the CONTROL role refuse it.
     */
    data class CaptureSettle(override val action: ActionKey, val execution: ExecutionKey, val buyer: AccountId, val seller: AccountId,
                             val quantity: Long, val priceNanos: Long) : FinanceInput {
        private var digestMemo: String? = null
        override fun digest(): String = digestMemo ?: super.digest().also { digestMemo = it }
    }
    data class Fund(override val action: ActionKey, val authority: Authority, val account: AccountId, val asset: AssetId,
                    val amount: Long) : FinanceInput {
        private var digestMemo: String? = null
        override fun digest(): String = digestMemo ?: super.digest().also { digestMemo = it }
    }
    /** Repair is an operator control (D4): [authority] must be OPERATOR, so trading principals cannot burn the attempt budget.
     *  [attempt] is a caller-chosen ordinal; each (execution, attempt) is evaluated at most once. */
    data class Settle(override val action: ActionKey, val authority: Authority, val execution: ExecutionKey, val attempt: Int) : FinanceInput {
        private var digestMemo: String? = null
        override fun digest(): String = digestMemo ?: super.digest().also { digestMemo = it }
    }

    fun canonical(): ByteArray = Canon.bytes { writeCanonical(this) }
    private fun writeCanonical(w: Canon.Writer) = w.run {
        str("calcify-finance-input-v1")
        str(action.namespace); str(action.domain); str(action.actionId)
        when (val i = this@FinanceInput) {
            is Capture -> { str("CAPTURE"); execution(i.execution); str(i.buyer.value); str(i.seller.value); long(i.quantity); long(i.priceNanos) }
            is CaptureSettle -> { str("CAPTURE_SETTLE"); execution(i.execution); str(i.buyer.value); str(i.seller.value); long(i.quantity); long(i.priceNanos) }
            is Fund -> { str("FUND"); str(i.authority.name); str(i.account.value); str(i.asset.value); long(i.amount) }
            is Settle -> { str("SETTLE"); str(i.authority.name); execution(i.execution); int(i.attempt) }
        }
    }
    /** SHA-256 of [canonical] as 64 hex. Inputs are immutable, so each variant remembers its digest: a lane computes it once to decide, a projector verifies it twice (decode, apply). */
    fun digest(): String = Canon.digestHex { writeCanonical(this) }
}

/** Length-framed canonical encoding; never delimiter concatenation. */
internal object Canon {
    /** Canonical ordering: strings compare as unsigned UTF-8 bytes (equal to code point order), never UTF-16 code units, so any
     *  implementation that sorts bytes or code points reproduces the same digest. Non-string selectors compare naturally. */
    fun <T> utf8Order(vararg selectors: (T) -> Comparable<*>): Comparator<T> = Comparator { a, b ->
        for (selector in selectors) {
            val x = selector(a); val y = selector(b)
            val c = if (x is String && y is String) java.util.Arrays.compareUnsigned(strictUtf8(x), strictUtf8(y))
            else @Suppress("UNCHECKED_CAST") (x as Comparable<Any>).compareTo(y)
            if (c != 0) return@Comparator c
        }
        0
    }
    /** Appends the canonical encoding (big-endian ints and longs, length-framed strict UTF-8) to one growing array. */
    class Writer internal constructor() {
        private var buffer = ByteArray(160)
        private var size = 0
        private fun room(extra: Int) {
            val needed = Math.addExact(size, extra)
            if (needed > buffer.size) buffer = buffer.copyOf(maxOf(if (buffer.size <= Int.MAX_VALUE / 2) buffer.size * 2 else Int.MAX_VALUE, needed))
        }
        fun str(v: String) {
            val length = v.length
            var ascii = true
            for (i in 0 until length) if (v[i].code >= 0x80) { ascii = false; break }
            if (ascii) {
                room(4 + length); putInt(length)
                for (i in 0 until length) buffer[size++] = v[i].code.toByte()
            } else {
                val b = strictUtf8(v); room(4 + b.size); putInt(b.size)
                System.arraycopy(b, 0, buffer, size, b.size); size += b.size
            }
        }
        private fun putInt(v: Int) { buffer[size++] = (v ushr 24).toByte(); buffer[size++] = (v ushr 16).toByte(); buffer[size++] = (v ushr 8).toByte(); buffer[size++] = v.toByte() }
        fun long(v: Long) { room(8); putInt((v ushr 32).toInt()); putInt(v.toInt()) }
        fun int(v: Int) { room(4); putInt(v) }
        fun execution(k: ExecutionKey) {
            val frame = k.cachedFrame()
            if (frame != null) raw(frame) else { str(k.runId); str(k.venueSessionId); str(k.instrumentId); str(k.executionId) }
        }
        fun leg(l: Leg) { str(l.account.value); str(l.asset.value); long(l.amount) }
        /** Appends bytes that are already canonical (a cached prefix or a field encoded earlier), verbatim. */
        fun raw(bytes: ByteArray) { room(bytes.size); System.arraycopy(bytes, 0, buffer, size, bytes.size); size += bytes.size }
        internal fun toByteArray(): ByteArray = buffer.copyOf(size)
        internal fun reset() { size = 0; if (buffer.size > 1 shl 16) buffer = ByteArray(160) }
        internal fun updateDigest(md: MessageDigest) = md.update(buffer, 0, size)
    }

    /** One thread's scratch: a reusable [Writer] (so an encoding costs only its result array), a digest and a 32 byte output. A lane is one thread. */
    internal class Scratch { val writer = Writer(); var busy = false; val md: MessageDigest = MessageDigest.getInstance("SHA-256"); val out = ByteArray(32) }
    private val scratch = ThreadLocal.withInitial { Scratch() }
    /** The calling thread's reusable digest, reset. */
    internal fun digest(): MessageDigest = scratch.get().md.also { it.reset() }

    /** Runs [block] on the thread's reusable writer; a nested call (a block that itself encodes) gets a fresh one, never the busy one. */
    private inline fun <T> withWriter(block: Writer.() -> Unit, finish: (Scratch, Writer) -> T): T {
        val s = scratch.get()
        if (s.busy) { val w = Writer(); w.block(); return finish(s, w) }
        s.busy = true
        val w = s.writer; w.reset()
        try { w.block(); return finish(s, w) } finally { s.busy = false }
    }
    fun bytes(block: Writer.() -> Unit): ByteArray = withWriter(block) { _, w -> w.toByteArray() }
    /** `SHA-256` of the canonical encoding [block] writes, as 64 lowercase hex, without materialising the encoding. */
    fun digestHex(block: Writer.() -> Unit): String = withWriter(block) { s, w -> s.md.reset(); w.updateDigest(s.md); s.md.digest(s.out, 0, 32); HEX.formatHex(s.out) }
    /** `SHA-256` of the canonical encoding [block] writes followed by [tail] (the state commitment hashes its framed header, then the trie digest), as 32 bytes. */
    fun digestBytes(tail: ByteArray? = null, block: Writer.() -> Unit): ByteArray = withWriter(block) { s, w -> s.md.reset(); w.updateDigest(s.md); if (tail != null) s.md.update(tail); s.md.digest() }
    private val HEX = HexFormat.of()
    fun sha(bytes: ByteArray): String = HEX.formatHex(digest().digest(bytes))
}

internal data class Captured(val buyer: AccountId, val seller: AccountId, val quantity: Long, val priceNanos: Long)
internal enum class ObligationStatus { PENDING, SETTLED }
internal data class Obligation(val status: ObligationStatus, val cashResidual: Long, val shareResidual: Long)
internal data class ActionRecord(val digest: String, val disposition: Disposition) {
    init { require(!disposition.neverRecorded) { "non-recorded disposition in action record" } }
}

/** Immutable. Entries are keyed per entity so a persistence adapter can store them individually (packet constraint C1). */
internal data class FinanceState(
    val positions: Map<Pair<AccountId, AssetId>, Long>,
    val executions: Map<ExecutionKey, Captured>,
    val obligations: Map<ExecutionKey, Obligation>,
    val attempts: Map<Pair<ExecutionKey, Int>, Disposition>,
    val actions: Map<ActionKey, ActionRecord>,
    val sequence: Long,
    val chain: String,
    /** Recorded operator controls (funding); bounded separately so refusals and trades cannot starve it. */
    val controls: Int = 0,
)

internal data class Decision(
    val sequence: Long, val action: ActionKey, val inputDigest: String, val disposition: Disposition,
    val legs: List<Leg>, val priorChain: String,
    /** SHA-256 of the state this decision produced, excluding the chain head (which is this decision's checksum). Commits state to the chain. */
    val stateDigest: String, val checksum: String,
) {
    init { require(!disposition.neverRecorded) { "non-recorded disposition in decision" } }
}

internal data class Outcome(val state: FinanceState, val disposition: Disposition, val decision: Decision?, val originalDisposition: Disposition? = null)

internal data class FinanceCheckpoint(val state: FinanceState, val checksum: String)

/**
 * Per-class budgets (O3a review F2). Only outcomes that change state are recorded and counted; malformed, unauthorized,
 * unknown and no-op outcomes are refused without consuming any class, so they cannot starve funding or repair.
 * Contract reading: 128 source executions + 160 settle attempts + 8 controls = 296 records, inside the 320-record history bound.
 */
internal data class FinanceConfig(
    val accounts: Set<AccountId>, val cashAsset: AssetId, val securityAsset: AssetId, val openingAccount: AccountId,
    val maxObligations: Int = 256, val maxCaptures: Int = 128, val maxSettleAttempts: Int = 160, val maxControls: Int = 8,
) {
    init {
        require(accounts.size in 2..16) { "2..16 accounts" }
        require(openingAccount !in accounts) { "opening account must be distinct" }
        require(cashAsset != securityAsset) { "distinct assets" }
        require(maxObligations in 1..256 && maxCaptures in 1..128 && maxSettleAttempts in 1..160 && maxControls in 1..8) { "budgets exceed pinned limits" }
        require(maxCaptures <= maxObligations) { "captures cannot exceed obligations" }
    }

    /** Binds every history and checkpoint to the configuration it was produced under. */
    fun digest(): String = Canon.sha(Canon.bytes {
        str("calcify-finance-config-v1")
        val sorted = accounts.map { it.value }.sortedWith(Canon.utf8Order<String>({ it })); int(sorted.size); sorted.forEach { str(it) }
        str(cashAsset.value); str(securityAsset.value); str(openingAccount.value)
        int(maxObligations); int(maxCaptures); int(maxSettleAttempts); int(maxControls)
    })
}
