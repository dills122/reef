package com.reef.platform.calcify.finance

import java.security.MessageDigest
import java.util.HexFormat

/**
 * The state commitment `C(S)` of the capacity profile, as a pure function (docs/work/CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md, sections 3 and 4.1).
 * This is the **reference**: a direct transcription of the specification with a naive trie built from the sorted key set, no incrementality. The
 * incremental engine is proven equal to it by differential tests, and an independent Python encoder
 * (`scripts/dev/calcify-financial/state_commitment_reference.py`) reproduces the committed goldens byte for byte.
 *
 * Nothing here is used by the finite P3 path, whose `stateDigest` is `FinanceLedger`'s whole-state digest; the two are never mixed (the label names the
 * instantiation and is bound into the capacity profile's configuration).
 *
 * The digest is an unkeyed integrity digest of public data (section 3.5): it proves a restored state is the state a decision committed to, not that the
 * decision chain is authentic.
 */
internal object StateCommitment {
    const val LABEL = "calcify-finance-state-v2-patricia"
    private const val KEY_LABEL = "calcify-finance-key-v2"

    /** One entity of the committed state: its class tag (`P`, `X`, `O`, `A`, `K`), its framed key fields and its framed value fields. */
    class Entity(val tag: String, val keyBytes: ByteArray, val valueBytes: ByteArray)

    private val hex = HexFormat.of()
    private fun sha(vararg parts: ByteArray): ByteArray = Canon.digest().also { md -> parts.forEach { md.update(it) } }.digest()
    /** `frame(KEY_LABEL) || frame(tag)` per tag: constant, so it is encoded once instead of once per entity. */
    private val keyPrefixes = HashMap<String, ByteArray>().also { m -> for (tag in listOf("P", "X", "O", "A", "K")) m[tag] = Canon.bytes { str(KEY_LABEL); str(tag) } }
    private fun keyPrefix(tag: String): ByteArray = keyPrefixes[tag] ?: Canon.bytes { str(KEY_LABEL); str(tag) }
    private val LEAF_PREFIX = byteArrayOf(0x00); private val VALUE_PREFIX = byteArrayOf(0x01)
    private val hexDigest = Regex("^[0-9a-f]{64}$")

    /**
     * The entity set `E(S)`: one record per map entry in five classes (section 3.1). A zero-amount position is an entity like any other. An action whose
     * recorded input digest is not 64 lowercase hex is refused (the model does not validate it, so the reference must).
     */
    fun entities(state: FinanceState): List<Entity> = buildList {
        for ((k, v) in state.positions) add(Entity("P", Canon.bytes { str(k.first.value); str(k.second.value) }, Canon.bytes { long(v) }))
        for ((k, e) in state.executions) add(Entity("X", Canon.bytes { execution(k) }, Canon.bytes { str(e.buyer.value); str(e.seller.value); long(e.quantity); long(e.priceNanos) }))
        for ((k, o) in state.obligations) add(Entity("O", Canon.bytes { execution(k) }, Canon.bytes { str(o.status.name); long(o.cashResidual); long(o.shareResidual) }))
        for ((k, d) in state.attempts) add(Entity("A", Canon.bytes { execution(k.first); int(k.second) }, Canon.bytes { str(d.name) }))
        for ((k, a) in state.actions) {
            require(hexDigest.matches(a.digest)) { "action input digest is not 64 lowercase hex" }
            add(Entity("K", Canon.bytes { str(k.namespace); str(k.domain); str(k.actionId) }, Canon.bytes { str(a.digest); str(a.disposition.name) }))
        }
    }

    /** `K(e) = SHA-256(frame(KEY_LABEL) || frame(tag) || keyBytes(e))`: the 256-bit trie key; bit 0 is the most significant. */
    fun keyOf(e: Entity): ByteArray { val md = Canon.digest(); md.update(keyPrefix(e.tag)); md.update(e.keyBytes); return md.digest() }

    /** `L(e) = SHA-256(0x00 || K(e) || SHA-256(0x01 || valueBytes(e)))`. */
    fun leafOf(e: Entity, key: ByteArray = keyOf(e)): ByteArray {
        val md = Canon.digest()
        md.update(VALUE_PREFIX); md.update(e.valueBytes)
        val value = md.digest()
        md.update(LEAF_PREFIX); md.update(key); md.update(value)
        return md.digest()
    }

    /** `SHA-256(0x03)`: the trie digest of the empty set. */
    val emptyTrie: ByteArray get() = sha(byteArrayOf(0x03))

    /**
     * The trie digest `T` over `(K(e), L(e))` pairs (section 4.1): each leaf sits at the shortest unique prefix of its key; a branch stores the absolute bit
     * index (0 to 255 from the most significant bit) at which its two subtrees first differ, left child where that bit is 0;
     * `branch = SHA-256(0x02 || u16_be(bitIndex) || left || right)`; a single leaf is its own digest; the empty set is [emptyTrie]. Pairs are sorted
     * here, so the result is a pure function of the key set. Two pairs with the same key are refused, never overwritten.
     */
    fun trieRoot(pairs: List<Pair<ByteArray, ByteArray>>): ByteArray {
        if (pairs.isEmpty()) return emptyTrie
        pairs.forEach { require(it.first.size == 32 && it.second.size == 32) { "keys and leaf digests are 32 bytes" } }
        val sorted = pairs.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first, b.first) }
        for (i in 1 until sorted.size) require(!sorted[i - 1].first.contentEquals(sorted[i].first)) { "two entities have the same trie key" }
        return build(sorted, 0, sorted.size)
    }

    private fun bit(key: ByteArray, index: Int): Int = (key[index ushr 3].toInt() ushr (7 - (index and 7))) and 1

    private fun firstDifferingBit(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 32) { val x = (a[i].toInt() xor b[i].toInt()) and 0xff; if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24 }
        throw IllegalArgumentException("identical keys")
    }

    private fun build(sorted: List<Pair<ByteArray, ByteArray>>, lo: Int, hi: Int): ByteArray {
        if (hi - lo == 1) return sorted[lo].second
        val index = firstDifferingBit(sorted[lo].first, sorted[hi - 1].first)
        var mid = lo
        while (bit(sorted[mid].first, index) == 0) mid++
        val left = build(sorted, lo, mid); val right = build(sorted, mid, hi)
        return sha(byteArrayOf(0x02, (index ushr 8).toByte(), index.toByte()), left, right)
    }

    /** `C(S) = SHA-256(frame(LABEL) || int64(sequence) || int32(controls) || T)` as 64 lowercase hex, the decision's `stateDigest` field. */
    fun commitment(sequence: Long, controls: Int, trie: ByteArray): String {
        require(trie.size == 32) { "T is 32 bytes" }
        return hex.formatHex(Canon.digestBytes(trie) { str(LABEL); long(sequence); int(controls) })
    }

    /** The reference: `C(S)` of a state, built from scratch. [keyOf] is injectable so crowded and degenerate key sets can be tested. */
    fun reference(entities: List<Entity>, sequence: Long, controls: Int, key: (Entity) -> ByteArray = ::keyOf): String =
        commitment(sequence, controls, trieRoot(entities.map { e -> val k = key(e); k to leafOf(e, k) }))

    fun reference(state: FinanceState): String = reference(entities(state), state.sequence, state.controls)
}
