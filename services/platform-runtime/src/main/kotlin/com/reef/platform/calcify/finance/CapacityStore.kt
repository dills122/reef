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
 *
 * An evicting lane may also restart from a lane snapshot ([writeSnapshot], written on a clean close) instead of the scan; see [restore] for when it is trusted.
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
    fun entityKey(e: StateCommitment.Entity): String = entityKey(e.tag, e.keyBytes)

    fun entityKey(tag: String, key: ByteArray): String {
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

    /** The plain restore decodes every key under `e/` and refuses an unknown class; the evicting restore reads class by class, so it proves the gaps between the five known classes are empty. */
    private fun requireNoUnknownEntityKeys(kv: LifecycleKv) {
        val classes = listOf("A", "K", "O", "P", "X")
        var from = "e/"
        for (c in classes) { require(kv.firstKeyBetween(from, "e/$c/") == null) { "malformed entity key" }; from = "e/${c}0" }   // '0' is the character after '/': the first key past every key of class c
        require(kv.firstKeyBetween(from, "e0") == null) { "malformed entity key" }      // "e0" is the first key past everything under "e/"
    }

    /** The value fields of a stored entity: the bytes behind the version frame. */
    fun valueFields(stored: ByteArray): ByteArray {
        val version = ENTITY_PREFIX
        require(stored.size >= version.size && stored.copyOfRange(0, version.size).contentEquals(version)) { "unknown entity version" }
        return stored.copyOfRange(version.size, stored.size)
    }

    fun decodeEntity(key: String, value: ByteArray): StateCommitment.Entity {
        val parts = key.split("/"); require(parts.size == 3 && parts[0] == "e" && parts[1].length == 1) { "malformed entity key" }
        val keyBytes = try { hex.parseHex(parts[2]) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("malformed entity key", e) }
        val version = ENTITY_PREFIX
        require(value.size >= version.size && value.copyOfRange(0, version.size).contentEquals(version)) { "unknown entity version" }
        val entity = StateCommitment.Entity(parts[1], keyBytes, value.copyOfRange(version.size, value.size))
        require(entityKey(entity) == key) { "non-canonical entity key" }
        return entity
    }

    const val SNAPSHOT_VERSION = "calcify-capacity-lane-snapshot-v1"
    private const val SNAPSHOT_MAGIC = 0x434c5331         // "CLS1"

    /**
     * Writes [ledger] (evicting, everything persisted) as a lane snapshot at [file]: magic, version, binding digest, configuration digest, then
     * [CapacityLedger.writeSnapshot] (sequence, controls, chain, counts, open work, filter, trie), then the SHA-256 of all of it. Written to a temporary file, forced to disk
     * and renamed over [file] atomically, so a reader sees a whole snapshot or none; a failure leaves no temporary file behind. The checksum is integrity, not authenticity:
     * the file belongs in the lane's private state directory.
     */
    fun writeSnapshot(ledger: CapacityLedger, bindingDigest: String, file: java.nio.file.Path) {
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        try {
            java.io.FileOutputStream(tmp.toFile()).use { fos ->
                val buffered = java.io.BufferedOutputStream(fos, 1 shl 20); val sum = java.security.MessageDigest.getInstance("SHA-256")
                val out = java.io.DataOutputStream(java.security.DigestOutputStream(buffered, sum))
                out.writeInt(SNAPSHOT_MAGIC)
                for (text in listOf(SNAPSHOT_VERSION, bindingDigest, ledger.config.digest())) { val b = text.toByteArray(StandardCharsets.UTF_8); out.writeInt(b.size); out.write(b) }
                ledger.writeSnapshot(out)
                out.flush(); buffered.write(sum.digest()); buffered.flush()
                fos.channel.force(true)
            }
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) { runCatching { java.nio.file.Files.deleteIfExists(tmp) }; throw e }
    }

    /**
     * The ledger a lane snapshot holds, refused unless the checksum recomputes, nothing trails it, the version, binding and configuration are this lane's, and
     * [CapacityLedger.readSnapshot]'s own checks pass. It is NOT yet bound to a store: [restore] does that.
     */
    fun readSnapshot(config: CapacityConfig, backing: EntityBacking, bindingDigest: String, file: java.nio.file.Path): CapacityLedger =
        java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file), 1 shl 20).use { buffered ->
            val sum = java.security.MessageDigest.getInstance("SHA-256")
            val input = java.io.DataInputStream(java.security.DigestInputStream(buffered, sum))
            require(input.readInt() == SNAPSHOT_MAGIC) { "not a lane snapshot" }
            fun text(): String { val n = input.readInt(); require(n in 0..256) { "corrupt lane snapshot" }; return String(ByteArray(n).also { input.readFully(it) }, StandardCharsets.UTF_8) }
            require(text() == SNAPSHOT_VERSION) { "unknown lane snapshot version" }
            require(text() == bindingDigest) { "the lane snapshot belongs to a different binding" }
            require(text() == config.digest()) { "the lane snapshot was written under a different configuration" }
            val ledger = CapacityLedger.readSnapshot(config, backing, input)
            val computed = sum.digest(); val stored = ByteArray(32); java.io.DataInputStream(buffered).readFully(stored)
            require(java.security.MessageDigest.isEqual(computed, stored)) { "lane snapshot checksum mismatch" }
            require(buffered.read() < 0) { "trailing lane snapshot bytes" }
            ledger
        }

    /**
     * The restored ledger, or null for an empty store (genesis). Refuses anything that is not exactly the state the head decision committed to.
     *
     * With [evictSettled] and a [snapshot] path, a lane snapshot there is tried first and used only if [readSnapshot] accepts it AND its sequence, chain and controls are
     * the store's `meta` AND the head decision (its checksum recomputes and is that chain) AND its trie gives the head decision's `stateDigest`: the same checks the scan
     * restore must pass, so a snapshot taken at any other point (a dirty close after an aborted transaction, a store wiped and restored from the changelog to another head,
     * a newer store after a rebalance) is never used. Any refusal or error falls back to the scan, which stays the verified path, and the file is deleted either way, so a
     * snapshot is used at most once and cannot outlive a later crash. [onSnapshot] hears null when the snapshot was used, or why it was not (absent included).
     */
    fun restore(config: CapacityConfig, kv: LifecycleKv, bindingDigest: String, evictSettled: Boolean = false, snapshot: java.nio.file.Path? = null, onSnapshot: (String?) -> Unit = {}): CapacityLedger? =
        try { restoreChecked(config, kv, bindingDigest, evictSettled, snapshot, onSnapshot) }
        finally { if (snapshot != null) runCatching { java.nio.file.Files.deleteIfExists(snapshot) } }     // also when a check before the snapshot step refuses the start: a snapshot is never kept past one start

    private fun restoreChecked(config: CapacityConfig, kv: LifecycleKv, bindingDigest: String, evictSettled: Boolean, snapshot: java.nio.file.Path?, onSnapshot: (String?) -> Unit): CapacityLedger? {
        val metaBytes = kv.get(META) ?: run {
            snapshot?.let { if (java.nio.file.Files.deleteIfExists(it)) onSnapshot("the store is empty") }
            require(kv.scan("").isEmpty()) { "capacity store has entries but no meta" }; return null
        }
        val meta = Meta.parse(metaBytes)
        require(meta.bindingDigest == bindingDigest) { "capacity store belongs to a different binding" }
        val head = kv.get(HEAD)?.let { FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(it)) }
        require((head == null) == (meta.sequence == 0L)) { "head decision presence differs from sequence" }
        require(meta.configDigest == config.digest()) { "capacity store was written under a different configuration" }
        if (evictSettled) requireNoUnknownEntityKeys(kv)
        if (evictSettled && snapshot != null) {
            val fast = try {
                if (!java.nio.file.Files.exists(snapshot)) { onSnapshot("absent"); null }
                else readSnapshot(config, EntityBacking { kv.get(it) }, bindingDigest, snapshot).also { s ->
                    require(s.sequence == meta.sequence && s.chain == meta.chain && s.controls == meta.controls) { "the lane snapshot (sequence ${s.sequence}) is not at the store's head (sequence ${meta.sequence})" }
                    requireHead(meta, head, s)
                    onSnapshot(null)
                }
            } catch (e: Exception) { onSnapshot(e.message ?: e.javaClass.name); null }
            finally { java.nio.file.Files.deleteIfExists(snapshot) }
            if (fast != null) return fast
        }
        val ledger = if (!evictSettled) kv.scanStreaming("e/") { entries -> CapacityLedger.restore(config, entries.map { (k, v) -> decodeEntity(k, v) }.asIterable(), meta.sequence, meta.controls, meta.chain) }
        else CapacityLedger.restoreEvicting(config, EntityBacking { kv.get(it) }, CapacityLedger.Companion.EntitySource { tag, each -> kv.scanStreaming("e/$tag/") { entries -> for ((k, v) in entries) each(decodeEntity(k, v)) } }, meta.sequence, meta.controls, meta.chain)
        requireHead(meta, head, ledger)
        return ledger
    }

    /** [ledger] is exactly the state the stored head decision committed to (or a genesis state when there is none). */
    private fun requireHead(meta: Meta, head: Decision?, ledger: CapacityLedger) {
        if (head == null) {
            require(meta.chain == ledger.genesisChain && ledger.entityCount == 0) { "genesis store is not empty or on another genesis" }
        } else {
            require(head.sequence == meta.sequence && head.checksum == meta.chain) { "head decision is not the stored chain head" }
            require(head.checksum == CapacityLedger.checksumOf(head.sequence, head.action, head.inputDigest, head.disposition, head.legs, head.priorChain, head.stateDigest)) { "head decision checksum does not recompute" }
            require(head.stateDigest == ledger.commitment()) { "the stored entities are not the state the head decision committed to" }
        }
    }
}
