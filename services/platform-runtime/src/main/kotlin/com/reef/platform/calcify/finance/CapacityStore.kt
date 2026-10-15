package com.reef.platform.calcify.finance

import com.reef.platform.calcify.LifecycleKv
import java.nio.charset.StandardCharsets
import java.util.HexFormat

/**
 * Persistence of a [CapacityLedger] as per-entity keys (design stage S4a, constraint C1: no whole-state value, no history list).
 *
 * Keys: `meta` (binding digest, configuration digest, sequence, chain head, controls), `head` (the last recorded decision, `FinanceDecisionV1` bytes, absent at genesis) and
 * `e/<tag>/<hex of the framed key fields>` with the framed value fields behind a version frame, one entity each. The writes for one decision are the entities
 * the ledger reports in [CapacityLedger.lastWrites] plus `head` and `meta`; the caller applies them together inside its transaction.
 *
 * [restore] rebuilds the ledger from an entity scan and accepts it only if it is the state the head decision committed to: the rebuilt trie's `C(S)` equals the
 * head's `stateDigest`, the head's checksum recomputes and equals the stored chain head, and the sequence, controls and counts agree. That is the synchronous
 * profile's restore; the lagged profile's ring and replay (design 5.6) are a separate stage.
 */
internal object CapacityStore {
    const val ENTITY_VERSION = "calcify-capacity-entity-v1"
    const val META_VERSION = "calcify-capacity-meta-v1"
    const val META = "meta"
    const val HEAD = "head"
    private val hex = HexFormat.of()

    /** The version frame every entity value starts with: constant, so encoded once. */
    private val ENTITY_PREFIX = Canon.bytes { str(ENTITY_VERSION) }

    private val HEX_DIGITS = "0123456789abcdef".toByteArray(StandardCharsets.ISO_8859_1)

    /** `e/<tag>/<lowercase hex of the framed key fields>`: one pass into one Latin-1 array, then one string (the tag is one ASCII character). */
    fun entityKey(e: StateCommitment.Entity): String {
        val key = e.keyBytes; val tag = e.tag
        if (tag.length != 1 || tag[0].code >= 0x80) return "e/$tag/${hex.formatHex(key)}"
        val out = ByteArray(4 + 2 * key.size)
        out[0] = 'e'.code.toByte(); out[1] = '/'.code.toByte(); out[2] = tag[0].code.toByte(); out[3] = '/'.code.toByte()
        var at = 4
        for (b in key) { val v = b.toInt() and 0xff; out[at++] = HEX_DIGITS[v ushr 4]; out[at++] = HEX_DIGITS[v and 15] }
        return String(out, StandardCharsets.ISO_8859_1)
    }
    fun entityValue(e: StateCommitment.Entity): ByteArray {
        val out = ByteArray(ENTITY_PREFIX.size + e.valueBytes.size)
        System.arraycopy(ENTITY_PREFIX, 0, out, 0, ENTITY_PREFIX.size); System.arraycopy(e.valueBytes, 0, out, ENTITY_PREFIX.size, e.valueBytes.size)
        return out
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
        val out = LinkedHashMap<String, ByteArray>()
        for (e in ledger.lastWrites) out[entityKey(e)] = entityValue(e)
        out[HEAD] = FinanceWire.encode(decision)
        out[META] = Meta(bindingDigest, ledger.config.digest(), ledger.sequence, ledger.chain, ledger.controls).bytes()
        return out
    }

    private fun decodeEntity(key: String, value: ByteArray): StateCommitment.Entity {
        val parts = key.split("/"); require(parts.size == 3 && parts[0] == "e" && parts[1].length == 1) { "malformed entity key" }
        val keyBytes = try { hex.parseHex(parts[2]) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("malformed entity key", e) }
        val version = ENTITY_PREFIX
        require(value.size >= version.size && value.copyOfRange(0, version.size).contentEquals(version)) { "unknown entity version" }
        val entity = StateCommitment.Entity(parts[1], keyBytes, value.copyOfRange(version.size, value.size))
        require(entityKey(entity) == key) { "non-canonical entity key" }
        return entity
    }

    /** The restored ledger, or null for an empty store (genesis). Refuses anything that is not exactly the state the head decision committed to. */
    fun restore(config: CapacityConfig, kv: LifecycleKv, bindingDigest: String): CapacityLedger? {
        val metaBytes = kv.get(META) ?: run { require(kv.scan("").isEmpty()) { "capacity store has entries but no meta" }; return null }
        val meta = Meta.parse(metaBytes)
        require(meta.bindingDigest == bindingDigest) { "capacity store belongs to a different binding" }
        val head = kv.get(HEAD)?.let { FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(it)) }
        require((head == null) == (meta.sequence == 0L)) { "head decision presence differs from sequence" }
        require(meta.configDigest == config.digest()) { "capacity store was written under a different configuration" }
        val ledger = CapacityLedger.restore(config, kv.scan("e/").map { (k, v) -> decodeEntity(k, v) }, meta.sequence, meta.controls, meta.chain)
        if (head == null) {
            require(meta.chain == ledger.genesisChain && ledger.entityCount == 0) { "genesis store is not empty or on another genesis" }
        } else {
            require(head.sequence == meta.sequence && head.checksum == meta.chain) { "head decision is not the stored chain head" }
            require(head.checksum == CapacityLedger.checksumOf(head.sequence, head.action, head.inputDigest, head.disposition, head.legs, head.priorChain, head.stateDigest)) { "head decision checksum does not recompute" }
            require(head.stateDigest == ledger.commitment()) { "the stored entities are not the state the head decision committed to" }
        }
        return ledger
    }
}
