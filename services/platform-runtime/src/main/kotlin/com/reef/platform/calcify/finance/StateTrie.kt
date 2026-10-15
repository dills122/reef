package com.reef.platform.calcify.finance

import java.security.MessageDigest

/**
 * The incremental form of the path-compressed binary trie of the capacity profile's state commitment (design section 4.1). It holds `(key, leaf digest)`
 * pairs of 32 bytes each and returns the same digest [StateCommitment.trieRoot] computes from scratch for the same key set, in O(depth) work per touched
 * entity: a `put` marks the nodes on its path stale and [root] re-hashes only those, so a decision that writes a handful of entities hashes a handful of paths.
 *
 * Shape (a pure function of the key set): each key is a leaf at the shortest unique prefix; a branch stores the absolute bit index (0 to 255 from the most
 * significant bit) at which its two subtrees first differ, left child where that bit is 0, indices strictly increasing along every path. Entities are never
 * deleted in the capacity profile (a rotating ledger is sealed and replaced), so there is no delete; that would need the collapse rule of 4.1.
 *
 * Not thread-safe: one writer, which is the commitment worker of the lagged profile or the deciding thread of the synchronous one.
 *
 * This linked-node form is the readable specification of the incremental algorithm. [CapacityLedger] runs [FlatStateTrie], the same trie in flat arrays, which
 * a differential test holds to this class's digests and hash counts; this class stays as its oracle.
 */
internal class StateTrie {
    private sealed class Node { abstract fun digest(trie: StateTrie): ByteArray }
    private class Leaf(val key: ByteArray, var leaf: ByteArray) : Node() { override fun digest(trie: StateTrie) = leaf }
    private class Branch(val index: Int, var left: Node, var right: Node) : Node() {
        var cached: ByteArray? = null
        override fun digest(trie: StateTrie): ByteArray {
            cached?.let { return it }
            val d = trie.sha(byteArrayOf(0x02, (index ushr 8).toByte(), index.toByte()), left.digest(trie), right.digest(trie))
            trie.branchHashes++
            cached = d
            return d
        }
    }

    private var rootNode: Node? = null
    private val md = MessageDigest.getInstance("SHA-256")

    /** Number of entities. */
    var size = 0
        private set

    /** Branch hashes computed so far (a test and metrics counter: the cost of a commitment update is the number of stale branches it re-hashed). */
    var branchHashes = 0L
        private set

    private fun sha(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray { md.update(a); md.update(b); md.update(c); return md.digest() }

    private fun bit(key: ByteArray, index: Int): Int = (key[index ushr 3].toInt() ushr (7 - (index and 7))) and 1

    private fun firstDifferingBit(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 32) { val x = (a[i].toInt() xor b[i].toInt()) and 0xff; if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24 }
        return -1
    }

    /** The leaf digest stored for [key], or null. */
    fun get(key: ByteArray): ByteArray? {
        require(key.size == 32) { "keys are 32 bytes" }
        var n = rootNode ?: return null
        while (n is Branch) n = if (bit(key, n.index) == 0) n.left else n.right
        return (n as Leaf).takeIf { it.key.contentEquals(key) }?.leaf
    }

    /** Inserts the pair, or replaces the leaf digest of an existing key; the nodes on the path are marked stale. */
    fun put(key: ByteArray, leaf: ByteArray) {
        require(key.size == 32 && leaf.size == 32) { "keys and leaf digests are 32 bytes" }
        val start = rootNode
        if (start == null) { rootNode = Leaf(key.copyOf(), leaf.copyOf()); size = 1; return }
        var closest = start
        while (closest is Branch) closest = if (bit(key, closest.index) == 0) closest.left else closest.right
        val existing = closest as Leaf
        val d = firstDifferingBit(key, existing.key)
        if (d < 0) {                                         // the same key: replace the leaf and mark its ancestors stale
            var n: Node = start
            while (n is Branch) { n.cached = null; n = if (bit(key, n.index) == 0) n.left else n.right }
            existing.leaf = leaf.copyOf()
            return
        }
        // a new key: descend to where the new branch at bit d belongs (the first node whose index exceeds d, or a leaf), marking the path stale
        var parent: Branch? = null; var parentLeft = false; var cur: Node = start
        while (cur is Branch && cur.index < d) { cur.cached = null; parent = cur; parentLeft = bit(key, cur.index) == 0; cur = if (parentLeft) cur.left else cur.right }
        val fresh = Leaf(key.copyOf(), leaf.copyOf())
        val branch = if (bit(key, d) == 0) Branch(d, fresh, cur) else Branch(d, cur, fresh)
        when { parent == null -> rootNode = branch; parentLeft -> parent.left = branch; else -> parent.right = branch }
        size++
    }

    /** The trie digest `T`: the root's digest, or `SHA-256(0x03)` for the empty set. Re-hashes only the stale branches. */
    fun root(): ByteArray = rootNode?.digest(this) ?: StateCommitment.emptyTrie

    /** The depth (number of branches) on the path to [key]'s leaf or its closest leaf; a test aid for the caterpillar bound. */
    fun depthOf(key: ByteArray): Int { var n = rootNode; var depth = 0; while (n is Branch) { depth++; n = if (bit(key, n.index) == 0) n.left else n.right }; return depth }
}
