package com.reef.platform.calcify.finance

import com.reef.platform.calcify.LifecycleKv
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

/**
 * S2-A2: the per-entity persistence of the O3a ledger state plus the input history (spec section 8; constraint C1: no whole-state value).
 *
 * Keys (all component text is hex of its UTF-8 so arbitrary identifiers can never collide or break the prefix scan):
 *   `meta`                               binding digest, history position, entry chain head, controls
 *   `head`                               the last recorded decision (FinanceDecisionV1 bytes), absent at genesis
 *   `position/<acct>/<asset>`, `execution/<key>`, `obligation/<key>`, `attempt/<key>/<n>`, `action/<ns>/<dom>/<id>`   one entity each
 *   `h/<20-digit position>`              one history entry per ledger input (INPUT) or per consumed record without an input (NO_INPUT)
 * Every value is versioned bytes. All writes for one record are produced as one map and applied together by the caller, inside the
 * surrounding Streams transaction.
 */
internal object FinanceStore {
    const val ENTITY_VERSION = "calcify-finance-entity-v1"
    const val META_VERSION = "calcify-finance-meta-v1"
    const val ENTRY_VERSION = "calcify-finance-entry-record-v1"
    /** Entries <= 128 captures + 16 NO_INPUT + 168 CONTROL writes; inside the packet's 320 envelope (spec section 8). */
    const val MAX_HISTORY_ENTRIES = 312

    private fun hex(s: String) = java.util.HexFormat.of().formatHex(s.toByteArray(StandardCharsets.UTF_8))
    private fun unhex(s: String) = String(java.util.HexFormat.of().parseHex(s), StandardCharsets.UTF_8)
    private fun ek(k: ExecutionKey) = listOf(k.runId, k.venueSessionId, k.instrumentId, k.executionId).joinToString("/") { hex(it) }
    fun positionKey(a: AccountId, s: AssetId) = "position/${hex(a.value)}/${hex(s.value)}"
    fun executionKey(k: ExecutionKey) = "execution/${ek(k)}"
    fun obligationKey(k: ExecutionKey) = "obligation/${ek(k)}"
    fun attemptKey(k: ExecutionKey, n: Int) = "attempt/${ek(k)}/${n.toString().padStart(10, '0')}"
    fun actionKey(k: ActionKey) = "action/${hex(k.namespace)}/${hex(k.domain)}/${hex(k.actionId)}"
    fun historyKey(position: Long) = "h/${position.toString().padStart(20, '0')}"
    const val META = "meta"
    const val HEAD = "head"

    // ---- entity values ----
    private fun <T> read(bytes: ByteArray, version: String, body: DataInputStream.() -> T): T {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        fun str(): String { val n = input.readInt(); require(n in 0..4096) { "corrupt frame" }; return String(input.readNBytes(n).also { require(it.size == n) }, StandardCharsets.UTF_8) }
        try {
            require(str() == version) { "unknown entity version" }
            val result = input.body()
            require(input.available() == 0) { "trailing entity bytes" }
            return result
        } catch (e: java.io.IOException) { throw IllegalArgumentException("corrupt entity bytes", e) }
    }
    private fun DataInputStream.text(): String { val n = readInt(); require(n in 0..4096) { "corrupt frame" }; val b = readNBytes(n); require(b.size == n); return String(b, StandardCharsets.UTF_8) }
    private fun execution(r: DataInputStream) = ExecutionKey(r.text(), r.text(), r.text(), r.text())

    private fun encodePosition(v: Long) = Canon.bytes { str(ENTITY_VERSION); long(v) }
    private fun encodeExecution(c: Captured) = Canon.bytes { str(ENTITY_VERSION); str(c.buyer.value); str(c.seller.value); long(c.quantity); long(c.priceNanos) }
    private fun encodeObligation(o: Obligation) = Canon.bytes { str(ENTITY_VERSION); str(o.status.name); long(o.cashResidual); long(o.shareResidual) }
    private fun encodeAttempt(d: Disposition) = Canon.bytes { str(ENTITY_VERSION); str(d.name) }
    private fun encodeAction(a: ActionRecord) = Canon.bytes { str(ENTITY_VERSION); str(a.digest); str(a.disposition.name) }

    data class Meta(val bindingDigest: String, val historyPosition: Long, val entryChainHead: String, val sequence: Long, val chain: String, val controls: Int) {
        fun bytes() = Canon.bytes { str(META_VERSION); str(bindingDigest); long(historyPosition); str(entryChainHead); long(sequence); str(chain); int(controls) }
        companion object {
            fun parse(bytes: ByteArray) = read(bytes, META_VERSION) { Meta(text(), readLong(), text(), readLong(), text(), readInt()) }
        }
    }

    /** Entity and head/meta writes implied by moving from [before] to [after]; entities never disappear, so a diff is complete. */
    fun entityWrites(before: FinanceState, after: FinanceState): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for ((k, v) in after.positions) if (before.positions[k] != v) out[positionKey(k.first, k.second)] = encodePosition(v)
        for ((k, v) in after.executions) if (before.executions[k] != v) out[executionKey(k)] = encodeExecution(v)
        for ((k, v) in after.obligations) if (before.obligations[k] != v) out[obligationKey(k)] = encodeObligation(v)
        for ((k, v) in after.attempts) if (before.attempts[k] != v) out[attemptKey(k.first, k.second)] = encodeAttempt(v)
        for ((k, v) in after.actions) if (before.actions[k] != v) out[actionKey(k)] = encodeAction(v)
        require(before.positions.keys.all { it in after.positions } && before.executions.keys.all { it in after.executions } &&
            before.obligations.keys.all { it in after.obligations } && before.attempts.keys.all { it in after.attempts } &&
            before.actions.keys.all { it in after.actions }) { "ledger entities never disappear" }
        return out
    }

    /** Assembles the ledger state from entity keys. Verification against the head decision and history is [verifyAndRestore]. */
    fun assemble(kv: LifecycleKv, meta: Meta): FinanceState {
        val positions = kv.scan("position/").associate { (key, value) ->
            val (a, s) = key.removePrefix("position/").split("/")
            (AccountId(unhex(a)) to AssetId(unhex(s))) to read(value, ENTITY_VERSION) { readLong() }
        }
        fun key(parts: List<String>) = ExecutionKey(unhex(parts[0]), unhex(parts[1]), unhex(parts[2]), unhex(parts[3]))
        val executions = kv.scan("execution/").associate { (k, v) -> key(k.removePrefix("execution/").split("/")) to read(v, ENTITY_VERSION) { Captured(AccountId(text()), AccountId(text()), readLong(), readLong()) } }
        val obligations = kv.scan("obligation/").associate { (k, v) -> key(k.removePrefix("obligation/").split("/")) to read(v, ENTITY_VERSION) { Obligation(ObligationStatus.valueOf(text()), readLong(), readLong()) } }
        val attempts = kv.scan("attempt/").associate { (k, v) ->
            val parts = k.removePrefix("attempt/").split("/")
            (key(parts.subList(0, 4)) to parts[4].toInt()) to read(v, ENTITY_VERSION) { Disposition.valueOf(text()) }
        }
        val actions = kv.scan("action/").associate { (k, v) ->
            val (ns, dom, id) = k.removePrefix("action/").split("/")
            ActionKey(unhex(ns), unhex(dom), unhex(id)) to read(v, ENTITY_VERSION) { ActionRecord(text(), Disposition.valueOf(text())) }
        }
        return FinanceState(positions, executions, obligations, attempts, actions, meta.sequence, meta.chain, meta.controls)
    }

    // ---- history entries ----
    enum class EntryKind { INPUT, NO_INPUT }
    data class Entry(val position: Long, val sourceRole: String, val sourcePartition: Int, val sourceOffset: Long, val memberOrdinal: Int, val kind: EntryKind,
                     val inputDigest: String, val outcome: String, val decisionChecksum: String, val prevEntryChain: String, val entryChain: String) {
        fun bytes() = Canon.bytes {
            str(ENTRY_VERSION); long(position); str(sourceRole); int(sourcePartition); long(sourceOffset); int(memberOrdinal); str(kind.name)
            str(inputDigest); str(outcome); str(decisionChecksum); str(prevEntryChain); str(entryChain)
        }
        companion object {
            fun parse(bytes: ByteArray) = read(bytes, ENTRY_VERSION) {
                Entry(readLong(), text(), readInt(), readLong(), readInt(), EntryKind.valueOf(text()), text(), text(), text(), text(), text())
            }
        }
    }

    fun genesisEntryChain(ledger: FinanceLedger) = Canon.sha(Canon.bytes { str("calcify-finance-history-genesis-v1"); str(ledger.genesisChain) })

    /** The spec's canonical entry chain; independent of the record encoding above. */
    fun entryChain(prev: String, position: Long, role: String, partition: Int, offset: Long, ordinal: Int, kind: EntryKind, inputDigest: String, outcome: String, decisionChecksum: String) =
        Canon.sha(Canon.bytes {
            str("calcify-finance-history-entry-v1"); str(prev); long(position); str(role); int(partition); long(offset); int(ordinal)
            str(kind.name); str(inputDigest); str(outcome); str(decisionChecksum)
        })

    fun entries(kv: LifecycleKv): List<Entry> = kv.scan("h/").map { Entry.parse(it.second) }

    /** Once-per-restore history verification: contiguous positions, chain from genesis, recorded-entry count against the ledger sequence. */
    fun verifyHistory(ledger: FinanceLedger, entries: List<Entry>, meta: Meta, head: Decision?) {
        require(entries.size.toLong() == meta.historyPosition && entries.size <= MAX_HISTORY_ENTRIES) { "history length differs from meta" }
        var prev = genesisEntryChain(ledger)
        var recorded = 0L
        var lastChecksum = ""
        entries.forEachIndexed { i, e ->
            require(e.position == i + 1L && e.prevEntryChain == prev) { "history position/link" }
            require(e.entryChain == entryChain(prev, e.position, e.sourceRole, e.sourcePartition, e.sourceOffset, e.memberOrdinal, e.kind, e.inputDigest, e.outcome, e.decisionChecksum)) { "history entry chain" }
            require(e.kind == EntryKind.INPUT || (e.outcome == "NO_INPUT" && e.decisionChecksum.isEmpty())) { "history NO_INPUT shape" }
            if (e.decisionChecksum.isNotEmpty()) { recorded++; lastChecksum = e.decisionChecksum }
            prev = e.entryChain
        }
        require(prev == meta.entryChainHead) { "history head differs from meta" }
        require(recorded == meta.sequence) { "recorded history entries differ from the ledger sequence" }
        require(lastChecksum == (head?.checksum ?: "")) { "last recorded entry is not the head decision" }
    }

    /** Full restore: meta, head decision, assembled state committed by that head, and the verified history. Returns state and meta. */
    fun verifyAndRestore(ledger: FinanceLedger, kv: LifecycleKv, bindingDigest: String): Pair<FinanceState, Meta>? {
        val metaBytes = kv.get(META) ?: run {
            require(kv.scan("").isEmpty()) { "finance store has entities but no meta" }
            return null
        }
        val meta = Meta.parse(metaBytes)
        require(meta.bindingDigest == bindingDigest) { "finance store belongs to a different binding" }
        val head = kv.get(HEAD)?.let { FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(it)) }
        val state = assemble(kv, meta)
        require((head == null) == (meta.sequence == 0L)) { "head decision presence differs from sequence" }
        ledger.restoreAt(ledger.checkpoint(state), head)
        verifyHistory(ledger, entries(kv), meta, head)
        return state to meta
    }
}
