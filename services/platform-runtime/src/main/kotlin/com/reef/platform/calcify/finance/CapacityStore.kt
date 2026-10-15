package com.reef.platform.calcify.finance

import com.reef.platform.calcify.LifecycleKv
import java.nio.charset.StandardCharsets
import java.util.HexFormat

/**
 * Persistence of a [CapacityLedger] as per-entity keys (design stage S4a, constraint C1: no whole-state value, no history list).
 *
 * Keys (layout v2, named by the meta version): `meta` (binding digest, configuration digest, sequence, chain head, controls), `head` (the last recorded decision,
 * `FinanceDecisionV1` bytes, absent at genesis), `e/<tag>/<hex of the framed key fields>` with the framed value fields, one entity each (positions `P`, actions `K`, and
 * settlement attempts `A` after the first), and `x/<hex of the execution key>`, ONE record per execution holding its captured economics `X`, its obligation `O` and its
 * first settlement attempt `A` (attempt 1; absent until it is made). The commitment is unchanged: restore splits an `x/` record back into the same `X`, `O` and `A`
 * entities, so every leaf and every `stateDigest` is what the entity-per-key layout (v1) gave. The writes for one record are the entities the ledger reports in
 * [CapacityLedger.lastWrites] folded by [storeWrites] plus, at a checkpoint, `head` and `meta`; the caller applies them together inside its transaction.
 *
 * [restore] rebuilds the ledger from an entity scan and accepts it only if it is the state the head decision committed to: the rebuilt trie's `C(S)` equals the
 * head's `stateDigest`, the head's checksum recomputes and equals the stored chain head, and the sequence, controls and counts agree. That is the synchronous
 * profile's restore; the lagged profile's ring and replay (design 5.6) are a separate stage.
 */
internal object CapacityStore {
    const val META_VERSION = "calcify-capacity-meta-v2"
    const val META = "meta"
    const val HEAD = "head"
    private val hex = HexFormat.of()

    fun entityKey(e: StateCommitment.Entity) = "e/${e.tag}/${hex.formatHex(e.keyBytes)}"
    private fun execKey(keyBytes: ByteArray) = "x/${hex.formatHex(keyBytes)}"

    /**
     * Folds the entities of one record (keyed by [entityKey], the last write per key) into store puts: positions, actions and later attempts as one put each, and every
     * touched execution as ONE `x/` put built from its captured, obligation and first-attempt entities in this record, completed from [ledger] for the parts this record
     * did not touch (a settlement in a later record than its capture).
     */
    fun storeWrites(ledger: CapacityLedger, entities: Map<String, StateCommitment.Entity>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>(entities.size + 2)
        var execs: LinkedHashMap<String, ByteArray>? = null          // hex of the execution key bytes -> its key bytes
        for ((key, e) in entities) {
            val exec = when (e.tag) {
                "X", "O" -> e.keyBytes
                "A" -> if (firstAttempt(e.keyBytes)) e.keyBytes.copyOfRange(0, e.keyBytes.size - 4) else null
                else -> null
            }
            if (exec == null) { out[key] = e.valueBytes; continue }
            val ex = execs ?: LinkedHashMap<String, ByteArray>().also { execs = it }
            ex.putIfAbsent(hex.formatHex(exec), exec)
        }
        execs?.forEach { (h, execBytes) ->
            val touchedX = entities["e/X/$h"]; val touchedO = entities["e/O/$h"]; val touchedA = entities["e/A/$h$ATTEMPT_ONE_HEX"]
            val complete = if (touchedX != null && touchedO != null && (touchedA != null || !ledger.hasFirstAttempt(execBytes))) null else ledger.executionParts(execBytes)
            out["x/$h"] = execRecord(touchedX?.valueBytes ?: complete!!.first, touchedO?.valueBytes ?: complete!!.second, touchedA?.valueBytes ?: complete?.third)
        }
        return out
    }

    private const val ATTEMPT_ONE_HEX = "00000001"
    private fun firstAttempt(attemptKeyBytes: ByteArray): Boolean {
        val n = attemptKeyBytes.size
        return n > 4 && attemptKeyBytes[n - 4].toInt() == 0 && attemptKeyBytes[n - 3].toInt() == 0 && attemptKeyBytes[n - 2].toInt() == 0 && attemptKeyBytes[n - 1].toInt() == 1
    }

    /** `x/` value: the three entity values, each behind its int32 length; a zero length means "no first attempt yet" (an attempt value is never empty). */
    private fun execRecord(x: ByteArray, o: ByteArray, a: ByteArray?): ByteArray {
        val b = java.nio.ByteBuffer.allocate(12 + x.size + o.size + (a?.size ?: 0))
        b.putInt(x.size).put(x).putInt(o.size).put(o).putInt(a?.size ?: 0); if (a != null) b.put(a)
        return b.array()
    }

    data class Meta(val bindingDigest: String, val configDigest: String, val sequence: Long, val chain: String, val controls: Int) {
        fun bytes() = Canon.bytes { str(META_VERSION); str(bindingDigest); str(configDigest); long(sequence); str(chain); int(controls) }
        companion object {
            fun parse(bytes: ByteArray): Meta {
                val b = java.nio.ByteBuffer.wrap(bytes)
                fun text(): String { require(b.remaining() >= 4) { "corrupt meta" }; val n = b.int; require(n in 0..4096 && b.remaining() >= n) { "corrupt meta" }; return ByteArray(n).also { b.get(it) }.toString(StandardCharsets.UTF_8) }
                require(text() == META_VERSION) { "unknown meta version" }
                val binding = text(); val config = text()
                val m = Meta(binding, config, b.also { require(it.remaining() >= 8) { "corrupt meta" } }.long, text(), b.also { require(it.remaining() >= 4) { "corrupt meta" } }.int)
                require(!b.hasRemaining()) { "trailing meta bytes" }
                return m
            }
        }
    }

    /** The store writes implied by the decision [ledger] just recorded: its entities (last write per key), the head decision and the meta. */
    fun writes(ledger: CapacityLedger, decision: Decision, bindingDigest: String): Map<String, ByteArray> {
        require(decision.sequence == ledger.sequence && decision.checksum == ledger.chain) { "the decision is not the ledger's latest" }
        val entities = LinkedHashMap<String, StateCommitment.Entity>()
        for (e in ledger.lastWrites) entities[entityKey(e)] = e
        val out = LinkedHashMap<String, ByteArray>(storeWrites(ledger, entities))
        out[HEAD] = FinanceWire.toProto(decision).toByteArray()
        out[META] = Meta(bindingDigest, ledger.config.digest(), ledger.sequence, ledger.chain, ledger.controls).bytes()
        return out
    }

    private fun decodeEntity(key: String, value: ByteArray): StateCommitment.Entity {
        val parts = key.split("/"); require(parts.size == 3 && parts[0] == "e" && parts[1].length == 1 && parts[1] !in setOf("X", "O")) { "malformed entity key" }
        val keyBytes = parseHex(parts[2])
        val entity = StateCommitment.Entity(parts[1], keyBytes, value)
        require(entityKey(entity) == key) { "non-canonical entity key" }
        return entity
    }

    private fun parseHex(text: String): ByteArray = try { hex.parseHex(text) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("malformed entity key", e) }

    /** Splits an `x/` record into its `X`, `O` and (when present) first-attempt `A` entities. */
    private fun decodeExecution(key: String, value: ByteArray): List<StateCommitment.Entity> {
        require(key.startsWith("x/")) { "malformed execution key" }
        val execBytes = parseHex(key.substring(2)); require(execKey(execBytes) == key) { "non-canonical execution key" }
        val b = java.nio.ByteBuffer.wrap(value)
        fun part(): ByteArray { require(b.remaining() >= 4) { "corrupt execution record" }; val n = b.int; require(n in 0..4096 && b.remaining() >= n) { "corrupt execution record" }; return ByteArray(n).also { b.get(it) } }
        val x = part(); val o = part(); val a = part(); require(!b.hasRemaining()) { "trailing execution record bytes" }
        require(x.isNotEmpty() && o.isNotEmpty()) { "execution record without its economics or obligation" }
        val out = arrayListOf(StateCommitment.Entity("X", execBytes, x), StateCommitment.Entity("O", execBytes, o))
        if (a.isNotEmpty()) out += StateCommitment.Entity("A", execBytes + byteArrayOf(0, 0, 0, 1), a)
        return out
    }

    /**
     * The restored ledger, or null for an empty store (genesis). Refuses anything that is not exactly the state the head decision committed to.
     *
     * The entities are written with every record; `head` and `meta` only at checkpoints (see [CapacityProcessorConfig.checkpointEvery]), so the store can hold more
     * decisions than its meta says. The sequence and the control count are therefore derived from the entities themselves (one action record per decision, one funded
     * action per control), and:
     *  - when the derived sequence equals the meta's, the stored head and chain are used (the layout with a checkpoint at every record);
     *  - when the entities are ahead of the meta (a store between checkpoints), the head decision comes from the published decision log, [logHead], which the same
     *    transaction extended: its checksum becomes the chain head and its `stateDigest` must equal the commitment of the rebuilt entities. That needs the log head, so a
     *    store ahead of its checkpoint is refused when [logHeadKnown] is false;
     *  - when the entities are behind the meta, the store lost writes and is refused.
     * When [logHeadKnown] is true the result must also be exactly the head of the log ([logHead] null: an empty log).
     */
    fun restore(config: CapacityConfig, kv: LifecycleKv, bindingDigest: String, logHead: Decision? = null, logHeadKnown: Boolean = false): CapacityLedger? {
        val metaBytes = kv.get(META) ?: run { require(kv.scan("").isEmpty()) { "capacity store has entries but no meta" }; return null }
        val meta = Meta.parse(metaBytes)
        require(meta.bindingDigest == bindingDigest) { "capacity store belongs to a different binding" }
        val storedHead = kv.get(HEAD)?.let { FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(it)) }
        require((storedHead == null) == (meta.sequence == 0L)) { "head decision presence differs from sequence" }
        require(meta.configDigest == config.digest()) { "capacity store was written under a different configuration" }
        val ledger = CapacityLedger.restore(config, kv.scan("e/").map { (k, v) -> decodeEntity(k, v) } + kv.scan("x/").flatMap { (k, v) -> decodeExecution(k, v) }, meta.sequence, meta.controls, meta.chain, allowAhead = true)
        val head: Decision? = if (ledger.sequence == meta.sequence) { require(ledger.controls == meta.controls) { "funded action count differs from controls" }; storedHead } else {
            require(logHeadKnown && logHead != null) { "the store is ahead of its last checkpoint (${meta.sequence}) and the decision log head is not available to verify it" }
            ledger.adoptChain(logHead.checksum)
            logHead
        }
        if (head == null) {
            require(meta.chain == ledger.genesisChain && ledger.entityCount == 0) { "genesis store is not empty or on another genesis" }
        } else {
            require(head.sequence == ledger.sequence && head.checksum == ledger.chain) { "head decision is not the stored chain head" }
            require(head.checksum == CapacityLedger.checksumOf(head.sequence, head.action, head.inputDigest, head.disposition, head.legs, head.priorChain, head.stateDigest)) { "head decision checksum does not recompute" }
            require(head.stateDigest == ledger.commitment()) { "the stored entities are not the state the head decision committed to" }
        }
        if (logHeadKnown) require(if (logHead == null) ledger.sequence == 0L else logHead.sequence == ledger.sequence && logHead.checksum == ledger.chain) { "the stored state is not the head of the decision log" }
        return ledger
    }
}
